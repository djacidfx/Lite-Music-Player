/*
 *     Copyright (C) 2026 nift4
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <libusb.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <cmath>
#include <vector>

// Basic idea: the feedback number is samples(!)/microframe. Isochronous means 1 microframe
// is one packet, so it's how much samples (integer samples only) one packet should be.
// And the question of how many packets are queued at once, is simply based on OS scheduling
// constraints, as the packets need to return from flight, filled up again, and submitted,
// before the USB controller buffer is empty.
// (It's important to remember that 10 transfers with each 1 iso packet, and 1 transfer with
// 10 iso packets, are exactly the same on the USB bus. It's just a question of how often we
// get woken up to refill buffers! So the tuning of packets per transfer is a question of
// efficiency, while the question of packet queue size is a question of will it underflow or
// not. The queue can also be too big: then the feedback loop becomes big enough the device
// can no longer compensate because it's internal buffer is empty or full, and xrun occurs.)
// For each SINGLE iso packet(!!! not per transfer) we do:
//    We clear the accumulated number's decimal part to just keep the fraction to carry over.
//    We read the feedback and add it to the accumulated number. The accumulated number is
//    exactly how much samples we need to send in this packet. (Except the fraction which is
//    kept for next time)
// linux uses this fractional accumulator:
/*
 * 	phase = (ep->phase & 0xffff) + (ep->freqm << ep->datainterval);
 * 	ret = min(phase >> 16, ep->maxframesize);
 * 	if (avail && ret >= avail)
 * 		ret = -EAGAIN; *this means we wait until we get new PCM *
* 	else
* 		ep->phase = phase;
* 	return ret;
*/
// but they do just use whatever the latest freqm retrieved from the device is, so feedback
// poll it is.
// repeat this until we have enough iso packets to fill one transfer, and send it out! then,
// once one of the earlier transfers is done, prepare the next one. (Notably it should
// always use the latest feedback value and not use any averages or similar.)

// For synchronous, the clock source is the USB clock. That means we send constant amount of
// samples per packet based on the assumption we send exactly samples for 125us, per packet.
// Adaptive sinks will essentially achieve the same result when we use the same strategy.
// The basic assumption for the above is that decoder is faster than real-time to ensure we
// always have enough data. We do NOT use an internal buffer, we use transfers as the buffer.
// If we read too many iso packets into one transfer at once, we would starve the decoder.
// If we do not read enough in, we waste CPU time with repeatedly having overhead of
// decoding and submitting transfer, so we optimally want as much as possible that would not
// starve decoder (but a lot would mean high packet queue size, which means high audio
// latency, which we don't want). The total packet queue (=buffer size, essentially) should
// be tuned for avoiding USB xrun if we are too slow to generate new packets, this means it
// should be some higher multiple of transfer queue to make sure if we are late once or
// twice we don't instantly xrun (maybe 4 times). It should also not be too high due to
// audio latency as previously mentioned.
// We can say 4 transfers and as such (packet queue size / 4) packets per transfer, with
// packet queue size being size of audio buffer. If audio buffer is too small, we will xrun,
// and if it's too big we simply have high latency.
// It just occurred to me that pause/flush can be implemented like that too! By cancelling
// transfers. So we can go safe and queue a lot of buffers and just cancel some transfers if
// we don't feel like it anymore. (This does not apply to async mode because the feedback
// must be as low-latency as possible, so the packet queue must be as small as possible and
// refilled in real-time-safe environment, that is, native thread. But because decoder isn't
// real-time-safe, we use an internal buffer for async mode. This internal buffer must be
// big enough to compensate for non-real-time decoder, while the packet queue is small to
// keep feedback latency small. We can also choose to use less transfers if we use a
// real-time-safe transfer filling environment.)
// TODO: implement purely event-handler-refill based feedback polling in C(++), some buffer (maybe
//  ring? idk yet) that Java can write from, and event handler can read _without blocking_. also
//  implement cancel even in this LL case.


// audio data    --> -------- --> transfers  ---v
// flush(cancel) --> | TODO |  <---- transfer completion
// release       --> --------  <-- usb device unplug, should propagate release upwards
//  |                    ^
//  \--> feedback queue -/
//

