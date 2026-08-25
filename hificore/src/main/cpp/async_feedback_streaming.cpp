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

// Basic idea: the feedback number is slots(!)/microframe. Isochronous means 1 microframe
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
// repeat this until we have enough iso packets to fill one transfer, and send it out! then,
// once one of the earlier transfers is done, prepare the next one. (Notably it should
// always use the latest feedback value and not use any averages or similar.)

// For async mode, the feedback must be as low-latency as possible, so the packet queue must be as
// small as possible and refilled in real-time-safe environment, that is, native thread. But because
// decoder isn't real-time-safe, we use an internal buffer for async mode. This internal buffer must
// be big enough to compensate for non-real-time decoder, while the packet queue is small to keep
// feedback latency small. We can also choose to use low amount of transfers if we use a
// real-time-safe transfer filling environment.)

enum StreamingError {
    STREAMING_NO_ERROR = LIBUSB_SUCCESS,
    STREAMING_ERROR_UNDERFLOW,
    STREAMING_ERROR_GLOBAL_UNDERFLOW,
};

struct timestamp {
    uint64_t frameCount;
    uint64_t nanoTime;
};

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
    std::atomic<int> error = LIBUSB_SUCCESS; // negative=libusb_error, 0=ok, positive=custom error

protected:
    libusb_transfer* transfer;
    ssize_t bufferSize;

    virtual int doSubmit() {
        return libusb_submit_transfer(transfer);
    }

    virtual void doCancel() {
        libusb_cancel_transfer(transfer);
    }

