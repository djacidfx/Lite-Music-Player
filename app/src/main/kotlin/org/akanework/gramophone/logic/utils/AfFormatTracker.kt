package org.akanework.gramophone.logic.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.parcelize.Parcelize

@Parcelize
data class AfFormatInfo(
    val routedDeviceName: String?, val routedDeviceId: Int?,
    val routedDeviceType: Int?, val mixPortId: Int?, val mixPortName: String?,
    val mixPortFlags: Int?, val ioHandle: Int?, val sampleRateHz: Int?,
    val audioFormat: String?, val channelCount: Int?, val channelMask: Int?,
    val grantedFlags: Int?, val trackId: Int?
) : Parcelable

private class MyMixPort(val id: Int, val name: String?, val flags: Int?, val channelMask: Int?)

@Parcelize
data class AudioTrackInfo(
    val encoding: Int, val sampleRateHz: Int, val channelConfig: Int,
    val offload: Boolean
) : Parcelable {
    companion object {
        @OptIn(UnstableApi::class)
        fun fromMedia3AudioTrackConfig(config: AudioTrackConfig) =
            AudioTrackInfo(
                config.encoding, config.sampleRate, config.channelConfig,
                config.offload
            )
    }
}

@UnstableApi
class AfFormatTracker(
    private val context: Context, private val playbackHandler: Handler,
    private val handler: Handler
) : AnalyticsListener {
    companion object {
        private const val TAG = "AfFormatTracker"

        init {
            try {
                System.loadLibrary("gramophone")
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun getAudioTrackPtr(audioTrack: AudioTrack): Long {
            val cls = audioTrack.javaClass
            val field = cls.getDeclaredField("mNativeTrackInJavaObj")
            field.isAccessible = true
            return field.get(audioTrack) as Long
        }

        private fun getHalSampleRate(audioTrack: AudioTrack): Int? {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED)
                throw IllegalArgumentException("cannot get hal sample rate for released AudioTrack")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // getHalSampleRate() exists since below commit which first appeared in Android U
                // https://cs.android.com/android/_/android/platform/frameworks/av/+/310037a32d56e361d5b5156b74f8846f92bc245e
                val ret = try {
                    getHalSampleRateInternal(getAudioTrackPtr(audioTrack))
                } catch (e: Throwable) {
                    Log.e(TAG, Log.getStackTraceString(e))
                    null
                }
                if (ret != null && ret != 0)
                    return ret
                return null
            }
            val output = getOutput(audioTrack)
            if (output == null)
                return null
            val af = getAfService()
            if (af == null)
                return null
            val inParcel = obtainParcel(af)
            val outParcel = obtainParcel(af)
            try {
                inParcel.writeInterfaceToken(af.interfaceDescriptor!!)
                inParcel.writeInt(output)
                // IAudioFlingerService.sampleRate(audio_io_handle_t)
                af.transact(3, inParcel, outParcel, 0)
                if (!readStatus(outParcel))
                    return null
                return outParcel.readInt()
            } finally {
                inParcel.recycle()
                outParcel.recycle()
            }
        }

        private external fun getHalSampleRateInternal(@Suppress("unused") audioTrackPtr: Long): Int

        private fun obtainParcel(binder: IBinder) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Parcel.obtain(binder) else Parcel.obtain()

        private fun getHalChannelCount(audioTrack: AudioTrack): Int? {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED)
                throw IllegalArgumentException("cannot get hal channel count for released AudioTrack")
            // before U, caller should query channel mask from audio_port/audio_port_v7
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) null else
            // getHalChannelCount() exists since below commit which first appeared in Android U
            // https://cs.android.com/android/_/android/platform/frameworks/av/+/310037a32d56e361d5b5156b74f8846f92bc245e
                try {
                    getHalChannelCountInternal(getAudioTrackPtr(audioTrack))
                } catch (e: Throwable) {
                    Log.e(TAG, Log.getStackTraceString(e))
                    null
                }
        }

        private external fun getHalChannelCountInternal(@Suppress("unused") audioTrackPtr: Long): Int

        private fun getHalFormat(audioTrack: AudioTrack): String? {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED)
                throw IllegalArgumentException("cannot get hal format for released AudioTrack")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // getHalFormat() exists since below commit which first appeared in Android U
                // https://cs.android.com/android/_/android/platform/frameworks/av/+/310037a32d56e361d5b5156b74f8846f92bc245e
                val ret = try {
                    getHalFormatInternal(getAudioTrackPtr(audioTrack))
                } catch (e: Throwable) {
                    Log.e(TAG, Log.getStackTraceString(e))
                    null
                }
                if (ret != null && ret != 0)
                    return audioFormatToString(ret.toUInt())
                return null
            }
            val output = getOutput(audioTrack)
            if (output == null)
                return null
            val af = getAfService()
            if (af == null)
                return null
            val inParcel = obtainParcel(af)
            val outParcel = obtainParcel(af)
            try {
                inParcel.writeInterfaceToken(af.interfaceDescriptor!!)
                inParcel.writeInt(output)
                // IAudioFlingerService.format(audio_io_handle_t)
                af.transact(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 4 else 5,
                    inParcel, outParcel, 0
                )
                if (!readStatus(outParcel))
                    return null
                // In T, return value changed from legacy audio_format_t to AudioFormatDescription
                // https://cs.android.com/android/_/android/platform/frameworks/av/+/b60bd1b586b74ddf375257c4d07323e271d84ff3
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (outParcel.readInt() != 1 /* kNonNullParcelableFlag */) {
                        Log.e(TAG, "got a null parcelable unexpectedly")
                        return null
                    }
                    return simplifyAudioFormatDescription(outParcel)?.let { audioFormatToString(it.toUInt()) }
                } else
                    return audioFormatToString(outParcel.readInt().toUInt())
            } finally {
                inParcel.recycle()
                outParcel.recycle()
            }
        }

        private external fun getHalFormatInternal(@Suppress("unused") audioTrackPtr: Long): Int

        private fun audioFormatToString(audioFormat: UInt): String {
            return when (audioFormat) {
                0xFFFFFFFFU -> /* AUDIO_FORMAT_INVALID         */
                    "AUDIO_FORMAT_INVALID"

                0U -> /* AUDIO_FORMAT_DEFAULT         */
                    "AUDIO_FORMAT_DEFAULT"

                0x01000000U -> /* AUDIO_FORMAT_MP3             */
                    "AUDIO_FORMAT_MP3"

                0x02000000U -> /* AUDIO_FORMAT_AMR_NB          */
                    "AUDIO_FORMAT_AMR_NB"

                0x03000000U -> /* AUDIO_FORMAT_AMR_WB          */
                    "AUDIO_FORMAT_AMR_WB"

                0x04000000U -> /* AUDIO_FORMAT_AAC             */
                    "AUDIO_FORMAT_AAC"

                0x05000000U -> /* AUDIO_FORMAT_HE_AAC_V1       */
                    "AUDIO_FORMAT_HE_AAC_V1"

                0x06000000U -> /* AUDIO_FORMAT_HE_AAC_V2       */
                    "AUDIO_FORMAT_HE_AAC_V2"

                0x07000000U -> /* AUDIO_FORMAT_VORBIS          */
                    "AUDIO_FORMAT_VORBIS"

                0x08000000U -> /* AUDIO_FORMAT_OPUS            */
                    "AUDIO_FORMAT_OPUS"

                0x09000000U -> /* AUDIO_FORMAT_AC3             */
                    "AUDIO_FORMAT_AC3"

                0x0A000000U -> /* AUDIO_FORMAT_E_AC3           */
                    "AUDIO_FORMAT_E_AC3"

                0x0B000000U -> /* AUDIO_FORMAT_DTS             */
                    "AUDIO_FORMAT_DTS"

                0x0C000000U -> /* AUDIO_FORMAT_DTS_HD          */
                    "AUDIO_FORMAT_DTS_HD"

                0x0D000000U -> /* AUDIO_FORMAT_IEC61937        */
                    "AUDIO_FORMAT_IEC61937"

                0x0E000000U -> /* AUDIO_FORMAT_DOLBY_TRUEHD    */
                    "AUDIO_FORMAT_DOLBY_TRUEHD"

                0x10000000U -> /* AUDIO_FORMAT_EVRC            */
                    "AUDIO_FORMAT_EVRC"

                0x11000000U -> /* AUDIO_FORMAT_EVRCB           */
                    "AUDIO_FORMAT_EVRCB"

                0x12000000U -> /* AUDIO_FORMAT_EVRCWB          */
                    "AUDIO_FORMAT_EVRCWB"

                0x13000000U -> /* AUDIO_FORMAT_EVRCNW          */
                    "AUDIO_FORMAT_EVRCNW"

                0x14000000U -> /* AUDIO_FORMAT_AAC_ADIF        */
                    "AUDIO_FORMAT_AAC_ADIF"

                0x15000000U -> /* AUDIO_FORMAT_WMA             */
                    "AUDIO_FORMAT_WMA"

                0x16000000U -> /* AUDIO_FORMAT_WMA_PRO         */
                    "AUDIO_FORMAT_WMA_PRO"

                0x17000000U -> /* AUDIO_FORMAT_AMR_WB_PLUS     */
                    "AUDIO_FORMAT_AMR_WB_PLUS"

                0x18000000U -> /* AUDIO_FORMAT_MP2             */
                    "AUDIO_FORMAT_MP2"

                0x19000000U -> /* AUDIO_FORMAT_QCELP           */
                    "AUDIO_FORMAT_QCELP"

                0x1A000000U -> /* AUDIO_FORMAT_DSD             */
                    "AUDIO_FORMAT_DSD"

                0x1B000000U -> /* AUDIO_FORMAT_FLAC            */
                    "AUDIO_FORMAT_FLAC"

                0x1C000000U -> /* AUDIO_FORMAT_ALAC            */
                    "AUDIO_FORMAT_ALAC"

                0x1D000000U -> /* AUDIO_FORMAT_APE             */
                    "AUDIO_FORMAT_APE"

                0x1E000000U -> /* AUDIO_FORMAT_AAC_ADTS        */
                    "AUDIO_FORMAT_AAC_ADTS"

                0x1F000000U -> /* AUDIO_FORMAT_SBC             */
                    "AUDIO_FORMAT_SBC"

                0x20000000U -> /* AUDIO_FORMAT_APTX            */
                    "AUDIO_FORMAT_APTX"

                0x21000000U -> /* AUDIO_FORMAT_APTX_HD         */
                    "AUDIO_FORMAT_APTX_HD"

                0x22000000U -> /* AUDIO_FORMAT_AC4             */
                    "AUDIO_FORMAT_AC4"

                0x23000000U -> /* AUDIO_FORMAT_LDAC            */
                    "AUDIO_FORMAT_LDAC"

                0x24000000U -> /* AUDIO_FORMAT_MAT             */
                    "AUDIO_FORMAT_MAT"

                0x25000000U -> /* AUDIO_FORMAT_AAC_LATM        */
                    "AUDIO_FORMAT_AAC_LATM"

                0x26000000U -> /* AUDIO_FORMAT_CELT            */
                    "AUDIO_FORMAT_CELT"

                0x27000000U -> /* AUDIO_FORMAT_APTX_ADAPTIVE   */
                    "AUDIO_FORMAT_APTX_ADAPTIVE"

                0x28000000U -> /* AUDIO_FORMAT_LHDC            */
                    "AUDIO_FORMAT_LHDC"

                0x29000000U -> /* AUDIO_FORMAT_LHDC_LL         */
                    "AUDIO_FORMAT_LHDC_LL"

                0x2A000000U -> /* AUDIO_FORMAT_APTX_TWSP       */
                    "AUDIO_FORMAT_APTX_TWSP"

                0x2B000000U -> /* AUDIO_FORMAT_LC3             */
                    "AUDIO_FORMAT_LC3"

                0x2C000000U -> /* AUDIO_FORMAT_MPEGH           */
                    "AUDIO_FORMAT_MPEGH"

                0x2D000000U -> /* AUDIO_FORMAT_IEC60958        */
                    "AUDIO_FORMAT_IEC60958"

                0x2E000000U -> /* AUDIO_FORMAT_DTS_UHD         */
                    "AUDIO_FORMAT_DTS_UHD"

                0x2F000000U -> /* AUDIO_FORMAT_DRA             */
                    "AUDIO_FORMAT_DRA"

                0x30000000U -> /* AUDIO_FORMAT_APTX_ADAPTIVE_QLEA */
                    "AUDIO_FORMAT_APTX_ADAPTIVE_QLEA"

                0x31000000U -> /* AUDIO_FORMAT_APTX_ADAPTIVE_R4   */
                    "AUDIO_FORMAT_APTX_ADAPTIVE_R4"

                0x32000000U -> /* AUDIO_FORMAT_DTS_HD_MA       */
                    "AUDIO_FORMAT_DTS_HD_MA"

                0x33000000U -> /* AUDIO_FORMAT_DTS_UHD_P2      */
                    "AUDIO_FORMAT_DTS_UHD_P2"

                /* Aliases */
                0x1U -> /* AUDIO_FORMAT_PCM_16_BIT        */
                    "AUDIO_FORMAT_PCM_16_BIT"        // (PCM | PCM_SUB_16_BIT)
                0x2U -> /* AUDIO_FORMAT_PCM_8_BIT         */
                    "AUDIO_FORMAT_PCM_8_BIT"        // (PCM | PCM_SUB_8_BIT)
                0x3U -> /* AUDIO_FORMAT_PCM_32_BIT        */
                    "AUDIO_FORMAT_PCM_32_BIT"        // (PCM | PCM_SUB_32_BIT)
                0x4U -> /* AUDIO_FORMAT_PCM_8_24_BIT      */
                    "AUDIO_FORMAT_PCM_8_24_BIT"        // (PCM | PCM_SUB_8_24_BIT)
                0x5U -> /* AUDIO_FORMAT_PCM_FLOAT         */
                    "AUDIO_FORMAT_PCM_FLOAT"        // (PCM | PCM_SUB_FLOAT)
                0x6U -> /* AUDIO_FORMAT_PCM_24_BIT_PACKED */
                    "AUDIO_FORMAT_PCM_24_BIT_PACKED"        // (PCM | PCM_SUB_24_BIT_PACKED)
                0x4000001U -> /* AUDIO_FORMAT_AAC_MAIN          */
                    "AUDIO_FORMAT_AAC_MAIN"  // (AAC | AAC_SUB_MAIN)
                0x4000002U -> /* AUDIO_FORMAT_AAC_LC            */
                    "AUDIO_FORMAT_AAC_LC"  // (AAC | AAC_SUB_LC)
                0x4000004U -> /* AUDIO_FORMAT_AAC_SSR           */
                    "AUDIO_FORMAT_AAC_SSR"  // (AAC | AAC_SUB_SSR)
                0x4000008U -> /* AUDIO_FORMAT_AAC_LTP           */
                    "AUDIO_FORMAT_AAC_LTP"  // (AAC | AAC_SUB_LTP)
                0x4000010U -> /* AUDIO_FORMAT_AAC_HE_V1         */
                    "AUDIO_FORMAT_AAC_HE_V1"  // (AAC | AAC_SUB_HE_V1)
                0x4000020U -> /* AUDIO_FORMAT_AAC_SCALABLE      */
                    "AUDIO_FORMAT_AAC_SCALABLE"  // (AAC | AAC_SUB_SCALABLE)
                0x4000040U -> /* AUDIO_FORMAT_AAC_ERLC          */
                    "AUDIO_FORMAT_AAC_ERLC"  // (AAC | AAC_SUB_ERLC)
                0x4000080U -> /* AUDIO_FORMAT_AAC_LD            */
                    "AUDIO_FORMAT_AAC_LD"  // (AAC | AAC_SUB_LD)
                0x4000100U -> /* AUDIO_FORMAT_AAC_HE_V2         */
                    "AUDIO_FORMAT_AAC_HE_V2"  // (AAC | AAC_SUB_HE_V2)
                0x4000200U -> /* AUDIO_FORMAT_AAC_ELD           */
                    "AUDIO_FORMAT_AAC_ELD"  // (AAC | AAC_SUB_ELD)
                0x4000300U -> /* AUDIO_FORMAT_AAC_XHE           */
                    "AUDIO_FORMAT_AAC_XHE"  // (AAC | AAC_SUB_XHE)
                0x1e000001U -> /* AUDIO_FORMAT_AAC_ADTS_MAIN     */
                    "AUDIO_FORMAT_AAC_ADTS_MAIN" // (AAC_ADTS | AAC_SUB_MAIN)
                0x1e000002U -> /* AUDIO_FORMAT_AAC_ADTS_LC       */
                    "AUDIO_FORMAT_AAC_ADTS_LC" // (AAC_ADTS | AAC_SUB_LC)
                0x1e000004U -> /* AUDIO_FORMAT_AAC_ADTS_SSR      */
                    "AUDIO_FORMAT_AAC_ADTS_SSR" // (AAC_ADTS | AAC_SUB_SSR)
                0x1e000008U -> /* AUDIO_FORMAT_AAC_ADTS_LTP      */
                    "AUDIO_FORMAT_AAC_ADTS_LTP" // (AAC_ADTS | AAC_SUB_LTP)
                0x1e000010U -> /* AUDIO_FORMAT_AAC_ADTS_HE_V1    */
                    "AUDIO_FORMAT_AAC_ADTS_HE_V1" // (AAC_ADTS | AAC_SUB_HE_V1)
                0x1e000020U -> /* AUDIO_FORMAT_AAC_ADTS_SCALABLE */
                    "AUDIO_FORMAT_AAC_ADTS_SCALABLE" // (AAC_ADTS | AAC_SUB_SCALABLE)
                0x1e000040U -> /* AUDIO_FORMAT_AAC_ADTS_ERLC     */
                    "AUDIO_FORMAT_AAC_ADTS_ERLC" // (AAC_ADTS | AAC_SUB_ERLC)
                0x1e000080U -> /* AUDIO_FORMAT_AAC_ADTS_LD       */
                    "AUDIO_FORMAT_AAC_ADTS_LD" // (AAC_ADTS | AAC_SUB_LD)
                0x1e000100U -> /* AUDIO_FORMAT_AAC_ADTS_HE_V2    */
                    "AUDIO_FORMAT_AAC_ADTS_HE_V2" // (AAC_ADTS | AAC_SUB_HE_V2)
                0x1e000200U -> /* AUDIO_FORMAT_AAC_ADTS_ELD      */
                    "AUDIO_FORMAT_AAC_ADTS_ELD" // (AAC_ADTS | AAC_SUB_ELD)
                0x1e000300U -> /* AUDIO_FORMAT_AAC_ADTS_XHE      */
                    "AUDIO_FORMAT_AAC_ADTS_XHE" // (AAC_ADTS | AAC_SUB_XHE)
                0x25000002U -> /* AUDIO_FORMAT_AAC_LATM_LC       */
                    "AUDIO_FORMAT_AAC_LATM_LC" // (AAC_LATM | AAC_SUB_LC)
                0x25000010U -> /* AUDIO_FORMAT_AAC_LATM_HE_V1    */
                    "AUDIO_FORMAT_AAC_LATM_HE_V1" // (AAC_LATM | AAC_SUB_HE_V1)
                0x25000100U -> /* AUDIO_FORMAT_AAC_LATM_HE_V2    */
                    "AUDIO_FORMAT_AAC_LATM_HE_V2" // (AAC_LATM | AAC_SUB_HE_V2)
                0xA000001U -> /* AUDIO_FORMAT_E_AC3_JOC         */
                    "AUDIO_FORMAT_E_AC3_JOC"  // (E_AC3 | E_AC3_SUB_JOC)
                0x24000001U -> /* AUDIO_FORMAT_MAT_1_0           */
                    "AUDIO_FORMAT_MAT_1_0" // (MAT | MAT_SUB_1_0)
                0x24000002U -> /* AUDIO_FORMAT_MAT_2_0           */
                    "AUDIO_FORMAT_MAT_2_0" // (MAT | MAT_SUB_2_0)
                0x24000003U -> /* AUDIO_FORMAT_MAT_2_1           */
                    "AUDIO_FORMAT_MAT_2_1" // (MAT | MAT_SUB_2_1)
                0x2C000013U -> /* AUDIO_FORMAT_MPEGH_SUB_BL_L3   */
                    "AUDIO_FORMAT_MPEGH_SUB_BL_L3"

                0x2C000014U -> /* AUDIO_FORMAT_MPEGH_SUB_BL_L4   */
                    "AUDIO_FORMAT_MPEGH_SUB_BL_L4"

                0x2C000023U -> /* AUDIO_FORMAT_MPEGH_SUB_LC_L3   */
                    "AUDIO_FORMAT_MPEGH_SUB_LC_L3"

                0x2C000024U -> /* AUDIO_FORMAT_MPEGH_SUB_LC_L4   */
                    "AUDIO_FORMAT_MPEGH_SUB_LC_L4"

                else ->
                    "AUDIO_FORMAT_($audioFormat)"
            }
        }

        @SuppressLint("PrivateApi") // sorry, not sorry...
        private fun listAudioPorts(): Pair<List<Any>, Int>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                return null // while listAudioPorts exists in L, it just returns an error
            val ports = ArrayList<Any?>()
            val generation = IntArray(1)
            try {
                Class.forName("android.media.AudioSystem").getMethod(
                    "listAudioPorts", ArrayList::class.java, IntArray::class.java
                ).invoke(null, ports, generation) as Int
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                return null
            }
            if (ports.contains(null))
                Log.e(TAG, "why does listAudioPorts() return a null port?!")
            return ports.filterNotNull() to generation[0]
        }

        @SuppressLint("PrivateApi") // only Android T, private API stability
        private fun simplifyAudioFormatDescription(out: Parcel): Int? {
            return try {
                Class.forName("android.media.audio.common.AidlConversion").getDeclaredMethod(
                    "aidl2legacy_AudioFormatDescription_Parcel_audio_format_t", Parcel::class.java
                ).also {
                    it.isAccessible = true
                }.invoke(null, out) as Int
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }

        private fun findAfFlagsForPort(id: Int, sr: Int, isForChannels: Boolean): Int? {
            // flags exposed to app process since below commit which first appeared in T release.
            // https://cs.android.com/android/_/android/platform/frameworks/av/+/99809024b36b243ad162c780c1191bb503a8df47
            if (!isForChannels && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                return null // need listAudioPorts or getAudioPort
            return try {
                findAfFlagsForPortInternal(id, sr, isForChannels).let {
                    if (it == Int.MAX_VALUE || it == Int.MIN_VALUE)
                        null // something went wrong, this was logged to logcat
                    else it
                }
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }

        @Suppress("unused") // for parameters
        private external fun findAfFlagsForPortInternal(id: Int, sr: Int, isForChannels: Boolean): Int

        private fun getOutput(audioTrack: AudioTrack): Int? {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED)
                throw IllegalArgumentException("cannot get hal output for released AudioTrack")
            return try {
                getOutputInternal(getAudioTrackPtr(audioTrack))
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }

        private external fun getOutputInternal(@Suppress("unused") audioTrackPtr: Long): Int

        private fun dump(audioTrack: AudioTrack): String? {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED)
                throw IllegalArgumentException("cannot dump released AudioTrack")
            return try {
                dumpInternal(getAudioTrackPtr(audioTrack))
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }

        private external fun dumpInternal(@Suppress("unused") audioTrackPtr: Long): String

        @SuppressLint("PrivateApi") // only used below U, stable private API
        private fun getAfService(): IBinder? {
            return try {
                Class.forName("android.os.ServiceManager").getMethod(
                    "getService", String::class.java
                ).invoke(null, "media.audio_flinger") as IBinder?
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                null
            }
        }

        private fun readStatus(parcel: Parcel): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            val status = parcel.readInt()
            if (status == 0) return true
            Log.e(TAG, "binder transaction failed with status $status")
            return false
        }
    }

    // only access sink or track on PlaybackThread
    private var lastAudioTrack: AudioTrack? = null
    private var audioSink: DefaultAudioSink? = null
    var format: AfFormatInfo? = null
        private set
    var formatChangedCallback: ((AfFormatInfo?) -> Unit)? = null

    private val routingChangedListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : AudioRouting.OnRoutingChangedListener {
            override fun onRoutingChanged(router: AudioRouting) {
                this@AfFormatTracker.onRoutingChanged(router as AudioTrack)
            }
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        @Suppress("deprecation")
        object : AudioTrack.OnRoutingChangedListener {
            override fun onRoutingChanged(router: AudioTrack) {
                this@AfFormatTracker.onRoutingChanged(router)
            }
        }
    } else null

    private fun onRoutingChanged(router: AudioTrack) {
        val audioTrack = (audioSink ?: throw NullPointerException(
            "audioSink is null in onAudioTrackInitialized"
        )).getAudioTrack()
        if (router !== audioTrack) return // stale callback
        if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED) return
        buildFormat(audioTrack)
    }

    // TODO why do we have to reflect on app code, there must be a better solution
    private fun DefaultAudioSink.getAudioTrack(): AudioTrack? {
        val cls = javaClass
        val field = cls.getDeclaredField("audioTrack")
        field.isAccessible = true
        return field.get(this) as AudioTrack?
    }

    fun setAudioSink(sink: DefaultAudioSink) {
        this.audioSink = sink
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioTrackConfig
    ) {
        format = null
        playbackHandler.post {
            val audioTrack = (audioSink ?: throw NullPointerException(
                "audioSink is null in onAudioTrackInitialized"
            )).getAudioTrack()
            if (audioTrack != lastAudioTrack) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioRouting.OnRoutingChangedListener
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("deprecation")
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioTrack.OnRoutingChangedListener
                    )
                }
                this.lastAudioTrack = audioTrack
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    audioTrack?.addOnRoutingChangedListener(
                        routingChangedListener as AudioRouting.OnRoutingChangedListener,
                        playbackHandler
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("deprecation")
                    audioTrack?.addOnRoutingChangedListener(
                        routingChangedListener as AudioTrack.OnRoutingChangedListener,
                        playbackHandler
                    )
                }
            }
            buildFormat(audioTrack)
        }
    }

    private fun buildFormat(audioTrack: AudioTrack?) {
        audioTrack?.let {
            val rd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                audioTrack.routedDevice else null
            val pn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd!!.productName.toString() else null
            val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd!!.type else null
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd!!.id else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                handler.post {
                    val sd = MediaRoutes.getSelectedAudioDevice(context)
                    if (rd != sd)
                        Log.w(
                            TAG,
                            "routedDevice ${rd?.productName}(${rd?.id}) is not the same as MediaRoute " +
                                    "selected device ${sd?.productName}(${sd?.id})"
                        )
                }
            }
            val oid = getOutput(audioTrack)
            val sr = getHalSampleRate(audioTrack)
            val mp = if (oid != null && sr != null && sr > 8000 && sr < 1600000) {
                getMixPortForThread(oid, sr)
            } else null
            val dump = dump(audioTrack)
            AfFormatInfo(
                pn, id, t,
                mp?.id, mp?.name, mp?.flags,
                oid, sr,
                getHalFormat(audioTrack), getHalChannelCount(audioTrack), mp?.channelMask,
                getFlagFromDump(dump), getIdFromDump(dump)
            )
        }.let {
            format = it
            formatChangedCallback?.invoke(it)
        }
    }

    private val flagRegex = Regex(".*, flags\\(0x(.*)\\).*")
    private val flagRegexOld = Regex(".*, flags\\((.*)\\).*")
    private fun getFlagFromDump(dump: String?): Int? {
        if (dump == null)
            return null
        // Flags are in dump output since below commit which first appeared in Pie.
        // https://cs.android.com/android/_/android/platform/frameworks/av/+/d114b624ea2ec5c51779b74132a60b4a46f6cdba
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            return null
        var dt = dump.trim().split('\n').map { it.trim() }
        if (dt.size < 2) {
            Log.e(
                TAG,
                "getFlagFromDump() failure: not enough lines, DUMP:\n$dump"
            )
            return null
        }
        if (dt[0] != "AudioTrack::dump") {
            Log.e(
                TAG,
                "getFlagFromDump() failure: L0 isn't AudioTrack::dump, DUMP:\n$dump"
            )
            return null
        }
        if (!dt[1].contains(" flags(")) {
            Log.e(
                TAG,
                "getFlagFromDump() failure: L1 didn't contain flags(, DUMP:\n$dump"
            )
            return null
        }
        var flagText = flagRegex.matchEntire(dt[1])?.groupValues[1]
        if (flagText == null) {
            flagText = flagRegexOld.matchEntire(dt[1])?.groupValues[1]
            if (flagText == null) {
                Log.e(
                    TAG,
                    "getFlagFromDump() failure: L1 didn't match regex, DUMP:\n$dump"
                )
                return null
            }
        }
        flagText.toIntOrNull(radix = 16)?.let { return it }
        Log.e(
            TAG,
            "getFlagFromDump() failure: $flagText didn't convert to int from base 16, DUMP:\n$dump"
        )
        return null
    }

    private val idRegex = Regex(".*id\\((.*)\\) .*")
    private fun getIdFromDump(dump: String?): Int? {
        if (dump == null)
            return null
        // ID is in dump output since below commit which first appeared in Q.
        // https://cs.android.com/android/_/android/platform/frameworks/av/+/fb8ede2a020e741cb892ee024fcfba7e689183f2
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return null
        var dt = dump.trim().split('\n').map { it.trim() }
        if (dt.size < 2) {
            Log.e(
                TAG,
                "getIdFromDump() failure: not enough lines, DUMP:\n$dump"
            )
            return null
        }
        if (dt[0] != "AudioTrack::dump") {
            Log.e(
                TAG,
                "getIdFromDump() failure: L0 isn't AudioTrack::dump, DUMP:\n$dump"
            )
            return null
        }
        if (!dt[1].contains("id(")) {
            Log.e(
                TAG,
                "getIdFromDump() failure: L1 didn't contain id(, DUMP:\n$dump"
            )
            return null
        }
        val idText = idRegex.matchEntire(dt[1])?.groupValues[1]
        if (idText == null) {
            Log.e(
                TAG,
                "getIdFromDump() failure: L1 didn't match regex, DUMP:\n$dump"
            )
            return null
        }
        idText.toIntOrNull()?.let { return it }
        Log.e(
            TAG,
            "getIdFromDump() failure: $idText didn't convert to int from base 10, DUMP:\n$dump"
        )
        return null
    }

    private fun getMixPortForThread(oid: Int, sampleRate: Int): MyMixPort? {
        val ports = listAudioPorts()
        if (ports != null)
            for (port in ports.first) {
                try {
                    if (port.javaClass.canonicalName != "android.media.AudioMixPort") continue
                    val ioHandle = port.javaClass.getMethod("ioHandle").invoke(port) as Int
                    if (ioHandle != oid) continue
                    val id = port.javaClass.getMethod("id").invoke(port) as Int
                    val name = port.javaClass.getMethod("name").invoke(port) as String?
                    val flags = findAfFlagsForPort(id, sampleRate, false)
                    val channelMask = findAfFlagsForPort(id, sampleRate, true)
                    return MyMixPort(id, name, flags, channelMask)
                } catch (t: Throwable) {
                    Log.e(TAG, Log.getStackTraceString(t))
                }
            }
        return null
    }
}