static void LIBUSB_CALL transfer_callback_wrapper(libusb_transfer* transfer);
class Transfer {
private:
    enum State {
        Idle,
        Active, // waiting for callback to then re-submit, staying active
        Canceled, // waiting for callback to then go to Idle
    };
    std::atomic<State> state{State::Idle};
    std::mutex idleNotificationMutex;
    std::atomic<libusb_error> error = LIBUSB_SUCCESS;

protected:
    libusb_transfer* transfer;

    virtual int doSubmit() {
        return libusb_submit_transfer(transfer);
    }

    virtual void doCancel() {
        libusb_cancel_transfer(transfer);
    }

public:
    Transfer(int isoSlots, libusb_device_handle* device, char endpoint, ssize_t buffer_size) {
        transfer = libusb_alloc_transfer(isoSlots);
        transfer->num_iso_packets = isoSlots;
        transfer->dev_handle = device;
        transfer->flags = 0;
        transfer->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
        transfer->timeout = 60; // placeholder, TBD
        transfer->endpoint = endpoint;
        transfer->buffer = static_cast<unsigned char *>(malloc(buffer_size));
        transfer->length = buffer_size;
        transfer->user_data = this;
        transfer->callback = transfer_callback_wrapper;
    }

    void awaitStop() {
        while (state.load() == Transfer::Canceled)
            state.wait(Transfer::Canceled);
        {
            std::unique_lock lock(idleNotificationMutex);
            // must not be optimized out / removed, otherwise:
            // 1. callback() could store idle state, then be descheduled
            // 2. here, we read idle state, and proceed to free transfer
            // 3. event thread is rescheduled and calls state.notify_all() on deallocated transfer
        }
    }

    // Threading contract: there is thread A which calls start/cancel, and thread B which is the
    // libusb event thread. So, start and cancel can't run at the same time, and two callbacks can't
    // run at the same time either, but start/cancel and callback can race with each other.
    void start() {
        State t = state.exchange(Transfer::Active);
        if (t == State::Active) {
            // already as requested.
            return;
        }
        if (t == State::Canceled) {
            // if state is currently canceled: we set it to Active again, so the callback will
            // re-submit once the prior cancellation is done.
            return;
        }
        // if state is currently idle: we'll have to submit the transfer.
        bool ok = process(false);
        int rc = ok ? doSubmit() : LIBUSB_SUCCESS;
        error = (libusb_error) -abs(rc);
        if (!ok || rc < LIBUSB_SUCCESS) {
            state.store(Transfer::Idle);
            return;
        }
    }

    libusb_error getLastError() {
        return error;
    }

    void cancel() {
        State t = Transfer::Active;
        if (state.compare_exchange_strong(t, Transfer::Canceled)) {
            // It's possible this races with callback completing on another thread, but that's OK,
            // libusb is explicitly thread-safe so the design here can be simple.
            // Another possible race is the callback reading that it's active, us then setting
            // canceled state and cancelling, and it only then resubmitting (so the cancellation
            // didn't take effect), which is OK, because we'll just cancel it once the transfer is
            // done which won't take long. Another possible tradeoff to make is to fix this specific
            // race with a mutex, which has the problem that it could block the callback thread if
            // libusb_cancel_transfer takes a long time.
            // TODO but is blocking really an issue? we don't need real time transfers when we
            //  cancel everything after all ^^
            doCancel();
        }
        // the transfer isn't active anymore, either it's already being canceled or idle.
    }

    // returns false if nothing to send/receive, true if should (re)submit
    virtual bool process(bool inCallback) = 0;

    virtual void callback(libusb_transfer* theTransfer) {
        State t = Transfer::Canceled;
        // This lock is explicitly designed to NEVER block the callback thread, only ever the thread
        // calling cancel. This is why atomic is used inside lock - the atomic can be accessed
        // without lock in start() or cancel().
        {
            std::unique_lock lock(idleNotificationMutex);
            if (state.compare_exchange_strong(t, Transfer::Idle)) {
                // the transfer got canceled. we now gave it back to the caller.
                state.notify_all();
                return;
            }
        }
        // The transfer is active. (If it were idle, we wouldn't be in a callback.)
        bool ok = process(true);
        int rc = ok ? doSubmit() : LIBUSB_SUCCESS;
        error = (libusb_error) -abs(rc);
        if (!ok || rc < LIBUSB_SUCCESS) {
            {
                std::unique_lock lock(idleNotificationMutex);
                state.store(Transfer::Idle);
                state.notify_all();
            }
            return;
        }
    }

