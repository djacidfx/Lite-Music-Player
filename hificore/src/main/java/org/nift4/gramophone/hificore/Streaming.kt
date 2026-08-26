package org.nift4.gramophone.hificore

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Log
import com.jwoolston.libusb.LibusbError
import com.jwoolston.libusb.UsbDevice
import com.jwoolston.libusb.UsbInterface

abstract class Streaming(
    protected val device: UsbDevice, protected val usbInterface: UsbInterface,
    protected val handle: Long, protected val ptr: Long, private val autoReleaseNativeBuf: Boolean
) {
    companion object {
        private const val TAG = "Streaming"
        external fun nativeCreateExplicit(
            nativeObject: Long,
            endpointData: Byte,
            endpointFb: Byte,
            source: Long,
            isoSlots: Int,
            transferQueueSize: Int,
            audioFrameSize: Int,
            audioSampleRate: Int,
            maxIsoPacketSizeBytes: Int,
            feedbackTransferCount: Int,
            bRefresh: Int,
            feedbackMinIsoSlots: Int
        ): Long

        external fun nativeCreateImplicit(
            nativeObject: Long,
            endpointData: Byte,
            endpointFb: Byte,
            source: Long,
            isoSlots: Int,
            transferQueueSize: Int,
            audioFrameSize: Int,
            audioSampleRate: Int,
            feedbackFrameSize: Int,
            feedbackSampleRate: Int
        ): Long

        external fun nativeCreateSync(
            nativeObject: Long,
            endpointData: Byte,
            source: Long,
            isoSlots: Int,
            transferQueueSize: Int,
            audioFrameSize: Int,
            audioSampleRate: Int
        ): Long
    }
    protected val handler = Handler(Looper.myLooper()!!)
    protected var sentAdvancing = false
    protected var paused = true
    protected var stopping = false
    protected var released = false
        private set
    protected val startRunnable = Runnable { startStreaming() }
    protected val tmp = LongArray(2)

    init {
        device.manager.enableUsbEventsForLooper(handler.looper)
    }

    protected fun errToStr(i: Int) = if (i <= 0) LibusbError.fromNative(i).toString()
    else if (i == 1) "Underflow" else "unknown: $i"

    @JvmName("getPtrChecked")
    protected fun getPtr(): Long {
        if (released) {
            throw IllegalStateException("Streaming was already released")
        }
        return ptr
    }

    // To keep streaming running:
    // 1. call nativeStart()
    // 2. if error is returned: handle error (for example, LIBUSB_ERROR_NO_DEVICE -> call stop),
    //    and if wanting to continue, go to step 1. if LIBUSB_SUCCESS is returned, go to step 3.
    // 3. wait 100ms, then go to step 1
    // ... and don't forget to write enough data :)
    protected open fun startStreaming() {
        if (released) return
        while (true) {
            val i = nativeStart(getPtr(), stopping)
            if (i == 2 && stopping) {
                stopping = false
                sentAdvancing = false
                return // do not reschedule start runnable anymore, we successfully stopped
            }
            if (i != 0) {
                if (i == 1 || i == 2) {
                    if (paused || stopping)
                        break
                    Log.e(TAG, "-->start in play(): underflow")
                    onUnderrun()
                    continue
                }
                Log.e(TAG, "-->start in play(): error ${errToStr(i)}")
                break//TODO are all other errors fatal
            } else {
                if (!sentAdvancing) {
                    nativeGetWriteCounter(getPtr(), tmp)
                    if (tmp[1] != 0L) {
                        // TODO: we could convert monotonic nanotime to epoch if we cared
                        val start = System.currentTimeMillis()
                        onPositionAdvancing(start)
                        sentAdvancing = true
                    }
                }
                break
            }
        }
        handler.postDelayed(startRunnable, 100)//TODO: 100ms, or maybe less?
    }

    protected open fun stopStreaming() {
        handler.removeCallbacks(startRunnable)
        nativeStop(getPtr())
    }

    open fun play() {
        Log.e(TAG, "-->play")
        paused = false
        startStreaming()
    }

    open fun pause() {
        Log.e(TAG, "-->pause")
        paused = true
        stopStreaming()
        if (stopping)
            stopping = false
        sentAdvancing = false
    }

    open fun flush() {
        stopStreaming()
        onGoingToResetWriteCounter()
        nativeResetWriteCounter(getPtr())
        sentAdvancing = false
        if (stopping)
            stopping = false
        else if (!paused)
            startStreaming()
    }

    open fun stop() {
        Log.e(TAG, "-->stop")
        stopping = true
    }

    open fun release() {
        if (released)
            return
        Log.e(TAG, "-->release")
        try {
            // set altsetting 0 (idle)
            device.setInterface(device.getConfigurationOrThrow()!!
                .getInterface(usbInterface.id, 0))
        } catch (e: Exception) {
            Log.e(TAG, "failed to reset to idle interface", e)
        }
        device.manager.disableUsbEventsForLooper(handler.looper, false)
        nativeRelease(ptr, autoReleaseNativeBuf)
        UsbDevice.releaseReferenceStatic(handle)
        released = true
        onRelease()
    }

    protected open fun onRelease() {}
    protected open fun onUnderrun() {}
    protected open fun onPositionAdvancing(start: Long) {}
    protected open fun onGoingToResetWriteCounter() {}

    protected open fun finalize() {
        release()
    }

    private external fun nativeStart(ptr: Long, empty: Boolean): Int
    protected external fun nativeGetWriteCounter(ptr: Long, out: LongArray)
    private external fun nativeResetWriteCounter(ptr: Long)
    private external fun nativeStop(ptr: Long)
    private external fun nativeRelease(ptr: Long, autoReleaseNativeBuf: Boolean)
}