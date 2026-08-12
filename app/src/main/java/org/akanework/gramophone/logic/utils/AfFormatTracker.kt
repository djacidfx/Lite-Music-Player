/*
 *     Copyright (C) 2025 nift4
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

package org.akanework.gramophone.logic.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Parcelable
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import kotlinx.parcelize.Parcelize
import org.akanework.gramophone.logic.utils.exoplayer.AudioTrackExtendedAudioOutput
import org.akanework.gramophone.logic.utils.exoplayer.ExtendedAudioOutput
import org.akanework.gramophone.logic.utils.exoplayer.NativeTrackExtendedAudioOutput
import org.nift4.gramophone.hificore.AudioSystemHiddenApi
import org.nift4.gramophone.hificore.AudioTrackHiddenApi

@Parcelize
data class AfFormatInfo(
    val routedDeviceName: String?, val routedDeviceId: Int?,
    val routedDeviceType: Int?, val audioSessionId: Int, val mixPortId: Int?,
    val mixPortName: String?, val mixPortFlags: Int?, val mixPortHwModule: Int?,
    val mixPortFast: Boolean?, val ioHandle: Int?, val sampleRateHz: UInt?,
    val audioFormat: String?, val channelCount: Int?, val channelMask: Int?,
    val grantedFlags: Int?, val policyPortId: Int?, val afTrackFlags: Int?,
    val isBluetoothOffload: Boolean?, val backend: String
) : Parcelable

@Parcelize
data class AudioTrackInfo(
    val encoding: Int, val sampleRateHz: Int, val channelConfig: Int,
    val offload: Boolean
) : Parcelable {
    companion object {
        fun fromMedia3AudioTrackConfig(config: AudioTrackConfig) =
            AudioTrackInfo(
                config.encoding, config.sampleRate, config.channelConfig,
                config.offload
            )
    }
}

class AfFormatTracker(
    private val context: Context, private val playbackHandler: Handler,
    private val handler: Handler
) : AnalyticsListener {
    companion object {
        private const val LOG_EVENTS = true
        private const val TAG = "AfFormatTracker"
    }

    // only access sink or output on PlaybackThread
    private var lastAudioOutput: ExtendedAudioOutput? = null
    private var lastPeriodUid: Any? = null
    private var audioSink: PostAmpAudioOutputProvider? = null
    var format: AfFormatInfo? = null
        private set
    var formatChangedCallback: ((AfFormatInfo?, Any?) -> Unit)? = null

    private val routingChangedListener = ::onRoutingChanged

    private fun onRoutingChanged(router: ExtendedAudioOutput) {
        val audioOutput = (audioSink ?: throw NullPointerException(
            "audioSink is null in onAudioTrackInitialized"
        )).getExtendedAudioOutput()
        if (router !== audioOutput) return // stale callback
        // reaching here implies router == lastAudioOutput
        buildFormat(audioOutput, lastPeriodUid)
    }

    fun setAudioSink(sink: PostAmpAudioOutputProvider) {
        this.audioSink = sink
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioTrackConfig
    ) {
        format = null
        playbackHandler.post {
            val audioOutput = (audioSink ?: throw NullPointerException(
                "audioSink is null in onAudioTrackInitialized"
            )).getExtendedAudioOutput()
            if (audioOutput != lastAudioOutput) {
                lastAudioOutput?.removeOnRoutingChangedListener(routingChangedListener)
                lastPeriodUid?.let { formatChangedCallback?.invoke(null, it) }
                this.lastAudioOutput = audioOutput
                this.lastPeriodUid = eventTime.mediaPeriodId?.periodUid
                audioOutput?.addOnRoutingChangedListener(routingChangedListener, playbackHandler)
            }
            buildFormat(audioOutput, eventTime.mediaPeriodId?.periodUid)
        }
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioTrackConfig
    ) {
        if (!playbackHandler.looper.thread.isAlive) return
        playbackHandler.post {
            if (lastAudioOutput?.isInitialized == false) {
                lastAudioOutput?.removeOnRoutingChangedListener(routingChangedListener)
                lastAudioOutput = null
                formatChangedCallback?.invoke(null, lastPeriodUid)
                lastPeriodUid = null
                format = null
            }
        }
    }

    private fun buildFormat(audioOutput: ExtendedAudioOutput?, periodUid: Any?) {
        audioOutput?.let { _ ->
            if (!audioOutput.isInitialized) return@let null
            val rd = audioOutput.routedDevice
            handler.post {
                val sd = MediaRoutes.getSelectedAudioDevice(context)
                if (rd != sd)
                    Log.w(
                        TAG,
                        "routedDevice ${rd?.productName}(${rd?.id}) is not the same as MediaRoute " +
                                "selected device ${sd?.productName}(${sd?.id})"
                    )
            }
            val ioHandle = audioOutput.getOutputPort()
            val halSampleRate = audioOutput.getHalSampleRate()
            val grantedFlags = audioOutput.getGrantedFlags()
            val mixPort = AudioSystemHiddenApi.getMixPortForThread(ioHandle)
            val primaryHw = AudioSystemHiddenApi.getPrimaryMixPort()?.hwModule
            // this call writes to mAfLatency and mLatency fields, hence call dump after this
            val latency = audioOutput.getLatency()
            val dump = audioOutput.dump()
            val isBluetoothOffload = if (rd?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || rd?.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || rd?.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
            ) {
                mixPort?.hwModule?.let { it == primaryHw }
            } else null
            AfFormatInfo(
                rd?.productName.toString(),
                rd?.id,
                rd?.type,
                audioOutput.audioSessionId,
                mixPort?.id,
                mixPort?.name,
                mixPort?.flags,
                mixPort?.hwModule,
                mixPort?.fast,
                ioHandle,
                halSampleRate ?: mixPort?.sampleRate,
                audioFormatToString(
                    audioOutput.getHalFormat() ?: mixPort?.format
                ),
                audioOutput.getHalChannelCount(),
                mixPort?.channelMask,
                grantedFlags,
                AudioTrackHiddenApi.getPortIdFromDump(dump),
                AudioTrackHiddenApi.findAfTrackFlags(dump, latency, audioOutput.getPtr(), grantedFlags),
                isBluetoothOffload,
                when (audioOutput) {
                    is NativeTrackExtendedAudioOutput -> "NativeTrack"
                    is AudioTrackExtendedAudioOutput -> "AudioTrack"
                    else -> audioOutput.javaClass.name
                }
            )
        }.let {
            if (LOG_EVENTS)
                Log.d(TAG, "audio hal format changed to: $it")
            format = it
            formatChangedCallback?.invoke(it, periodUid)
        }
    }

    private fun audioFormatToString(audioFormat: UInt?): String {
        for (encoding in AudioFormatDetector.Encoding.entries) {
            if (encoding.isSupportedAsNative && encoding.native == audioFormat)
                encoding.enc2?.let { return it }
        }
        return "AUDIO_FORMAT_($audioFormat)"
    }
}