    // dtor can only be canceled after cancel()
    virtual ~Transfer() {
        awaitStop();
        free(transfer->buffer);
        libusb_free_transfer(transfer);
    }
};
static void LIBUSB_CALL transfer_callback_wrapper(libusb_transfer* transfer) {
    ((Transfer*) transfer->user_data)->callback(transfer);
}

// Unambiguously Q10.14 feedback for full-speed operation of USB Audio Class 1.0 device.
constexpr ssize_t kFeedbackSizeUac1 = 3;
// Some devices sadly misinterpret the USB Audio Class 2.0 specification, or worse, understand it
// correctly but add a workaround for Windows, sending Q16.16 feedback instead of Q10.14 even if
// they're only full-speed, so we always have to use HS-sized buffer. By specification, full-speed
// devices send Q10.14 feedback, and high-speed devices send Q16.16 feedback.
constexpr ssize_t kFeedbackSizeUac2 = 4;
// The issue with feedback polling in userspace is that it is hard to time this to be every n
// (micro)frames (we do know in which microframe a device updates its feedback value based on
// bRefresh for UAC1 or bInterval for newer USB Audio Class versions). We would ideally want a
// reduced polling rate - lower than bInterval - because scheduler won't let us wake up this often,
// but when we poll, we want an value that was freshly updated by the device.
// But we can't reduce polling rate as we don't have interval field in usbfs URBs :( the kernel is
// hardcoded to schedule ISO frames at maximum polling rate, and we can't work around this (if we
// try to submit URBs that are too small, the ISO queue will empty and the timeslot will be used for
// something else, which incurs even worse delays upwards of 16 microframes on some controllers).
// As such, we are forced to use the bInterval of the EP as the polling rate, and hence get multiple
// packets at once, but discard all except the latest one. In UAC1 case where bInterval is always
// faster than bRefresh, we can't even time batches to have the last packet as the most up-to-date
// one, because libusb doesn't support non-ASAP transfers. (But given that UAC2 and later aren't
// affected by this limitation, it doesn't seem worth to fix at the moment.)
// Hence, we want queue size to be as small as possible for UAC2 (because every packet is meaningful
// there), but at least bRefresh size for UAC1 to not waste CPU. We also don't want to go far above
// the minimum as too much latency in handling feedback will confuse the device.
constexpr int kDefaultFeedbackBatchSizeInFrames = 2; // TODO: a bit low...?
static int calculateIso(int bRefresh, libusb_device_handle* device) {
    int isoSlots = kDefaultFeedbackBatchSizeInFrames;
    if (bRefresh != 0)
        isoSlots = std::max(isoSlots, (int)pow(2, bRefresh));
    if (libusb_get_device_speed(libusb_get_device(device)) != LIBUSB_SPEED_FULL)
        isoSlots *= 8; // convert unit from frame to microframe (HS) / bus interval (SS)
    return isoSlots;
}
class ExplicitFeedbackTransfer : public Transfer {
private:
    std::atomic<int>* out;
    ExplicitFeedbackTransfer(libusb_device_handle *device, char endpoint, std::atomic<int>* out,
                     int isoSlots, int feedbackSize) : Transfer(isoSlots, device, endpoint,
                                              isoSlots * feedbackSize), out(out) {
        libusb_set_iso_packet_lengths(transfer, feedbackSize);
    }
public:
    // let bRefresh be 0 if device is not UAC1
    ExplicitFeedbackTransfer(
            int bRefresh, libusb_device_handle *device, char endpoint, std::atomic<int>* out) :
            ExplicitFeedbackTransfer(device, endpoint, out, calculateIso(bRefresh, device),
                             bRefresh != 0 ? kFeedbackSizeUac1 : kFeedbackSizeUac2) {}
    bool process(bool inCallback) override {
        if (inCallback) {
            for (int i = transfer->num_iso_packets - 1; i >= 0; i--) {
                if (transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED) {
                    if (transfer->iso_packet_desc[i].actual_length == 3) {
                        unsigned char *buf = libusb_get_iso_packet_buffer_simple(transfer, i);
                        // TODO: BE? LE?
                        // Q10.14 -> Q16.16
                        out->store((int32_t) ((((uint32_t) buf[0]) | ((uint32_t) buf[1]) << 8
                            | ((uint32_t) buf[2]) << 16) << 2));
                        break;
                    } else if (transfer->iso_packet_desc[i].actual_length == 4) {
                        void *buf = libusb_get_iso_packet_buffer_simple(transfer, i);
                        // TODO: BE? LE?
                        out->store(*(int *) buf);
                        break;
                    }
                }
            }
        }
        return true;
    }
};

