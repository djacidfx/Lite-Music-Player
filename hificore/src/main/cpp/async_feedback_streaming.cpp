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
#include <bits/stdatomic.h>

// to find the endpoint, according to USB 2.0 specification chapter 9.6.6, the feedback EP
// for a data EP is the first opposite-direction EP with the same _or lower_ number.
// so we first choose a data EP and can then compute the feedback EP from that information.
// the feedback EP might be a Feedback-only EP or an implicit feedback data EP, we don't
// support the latter, and should not select such alt settings I suppose (TODO).
// TODO https://github.com/torvalds/linux/blob/8d3ae59288f1e7d58d76558a6ee96d533bc5019f/sound/usb/pcm.c#L375
//  why does Linux do this? is this carried over from UAC1 (need to check old spec!)?

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

// the issue with freqm polling is that it seems hard to time this to be every n microframes
// as we don't have any kind of accuracy with submit_transfer doing ASAP timing, and Linux
// has urb->interval and I think we don't :( we are forced to use the bInterval of the EP,
// as usbdevfs for some reason doesn't support reducing interval. We can however batch
// exactly that many isoc packets into a transfer and just read the latest one. Reason we
// can't just provide less packets is that the queue will go idle, and after idle queue, the
// first packet will be executed instantly. So even not setting ASAP and setting start_frame
// will behave the exact same (as we were polling slower than bInterval and thus queue
// starved and went idle).
// TODO smart parsing of freqm if I'm bored?
//  https://github.com/torvalds/linux/blob/8d3ae59288f1e7d58d76558a6ee96d533bc5019f/sound/usb/endpoint.c#L1877

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
    libusb_error error = LIBUSB_SUCCESS;

protected:
    libusb_transfer* transfer;

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
        libusb_error rc = ok ? (libusb_error)libusb_submit_transfer(transfer) : LIBUSB_SUCCESS;
        error = rc;
        if (!ok || rc != LIBUSB_SUCCESS) {
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
            libusb_cancel_transfer(transfer);
        }
        // the transfer isn't active anymore, either it's already being canceled or idle.
    }

    // returns false if nothing to send/receive, true if should (re)submit
    virtual bool process(bool inCallback) = 0;

    void callback() {
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
        libusb_error rc = ok ? (libusb_error)libusb_submit_transfer(transfer) : LIBUSB_SUCCESS;
        error = rc;
        if (!ok || rc != LIBUSB_SUCCESS) {
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
        while (state.load() == Transfer::Canceled)
            state.wait(Transfer::Canceled);
        {
            std::unique_lock lock(idleNotificationMutex);
            // must not be optimized out / removed, otherwise:
            // 1. callback() could store idle state, then be descheduled
            // 2. here, we read idle state, and proceed to free transfer
            // 3. event thread is rescheduled and calls state.notify_all() on deallocated transfer
        }
        free(transfer->buffer);
        libusb_free_transfer(transfer);
    }
};
static void LIBUSB_CALL transfer_callback_wrapper(libusb_transfer* transfer) {
    ((Transfer*) transfer->user_data)->callback();
}

// TODO: int feels like both wrong size and semantically wrong type...
constexpr ssize_t kFeedbackSize = (ssize_t) sizeof(int);
class FeedbackTransfer : Transfer {
    std::atomic<int>* out;
    FeedbackTransfer
    (int isoSlots, libusb_device_handle *device, char endpoint, std::atomic<int>* out) :
    Transfer(isoSlots, device, endpoint,isoSlots * kFeedbackSize), out(out) {
        libusb_set_iso_packet_lengths(transfer, kFeedbackSize);
    }
    bool process(bool inCallback) override {
        if (inCallback) {
            for (int i = transfer->num_iso_packets - 1; i >= 0; i--) {
                if (transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED
                    && transfer->iso_packet_desc[i].actual_length == kFeedbackSize) {
                    void* buf = libusb_get_iso_packet_buffer_simple(transfer, i);
                    // TODO: it's for sure more work to parse the feedback. should that be done here?
                    out->store(*(int*)buf);
                    break;
                }
            }
        }
        return true;
    }
};

class AudioTransfer : Transfer {
    bool process(bool inCallback) override {
        // TODO
        return false;
    }
};

class AsyncFeedbackStreaming {
    std::atomic<int> feedback;

    void start() {

    }
};