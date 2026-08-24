package org.nift4.gramophone.hificore

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.audio.AudioOutput
import com.jwoolston.libusb.LibusbError
import com.jwoolston.libusb.UsbConstants
import com.jwoolston.libusb.UsbDevice
import com.jwoolston.libusb.UsbEndpoint
import com.jwoolston.libusb.UsbInterface
import java.nio.ByteBuffer

class AsynchronousLibusbAudioOutput(
    private val device: UsbDevice, private val usbInterface: UsbInterface,
    private val handle: Long, private val ptr: Long
) : AudioOutput {
    companion object {
        private const val TAG = "AsynchronousLibusbAO"

        fun new(device: UsbDevice, usbInterface: UsbInterface) = createExplicitFeedback(device,
            usbInterface,
            usbInterface.endpointCount.let {
                for (i in 0..<it) {
                    val ep = usbInterface.getEndpoint(i)
                    if (ep.endpointNumber == 1 && ep.direction == UsbConstants.USB_DIR_OUT)
                        return@let ep
                }
                throw IllegalArgumentException("no stream ep?")
            }, usbInterface.endpointCount.let {
                for (i in 0..<it) {
                    val ep = usbInterface.getEndpoint(i)
                    if (ep.endpointNumber == 1 && ep.direction == UsbConstants.USB_DIR_IN)
                        return@let ep
                }
                throw IllegalArgumentException("no fb ep?")
            }, 4410, 8,
            10, 4, 44100,
            1.5, 8, 0, 8)

        // let bRefresh be 0 if device is not UAC1
        fun createExplicitFeedback(device: UsbDevice, usbInterface: UsbInterface,
                                   endpointData: UsbEndpoint,
                                   endpointFb: UsbEndpoint, javaBufferSizeFrames: Int,
                                   isoSlots: Int, transferQueueSize: Int, audioFrameSize: Int,
                                   audioSampleRate: Int, audioBufferSizeFramesFactor: Double,
                                   feedbackTransferCount: Int, bRefresh: Int,
                                   feedbackMinIsoSlots: Int): AsynchronousLibusbAudioOutput {
            val handle = device.takeReference()
            val ptr = nativeCreateExplicit(device.nativeObject, endpointData.address.toByte(),
                endpointFb.address.toByte(), javaBufferSizeFrames, isoSlots, transferQueueSize,
                audioFrameSize, audioSampleRate, audioBufferSizeFramesFactor, feedbackTransferCount,
                bRefresh, feedbackMinIsoSlots)
            return AsynchronousLibusbAudioOutput(device, usbInterface, handle, ptr)
        }

        private external fun nativeCreateExplicit(
            nativeObject: Long,
            endpointData: Byte,
            endpointFb: Byte,
            javaBufferSizeFrames: Int,
            isoSlots: Int,
            transferQueueSize: Int,
            audioFrameSize: Int,
            audioSampleRate: Int,
            audioBufferSizeFramesFactor: Double,
            feedbackTransferCount: Int,
            bRefresh: Int,
            feedbackMinIsoSlots: Int
        ): Long

        // in and out sample rate must be derived from the same clock, but one or both of these may
        // still be subjected to clock division, hence they may differ.
        fun createImplicitFeedback(device: UsbDevice, usbInterface: UsbInterface,
                                   endpointData: UsbEndpoint,
                                   endpointFb: UsbEndpoint, javaBufferSizeFrames: Int,
                                   isoSlots: Int, transferQueueSize: Int, audioFrameSize: Int,
                                   audioSampleRate: Int, feedbackFrameSize: Int,
                                   feedbackSampleRate: Int): AsynchronousLibusbAudioOutput {
            val handle = device.takeReference()
            val ptr = nativeCreateImplicit(device.nativeObject, endpointData.address.toByte(),
                endpointFb.address.toByte(), javaBufferSizeFrames, isoSlots, transferQueueSize,
                audioFrameSize, audioSampleRate, feedbackFrameSize, feedbackSampleRate)
            return AsynchronousLibusbAudioOutput(device, usbInterface, handle, ptr)
        }

        private external fun nativeCreateImplicit(
            nativeObject: Long,
            endpointData: Byte,
            endpointFb: Byte,
            javaBufferSizeFrames: Int,
            isoSlots: Int,
            transferQueueSize: Int,
            audioFrameSize: Int,
            audioSampleRate: Int,
            feedbackFrameSize: Int,
            feedbackSampleRate: Int
        ): Long
    }
    private val listeners = mutableListOf<AudioOutput.Listener>()
    private val handler = Handler(Looper.myLooper()!!)
    private var sentAdvancing = false
    private var paused = true
    private var released = false
    private val startRunnable = ::startStreaming

    init {
        device.manager.enableUsbEventsForLooper(handler.looper)

        val rate = byteArrayOf(
            0x44.toByte(),
            0xAC.toByte(),
            0x00,
            0x00
        )

        // TODO: watch Active Alternate Setting Control
        // TODO: honor Valid Alternate Settings Control
        // TODO: Terminal Connector Control Interrupt support for jack detection
        // TODO: i have a fuzzy memory of some value that tells me how long i need to wait after altsetting until you can hear something
        //https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/usb-2-0-audio-drivers#class-requests-and-interrupt-data-messages
        val r: Int = device.controlTransfer(
            0x21,
            0x01,
            0x0100,
            0x2900,
            rate,
            0,
            rate.size,
            1000
        )

        device.setInterface(usbInterface)
    }

    private fun errToStr(i: Int) = if (i <= 0) LibusbError.fromNative(i).toString()
    else if (i == 1) "Underflow" else "unknown: $i"

    private fun getPtr(): Long {
        if (released) {
            throw IllegalStateException("AsyncFeedbackStreaming was already released")
        }
        return ptr
    }

    // To keep streaming running:
    // 1. call nativeStart()
    // 2. if error is returned: handle error (for example, LIBUSB_ERROR_NO_DEVICE -> call stop),
    //    and if wanting to continue, go to step 1. if LIBUSB_SUCCESS is returned, go to step 3.
    // 3. wait 100ms, then go to step 1
    // ... and don't forget to write enough data :)
    private fun startStreaming() {
        if (released) return
        while (true) {
            val i = nativeStart(getPtr())
            if (i != 0) {
                Log.e(TAG, "-->start in play(): error ${errToStr(i)}")
                break//TODO
            } else {
                if (!sentAdvancing && positionUs > 0) {
                    listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
                    sentAdvancing = true
                }
                break
            }
        }
        handler.postDelayed(startRunnable, 100)
    }

    private fun stopStreaming() {
        handler.removeCallbacks(startRunnable)
        nativeStop(getPtr())
    }

    override fun play() {
        Log.e(TAG, "-->play")
        paused = false
        startStreaming()
    }

    override fun pause() {
        Log.e(TAG, "-->pause")
        paused = true
        stopStreaming()
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        if (!buffer.isDirect) {
            throw IllegalArgumentException("Buffer must be direct")
        }
        val progress = nativeWrite(getPtr(), buffer, buffer.position(),
            buffer.remaining())
        buffer.position(buffer.position() + progress)
        return !buffer.hasRemaining()
    }

    override fun flush() {
        stopStreaming()
        if (!paused)
            startStreaming()
    }

    override fun stop() {
        Log.e(TAG, "-->stop")
        //TODO:stopping = true --> should tell startStreaming() to treat underrun as non-error (and
        // maybe if we can find out everything had underrun we r done stopping?)
    }

    override fun release() {
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
        nativeRelease(ptr)
        UsbDevice.releaseReferenceStatic(handle)
        released = true
        listeners.forEach { it.onReleased() }
    }

    override fun setVolume(volume: Float) {
        //TODO("Not yet implemented")
    }

    override fun isOffloadedPlayback(): Boolean {
        return false
    }

    override fun getAudioSessionId(): Int {
        //TODO("Not yet implemented")
        return C.AUDIO_SESSION_ID_UNSET
    }

    override fun getSampleRate(): Int {
        //TODO("Not yet implemented")
        return 44100
    }

    override fun getBufferSizeInFrames(): Long {
        return 441 * 4
    }

    override fun getPositionUs(): Long {
        //return timestampFrames * 10000 / 441 //TODO ASAP
        return 0
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        //TODO("Not yet implemented")
        return PlaybackParameters.DEFAULT
    }

    override fun isStalled(): Boolean {
        //TODO("Not yet implemented")
        return false
    }

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        //TODO("Not yet implemented")
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        throw UnsupportedOperationException()
    }

    override fun setOffloadEndOfStream() {
        throw UnsupportedOperationException()
    }

    override fun attachAuxEffect(effectId: Int) {
        throw UnsupportedOperationException()
    }

    override fun setAuxEffectSendLevel(level: Float) {
        throw UnsupportedOperationException()
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        //TODO("Not yet implemented")
    }

    protected fun finalize() {
        release()
    }

    private external fun nativeStart(ptr: Long): Int
    private external fun nativeWrite(ptr: Long, buf: ByteBuffer, position: Int, remaining: Int): Int
    private external fun nativeStop(ptr: Long)
    private external fun nativeRelease(ptr: Long)
}