// Implicit feedback boils down to: 1. start capture 2. wait for URB to return 3. send exactly
// as many samples to output 4. repeat.
// Some different designs are possible here: the simplest one is a that a large number of feedback
// EP transfers are queued, and they dequeue a data EP transfer from backlog, fill in the data,
// queue the data EP transfer, and then re-queue themselves (feedback EP transfer). Implementing
// this is annoying, because the completion callbacks of feedback EP and data EP are interleaved
// randomly. However, from single endpoint POV, the callback order is exactly as it was submitted,
// which means the completion callback of a feedback EP would, when using a queue design,
// always dequeue the exact same data EP transfer in every loop iteration. This means we can
// establish this pairing ahead of time, and because libusb callbacks are serialized in _some_ order
// we are completely lock free for common case.
class ImplicitFeedbackTransfer : public Transfer {
private:
    libusb_transfer* transferData;
    std::atomic<int> waitingCount;

    int doSubmit() override {
        if (transferData->num_iso_packets > 0) {
            waitingCount += 2;
            auto rc = (libusb_error) libusb_submit_transfer(transferData);
            if (rc != LIBUSB_SUCCESS) {
                transferData->num_iso_packets = 0; // mark as not ready to send
                waitingCount -= 2;
                return rc;
            }
            rc = (libusb_error) libusb_submit_transfer(transfer);
            if (rc != LIBUSB_SUCCESS) {
                if (--waitingCount == 0)
                    return rc; // can set idle, the other callback is already done
                return -rc; // positive error -> wait for callback before setting idle
            }
            return rc;
        } else {
            waitingCount += 1;
            auto rc = (libusb_error) libusb_submit_transfer(transfer);
            if (rc != LIBUSB_SUCCESS) {
                waitingCount -= 1;
                return rc;
            }
            return rc;
        }
    }

    void doCancel() override {
        libusb_cancel_transfer(transfer);
        libusb_cancel_transfer(transferData);
    }

    void callback(libusb_transfer *theTransfer) override {
        if (--waitingCount > 0)
            return;
        transferData->num_iso_packets *= -1; // mark as not ready to send
        Transfer::callback(theTransfer);
    }

public:
    // dataSize/feedbackSize should be pessimistic but reasonable maximums
    ImplicitFeedbackTransfer(libusb_device_handle *device, char endpoint, char endpointData,
                             int isoSlots, int feedbackSize, int dataSize
    ) : Transfer(isoSlots, device, endpoint, isoSlots * feedbackSize) {
        libusb_set_iso_packet_lengths(transfer, feedbackSize);
        transferData = libusb_alloc_transfer(isoSlots);
        transferData->num_iso_packets = 0;
        transferData->dev_handle = device;
        transferData->flags = 0;
        transferData->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
        transferData->timeout = 60; // placeholder, TBD
        transferData->endpoint = endpointData;
        transferData->length = isoSlots * dataSize;
        transferData->buffer = static_cast<unsigned char *>(malloc(transferData->length));
        transferData->user_data = this;
        transferData->callback = transfer_callback_wrapper;
        libusb_set_iso_packet_lengths(transferData, dataSize);
    }

    ~ImplicitFeedbackTransfer() override {
        awaitStop();
        free(transferData->buffer);
        libusb_free_transfer(transferData);
        Transfer::~Transfer();
    }

