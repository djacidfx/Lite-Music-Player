package org.nift4.gramophone.hificore

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import com.jwoolston.libusb.UsbConstants
import com.jwoolston.libusb.UsbDevice
import com.jwoolston.libusb.UsbEndpoint
import com.jwoolston.libusb.UsbInterface
import java.nio.ByteBuffer

class MixedAudioOutput(
    private val mixer: SoftMixedStreaming, javaBufferSizeFrames: Int, audioFrameSize: Int,
) : AudioOutput {
    private val listeners = mutableListOf<AudioOutput.Listener>()
    private var lastTimestampRawPositionFrames = 0uL
    private var expectTimestampFramePositionReset = false
    private var accumulatedRawTimestampFramePosition = 0uL
    private val buf = Buffer(javaBufferSizeFrames, audioFrameSize)
    private val tmp = LongArray(2)

    init {
        mixer.addBuffer(buf)
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        return buf.write(buffer)
    }

    //TODO:override fun onGoingToResetWriteCounter() {
    //    expectTimestampFramePositionReset = true
    //}

    override fun play() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun flush() {
        TODO("Not yet implemented")
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun release() {
        mixer.removeBuffer(buf)
        buf.release()
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
        //TODO:mixer.getWriteCounterForBuffer(buf, tmp)
        val rawPositionFrames = tmp[0].toULong()
        val nanoTime = tmp[1].toULong()
        if (nanoTime > 0uL) {
            if (lastTimestampRawPositionFrames > rawPositionFrames) {
                if (expectTimestampFramePositionReset) {
                    // ExoPlayer expects getPositionUs() to _not_ reset on a flush, but we reset it,
                    // hence we compensate for that here.
                    accumulatedRawTimestampFramePosition += lastTimestampRawPositionFrames
                    expectTimestampFramePositionReset = false
                } else {
                    // TODO wait, what?
                }
            }
            lastTimestampRawPositionFrames = rawPositionFrames
            val frameCounter = rawPositionFrames + accumulatedRawTimestampFramePosition
            val timestampPositionUs = Util.sampleCountToDurationUs(frameCounter.toLong(),
                sampleRate)
            val elapsedSinceTimestampUs = (System.nanoTime() - nanoTime.toLong()) / 1000
            return timestampPositionUs + elapsedSinceTimestampUs
        } else {
            return Util.sampleCountToDurationUs(accumulatedRawTimestampFramePosition.toLong(),
                sampleRate)
        }
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
}