public:
    Transfer(int isoSlots, libusb_device_handle* device, char endpoint, ssize_t buffer_size) :
    bufferSize(buffer_size) {
        transfer = libusb_alloc_transfer(isoSlots);
        transfer->num_iso_packets = isoSlots;
        transfer->dev_handle = device;
        transfer->flags = 0;
        transfer->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
        transfer->timeout = 60000; // placeholder, TBD
        transfer->endpoint = endpoint;
        transfer->buffer = static_cast<unsigned char *>(malloc(buffer_size));
        transfer->length = buffer_size;
        transfer->user_data = this;
        transfer->callback = transfer_callback_wrapper;
    }

    void awaitStop() {
        state.wait(Transfer::Active);
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
    bool start1() {
        State t = state.exchange(Transfer::Active);
        if (t == State::Active) {
            // already as requested.
            return false;
        }
        if (t == State::Canceled) {
            // if state is currently canceled: we set it to Active again, so the callback will
            // re-submit once the prior cancellation is done.
            return false;
        }
        // if state is currently idle: we'll have to submit the transfer.
        int rc = process(false);
        error = rc;
        if (rc != 0) {
            state.store(Transfer::Idle);
            return false;
        }
        return true;
    }

    // The reason start process is split into two parts is that process() shouldn't race with itself
    void start2() {
        int rc = doSubmit();
        error = (libusb_error) -abs(rc);
        if (rc < LIBUSB_SUCCESS) {
            state.store(Transfer::Idle);
            return;
        }
    }

    int dequeueError() {
        return error.exchange(LIBUSB_SUCCESS);
    }

    bool dequeueUnderflow() {
        int expected = STREAMING_ERROR_UNDERFLOW;
        if (error.compare_exchange_strong(expected, LIBUSB_SUCCESS)) {
            awaitStop();
            return true;
        }
        return false;
    }

    bool isIdle() {
        return state.load() == Transfer::Idle;
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

    // returns error code if nothing to send/receive, 0 if should (re)submit
    virtual int process(bool inCallback) = 0;

    virtual void callback(libusb_transfer* theTransfer) {
        State t = Transfer::Canceled;
        // This lock is explicitly designed to NEVER block the callback thread, only ever the thread
        // calling cancel. This is why atomic is used inside lock - the atomic can be accessed
        // without lock in start() or cancel().
        {
            std::unique_lock lock(idleNotificationMutex);
            if (state.compare_exchange_strong(t, Transfer::Idle)) {
                // the transfer got canceled. we now gave it back to the caller. let's drop the
                // transfer status because it doesn't matter.
                state.notify_all();
                return;
            }
        }
        // The transfer is active. (If it were idle, we wouldn't be in a callback.)
        bool ok = false;
        int rc;
        switch (transfer->status) {
            case LIBUSB_TRANSFER_COMPLETED:
                rc = process(true);
                if (rc == 0) {
                    ok = true;
                } else {
                    error = rc;
                }
                break;
            case LIBUSB_TRANSFER_TIMED_OUT:
                error = LIBUSB_ERROR_TIMEOUT;
                break;
            case LIBUSB_TRANSFER_STALL:
                error = LIBUSB_ERROR_PIPE;
                break;
            case LIBUSB_TRANSFER_NO_DEVICE:
                error = LIBUSB_ERROR_NO_DEVICE;
                break;
            case LIBUSB_TRANSFER_OVERFLOW:
                error = LIBUSB_ERROR_OVERFLOW;
                break;
            case LIBUSB_TRANSFER_ERROR:
            case LIBUSB_TRANSFER_CANCELLED:
                error = LIBUSB_ERROR_IO;
                break;
            default:
                error = LIBUSB_ERROR_OTHER;
                break;
        }
        if (ok) {
            rc = doSubmit();
            error = (libusb_error) -abs(rc);
            ok = rc >= LIBUSB_SUCCESS;
        }
        if (!ok) {
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

static uint16_t q16Accumulate(uint32_t* accumulator, uint32_t value) {
    *accumulator += value;
    uint16_t frames = *accumulator >> 16;
    *accumulator &= 0xffff;
    return frames;
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
static int calculateIso(int bRefresh, int minIsoSlots, libusb_device_handle* device) {
    int isoSlots = minIsoSlots;
    if (bRefresh != 0)
        isoSlots = std::max(isoSlots, (int)pow(2, bRefresh));
    if (libusb_get_device_speed(libusb_get_device(device)) != LIBUSB_SPEED_FULL)
        isoSlots *= 8; // convert unit from frame to microframe (HS) / bus interval (SS)
    return isoSlots;
}
class ExplicitFeedbackTransfer : public Transfer {
private:
    std::atomic<uint32_t>* out;
    ExplicitFeedbackTransfer(libusb_device_handle *device, char endpoint, std::atomic<uint32_t>* out,
                     int isoSlots, int feedbackSize) : Transfer(isoSlots, device, endpoint,
                                              isoSlots * feedbackSize), out(out) {
        libusb_set_iso_packet_lengths(transfer, feedbackSize);
    }
public:
    // let bRefresh be 0 if device is not UAC1
    ExplicitFeedbackTransfer(int bRefresh, int minIsoSlots, libusb_device_handle *device,
                             char endpoint, std::atomic<uint32_t>* out) :
            ExplicitFeedbackTransfer(device, endpoint, out, calculateIso(bRefresh,
                                                                         minIsoSlots, device),
                             bRefresh != 0 ? kFeedbackSizeUac1 : kFeedbackSizeUac2) {}
    int process(bool inCallback) override {
        if (inCallback) {
            for (int i = transfer->num_iso_packets - 1; i >= 0; i--) {
                if (transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED) {
                    if (transfer->iso_packet_desc[i].actual_length == 3) {
                        unsigned char *buf = libusb_get_iso_packet_buffer_simple(transfer, i);
                        // Q10.14 -> Q16.16
                        out->store((((uint32_t) buf[0]) | ((uint32_t) buf[1]) << 8
                            | ((uint32_t) buf[2]) << 16) << 2);
                        break;
                    } else if (transfer->iso_packet_desc[i].actual_length == 4) {
                        void *buf = libusb_get_iso_packet_buffer_simple(transfer, i);
                        out->store(*(uint32_t *) buf);
                        break;
                    }
                }
            }
        }
        return 0;
    }
};

class Buffer { // SPSC
public:
    unsigned char* data;
    uint32_t size;
    std::atomic<uint32_t> read;
    std::atomic<uint32_t> write;
};

// the transfer is already pre-filled with both num_iso_packet and the iso packet's lengths, we just
// have to fill the buffer. we shouldn't modify read pointer if we underflow to not drop data into
// the void (as underflow means we won't send this transfer). this function may race with itself on
// input buffer access, but it has exclusive access to output buffer.
static StreamingError read_buffer_into_transfer(Buffer* b, unsigned char* outBuf, size_t length) {
    if (length == 0) {
        return STREAMING_NO_ERROR;
    }
    uint32_t size = b->size;
    uint32_t read = b->read.load();
    uint32_t readMod = read % size;
    uint32_t available = b->write.load() - read;
    if (available < length)
        return STREAMING_ERROR_UNDERFLOW;
    if (readMod + length <= size) { // normal case, single memcpy
        memcpy(outBuf, b->data + readMod, length);
    } else { // wrap is in the middle of our transfer
        memcpy(outBuf, b->data + readMod, size - readMod);
        memcpy(outBuf + (size - readMod), b->data, length - (size - readMod));
    }
    b->read.store(read + length);
    return STREAMING_NO_ERROR;
}

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
static int calculateNormalSlotCountPerIso(libusb_device_handle *device, int sampleRate) {
    bool f = libusb_get_device_speed(libusb_get_device(device)) == LIBUSB_SPEED_FULL;
    return (sampleRate * (f ? 1000 : 125) / 1000000);
}
class ImplicitFeedbackTransfer : public Transfer {
private:
    libusb_transfer* transferData;
    std::atomic<int> waitingCount;
    int frameSizeIn;
    int frameSizeOut;
    int sampleRateOut;
    int sampleRateIn;
    uint32_t* q16Accumulator;
    Buffer* b;
    std::atomic<timestamp>* writeCounter;

    ImplicitFeedbackTransfer(libusb_device_handle *device, char endpoint, char endpointData,
                             int isoSlots, int sampleRateIn, int sampleRateOut, int dataSizeFrames,
                             int feedbackSizeFrames, int frameSizeIn, int frameSizeOut, uint32_t*
    q16Accumulator, Buffer* b, std::atomic<timestamp>* writeCounter) : Transfer
    (isoSlots, device, endpoint, isoSlots * feedbackSizeFrames * frameSizeIn),
    frameSizeIn(frameSizeIn), frameSizeOut(frameSizeOut), sampleRateIn(sampleRateIn),
    sampleRateOut(sampleRateOut), q16Accumulator(q16Accumulator), b(b), writeCounter(writeCounter) {
        int dataSize = dataSizeFrames * frameSizeOut;
        libusb_set_iso_packet_lengths(transfer, feedbackSizeFrames * frameSizeIn);
        transferData = libusb_alloc_transfer(isoSlots);
        transferData->num_iso_packets = 0;
        transferData->dev_handle = device;
        transferData->flags = 0;
        transferData->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
        transferData->timeout = 60000; // placeholder, TBD
        transferData->endpoint = endpointData;
        transferData->length = isoSlots * dataSize;
        transferData->buffer = static_cast<unsigned char *>(malloc(transferData->length));
        transferData->user_data = this;
        transferData->callback = transfer_callback_wrapper;
        libusb_set_iso_packet_lengths(transferData, dataSize);
    }

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
        if (theTransfer == transferData) {
            timespec tp{};
            clock_gettime(CLOCK_MONOTONIC, &tp);
            timestamp t = writeCounter->load();
            //TODO: can we get nanoTime from libusb or kernel, or something...
            t.nanoTime = (uint64_t)((tp.tv_sec * 1000000000LL) + tp.tv_nsec);
            t.frameCount += theTransfer->length / frameSizeOut;
            writeCounter->store(t);
        }
        if (--waitingCount > 0)
            return;
        transferData->num_iso_packets *= -1; // mark as not ready to send
        Transfer::callback(theTransfer);
    }

public:
    // in and out sample rate must be derived from the same clock, but one or both of these may
    // still be subjected to clock division, hence they may differ.
    ImplicitFeedbackTransfer(libusb_device_handle *device, char endpoint, char endpointData,
                             int isoSlots, int sampleRateIn, int sampleRateOut, int frameSizeIn,
                             int frameSizeOut, uint32_t* q16Accumulator, Buffer* b,
                             std::atomic<timestamp>* writeCounter
    ) : ImplicitFeedbackTransfer(
            device, endpoint, endpointData, isoSlots, sampleRateIn, sampleRateOut,
            calculateNormalSlotCountPerIso(device, sampleRateOut) + 1,
            calculateNormalSlotCountPerIso(device, sampleRateIn) + 1,
            frameSizeIn, frameSizeOut, q16Accumulator, b, writeCounter) {}

    ~ImplicitFeedbackTransfer() override {
        awaitStop();
        free(transferData->buffer);
        libusb_free_transfer(transferData);
        Transfer::~Transfer();
    }

    int process(bool inCallback) override {
        if (inCallback) {
            transferData->num_iso_packets *= -1; // if we weren't canceled, restore original number
            int j = 0;
            unsigned int totalLengthToSend = 0;
            for (int i = 0; i < transfer->num_iso_packets; i++) {
                uint32_t outputFramesQ16;
                if (transfer->iso_packet_desc[i].status == LIBUSB_TRANSFER_COMPLETED &&
                        transfer->iso_packet_desc[i].actual_length > 0) {
                    // one packet must have integer number of audio frames
                    uint32_t inFrames = transfer->iso_packet_desc[i].actual_length / frameSizeIn;
                    outputFramesQ16 = ((uint64_t)inFrames * sampleRateOut << 16) / sampleRateIn;
                } else {
                    // guesstimate at least somewhat, as some amount of errors is expected with ISO
                    uint32_t usbFrameDuration = libusb_get_device_speed(libusb_get_device(
                            transfer->dev_handle)) == LIBUSB_SPEED_FULL ? 1000 : 125;
                    outputFramesQ16 = ((uint64_t)sampleRateOut * usbFrameDuration << 16) / 1000000;
                }
                uint32_t outBytes = (uint32_t)q16Accumulate(
                        q16Accumulator, outputFramesQ16) * frameSizeOut;
                transferData->iso_packet_desc[j++].length = outBytes;
                totalLengthToSend += outBytes;
            }
            transferData->num_iso_packets = j;
            transferData->length = (int)totalLengthToSend;
            return read_buffer_into_transfer(b, transfer->buffer, totalLengthToSend);
        }
        return STREAMING_NO_ERROR;
    }
};

class AudioTransfer : public Transfer {
    std::atomic<uint32_t>* feedbackIn;
    std::atomic<uint32_t>* q16Accumulator;
    int frameSize;
    int sampleRate;
    Buffer* b;
    std::atomic<timestamp>* writeCounter;
public:
    AudioTransfer(int isoSlots, libusb_device_handle *device, char endpoint,
                  int maxIsoPacketSizeBytes, int frameSize, int sampleRate,
                  std::atomic<uint32_t>* feedbackIn, std::atomic<uint32_t>* q16Accumulator,
                  Buffer* b, std::atomic<timestamp>* writeCounter)
            : Transfer(isoSlots, device, endpoint, maxIsoPacketSizeBytes * isoSlots),
            feedbackIn(feedbackIn), frameSize(frameSize), sampleRate(sampleRate),
            q16Accumulator(q16Accumulator), b(b), writeCounter(writeCounter) {}

    void callback(libusb_transfer *theTransfer) override {
        timespec tp{};
        clock_gettime(CLOCK_MONOTONIC, &tp);
        timestamp t = writeCounter->load();
        //TODO: can we get nanoTime from libusb or kernel, or something...
        t.nanoTime = (uint64_t)((tp.tv_sec * 1000000000LL) + tp.tv_nsec);
        t.frameCount += theTransfer->length / frameSize;
        writeCounter->store(t);
        Transfer::callback(theTransfer);
    }

    int process(bool inCallback) override {
        uint32_t feedback = feedbackIn->load();
        if (feedback == 0) {
            // guesstimate at least somewhat, as some amount of errors is expected with ISO
            uint32_t usbFrameDuration = libusb_get_device_speed(libusb_get_device(
                    transfer->dev_handle)) == LIBUSB_SPEED_FULL ? 1000 : 125;
            feedback = ((uint64_t)sampleRate * usbFrameDuration << 16) / 1000000;
        }
        unsigned int totalLengthToSend = 0;
        for (int i = 0; i < transfer->num_iso_packets; i++) {
            uint32_t outBytes;
            while (true) {
                uint32_t old = q16Accumulator->load();
                uint32_t acc = old;
                outBytes = q16Accumulate(&acc, feedback) * frameSize;

                if (q16Accumulator->compare_exchange_weak(old, acc,
                                                          std::memory_order_relaxed,
                                                          std::memory_order_relaxed)) {
                    break;
                }
            }
            int maxSize = bufferSize / transfer->num_iso_packets;
            if (outBytes > maxSize)
                outBytes = maxSize;
            transfer->iso_packet_desc[i].length = outBytes;
            totalLengthToSend += outBytes;
        }
        transfer->length = (int)totalLengthToSend;
        // for now, short transfers aren't implemented, it's all or nothing. alternative according
        // to spec would be zero-sized iso packets, but it doesn't seem like a great idea.
        return read_buffer_into_transfer(b, transfer->buffer, totalLengthToSend);
    }
};

class AsyncFeedbackStreaming {
protected:
    Buffer b;
    std::atomic<timestamp> writeCounter;
public:
    // To keep streaming running:
    // 1. call start(whether you intend to empty the buffer)
    // 2. if error is returned: handle error (for example, LIBUSB_ERROR_NO_DEVICE -> call stop),
    //    and if wanting to continue, go to step 1. if LIBUSB_SUCCESS is returned, go to step 3.
    // 3. wait 100ms, then go to step 1
    virtual int start(bool empty) = 0;
    virtual timestamp getWriteCounter() {
        return writeCounter.load();
    }
    void resetWriteCounter() {
        writeCounter.store({});
    }
    virtual void stop() = 0;

    AsyncFeedbackStreaming(int javaBufferSizeFrames, int audioFrameSize) {
        b.size = javaBufferSizeFrames * audioFrameSize;
        b.data = static_cast<unsigned char *>(malloc(b.size));
    }

    uint32_t write(unsigned char* inBuf, uint32_t length) {
        if (length == 0) {
            return 0;
        }
        uint32_t size = b.size;
        uint32_t write = b.write.load();
        uint32_t writeMod = write % size;
        uint32_t space = size - (write - b.read.load());
        if (space == 0) {
            return 0;
        }
        if (space < length)
            length = space;
        if (writeMod + length <= size) { // normal case, single memcpy
            memcpy(b.data + writeMod, inBuf, length);
        } else { // wrap is in the middle of our transfer
            memcpy(b.data + writeMod, inBuf, size - writeMod);
            memcpy(b.data, inBuf + (size - writeMod), length - (size - writeMod));
        }
        b.write.store(write + length);
        return length;
    }

    virtual ~AsyncFeedbackStreaming() {
        free(b.data);
    }
};

class ExplicitAsyncFeedbackStreaming : public AsyncFeedbackStreaming {
    std::atomic<uint32_t> feedback;
    std::atomic<uint32_t> accumulator;
    std::vector<ExplicitFeedbackTransfer*> feedbackTransfers;
    std::vector<AudioTransfer*> audioTransfers;

public:
    ExplicitAsyncFeedbackStreaming(libusb_device_handle *device, char endpointData, char endpointFb,
                                   int javaBufferSizeFrames, int audioIsoSlots,
                                   int audioTransferCount, int audioFrameSize, int audioSampleRate,
                                   int maxIsoPacketSizeBytes, int feedbackTransferCount,
                                   int bRefresh, int feedbackMinIsoSlots) : AsyncFeedbackStreaming(
                                           javaBufferSizeFrames, audioFrameSize) {
        for (int i = 0; i < feedbackTransferCount; i++) {
            feedbackTransfers.emplace_back(new ExplicitFeedbackTransfer(
                    bRefresh, feedbackMinIsoSlots, device, endpointFb,
                    &feedback));
        }
        for (int i = 0; i < audioTransferCount; i++) {
            audioTransfers.emplace_back(new AudioTransfer(
                    audioIsoSlots, device, endpointData,
                    maxIsoPacketSizeBytes, audioFrameSize,
                    audioSampleRate, &feedback, &accumulator, &b,
                    &writeCounter));
        }
    }

    int start(bool empty) override {
        bool allUnderrun = false;
        bool oneUnderrun = false;
        for (auto & audioTransfer : audioTransfers) {
            bool underrun = audioTransfer->dequeueUnderflow();
            oneUnderrun = oneUnderrun || underrun;
            allUnderrun = allUnderrun && underrun;
        }
        if (allUnderrun)
            return STREAMING_ERROR_GLOBAL_UNDERFLOW;
        else if (oneUnderrun)
            return STREAMING_ERROR_UNDERFLOW;
        for (auto & audioTransfer : audioTransfers) {
            int error = audioTransfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            int error = feedbackTransfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        bool allIdle = false;
        bool oneIdle = false;
        for (auto & audioTransfer : audioTransfers) {
            bool idle = audioTransfer->isIdle();
            oneIdle = oneIdle || idle;
            allIdle = allIdle && idle;
        }
        if (!allIdle && oneIdle) {
            // to ensure we read data from ring buffer in proper order and don't cause races, stop
            // transfers and restart them properly
            if (empty) {
                for (auto & audioTransfer : audioTransfers) {
                    // first let the audio transfers stop themselves
                    audioTransfer->awaitStop();
                }
            }
            stop();
        }
        feedback.store(0);
        std::vector<Transfer*> transfersToStart;
        for (auto & audioTransfer : audioTransfers) {
            if (audioTransfer->start1())
                transfersToStart.push_back(audioTransfer);
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            if (feedbackTransfer->start1())
                transfersToStart.push_back(feedbackTransfer);
        }
        for (auto & transfer : transfersToStart) {
            transfer->start2();
        }
        for (auto & audioTransfer : audioTransfers) {
            int error = audioTransfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            int error = feedbackTransfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        return LIBUSB_SUCCESS;
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
            audioTransfer->dequeueError(); // drop error if any
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            feedbackTransfer->awaitStop();
            feedbackTransfer->dequeueError(); // drop error if any
        }
    }
    ~ExplicitAsyncFeedbackStreaming() override {
        for (auto & audioTransfer : audioTransfers) {
            delete audioTransfer;
        }
        for (auto & feedbackTransfer : feedbackTransfers) {
            delete feedbackTransfer;
        }
    }
};

class ImplicitAsyncFeedbackStreaming : public AsyncFeedbackStreaming {
    std::vector<ImplicitFeedbackTransfer*> transfers;
    uint32_t q16Accumulator = 0; // only accessed on callback thread

public:
    ImplicitAsyncFeedbackStreaming(libusb_device_handle *device, char endpointData, char endpointFb,
                                   int javaBufferSizeFrames, int isoSlots, int transferQueueSize,
                                   int audioFrameSize, int audioSampleRate,
                                   int feedbackFrameSize, int feedbackSampleRate
                                   ) : AsyncFeedbackStreaming(javaBufferSizeFrames, audioFrameSize) {
        for (int i = 0; i < transferQueueSize; i++) {
            transfers.push_back(new ImplicitFeedbackTransfer(
                    device, endpointFb, endpointData, isoSlots,
                    audioSampleRate, feedbackSampleRate,
                    feedbackFrameSize, audioFrameSize, &q16Accumulator, &b,
                    &writeCounter));
        }
    }
    int start(bool empty) override {
        bool allUnderrun = false;
        bool oneUnderrun = false;
        for (auto & transfer : transfers) {
            bool underrun = transfer->dequeueUnderflow();
            oneUnderrun = oneUnderrun || underrun;
            allUnderrun = allUnderrun && underrun;
        }
        if (allUnderrun)
            return STREAMING_ERROR_GLOBAL_UNDERFLOW;
        else if (oneUnderrun)
            return STREAMING_ERROR_UNDERFLOW;
        for (auto & transfer : transfers) {
            int error = transfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        bool allIdle = false;
        bool oneIdle = false;
        for (auto & transfer : transfers) {
            bool idle = transfer->isIdle();
            oneIdle = oneIdle || idle;
            allIdle = allIdle && idle;
        }
        if (!allIdle && oneIdle) {
            // to ensure we read data from ring buffer in proper order and don't cause races, stop
            // transfers and restart them properly
            if (empty) {
                for (auto &transfer: transfers) {
                    // first let the audio transfers stop themselves
                    transfer->awaitStop();
                }
            }
            stop();
        }
        std::vector<Transfer*> transfersToStart;
        for (auto & transfer : transfers) {
            if (transfer->start1())
                transfersToStart.push_back(transfer);
        }
        for (auto & transfer : transfersToStart) {
            transfer->start2();
        }
        for (auto & transfer : transfers) {
            int error = transfer->dequeueError();
            if (error != LIBUSB_SUCCESS)
                return error;
        }
        return LIBUSB_SUCCESS;
    }

    void stop() override {
        for (auto & transfer : transfers) {
            transfer->cancel();
        }
        for (auto & transfer : transfers) {
            transfer->awaitStop();
        }
    }

    ~ImplicitAsyncFeedbackStreaming() override {
        for (auto & transfer : transfers) {
            delete transfer;
        }
    }
};

extern "C"
JNIEXPORT jint JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeStart(JNIEnv *env, jobject thiz,
                                                                      jlong ptr, jboolean empty) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    return asyncFeedbackStreaming->start(empty);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeGetWriteCounter(JNIEnv *env,
                                                                                       jobject thiz,
                                                                                       jlong ptr,
                                                                                       jlongArray out) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    timestamp ts = asyncFeedbackStreaming->getWriteCounter();
    env->SetLongArrayRegion(out, 0, 2, (jlong*)&ts);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeResetWriteCounter(JNIEnv *env,
                                                                                       jobject thiz,
                                                                                       jlong ptr) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    asyncFeedbackStreaming->resetWriteCounter();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeStop(JNIEnv *env, jobject thiz,
                                                                      jlong ptr) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    asyncFeedbackStreaming->stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeRelease(JNIEnv *env, jobject thiz,
                                                                     jlong ptr) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    delete asyncFeedbackStreaming;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_nativeWrite(JNIEnv *env, jobject thiz,
                                                                      jlong ptr, jobject buf,
                                                                      jint position,
                                                                      jint remaining) {
    auto* asyncFeedbackStreaming = (AsyncFeedbackStreaming*) ptr;
    auto* inBuf = static_cast<unsigned char *>(env->GetDirectBufferAddress(buf));
    return (jint)asyncFeedbackStreaming->write(inBuf + position, remaining);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_00024Companion_nativeCreateExplicit(
        JNIEnv *env, jobject thiz, jlong native_object,
        jbyte endpoint_data, jbyte endpoint_fb, jint java_buffer_size_frames, jint iso_slots,
        jint transfer_queue_size, jint audio_frame_size, jint audio_sample_rate,
        jint max_iso_packet_size_bytes, jint feedback_transfer_count, jint b_refresh,
        jint feedback_min_iso_slots) {
    auto* device = (libusb_device_handle*) native_object;
    return (jlong) new ExplicitAsyncFeedbackStreaming(
            device, endpoint_data, endpoint_fb,
            java_buffer_size_frames, iso_slots,
            transfer_queue_size, audio_frame_size,
            audio_sample_rate,
            max_iso_packet_size_bytes,
            feedback_transfer_count, b_refresh,
            feedback_min_iso_slots);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_nift4_gramophone_hificore_AsynchronousLibusbAudioOutput_00024Companion_nativeCreateImplicit(
        JNIEnv *env, jobject thiz, jlong native_object,
        jbyte endpoint_data, jbyte endpoint_fb, jint java_buffer_size_frames, jint iso_slots,
        jint transfer_queue_size, jint audio_frame_size, jint audio_sample_rate,
        jint feedback_frame_size, jint feedback_sample_rate) {
    auto* device = (libusb_device_handle*) native_object;
    return (jlong) new ImplicitAsyncFeedbackStreaming(
            device, endpoint_data, endpoint_fb,
            java_buffer_size_frames, iso_slots,
            transfer_queue_size, audio_frame_size,
            audio_sample_rate, feedback_frame_size,
            feedback_sample_rate);
}