    bool process(bool inCallback) override {
        if (inCallback) {
            transferData->num_iso_packets *= -1; // if we weren't canceled, restore original number
            int j = 0;
            unsigned int totalLength = 0;
            for (int i = 0; i < transfer->num_iso_packets; i++) {
                if (transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED &&
                        transfer->iso_packet_desc[i].actual_length > 0) {
                    // TODO: convert input->output length (for example: input 16bit, output 32bit)
                    transferData->iso_packet_desc[j++].length =
                            transfer->iso_packet_desc[i].actual_length;
                    totalLength += transfer->iso_packet_desc[i].actual_length;
                } else {
                    // TODO: need to guesstimate at least somewhat, as some amount of errors is
                    //  expected!
                }
            }
            // TODO actually copy the data and adjust j to remove cut packets on underflow
            // We might send short transfers, but it is what it is
            transferData->num_iso_packets = j;
        }
        return true;
    }
};

class AudioTransfer : public Transfer {
    std::atomic<int>* feedbackIn;
public:
    AudioTransfer(int isoSlots, libusb_device_handle *device, char endpoint, ssize_t bufferSize,
                  std::atomic<int>* feedbackIn)
            : Transfer(isoSlots, device, endpoint, bufferSize), feedbackIn(feedbackIn) {}

    bool process(bool inCallback) override {
        // TODO actually read audio data from buffer and write it or something
        return false;
    }
};

class AsyncFeedbackStreaming {
public:
    virtual void start() = 0;
    virtual void stop() = 0;
    virtual ~AsyncFeedbackStreaming() = default;
};

class ExplicitAsyncFeedbackStreaming : public AsyncFeedbackStreaming {
    std::atomic<int> feedback;
    std::vector<ExplicitFeedbackTransfer*> feedbackTransfers;
    std::vector<AudioTransfer*> audioTransfers;

    // TODO some sorta error reaper that checks for idle transfer and does something about them
    //  (should this be done on libusb callback thread or on another one)?

public:
    ExplicitAsyncFeedbackStreaming(libusb_device_handle *device, char endpointData, char endpointFb,
                                   int bRefresh, int audioIsoSlots, int audioBufferSize) {
        for (int i = 0; i < 4; i++) { // TODO arbitrary 4
            feedbackTransfers.emplace_back(new ExplicitFeedbackTransfer(bRefresh, device,
                                                                        endpointFb,
                                                                        &feedback));
            audioTransfers.emplace_back(new AudioTransfer(audioIsoSlots, device,
                                                          endpointData,
                                                          audioBufferSize,
                                                          &feedback));
        }
    }

    void start() override {
        for (auto & audioTransfer : audioTransfers) {
            audioTransfer->start();
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            feedbackTransfer->start();
        }
    }

    void stop() override {
        for (auto & audioTransfer : audioTransfers) {
            audioTransfer->cancel();
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            feedbackTransfer->cancel();
        }
        for (auto & audioTransfer : audioTransfers) {
            audioTransfer->awaitStop();
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            feedbackTransfer->awaitStop();
        }
    }
    ~ExplicitAsyncFeedbackStreaming() override = default;
};

class ImplicitAsyncFeedbackStreaming : public AsyncFeedbackStreaming {
    std::vector<ImplicitFeedbackTransfer*> transfers;

    // TODO some sorta error reaper that checks for idle transfer and does something about them
    //  (should this be done on libusb callback thread or on another one)?

public:
    ImplicitAsyncFeedbackStreaming(libusb_device_handle *device, char endpointFb, char endpointData,
                                   int isoSlots, int feedbackSize, int dataSize) {
        for (int i = 0; i < 4; i++) { // TODO arbitrary 4
            transfers.push_back(new ImplicitFeedbackTransfer(device, endpointFb,
                                                                endpointData, isoSlots,
                                                                feedbackSize, dataSize));
        }
    }
    void start() override {
        for (auto & transfer : transfers) {
            transfer->start();
        }
    }

    void stop() override {
        for (auto & transfer : transfers) {
            transfer->cancel();
        }
        for (auto & transfer : transfers) {
            transfer->awaitStop();
        }
    }

    ~ImplicitAsyncFeedbackStreaming() override = default;
};