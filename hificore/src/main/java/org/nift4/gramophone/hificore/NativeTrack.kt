/*
 *     Copyright (C) 2011 The Android Open Source Project
 *                   2025 nift4
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

package org.nift4.gramophone.hificore

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMetadataReadMap
import android.media.AudioPresentation
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.VolumeShaper
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.Handler
import android.os.Parcel
import android.os.PersistableBundle
import android.util.ArrayMap
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.media3.common.util.Log
import java.nio.ByteBuffer


/*
 * Exposes most of the API surface of AudioTrack.cpp, with one minor exceptions:
 * - setCallerName/getCallerName because I want to avoid offset hardcoding, and it's only used for metrics
 * All native method calls are wrapped to avoid Throwables from being thrown - only Exceptions will be thrown by
 * this class or its methods. However, you should always be prepared to handle such an exception, as everything can
 * fail.
 * TODO: tone down the magic numbers a bit.
 */
/*
 * TODO:
 * https://cs.android.com/android/_/android/platform/frameworks/av/+/978f2737e2f5f0ada8e109f9bf57a02d512b06c4
 * https://cs.android.com/android/_/android/platform/frameworks/av/+/9651a54f1e0afe7a33cd99afded17fe6afd1f77d
 * https://cs.android.com/android/_/android/platform/frameworks/av/+/7a476ef1a439b7a4f4368bb05ac95f732a84a3cf
 * https://cs.android.com/android/_/android/platform/frameworks/av/+/a40a5aabe7ec0d042d88e263703ff13ea5720dca
 * https://cs.android.com/android/_/android/platform/frameworks/av/+/955b2624c813135d2622c6466eb4e101cccc2358
 *
 * audit the offload EOS handling as that's mostly implemented in java and might not work in this
 * proxy setup because proxy doesn't receive stream end callback.
 */
@Suppress("unused")
class NativeTrack private constructor(builder: Builder) {
    companion object {
        const val AUDIO_OUTPUT_FLAG_NONE = 0x0
        const val AUDIO_OUTPUT_FLAG_DIRECT = 0x1
        const val AUDIO_OUTPUT_FLAG_PRIMARY = 0x2
        const val AUDIO_OUTPUT_FLAG_FAST = 0x4
        const val AUDIO_OUTPUT_FLAG_DEEP_BUFFER = 0x8
        const val AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD = 0x10
        const val AUDIO_OUTPUT_FLAG_NON_BLOCKING = 0x20
        const val AUDIO_OUTPUT_FLAG_HW_AV_SYNC = 0x40
        const val AUDIO_OUTPUT_FLAG_TTS = 0x80
        const val AUDIO_OUTPUT_FLAG_RAW = 0x100
        const val AUDIO_OUTPUT_FLAG_SYNC = 0x200
        const val AUDIO_OUTPUT_FLAG_IEC958_NONAUDIO = 0x400
        const val AUDIO_OUTPUT_FLAG_DIRECT_PCM = 0x2000
        const val AUDIO_OUTPUT_FLAG_MMAP_NOIRQ = 0x4000
        const val AUDIO_OUTPUT_FLAG_VOIP_RX = 0x8000
        const val AUDIO_OUTPUT_FLAG_INCALL_MUSIC = 0x10000
        const val AUDIO_OUTPUT_FLAG_GAPLESS_OFFLOAD = 0x20000
        const val AUDIO_OUTPUT_FLAG_SPATIALIZER = 0x40000
        const val AUDIO_OUTPUT_FLAG_ULTRASOUND = 0x80000
        const val AUDIO_OUTPUT_FLAG_BIT_PERFECT = 0x100000
        private const val TAG = "NativeTrack.kt"
        private const val WOULD_BLOCK = -11L
        private const val DEAD_OBJECT = -32
        private const val NO_INIT = -19L
        private const val STREAM_TYPE_DEFAULT = -1
        const val STATE_ACTIVE = 0
        const val STATE_STOPPED = 1
        const val STATE_PAUSED = 2
        const val STATE_PAUSED_STOPPING = 3
        const val STATE_FLUSHED = 4
        const val STATE_STOPPING = 5
        const val ENCAPSULATION_MODE_NONE = 0 // AudioTrack.ENCAPSULATION_MODE_NONE
        const val ENCAPSULATION_MODE_ELEMENTARY_STREAM =
            1 // AudioTrack.ENCAPSULATION_MODE_ELEMENTARY_STREAM
        const val ENCAPSULATION_MODE_HANDLE = 2 // AudioTrack.ENCAPSULATION_MODE_HANDLE

        enum class TransferMode(val id: Int) {
            Callback(1), // onMoreData() called by track
            Obtain(2), // user calls obtainBuffer() and releaseBuffer()
            Sync(3), // user calls write()
            Shared(4), // shared memory ctor parameter

            @RequiresApi(Build.VERSION_CODES.Q)
            SyncWithCallback(5) // user calls write(), track calls onCanWriteMoreData()
        }

        data class DirectPlaybackSupport(
            val normalOffload: Boolean, val gaplessOffload: Boolean,
            val directBitstream: Boolean
        ) {
            companion object {
                val NONE = DirectPlaybackSupport(
                    normalOffload = false,
                    gaplessOffload = false,
                    directBitstream = false
                )
                val OFFLOAD = DirectPlaybackSupport(
                    normalOffload = true,
                    gaplessOffload = false,
                    directBitstream = false
                )
                val GAPLESS_OFFLOAD = DirectPlaybackSupport(
                    normalOffload = false,
                    gaplessOffload = true,
                    directBitstream = false
                )
                val DIRECT = DirectPlaybackSupport(
                    normalOffload = false,
                    gaplessOffload = false,
                    directBitstream = true
                )
            }

            val offload
                get() = normalOffload || gaplessOffload
            val directOrOffload
                get() = directBitstream || offload
        }

        @JvmStatic
        @JvmName("getDirectPlaybackSupport")
        fun getDirectPlaybackSupport(
            context: Context, sampleRate: Int, encoding: UInt, platformEncoding: Int?,
            channelMask: UInt, platformChannelMask: Int?
        ): DirectPlaybackSupport {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = platformEncoding?.let {
                platformChannelMask?.let {
                    buildAudioFormat(sampleRate, platformEncoding, platformChannelMask)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && format != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!(@Suppress("deprecation")
                        AudioTrack.isDirectPlaybackSupported(format, attributes))
                    ) {
                        // No direct or offload port exists... but let's try inactive routes.
                        val type = @Suppress("deprecation")
                        AudioManager.getPlaybackOffloadSupport(format, attributes)
                        if (type != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED) {
                            // TODO: also none, but explain that offload is available on diff routes
                            return DirectPlaybackSupport.NONE
                        }
                        return DirectPlaybackSupport.NONE // if there's nothing suitable, give up
                    }
                    // Data point: either direct or offload port must exist.
                    val am = context.getSystemService<AudioManager>()!!
                    val profiles = am.getDirectProfilesForAttributes(attributes).toMutableList()
                    return if (profiles.isNotEmpty()) {
                        // Data point: there is no non-offloadable effect.
                        profiles.removeIf {
                            it.format != format.encoding ||
                                    !it.channelMasks.contains(format.channelMask) ||
                                    !it.sampleRates.contains(format.sampleRate)
                        }
                        if (profiles.isEmpty()) {
                            Log.w(
                                TAG, "missing matching profile for" +
                                        "$format: ${am.getDirectProfilesForAttributes(attributes)}"
                            )
                        }
                        val offloadType = @Suppress("deprecation")
                        AudioManager.getPlaybackOffloadSupport(format, attributes)
                        if (offloadType != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED) {
                            // Best case, as we can with confidence say what we have.
                            val hasGaplessOffloadCurrently = offloadType ==
                                    AudioManager.PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED
                            val hasDirect =
                                (AudioManager.getDirectPlaybackSupport(format, attributes)
                                        and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
                            DirectPlaybackSupport(
                                !hasGaplessOffloadCurrently,
                                hasGaplessOffloadCurrently, hasDirect
                            )
                        } else {
                            // Either offload is prevented by master mono or props, or it doesn't exist.
                            if (AudioSystemHiddenApi.getMasterMono() == true) {
                                // TODO: flag that offload is not working due to master mono
                                return DirectPlaybackSupport.NONE
                            }
                            if (profiles.size > 1) {
                                // While possible, odds are that there is a direct port instead of
                                // two offload ports.
                                DirectPlaybackSupport.DIRECT
                            } else DirectPlaybackSupport.DIRECT // TODO: low confidence flag
                        }
                    } else {
                        // Data point: there's a non-offloadable effect present. But the port could
                        // still be unimpacted because it's direct.
                        DirectPlaybackSupport.DIRECT // TODO: low confidence flag
                    }
                } else {
                    // be careful: both of these methods consider inactive routes
                    return when (getPlaybackOffloadSupportPlatformCompat(format, attributes)) {
                        AudioManager.PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED -> DirectPlaybackSupport.GAPLESS_OFFLOAD
                        AudioManager.PLAYBACK_OFFLOAD_SUPPORTED -> DirectPlaybackSupport.OFFLOAD
                        else -> {
                            // isDirectPlaybackSupported does not care whether offload is possible,
                            // and will happily return true if offload profile is found and pretend
                            // it's direct. but we can't detect it.
                            if (@Suppress("deprecation")
                                AudioTrack.isDirectPlaybackSupported(format, attributes)
                            )
                                DirectPlaybackSupport.DIRECT // TODO: low confidence flag
                            else DirectPlaybackSupport.NONE
                        }
                    }
                }
            }
            val bitWidth = bitsPerSampleForFormat(encoding)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // TODO implement native getDirectProfilesForAttributes
                return DirectPlaybackSupport.NONE // TODO implement native getDirectPlaybackSupport
            }
            // TODO before T, inactive routes were considered in isDirectPlaybackSupported according to AOSP doc.
            //  does that apply to getPlaybackOffloadSupport and isOffloadSupported too?
            val bitrate = if (bitWidth != 0) {
                bitWidth * Integer.bitCount(channelMask.toInt()) * sampleRate
            } else 128 // arbitrary guess for compressed formats
            val durationUs = 2100L /* 3.5min * 60 */ * 1000 * 1000 // must be >60s
            val directOffloadFlag = AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD
            if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1)
                || !formatIsRawPcm(encoding) || bitWidth < 24
            ) {
                // this cannot be trusted on N/O with 24+ bit PCM formats due to format confusion bug
                // be careful: this considers inactive routes too
                // TODO verify if this works on Q/R/S
                when (try {
                    isOffloadSupported(
                        sampleRate,
                        encoding.toInt(),
                        channelMask.toInt(),
                        0,
                        bitWidth,
                        0
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, Log.getThrowableString(t)!!)
                    0
                }) {
                    2 -> return DirectPlaybackSupport.GAPLESS_OFFLOAD
                    1 -> return DirectPlaybackSupport.OFFLOAD
                    0 -> {}
                    else -> throw IllegalStateException()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // TODO implement native isDirectOutputSupported
                    // TODO low confidence flag
                    return DirectPlaybackSupport.DIRECT
                }
            } else {
                // check for offload on N/O with 24+ bit PCM formats by opening track...
                // safeguard against bad direct track recycling on O by opening new session every time
                val sessionId = context.getSystemService<AudioManager>()!!.generateAudioSessionId()
                try {
                    val track = Builder(context, attributes, encoding, channelMask).apply {
                        this.sampleRate = sampleRate
                        this.trackFlags = directOffloadFlag
                        this.sessionId = sessionId
                        this.maxRequiredSpeed = 1.0f
                        this.bitRate = bitrate
                        this.durationUs = durationUs
                        this.doNotReconnect = true
                    }.build()
                    val port = AudioSystemHiddenApi.getMixPortForThread(track.getOutput())
                    val flags = track.flags()
                    track.release()
                    if (port == null) {
                        Log.w(TAG, "port is null")
                        return DirectPlaybackSupport.NONE
                    }
                    if (port.format != encoding) {
                        Log.e(
                            TAG,
                            "port ${port.name} was found, but is format ${port.format} instead of $encoding"
                        )
                        return DirectPlaybackSupport.NONE
                    }
                    if ((flags and directOffloadFlag) == directOffloadFlag) {
                        return DirectPlaybackSupport.OFFLOAD
                    }
                    if ((flags and directOffloadFlag) == AUDIO_OUTPUT_FLAG_DIRECT) {
                        return DirectPlaybackSupport.DIRECT
                    }
                } catch (t: Throwable) {
                    Log.e(
                        TAG,
                        Log.getThrowableString(t)!!
                    ) // TODO don't stacktrace when set fails due to unsupported format
                }
            }
            // check for direct output below Q by opening track...
            val sessionId = context.getSystemService<AudioManager>()!!.generateAudioSessionId()
            try {
                val track = Builder(context, attributes, encoding, channelMask).apply {
                    this.sampleRate = sampleRate
                    this.trackFlags = AUDIO_OUTPUT_FLAG_DIRECT
                    this.sessionId = sessionId
                    this.maxRequiredSpeed = 1.0f
                    this.bitRate = bitrate
                    this.durationUs = durationUs
                    this.doNotReconnect = true
                }.build()
                val port = AudioSystemHiddenApi.getMixPortForThread(track.getOutput())
                val flags = track.flags()
                track.release()
                if (port == null) {
                    Log.w(TAG, "port is null")
                    return DirectPlaybackSupport.NONE
                }
                if (port.format != encoding) {
                    Log.e(
                        TAG,
                        "port ${port.name} was found, but is format ${port.format} instead of $encoding"
                    )
                    return DirectPlaybackSupport.NONE
                }
                if ((flags and directOffloadFlag) == directOffloadFlag) {
                    return DirectPlaybackSupport.OFFLOAD
                }
                if ((flags and directOffloadFlag) == AUDIO_OUTPUT_FLAG_DIRECT) {
                    return DirectPlaybackSupport.DIRECT
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    Log.getThrowableString(t)!!
                ) // TODO don't stacktrace when set fails due to unsupported format
            }
            return DirectPlaybackSupport.NONE
        }

        /*private external fun getDirectPlaybackSupport(usage: Int, contentType: Int, attrFlags: Int,
                                                      sampleRate: Int, format: Int, channelMask: Int,
                                                      bitRate: Int, bitWidth: Int, offloadBufferSize: Int) TODO*/
        // TODO implement native getDirectProfilesForAttributes
        // TODO implement native isDirectOutputSupported
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun getPlaybackOffloadSupportPlatformCompat(
            format: AudioFormat,
            attributes: AudioAttributes
        ): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (@Suppress("deprecation") AudioManager.getPlaybackOffloadSupport(
                    format, attributes
                ))
            } else {
                @SuppressLint("InlinedApi") if (
                    AudioManager.isOffloadedPlaybackSupported(format, attributes))
                    AudioManager.PLAYBACK_OFFLOAD_SUPPORTED
                else AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED
            }
        }

        @JvmStatic
        @JvmName("getMinBufferSize")
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: UInt): Int {
            val minFrameCount = getMinFrameCount(STREAM_TYPE_DEFAULT, sampleRateInHz)
            val bps = bitsPerSampleForFormat(audioFormat)
            if (bps == 0) // compressed
                return minFrameCount
            return minFrameCount * Integer.bitCount(channelConfig) * (bps / 8)
        }

        @JvmStatic
        fun getMinFrameCount(streamType: Int, sampleRateInHz: Int): Int {
            prepareForLib()
            val comboRet = try {
                getMinFrameCountInternal(streamType, sampleRateInHz)
            } catch (t: Throwable) {
                throw NativeTrackException(
                    "failed to get min frame count ($streamType, $sampleRateInHz)",
                    t
                )
            }.toULong()
            val ret = (comboRet shr 32).toInt()
            val out = comboRet.toInt()
            if (ret != 0) {
                throw NativeTrackException("getMinFrameCount() failed: $ret (data=$out)")
            }
            return out
        }

        private external fun getMinFrameCountInternal(streamType: Int, sampleRateInHz: Int): Long
        private fun prepareForLib() {
            if (!AudioTrackHiddenApi.canLoadLib())
                throw NativeTrackException("this device is banned")
            if (!AudioTrackHiddenApi.libLoaded)
                throw NativeTrackException("lib isn't loaded but device isn't banned")
            if (!try {
                    initDlsym()
                } catch (t: Throwable) {
                    throw NativeTrackException("initDlsym() failed", t)
                }
            )
                throw NativeTrackException("initDlsym() returned false")
        }

        @JvmStatic
        @JvmName("bitsPerSampleForFormat")
        fun bitsPerSampleForFormat(format: UInt): Int {
            val cafOffloadMain = when {
                Build.VERSION.SDK_INT >= 25 -> null
                else -> 0x1A000000U
            }
            val normalized =
                if (cafOffloadMain != null && (format and 0xff000000U) == cafOffloadMain) {
                    format and (0xff000000U.inv())
                } else format
            return when (normalized) {
                0x1U, 0x0D000000U -> 16
                0x2U -> 8
                0x3U, 0x4U, 0x5U -> 32
                0x6U -> 24
                else -> 0
            }
        }

        @JvmStatic
        @JvmName("formatIsRawPcm")
        fun formatIsRawPcm(format: UInt) =
            (format and 0xff000000U /* AUDIO_FORMAT_MAIN_MASK */) == 0U

        private fun buildAudioFormat(
            sampleRate: Int,
            encoding: Int,
            channelMask: Int
        ): AudioFormat? {
            val formatBuilder = AudioFormat.Builder()
            try {
                formatBuilder.setSampleRate(sampleRate)
            } catch (_: IllegalArgumentException) {
                formatBuilder.setSampleRate(48000)
                try {
                    @SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
                    val field = formatBuilder.javaClass.getDeclaredField("mSampleRate")
                    field.isAccessible = true
                    field.set(formatBuilder, sampleRate)
                } catch (t: Throwable) {
                    Log.e(TAG, Log.getThrowableString(t)!!)
                    return null
                }
            }
            try {
                formatBuilder.setEncoding(encoding)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, Log.getThrowableString(e)!!)
                return null
            }
            return formatBuilder.setChannelMask(channelMask).build()
        }

        private external fun isOffloadSupported(
            sampleRate: Int, format: Int, channelMask: Int, bitRate: Int,
            bitWidth: Int, offloadBufferSize: Int
        ): Int

        private external fun initDlsym(): Boolean
    }

    class Builder(val context: Context, val attributes: AudioAttributes,
        val format: UInt, val channelMask: UInt) {
        var sampleRate: Int = 0
        var streamType: Int = STREAM_TYPE_DEFAULT
        var frameCount: Int? = null
        var trackFlags: Int = 0
        var sessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
        var maxRequiredSpeed: Float = 8f
        var selectedDeviceId: Int? = null
        var bitRate: Int = 0
        var durationUs: Long = 0L
        var hasVideo: Boolean = false
        var smallBuf: Boolean = false
        var isStreaming: Boolean = false
        var offloadBufferSize: Int = 0
        var notificationFrames: Int = 0
        var doNotReconnect: Boolean = false
        var transferMode: TransferMode = TransferMode.Sync
        var contentId: Int? = null
        var syncId: Int? = null
        var encapsulationMode: Int = ENCAPSULATION_MODE_NONE
        var sharedMem: ByteBuffer? = null

        companion object {
            @JvmName("create")
            @JvmStatic
            fun create(context: Context, attributes: AudioAttributes,
                        format: Int, channelMask: Int) = Builder(
                context, attributes, format.toUInt(), channelMask.toUInt())
        }

        fun setBufferSizeInBytes(buffSizeInBytes: Int) {
            val bps = bitsPerSampleForFormat(format) / 8
            val frameSize = if (bps != 0) bps * channelMask.countOneBits() else 1
            if ((buffSizeInBytes % frameSize) != 0)
                throw IllegalArgumentException("bad buffer size $buffSizeInBytes for fs $frameSize")
            frameCount = buffSizeInBytes / frameSize
        }

        fun build() = NativeTrack(this)
    }

    private val cachedFormat: UInt
    private val cachedChannelMask: UInt
    private val transferMode: TransferMode
    private var sessionId: Int
    private var cachedBuffer: ByteBuffer?
    val ptr: Long
    @Volatile
    var myState: State

    // proxy limitations: a lot of fields not initialized (mSampleRate, mAudioFormat, mOffloaded, ...) which can
    // cause some internal checks in various methods to fail; stream event and playback position callbacks both
    // are no-op; we MUST call play(), pause(), stop() and don't use the native methods ourselves for this to work;
    // we must also not cache playing/paused/stopped/volume ourselves because it may change under our feet.
    // however, it does:
    // - register (and overwrite) any codec format listeners on native side ; good for us because we can't register
    //   one in a normal way due to dependence on volatile offsets. i.e. with proxy we get codec format listeners!
    // - register player base (which we really should have on N+ to be a nice citizen and have stuff like ducking)
    // - register (and overwrite) routing callback, which is meh but we can just use the java one, it don't hurt
    // - allow for volume shapers! these would be near-impossible using the native API because it's all inline.
    // it's a bit fiddly, but we get all possibilities of a native AudioTrack and a Java one - combined.
    // reminder: do not call write() or any other standard APIs as we break a lot of assumptions. + proxy is not
    //  always available (i.e. L/M), hence native methods are preferable where we can.
    private val proxy: AudioTrack?
    private val codecListener: AudioTrack.OnCodecFormatChangedListener?
    private val routingListener: AudioRouting.OnRoutingChangedListener?
    private val audioManager: AudioManager

    init {
        if (builder.sharedMem?.isDirect == false)
            throw IllegalArgumentException("shared memory specified but isn't direct")
        if (builder.sharedMem == null && builder.transferMode == TransferMode.Shared)
            throw IllegalArgumentException("transfer mode is Shared but sharedMem is null")
        if (builder.sharedMem != null && builder.transferMode != TransferMode.Shared)
            throw IllegalArgumentException("transfer mode is not Shared but sharedMem is specified")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            builder.transferMode == @Suppress("NewApi") TransferMode.SyncWithCallback
        )
            throw IllegalArgumentException("SyncWithCallback not supported on this android version")
        if (builder.frameCount != null && builder.frameCount == 0)
            throw IllegalArgumentException("frameCount cannot be zero (did you mean to use null?)")
        if (builder.selectedDeviceId != null && builder.selectedDeviceId == 0)
            throw IllegalArgumentException("selectedDeviceId cannot be zero (did you mean to use null?)")
        if (builder.syncId != null && builder.syncId!! < 1)
            throw IllegalArgumentException("syncId must be positive (did you mean to use null?)")
        if (builder.contentId != null && builder.contentId!! < 0)
            throw IllegalArgumentException("contentId cannot be negative (did you mean to use null?)")
        if (builder.contentId == 0 && builder.syncId == null)
            throw IllegalArgumentException("CONTENT_ID_NONE with no syncId (did you mean to use null?)")
        prepareForLib()
        audioManager = builder.context.getSystemService<AudioManager>()!!
        ptr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ats = builder.context.attributionSource
            val parcel = Parcel.obtain()
            try {
                ats.writeToParcel(parcel, 0)
                try {
                    create(parcel)
                } catch (t: Throwable) {
                    throw NativeTrackException("create() threw exception", t)
                }
            } finally {
                parcel.recycle()
            }
        } else try {
            create(null)
        } catch (t: Throwable) {
            throw NativeTrackException("create() threw exception", t)
        }
        if (ptr == 0L) {
            throw NativeTrackException("create() returned NULL")
        }
        this.sessionId = if (builder.sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE)
            builder.context.getSystemService<AudioManager>()!!.generateAudioSessionId()
        else builder.sessionId
        val usage = builder.attributes.usage
        val contentType = builder.attributes.contentType
        val hasOutputFlagDeepBufferSet = (builder.trackFlags and AUDIO_OUTPUT_FLAG_DEEP_BUFFER) != 0
        val attrFlags = builder.attributes.flags or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
                    && builder.attributes.isContentSpatialized
                ) 0x4000 else 0) or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 &&
                    builder.attributes.spatializationBehavior
                    == AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
                ) 0x8000 else 0) or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && builder.attributes.areHapticChannelsMuted()
                ) 0x800 else 0) or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    when (builder.attributes.allowedCapturePolicy) {
                        AudioAttributes.ALLOW_CAPTURE_BY_NONE -> 0x1400
                        AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM -> 0x400
                        else -> 0x0
                    } else 0) or (if (hasOutputFlagDeepBufferSet) 0x200 else 0x0)
        val bitWidth = bitsPerSampleForFormat(builder.format)
        // java streamType is compatible with native streamType
        val ret = try {
            set(
                ptr = ptr,
                streamType = builder.streamType,
                sampleRate = builder.sampleRate,
                format = builder.format.toInt(),
                channelMask = builder.channelMask.toInt(),
                frameCount = builder.frameCount ?: 0,
                trackFlags = builder.trackFlags,
                sessionId = this.sessionId,
                maxRequiredSpeed = builder.maxRequiredSpeed,
                selectedDeviceId = builder.selectedDeviceId ?: 0,
                bitRate = builder.bitRate,
                durationUs = builder.durationUs,
                hasVideo = builder.hasVideo,
                smallBuf = builder.smallBuf,
                isStreaming = builder.isStreaming,
                bitWidth = bitWidth,
                offloadBufferSize = builder.offloadBufferSize,
                usage = usage,
                contentType = contentType,
                attrFlags = attrFlags,
                notificationFrames = builder.notificationFrames,
                doNotReconnect = builder.doNotReconnect,
                transferMode = builder.transferMode.id,
                contentId = builder.contentId ?: 0,
                syncId = builder.syncId ?: 0,
                encapsulationMode = builder.encapsulationMode,
                sharedMem = builder.sharedMem
            )
        } catch (t: Throwable) {
            try {
                dtor(ptr)
            } catch (t2: Throwable) {
                throw NativeTrackException(
                    "dtor() threw exception after set() threw exception: " +
                            Log.getThrowableString(t2)!!, t
                )
            }
            throw NativeTrackException("set() threw exception", t)
        }
        cachedFormat = builder.format
        cachedChannelMask = builder.channelMask
        cachedBuffer = builder.sharedMem
        this.transferMode = builder.transferMode
        if (ret != 0) {
            try {
                dtor(ptr)
            } catch (t: Throwable) {
                throw NativeTrackException(
                    "dtor() threw exception after set() failed with code $ret",
                    t
                )
            }
            throw NativeTrackException("set() failed with code $ret")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            proxy = try {
                getProxy(ptr, this.sessionId)
            } catch (t: Throwable) {
                try {
                    dtor(ptr)
                } catch (t2: Throwable) {
                    throw NativeTrackException(
                        "dtor() threw exception after getProxy() threw exception: " +
                                Log.getThrowableString(t2)!!, t
                    )
                }
                throw NativeTrackException("getProxy() threw exception", t)
            }
            if (proxy == null) {
                try {
                    dtor(ptr)
                } catch (t: Throwable) {
                    throw NativeTrackException(
                        "dtor() threw exception after getProxy() returned null, " +
                                "check prior logs", t
                    )
                }
                throw NativeTrackException("getProxy() returned null, check prior logs")
            }
            routingListener =
                AudioRouting.OnRoutingChangedListener { this@NativeTrack.onRoutingChanged() }
            proxy.addOnRoutingChangedListener(routingListener, null)
        } else {
            proxy = null
            routingListener = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            codecListener = AudioTrack.OnCodecFormatChangedListener { audioTrack, info ->
                this@NativeTrack.onCodecFormatChanged(info)
            }
            proxy!!.addOnCodecFormatChangedListener({ r -> r.run() }, codecListener)
        } else codecListener = null
        myState = State.ALIVE
    }

    private external fun create(parcel: Parcel?): Long

    /*
     * CAUTION: Until including Android 7.1, direct outputs could be reused even with different session IDs.
     *          If another app is using a direct (or offload) stream, we might end up with no audio (there can
     *          only ever be one client). However, this problem is isolated to MediaPlayer using compressed offload
     *          (or another app like us doing that), and us using hidden API to offload in the same format, sample
     *          rate and channel mask. Audio focus sadly isn't enough as the track needs to be released to avoid
     *          this bug, so either avoid other media player apps or using compressed offload on these versions.
     *
     * CAUTION: From Android 7.0 until Android 8.1, direct outputs with PCM modes int24, int32 or float32 were all
     *          treated as compatible. To avoid track creation failures caused by ourselves, we should always
     *          release active tracks on any direct output before attempting to switch formats - otherwise it may
     *          try to reuse the track despite the different format. But if we're unlucky, on Android 7.x only, we
     *          may get a busy output with a different format anyway because another app has an active direct PCM
     *          track - which will result in set() failing. There's another case where set() could fail: on N/O,
     *          when we request float32, APM may select int32 instead (because both have the same bit width) - if
     *          we request int32, we may get float32; and with some bad luck, if we request a 32-bit format, we may
     *          even get int24 (if no 32 bit format is supported) - or when requesting int24, we may get a 32-bit
     *          format (if int24 is not supported). After APM gives us that output, AF will fail creating the track
     *          causing set() to fail. In that case, we have to try again with another format.
     */
    private external fun set(
        ptr: Long, streamType: Int, sampleRate: Int, format: Int, channelMask: Int,
        frameCount: Int, trackFlags: Int, sessionId: Int, maxRequiredSpeed: Float,
        selectedDeviceId: Int, bitRate: Int, durationUs: Long, hasVideo: Boolean,
        smallBuf: Boolean, isStreaming: Boolean, bitWidth: Int, offloadBufferSize: Int,
        usage: Int, contentType: Int, attrFlags: Int, notificationFrames: Int,
        doNotReconnect: Boolean, transferMode: Int, contentId: Int, syncId: Int,
        encapsulationMode: Int, sharedMem: ByteBuffer? /* direct */
    ): Int

    private external fun getRealPtr(ptr: Long): Long
    private external fun notificationFramesActFromOffset(ptr: Long): Int
    private external fun dtor(ptr: Long)

    @RequiresApi(Build.VERSION_CODES.N)
    private external fun getProxy(ptr: Long, sessionId: Int): AudioTrack?

    fun release() {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState already")
        myState = State.RELEASED
        cachedBuffer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && codecListener != null) {
            proxy!!.removeOnCodecFormatChangedListener(codecListener)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            proxy!!.removeOnRoutingChangedListener(routingListener)
            proxy.release() // this doesn't free native obj because we hold extra strong ref, cleared in dtor()
        }
        dtor(ptr)
    }

    fun dump(): String {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            AudioTrackHiddenApi.dumpInternal(getRealPtr(ptr))
        } catch (t: Throwable) {
            throw NativeTrackException("failed to dump", t)
        }
    }

    fun state(): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        return AudioTrackHiddenApi.getStateFromDump(dump())
            ?: throw IllegalStateException("state failed, check prior logs")
    }

    fun isPlaying(): Boolean {
        return getPlayState() == AudioTrack.PLAYSTATE_PLAYING
    }

    // simplified to match android.media.AudioTrack
    fun getPlayState(): Int {
        // TODO: interaction with the simulated offload play state might be wrong here
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            return proxy!!.playState
        return when (val state = state()) {
            STATE_ACTIVE, STATE_STOPPING -> AudioTrack.PLAYSTATE_PLAYING
            STATE_STOPPED, STATE_FLUSHED -> AudioTrack.PLAYSTATE_STOPPED
            STATE_PAUSED, STATE_PAUSED_STOPPING -> AudioTrack.PLAYSTATE_PAUSED
            else -> throw IllegalStateException("invalid state $state")
        }
    }

    fun frameCount(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            return proxy!!.bufferSizeInFrames
        return AudioTrackHiddenApi.getFrameCountFromDump(dump())
            ?: throw IllegalStateException("frameCount failed, check prior logs")
    }

    // alias to match android.media.AudioTrack
    fun getBufferSizeInFrames() = frameCount()

    @JvmName("format")
    fun format(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return cachedFormat
    }

    fun sessionId() = sessionId

    // alias to match android.media.AudioTrack
    fun getAudioSessionId() = sessionId()

    /**
     * The accuracy of this method depends on the Android version:
     * Android 8.0 or later / Non-CAF Android 7.1: all flags are adjusted to match output capabilities
     * CAF Android 7.0 / 7.1: system only adjusts fast flag, we adjust direct flag through a trick
     * Non-CAF Android 7.0: system only adjusts fast flag
     * (because Audio HALs from Android 7.x time don't support using compressed formats in for anything except
     * passthrough or offload, we can assume that if we request offload, we get offload, passthrough, or a creation
     * failure. this last disambiguation must be done by end user based on mix port name.)
     * Android 5.x / 6.x: system only adjusts fast, direct and offload flag
     */
    fun flags(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            AudioTrackHiddenApi.getFlagsInternal(proxy, getRealPtr(ptr))
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get flags", t)
        }.let {
            if (it == Int.MAX_VALUE || it == Int.MIN_VALUE)
                throw NativeTrackException("something went wrong while getting flags, check prior logs")
            else it
        }
    }

    fun notificationPeriodInFrames(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            AudioTrackHiddenApi.getNotificationFramesActFromDump(dump())
                ?: throw IllegalStateException("notificationFramesAct failed, check prior logs")
        else try {
            notificationFramesActFromOffset(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get notificationFramesAct", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun policyPortId(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return AudioTrackHiddenApi.getPortIdFromDump(dump())
            ?: throw IllegalStateException("getPortId failed, check prior logs")
    }

    @JvmName("channelMask")
    fun channelMask(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return cachedChannelMask
    }

    @JvmName("latency")
    fun latency(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            try {
                (AudioTrack::class.java.getMethod("getLatency").invoke(proxy) as Int).toUInt()
            } catch (t: Throwable) {
                throw NativeTrackException("getLatency failed", t)
            }
        else
            (AudioTrackHiddenApi.getLatencyFromDump(dump())
                ?: throw NativeTrackException("getLatencyFromDump failed, see prior logs")).toUInt()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun getUnderrunCount(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.underrunCount
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @JvmName("getBufferDurationInUs")
    fun getBufferDurationInUs(): ULong {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val ret = try {
            getBufferDurationInUsInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get buffer duration us", t)
        }
        if (ret == DEAD_OBJECT.toLong()) {
            myState = State.DEAD_OBJECT
            throw NativeTrackException("getBufferDurationInUs() failed, track died")
        }
        if (ret < 0) {
            throw NativeTrackException("getBufferDurationInUs() failed: $ret")
        }
        return ret.toULong()
    }

    private external fun getBufferDurationInUsInternal(ptr: Long): Long

    @RequiresApi(Build.VERSION_CODES.N)
    fun setBufferSizeInFrames(size: Int) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        proxy!!.bufferSizeInFrames = size
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun getBufferCapacityInFrames(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.bufferCapacityInFrames
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun getStartThresholdInFrames(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.startThresholdInFrames
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun setStartThresholdInFrames(size: Int) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        proxy!!.startThresholdInFrames = size
    }

    fun sharedBuffer(): ByteBuffer {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (transferMode != TransferMode.Shared)
            throw IllegalStateException("transfer mode isn't shared, sharedBuffer() can't be called")
        return cachedBuffer!!
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun getMetrics(): PersistableBundle {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.metrics
    }

    fun start() {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (proxy != null) {
            proxy.play()
        } else {
            val ret = try {
                startInternal(ptr)
            } catch (t: Throwable) {
                throw NativeTrackException("failed to play", t)
            }
            if (ret == DEAD_OBJECT) {
                myState = State.DEAD_OBJECT
                throw NativeTrackException("start() failed, track died")
            }
            if (ret != 0) {
                throw NativeTrackException("start() failed: $ret")
            }
        }
    }

    // alias to match android.media.AudioTrack
    fun play() = start()

    private external fun startInternal(ptr: Long): Int

    fun stop() {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (proxy != null) {
            proxy.stop()
        } else {
            try {
                stopInternal(ptr)
            } catch (t: Throwable) {
                throw NativeTrackException("failed to stop", t)
            }
        }
    }

    private external fun stopInternal(ptr: Long)

    fun stopped(): Boolean {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            stoppedInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to check if stopped", t)
        }
    }

    private external fun stoppedInternal(ptr: Long): Boolean

    fun flush() {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        try {
            flushInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to flush", t)
        }
    }

    private external fun flushInternal(ptr: Long)

    fun pause() {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (proxy != null) {
            proxy.pause()
        } else {
            try {
                pauseInternal(ptr)
            } catch (t: Throwable) {
                throw NativeTrackException("failed to pause", t)
            }
        }
    }

    private external fun pauseInternal(ptr: Long)

    @RequiresApi(Build.VERSION_CODES.S_V2)
    @JvmName("pauseAndWait")
    fun pauseAndWait(timeoutMs: ULong): Boolean {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            pauseAndWaitInternal(ptr, timeoutMs.toLong())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to pause", t)
        }
        // no-op as far as track is concerned, but java object and system should be notified about the pause.
        proxy?.pause()
        return ret
    }

    private external fun pauseAndWaitInternal(ptr: Long, timeoutMs: Long): Boolean

    fun setVolume(volume: Float) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (proxy != null) {
            proxy.setVolume(volume)
        } else {
            val ret = try {
                setVolumeInternal(ptr, volume)
            } catch (t: Throwable) {
                throw NativeTrackException("failed to set volume to $volume", t)
            }
            if (ret != 0) {
                throw NativeTrackException("setVolume($volume) failed: $ret")
            }
        }
    }

    private external fun setVolumeInternal(ptr: Long, volume: Float): Int

    fun setAuxEffectSendLevel(level: Float): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        return if (proxy != null) {
            proxy.setAuxEffectSendLevel(level)
        } else {
            try {
                setAuxEffectSendLevelInternal(ptr, level)
            } catch (t: Throwable) {
                throw NativeTrackException("failed to set aux effect send level to $level", t)
            }
        }
    }

    private external fun setAuxEffectSendLevelInternal(ptr: Long, level: Float): Int

    fun getAuxEffectSendLevel(): Float {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getAuxEffectSendLevelInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get aux effect send level", t)
        }
    }

    private external fun getAuxEffectSendLevelInternal(ptr: Long): Float

    @JvmName("setSampleRate")
    fun setSampleRate(rate: UInt) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setSampleRateInternal(ptr, rate.toInt())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set sample rate to $rate", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setSampleRate($rate) failed: $ret")
        }
    }

    private external fun setSampleRateInternal(ptr: Long, rate: Int): Int

    @JvmName("getPlaybackSampleRate")
    fun getPlaybackSampleRate(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getSampleRateInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get sample rate", t)
        }.toUInt()
    }

    private external fun getSampleRateInternal(ptr: Long): Int

    @JvmName("getOriginalSampleRate")
    fun getOriginalSampleRate(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getOriginalSampleRateInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get sample rate", t)
        }.toUInt()
    }

    private external fun getOriginalSampleRateInternal(ptr: Long): Int

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @JvmName("getHalSampleRate")
    fun getHalSampleRate(): UInt {
        return AudioTrackHiddenApi.getHalSampleRate(proxy!!) ?: 0u
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @JvmName("getHalChannelCount")
    fun getHalChannelCount(): Int {
        return AudioTrackHiddenApi.getHalChannelCount(proxy!!) ?: 0
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @JvmName("getHalFormat")
    fun getHalFormat(): UInt {
        return AudioTrackHiddenApi.getHalFormat(proxy!!) ?: 0u
    }

    fun setPlaybackRate(rate: PlaybackRate) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setPlaybackRateInternal(
                ptr,
                rate.speed,
                rate.pitch,
                if (rate.stretchForVoice) 1 else 0,
                when (rate.fallback) {
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_CUT_REPEAT -> -1
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_DEFAULT -> 0
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_MUTE -> 1
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_FAIL -> 2
                }
            )
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set playback rate to $rate", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setPlaybackRate($rate) failed: $ret")
        }
    }

    // simplified for android.media.AudioTrack
    fun setPlaybackParams(params: PlaybackParams) {
        setPlaybackRate(PlaybackRate(
            speed = params.speed,
            pitch = params.pitch,
            fallback = when (params.audioFallbackMode) {
                PlaybackParams.AUDIO_FALLBACK_MODE_FAIL ->
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_FAIL
                PlaybackParams.AUDIO_FALLBACK_MODE_MUTE ->
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_MUTE
                else ->
                    StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_DEFAULT
            },
            stretchForVoice = false
        ))
    }

    private external fun setPlaybackRateInternal(
        ptr: Long,
        speed: Float,
        pitch: Float,
        stretchMode: Int,
        fallback: Int
    ): Int

    enum class StretchFallbackMode {
        AUDIO_TIMESTRETCH_FALLBACK_CUT_REPEAT,
        AUDIO_TIMESTRETCH_FALLBACK_DEFAULT,
        AUDIO_TIMESTRETCH_FALLBACK_MUTE,
        AUDIO_TIMESTRETCH_FALLBACK_FAIL
    }

    data class PlaybackRate(
        val speed: Float,
        val pitch: Float,
        val stretchForVoice: Boolean,
        val fallback: StretchFallbackMode
    )

    fun getPlaybackRate(): PlaybackRate {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val speedPitch = FloatArray(2)
        val ret = getPlaybackRateInternal(ptr, speedPitch).toULong()
        val stretchForVoice = ((ret and 0xffffffff00000000UL) shr 32).toInt()
        val fallbackMode = (ret and 0x00000000ffffffffUL).toUInt().toInt()
        return PlaybackRate(
            speedPitch[0], speedPitch[1],
            stretchForVoice == 1, when (fallbackMode) {
                -1 -> StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_CUT_REPEAT
                0 -> StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_DEFAULT
                1 -> StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_MUTE
                2 -> StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_FAIL
                else -> throw IllegalArgumentException("timestretch $fallbackMode")
            }
        )
    }

    // simplified for android.media.AudioTrack
    fun getPlaybackParams() = PlaybackParams().apply {
        val rate = getPlaybackRate()
        setPitch(rate.pitch)
        setSpeed(rate.speed)
        setAudioFallbackMode(when (rate.fallback) {
            StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_MUTE -> PlaybackParams.AUDIO_FALLBACK_MODE_MUTE
            StretchFallbackMode.AUDIO_TIMESTRETCH_FALLBACK_FAIL -> PlaybackParams.AUDIO_FALLBACK_MODE_FAIL
            else -> PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT
        })
    }

    private external fun getPlaybackRateInternal(ptr: Long, speedPitch: FloatArray): Long

    @RequiresApi(Build.VERSION_CODES.S)
    fun setDualMonoMode(dualMonoMode: Int) {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        proxy!!.setDualMonoMode(dualMonoMode)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun getDualMonoMode(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.dualMonoMode
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun setAudioDescriptionMixLevel(level: Float) {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        proxy!!.audioDescriptionMixLeveldB = level
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun getAudioDescriptionMixLevel(): Float {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.audioDescriptionMixLeveldB
    }

    @JvmName("setLoop")
    fun setLoop(loopStart: UInt, loopEnd: UInt, loopCount: Int) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setLoopInternal(ptr, loopStart.toInt(), loopEnd.toInt(), loopCount)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set loop to $loopStart/$loopEnd/$loopCount", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setLoop($loopStart, $loopEnd, $loopCount) failed: $ret")
        }
    }

    // alias to match android.media.AudioTrack
    @JvmName("setLoopPoints")
    fun setLoopPoints(loopStart: UInt, loopEnd: UInt, loopCount: Int) =
        setLoop(loopStart, loopEnd, loopCount)

    private external fun setLoopInternal(
        ptr: Long,
        loopStart: Int,
        loopEnd: Int,
        loopCount: Int
    ): Int

    @JvmName("setMarkerPosition")
    fun setMarkerPosition(markerPosition: UInt) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setMarkerPositionInternal(ptr, markerPosition.toInt())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set marker pos to $markerPosition", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setMarkerPosition($markerPosition) failed: $ret")
        }
    }

    // alias for android.media.AudioTrack
    @JvmName("setNotificationMarkerPosition")
    fun setNotificationMarkerPosition(markerPosition: UInt) = setMarkerPosition(markerPosition)

    private external fun setMarkerPositionInternal(ptr: Long, pos: Int): Int

    @JvmName("getMarkerPosition")
    fun getMarkerPosition(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val comboRet = try {
            getMarkerPositionInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get marker pos", t)
        }.toULong()
        val ret = ((comboRet and 0xffffffff00000000UL) shr 32).toInt()
        val data = (comboRet and 0x00000000ffffffffUL).toUInt()
        if (ret != 0) {
            throw NativeTrackException("getMarkerPosition() failed: $ret (data=$data)")
        }
        return data
    }

    // alias for android.media.AudioTrack
    @JvmName("getNotificationMarkerPosition")
    fun getNotificationMarkerPosition() = getMarkerPosition()

    private external fun getMarkerPositionInternal(ptr: Long): Long

    @JvmName("setPositionUpdatePeriod")
    fun setPositionUpdatePeriod(pos: UInt) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setPositionUpdatePeriodInternal(ptr, pos.toInt())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set pos update period to $pos", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setPositionUpdatePeriod($pos) failed: $ret")
        }
    }

    // alias to match android.media.AudioTrack
    @JvmName("setPositionNotificationPeriod")
    fun setPositionNotificationPeriod(pos: UInt) = setPositionUpdatePeriod(pos)

    private external fun setPositionUpdatePeriodInternal(ptr: Long, pos: Int): Int

    @JvmName("getPositionUpdatePeriod")
    fun getPositionUpdatePeriod(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val comboRet = try {
            getPositionUpdatePeriodInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get pos update period", t)
        }.toULong()
        val ret = ((comboRet and 0xffffffff00000000UL) shr 32).toInt()
        val data = (comboRet and 0x00000000ffffffffUL).toUInt()
        if (ret != 0) {
            throw NativeTrackException("getPositionUpdatePeriod() failed: $ret (data=$data)")
        }
        return data
    }

    // alias to match android.media.AudioTrack
    fun getPositionNotificationPeriod() = getPositionUpdatePeriod()

    private external fun getPositionUpdatePeriodInternal(ptr: Long): Long

    @JvmName("setPosition")
    fun setPosition(pos: UInt) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setPositionInternal(ptr, pos.toInt())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set pos to $pos", t)
        }
        if (ret != 0) {
            throw NativeTrackException("setPosition($pos) failed: $ret")
        }
    }

    // alias to match android.media.AudioTrack
    @JvmName("setPlaybackHeadPosition")
    fun setPlaybackHeadPosition(pos: UInt) = setPosition(pos)

    private external fun setPositionInternal(ptr: Long, pos: Int): Int

    @JvmName("getPosition")
    fun getPosition(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val comboRet = try {
            getPositionInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get pos", t)
        }.toULong()
        val ret = ((comboRet and 0xffffffff00000000UL) shr 32).toInt()
        val data = (comboRet and 0x00000000ffffffffUL).toUInt()
        if (ret != 0) {
            throw NativeTrackException("getPosition() failed: $ret (data=$data)")
        }
        return data
    }

    // alias to match android.media.AudioTrack
    @JvmName("getPlaybackHeadPosition")
    fun getPlaybackHeadPosition() = getPosition()

    private external fun getPositionInternal(ptr: Long): Long

    @JvmName("getBufferPosition")
    fun getBufferPosition(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val comboRet = try {
            getBufferPositionInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get buffer pos", t)
        }.toULong()
        val ret = ((comboRet and 0xffffffff00000000UL) shr 32).toInt()
        val data = (comboRet and 0x00000000ffffffffUL).toUInt()
        if (ret != 0) {
            throw NativeTrackException("getBufferPosition() failed: $ret (data=$data)")
        }
        return data
    }

    private external fun getBufferPositionInternal(ptr: Long): Long

    fun reload(): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        return try {
            reloadInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to reload", t)
        }
    }

    // alias to match android.media.AudioTrack
    fun reloadStaticData() = reload()

    private external fun reloadInternal(ptr: Long): Int

    fun getAudioTrackPtr(): Long {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getAudioTrackPtrInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get audio track ptr", t)
        }
    }

    private external fun getAudioTrackPtrInternal(ptr: Long): Long

    fun getOutput(): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getOutputInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get output", t)
        }
    }

    private external fun getOutputInternal(ptr: Long): Int

    fun setSelectedDevice(audioDeviceInfo: AudioDeviceInfo?): Boolean {
        if (audioDeviceInfo != null && !audioDeviceInfo.isSink)
            return false
        val id = audioDeviceInfo?.id ?: 0 /* AUDIO_PORT_HANDLE_NONE */
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setSelectedDeviceInternal(ptr, id)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set selected device", t)
        }
        if (ret == DEAD_OBJECT) {
            myState = State.DEAD_OBJECT
            throw NativeTrackException("setSelectedDevice($id) failed, track died")
        }
        if (ret != 0) {
            Log.w(TAG, "setSelectedDevice($id) failed: $ret")
            return false
        }
        return true
    }

    // alias for android.media.AudioTrack
    fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = setSelectedDevice(audioDeviceInfo)

    private external fun setSelectedDeviceInternal(ptr: Long, id: Int): Int

    fun getSelectedDevice(): AudioDeviceInfo? {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val id = try {
            getSelectedDeviceInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set selected device", t)
        }
        if (id == 0) return null
        // this is somewhat racy, we can lose a device between these two calls, but shrug
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == id }
        return device
    }

    private external fun getSelectedDeviceInternal(ptr: Long): Int

    fun getRoutedDevices(): List<AudioDeviceInfo> {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ids = try {
            getRoutedDevicesInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set selected device", t)
        }
        // this is somewhat racy, we can lose a device between these two calls, but shrug
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return ids.map { id -> devices.find { it.id == id } }.filterNotNull()
    }

    // alias to match android.media.AudioTrack
    fun getRoutedDevice() = getRoutedDevices().firstOrNull()

    private external fun getRoutedDevicesInternal(ptr: Long): IntArray

    fun attachAuxEffect(effectId: Int): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            attachAuxEffectInternal(ptr, effectId)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to attach aux effect $effectId", t)
        }
        if (ret == DEAD_OBJECT) {
            myState = State.DEAD_OBJECT
            throw NativeTrackException("attachAuxEffect($effectId) failed, track died")
        }
        return ret
    }

    private external fun attachAuxEffectInternal(ptr: Long, effectId: Int): Int

    fun obtainBufferWithNonContig(requestedFrames: Long, waitCount: Int): Pair<ByteBuffer, Long> {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val nc = LongArray(1)
        val ret = try {
            obtainBufferInternal(ptr, frameSize(), waitCount, nc, requestedFrames)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to obtain buffer of $requestedFrames frames", t)
        }
        if (ret == null) {
            throw NativeTrackException("failed to obtain buffer of $requestedFrames frames, check prior logs")
        }
        return ret to nc[0]
    }

    fun obtainBuffer(requestedFrames: Long, waitCount: Int): ByteBuffer {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            obtainBufferInternal(ptr, frameSize(), waitCount, null, requestedFrames)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to obtain buffer of $requestedFrames frames", t)
        }
        if (ret == null) {
            throw NativeTrackException("failed to obtain buffer of $requestedFrames frames, check prior logs")
        }
        return ret
    }

    private external fun obtainBufferInternal(
        ptr: Long, frameSize: Int, waitCount: Int, nonContig: LongArray?,
        requestedFrameCount: Long
    ): ByteBuffer?

    /** set limit to amount of written bytes, and don't call any method on buf after giving it to this method */
    fun releaseBuffer(buf: ByteBuffer) {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        try {
            releaseBufferInternal(ptr, frameSize(), buf, buf.limit())
        } catch (t: Throwable) {
            throw NativeTrackException("failed to release buffer $buf", t)
        }
    }

    private external fun releaseBufferInternal(
        ptr: Long,
        frameSize: Int,
        buf: ByteBuffer,
        limit: Int
    )

    fun write(buf: ByteBuffer, offset: Int?, size: Int?, blocking: Boolean): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        if (!buf.isDirect) {
            val ret = write(
                buf.array(), buf.arrayOffset() + (offset ?: buf.position()),
                size ?: (buf.limit() - (offset ?: buf.position())), blocking
            )
            if (ret > 0)
                buf.position(buf.position() + ret)
            return ret
        }
        // TODO replicate blockUntilOffloadDrain()
        val ret = try {
            writeInternal(
                ptr, buf, offset ?: buf.position(),
                size ?: (buf.limit() - (offset ?: buf.position())), blocking
            )
        } catch (t: Throwable) {
            throw NativeTrackException("write($buf / $blocking) failed", t)
        }
        if (ret > 0 && offset == null)
            buf.position(buf.position() + ret.toInt())
        return handleWriteRet(ret)
    }

    fun write(buf: ByteArray, offset: Int, size: Int?, blocking: Boolean): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        // TODO replicate blockUntilOffloadDrain()
        val ret = try {
            writeInternal(ptr, buf, offset, size ?: buf.size, blocking)
        } catch (t: Throwable) {
            throw NativeTrackException("write(${buf.size} / $blocking) failed", t)
        }
        return handleWriteRet(ret)
    }

    fun write(buf: FloatArray, offset: Int, size: Int?, blocking: Boolean): Int {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        // TODO assert format is float
        // TODO replicate blockUntilOffloadDrain()
        val ret = try {
            writeInternal(ptr, buf, offset, size ?: buf.size, blocking)
        } catch (t: Throwable) {
            throw NativeTrackException("write(${buf.size} / $blocking) failed", t)
        }
        return handleWriteRet(ret)
    }

    fun write(buf: ByteBuffer, offset: Int?, size: Int?, blocking: Boolean, timestamp: Long): Int {
        TODO("Implement HW_AV_SYNC write API")
    }

    private fun handleWriteRet(ret: Long): Int {
        var ret = ret
        if (ret == DEAD_OBJECT.toLong() || ret == NO_INIT) {
            myState = State.DEAD_OBJECT
        }
        if (ret == WOULD_BLOCK)
            ret = 0L
        return ret.toInt()
    }

    private external fun writeInternal(
        ptr: Long,
        buf: ByteBuffer,
        offset: Int,
        size: Int,
        blocking: Boolean
    ): Long

    private external fun writeInternal(
        ptr: Long,
        buf: ByteArray,
        offset: Int,
        size: Int,
        blocking: Boolean
    ): Long

    private external fun writeInternal(
        ptr: Long,
        buf: FloatArray,
        offset: Int,
        size: Int,
        blocking: Boolean
    ): Long

    fun channelCount(): Int {
        return Integer.bitCount(channelMask().toInt())
    }

    fun frameSize(): Int { // in bytes
        val bps = bitsPerSampleForFormat(format())
        if (bps == 0) // compressed
            return 1
        return channelCount() * (bps / 8)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun createVolumeShaper(config: VolumeShaper.Configuration): VolumeShaper {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        return proxy!!.createVolumeShaper(config)
    }

    @JvmName("getUnderrunFrames")
    fun getUnderrunFrames(): UInt {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            getUnderrunFramesInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get underrun frames", t)
        }.toUInt()
    }

    private external fun getUnderrunFramesInternal(ptr: Long): Int

    fun setParameters(params: String) {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        val ret = try {
            setParametersInternal(ptr, params)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to set parameters $params", t)
        }
        if (ret == DEAD_OBJECT) {
            myState = State.DEAD_OBJECT
            throw NativeTrackException("setParameters() failed, track died")
        }
        if (ret != 0) {
            throw NativeTrackException("setParameters($params) failed: $ret")
        }
    }

    private external fun setParametersInternal(ptr: Long, params: String): Int

    fun getParameters(params: String): String {
        if (myState != State.ALIVE)
            throw IllegalStateException("state is $myState")
        return try {
            getParametersInternal(ptr, params)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get parameters $params", t)
        }
    }

    private external fun getParametersInternal(ptr: Long, params: String): String

    @RequiresApi(Build.VERSION_CODES.P)
    fun selectPresentation(presentation: AudioPresentation) {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val ret = proxy!!.setPresentation(presentation)
        if (ret != 0) {
            throw NativeTrackException("selectPresentation failed: $ret")
        }
    }

    // alias to match android.media.AudioTrack
    @RequiresApi(Build.VERSION_CODES.P)
    fun setPresentation(presentation: AudioPresentation) = selectPresentation(presentation)

    /**
     * Retrieve current position in frames and anchor realtime in nanoseconds.
     */
    fun getTimestamp(out: AudioTimestamp): Boolean {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        // It's unfortunate, but we have to either create garbage every time or use synchronized
        val temp = LongArray(2)
        val ret = try {
            getTimestampInternal(ptr, temp)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get timestamps", t)
        }
        if (ret == DEAD_OBJECT) {
            myState = State.DEAD_OBJECT
        }
        if (ret != 0) {
            return false
        }
        out.framePosition = temp[0]
        out.nanoTime = temp[1]
        return true
    }

    private external fun getTimestampInternal(ptr: Long, out: LongArray): Int

    enum class Timebase {
        Monotonic,
        Boottime,
    }

    class ExtendedTimestamp(
        private val mPosition: LongArray, private val mTimeNs: LongArray,
        private val mTimebaseOffset: LongArray, val mFlushed: Long
    ) {
        data class Timestamp(
            val position: Long,
            val time: Long,
            val timebase: Timebase,
            val location: TimestampLocation
        )

        fun getBestTimestamp(timebase: Timebase): Timestamp? {
            getTimestamp(TimestampLocation.Kernel, timebase)?.let { return it }
            return getTimestamp(TimestampLocation.Server, timebase)
        }

        fun getTimestamp(location: TimestampLocation, timebase: Timebase): Timestamp? {
            val i = when (location) {
                TimestampLocation.Client -> 0
                TimestampLocation.Server -> 1
                TimestampLocation.Kernel -> 2
                TimestampLocation.ServerPriorToLastKernelOk -> 3
                TimestampLocation.KernelPriorToLastKernelOk -> 4
            }
            if (mTimeNs[i] > 0) {
                return Timestamp(
                    mPosition[i], mTimeNs[i] +
                            mTimebaseOffset[if (timebase == Timebase.Boottime) 1 else 0], timebase,
                    if (i == 2) TimestampLocation.Kernel else TimestampLocation.Server
                )
            }
            return null
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun getTimestamp(): ExtendedTimestamp {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val mPosition = LongArray(5)
        val mTimeNs = LongArray(5)
        val mTimebaseOffset = LongArray(2)
        val mFlushed = LongArray(1)
        val ret = try {
            getTimestamp2Internal(ptr, mPosition, mTimeNs, mTimebaseOffset, mFlushed)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get ext timestamps", t)
        }
        if (ret != 0) {
            throw NativeTrackException("getTimestamp() failed: $ret")
        }
        return ExtendedTimestamp(mPosition, mTimeNs, mTimebaseOffset, mFlushed[0])
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private external fun getTimestamp2Internal(
        ptr: Long, mPosition: LongArray, mTimeNs: LongArray,
        mTimebaseOffset: LongArray, mFlushed: LongArray
    ): Int

    enum class TimestampLocation {
        Client,
        Server,
        Kernel,
        ServerPriorToLastKernelOk,
        KernelPriorToLastKernelOk,
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun pendingDuration(location: TimestampLocation): Int {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        val location2 = when (location) {
            TimestampLocation.Client -> 1
            TimestampLocation.Server -> 2
            TimestampLocation.Kernel -> 3
            TimestampLocation.ServerPriorToLastKernelOk -> 4
            TimestampLocation.KernelPriorToLastKernelOk -> 5
        }
        val data = try {
            pendingDurationInternal(ptr, location2)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to get pending duration from $location", t)
        }
        val ret = (data shr 32).toInt()
        val out = data.toInt()
        if (ret != 0) {
            throw NativeTrackException("failed to get pending duration from $location, ret = $ret")
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private external fun pendingDurationInternal(ptr: Long, location: Int): Long

    @RequiresApi(Build.VERSION_CODES.O)
    fun hasStarted(): Boolean {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return try {
            hasStartedInternal(ptr)
        } catch (t: Throwable) {
            throw NativeTrackException("failed to check if stopped", t)
        }
    }

    private external fun hasStartedInternal(ptr: Long): Boolean

    @RequiresApi(Build.VERSION_CODES.S)
    fun setLogSessionId(params: LogSessionId) {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        proxy!!.logSessionId = params
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun getLogSessionId(): LogSessionId {
        if (myState == State.RELEASED)
            throw IllegalStateException("state is $myState")
        return proxy!!.logSessionId
    }

    class NativeTrackException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    enum class State {
        DEAD_OBJECT, // we got killed by lower layer
        RELEASED, // release() called
        ALIVE, // ready to use
    }

    fun interface OnRoutingChangedListener {
        fun onRoutingChanged(router: NativeTrack)
    }

    interface OnPlaybackPositionUpdateListener {
        fun onMarkerReached(track: NativeTrack, markerPosition: Int)
        fun onPeriodicNotification(track: NativeTrack, newPos: Int)
    }

    interface StreamEventCallback {
        fun onDataRequest(track: NativeTrack, frameCount: Long, sizeBytes: Long)
        fun onTearDown(track: NativeTrack)
        fun onPresentationEnded(track: NativeTrack)
    }

    private val mPlaybackPositionListeners: ArrayMap<OnPlaybackPositionUpdateListener, Handler?> =
        ArrayMap<OnPlaybackPositionUpdateListener, Handler?>()

    fun addOnPlaybackPositionUpdateListener(listener: OnPlaybackPositionUpdateListener?, handler: Handler?) {
        if (listener != null && !mPlaybackPositionListeners.containsKey(listener)) {
            synchronized(mPlaybackPositionListeners) {
                mPlaybackPositionListeners.put(listener, handler)
            }
        }
    }

    fun removeOnPlaybackPositionUpdateListener(listener: OnPlaybackPositionUpdateListener?) {
        synchronized(mPlaybackPositionListeners) {
            if (mPlaybackPositionListeners.containsKey(listener)) {
                mPlaybackPositionListeners.remove(listener)
            }
        }
    }

    private inline fun <T> runCallbacks(listeners: ArrayMap<T, Handler?>,
                                        crossinline callback: (T) -> Unit) {
        val listeners = synchronized(listeners) { listeners.entries }
        listeners.forEach { (listener, handler) ->
            if (handler != null)
                handler.post { callback(listener) }
            else callback(listener)
        }
    }

    // called from native, on callback thread (not main thread!)
    private fun onUnderrun() {
        // TODO: not impl'ed for now, can be done later if needed...
    }

    // called from native, on callback thread (not main thread!)
    private fun onMarker(markerPosition: Int) {
        runCallbacks(mPlaybackPositionListeners) {
            it.onMarkerReached(this, markerPosition)
        }
    }

    // called from native, on callback thread (not main thread!)
    private fun onNewPos(newPos: Int) {
        runCallbacks(mPlaybackPositionListeners) {
            it.onPeriodicNotification(this, newPos)
        }
    }

    private val mStreamEventCallbacks: ArrayMap<StreamEventCallback, Handler?> =
        ArrayMap<StreamEventCallback, Handler?>()

    fun registerStreamEventCallback(listener: StreamEventCallback?, handler: Handler?) {
        if (listener != null && !mStreamEventCallbacks.containsKey(listener)) {
            synchronized(mStreamEventCallbacks) {
                mStreamEventCallbacks.put(listener, handler)
            }
        }
    }

    fun unregisterStreamEventCallback(listener: StreamEventCallback?) {
        synchronized(mStreamEventCallbacks) {
            if (mStreamEventCallbacks.containsKey(listener)) {
                mStreamEventCallbacks.remove(listener)
            }
        }
    }

    // called from native, on callback thread (not main thread!)
    private fun onStreamEnd() {
        runCallbacks(mStreamEventCallbacks) {
            it.onPresentationEnded(this)
        }
    }

    // called from native, on callback thread (not main thread!)
    private fun onNewIAudioTrack() {
        runCallbacks(mStreamEventCallbacks) {
            it.onTearDown(this)
        }
    }

    // called from native, on callback thread (not main thread!)
    private fun onNewTimestamp(timestampMs: Int, timeNanoSec: Long) {
        // TODO: not impl'ed for now, can be done later if needed...
    }

    // called from native, on callback thread (not main thread!)
    private fun onLoopEnd(loopsRemaining: Int) {
        // TODO: not impl'ed for now, can be done later if needed...
    }

    // called from native, on callback thread (not main thread!)
    private fun onBufferEnd() {
        // TODO: not impl'ed for now, can be done later if needed...
    }

    // called from native, on callback thread (not main thread!)
    // Be careful to not hold a reference to the buffer after returning. It will immediately be invalid!
    private fun onMoreData(frameCount: Long, buffer: ByteBuffer): Long {
        // TODO: not impl'ed for now, can be done later if needed...
        return 0 // amount of bytes written
    }

    // called from native, on callback thread (not main thread!)
    private fun onCanWriteMoreData(frameCount: Long, sizeBytes: Long) {
        runCallbacks(mStreamEventCallbacks) {
            it.onDataRequest(this, frameCount, sizeBytes)
        }
    }

    private val resetAudioPortGenerationMethod by lazy {
        AudioManager::class.java.getMethod("resetAudioPortGeneration")
    }

    // called from native, on random thread (not main thread!) - only M for now, N+ uses proxy
    private fun onAudioDeviceUpdate(ioHandle: Int, routedDevices: IntArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // handled by proxy on N+
            try {
                resetAudioPortGenerationMethod.invoke(null)
            } catch (e: Exception) {
                Log.w(TAG, "failed to reset generation of audio ports", e)
            }
        }
        handleRoutingChanged()
    }

    // called on audio track initialization thread, most often main thread but not always
    private fun onRoutingChanged() {
        handleRoutingChanged()
    }

    private val mRoutingChangeListeners: ArrayMap<OnRoutingChangedListener, Handler?> =
        ArrayMap<OnRoutingChangedListener, Handler?>()

    fun addOnRoutingChangedListener(listener: OnRoutingChangedListener?, handler: Handler?) {
        if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
            synchronized(mRoutingChangeListeners) {
                mRoutingChangeListeners.put(listener, handler)
            }
        }
    }

    fun removeOnRoutingChangedListener(listener: OnRoutingChangedListener?) {
        synchronized(mRoutingChangeListeners) {
            if (mRoutingChangeListeners.containsKey(listener)) {
                mRoutingChangeListeners.remove(listener)
            }
        }
    }

    private fun handleRoutingChanged() {
        runCallbacks(mRoutingChangeListeners) {
            it.onRoutingChanged(this)
        }
    }

    // called on random thread
    private fun onCodecFormatChanged(metadata: AudioMetadataReadMap?) {
        // TODO: not impl'ed for now, can be done later if needed...
    }
}