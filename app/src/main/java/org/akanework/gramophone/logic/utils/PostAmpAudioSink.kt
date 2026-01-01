package org.akanework.gramophone.logic.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.utils.AudioFormatDetector.audioDeviceTypeToString
import org.akanework.gramophone.logic.utils.ReplayGainUtil.Mode
import org.nift4.gramophone.hificore.AudioSystemHiddenApi
import org.nift4.gramophone.hificore.AudioTrackHiddenApi
import org.nift4.gramophone.hificore.ReflectionAudioEffect
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

// TODO: less hacky https://github.com/nift4/media/commit/22d2156bec74542a0764bf0ec27c839cc70874ed
// TODO: less hacky https://github.com/nift4/media/commit/2988651676987cfd42affc21e1939d6cacbfbe7f
class PostAmpAudioSink(
    val sink: DefaultAudioSink, val rgAp: ReplayGainAudioProcessor, val context: Context
) : ForwardingAudioSink(sink), AudioSystemHiddenApi.VolumeChangeListener {
    companion object {
        private const val TAG = "PostAmpAudioSink"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // TODO: what is com.lge.media.EXTRA_VOLUME_STREAM_HIFI_VALUE, and is it needed for
            //  volume change tracking on LG stock ROM?
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION"
                || intent?.action == "android.media.MASTER_VOLUME_CHANGED_ACTION"
                || intent?.action == "android.media.MASTER_MUTE_CHANGED_ACTION"
                || intent?.action == "android.media.STREAM_MUTE_CHANGED_ACTION"
            ) {
                myOnReceiveBroadcast(intent)
            }
        }
    }
    private val audioManager = context.getSystemService<AudioManager>()!!
    private var handler: Handler? = null
    private val isVolumeAvailable by lazy {
        try {
            Volume.isAvailable()
        } catch (e: Throwable) {
            Log.e(TAG, "failed to check if volume is available", e)
            false
        }
    }
    private val isDpeAvailable by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ReflectionAudioEffect.isEffectTypeAvailable(
                    AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING,
                    ReflectionAudioEffect.EFFECT_TYPE_NULL
                )
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to check if DPE is available", e)
            false
        }
    }
    private val isDpeOffloadable by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ReflectionAudioEffect.isEffectTypeOffloadable(
                    AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING,
                    ReflectionAudioEffect.EFFECT_TYPE_NULL
                )
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to check if DPE is offloadable", e)
            false
        }
    }
    private var volumeEffect: Volume? = null
    private var dpeEffect: DynamicsProcessing? = null
    private var dpeCanary: ReflectionAudioEffect? = null
    private var hasVolume = false
    private var hasDpe = false
    private var offloadEnabled: Boolean? = null
    private var format: Format? = null
    private var tags: ReplayGainUtil.ReplayGainInfo? = null
    private var pendingFormat: Format? = null
    private var pendingTags: ReplayGainUtil.ReplayGainInfo? = null
    private var deviceType: Int? = null
    private var audioSessionId = 0
    private var lastOutput: Int? = null
    private var volume = 1f
    private var rgVolume = 1f

    init {
        var forVolumeChanged = false
        try {
            AudioSystemHiddenApi.addVolumeCallback(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "failed to register volume cb", e)
            forVolumeChanged = true
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                if (forVolumeChanged) // only register if better native callback doesn't work
                    addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_MUTE_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            },
            @SuppressLint("WrongConstant") // why is this needed?
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        synchronized(rgAp) {
            rgAp.boostGainChangedListener = {
                handler?.post { // if null, there are no effects that need to be notified anyway
                    if (hasDpe) {
                        calculateGain()
                    } else {
                        updateVolumeEffect()
                    }
                }
            }
            rgAp.offloadEnabledChangedListener = {
                if (offloadEnabled != null) {
                    handler!!.post {
                        mySetAudioSessionId(null)
                    }
                }
            }
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        super.setListener(object : AudioSink.Listener by listener {
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                updateVolumeEffect() // TODO: why was this needed again?
                listener.onPositionAdvancing(playoutStartSystemTimeMs)
            }

            override fun onOffloadBufferEmptying() {
                listener.onOffloadBufferEmptying()
            }

            override fun onOffloadBufferFull() {
                listener.onOffloadBufferFull()
            }

            override fun onAudioSinkError(audioSinkError: Exception) {
                listener.onAudioSinkError(audioSinkError)
            }

            override fun onAudioCapabilitiesChanged() {
                listener.onAudioCapabilitiesChanged()
            }

            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                myApplyPendingConfig()
                listener.onAudioTrackInitialized(audioTrackConfig)
            }

            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                listener.onAudioTrackReleased(audioTrackConfig)
            }

            override fun onSilenceSkipped() {
                listener.onSilenceSkipped()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                mySetAudioSessionId(audioSessionId)
                listener.onAudioSessionIdChanged(audioSessionId)
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onRoutingChanged(router: AudioTrack, routedDevice: AudioDeviceInfo?) {
                myOnRoutingChanged(routedDevice)
                listener.onRoutingChanged(router, routedDevice)
            }
        })
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        pendingFormat = inputFormat
        pendingTags = ReplayGainUtil.parse(pendingFormat)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    fun canReuse(): Boolean {
        val mode: Mode
        synchronized(rgAp) {
            mode = rgAp.mode
        }
        if (mode == Mode.Track && ((pendingTags?.trackGain ?: 0f) != (tags?.trackGain ?: 0f)
                    || (pendingTags?.trackPeak ?: 1f) != (tags?.trackPeak ?: 1f))) {
            Log.i(TAG, "can't reuse: track - $pendingTags vs $tags")
            return false
        }
        if (mode == Mode.Album && ((pendingTags?.albumGain ?: 0f) != (tags?.albumGain ?: 0f)
                    || (pendingTags?.albumPeak ?: 1f) != (tags?.albumPeak ?: 1f))) {
            Log.i(TAG, "can't reuse: album - $pendingTags vs $tags")
            return false
        }
        return true
    }

    override fun setVolume(volume: Float) {
        if (this.volume != volume) {
            // Only call setVolume() if data changed to avoid needlessly resetting Volume effect
            this.volume = volume
            setVolumeInternal()
        }
    }

    private fun setVolumeInternal() {
        super.setVolume(volume * rgVolume)
        updateVolumeEffect() // setVolume() will reset volume effect state, so configure it again
    }

    private fun myOnReceiveBroadcast(intent: Intent) {
        if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
            onVolumeChanged() // The volume changed, notify Volume/DPE to avoid clipping.
        } else {
            updateVolumeEffect() // Someone may have reset volume effect state, configure it again
        }
    }

    override fun onVolumeChanged(
        groupId: Int,
        flags: Int
    ) {
        // TODO use below class to find out which group id corresponds to music and only listen to
        //  those change events (or maybe not? we don't for broadcasts? should we, there, too?)
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/media/java/android/media/audiopolicy/AudioProductStrategy.java;l=80?q=getAudioProductStrategies&ss=android%2Fplatform%2Fsuperproject%2Fmain
        Log.i(TAG, "volume changed: $groupId, $flags")
        onVolumeChanged()
    }

    private fun onVolumeChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasDpe) {
            // have to recalculate gain to avoid clipping if boost headroom just got too small
            calculateGain()
        } else if (hasVolume) {
            updateVolumeEffect() // external volume change will reset volume effect
        }
    }

    private fun myApplyPendingConfig() {
        if (pendingFormat != null && pendingTags != null) {
            format = pendingFormat
            tags = pendingTags
            pendingFormat = null
            pendingTags = null
        } else {
            Log.w(TAG, "pending format is null, did the previous AudioTrack die?")
        }
        calculateGain() // parse new tags and apply to DPE/setVolume()
    }

    private fun calculateGain() {
        val lastRgVolume = rgVolume
        // Nonchalantly borrow settings from ReplayGainAudioProcessor
        val mode: Mode
        val rgGain: Int
        val nonRgGain: Int
        val boostGainDb: Int
        val reduceGain: Boolean
        synchronized(rgAp) {
            mode = rgAp.mode
            rgGain = rgAp.rgGain
            nonRgGain = rgAp.nonRgGain
            boostGainDb = rgAp.boostGain
            reduceGain = rgAp.reduceGain
        }
        val useDpe = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasDpe
        val isOffload = Flags.TEST_RG_OFFLOAD ||
                format?.let { it.sampleMimeType != MimeTypes.AUDIO_RAW } == true
        if (useDpe) {
            try {
                dpeEffect!!.enabled = isOffload || boostGainDb > 0/* && !hasVolume*/
            } catch (e: IllegalStateException) {
                Log.e(TAG, "dpe enable=$isOffload failed", e)
            }
        }
        val boostGainDbLimited =
            if (useDpe && boostGainDb > 0) {
                val headroomDb = getHeadroomDb()
                Log.d(TAG, "dpe gain boost: headroom $headroomDb, boost $boostGainDb")
                min(headroomDb, boostGainDb.toFloat())
            } else 0f
        if (isOffload) {
            val calcGain = ReplayGainUtil.calculateGain(
                tags, mode, rgGain, reduceGain || !useDpe,
                if (useDpe) ReplayGainUtil.RATIO else null
            )
            val gain = calcGain?.first ?: ReplayGainUtil.dbToAmpl(nonRgGain.toFloat())
            val kneeThresholdDb = calcGain?.second
            rgVolume = if (useDpe) 1f else min(gain, 1f)
            try {
                if (useDpe) {
                    dpeEffect!!.setInputGainAllChannelsTo(ReplayGainUtil.amplToDb(gain) + boostGainDbLimited)
                    dpeEffect!!.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            true, kneeThresholdDb != null, 0,
                            ReplayGainUtil.TAU_ATTACK * 1000f,
                            ReplayGainUtil.TAU_RELEASE * 1000f,
                            ReplayGainUtil.RATIO, kneeThresholdDb ?: 999999f, 0f
                        )
                    )
                }
            } catch (e: UnsupportedOperationException) {
                Log.e(TAG, "we raced with someone else about DPE and we lost", e)
            }
        } else {
            if (useDpe && boostGainDb > 0) {
                dpeEffect!!.setInputGainAllChannelsTo(boostGainDbLimited)
                dpeEffect!!.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(
                        true, false, 0,
                        ReplayGainUtil.TAU_ATTACK * 1000f,
                        ReplayGainUtil.TAU_RELEASE * 1000f,
                        ReplayGainUtil.RATIO, 999999f, 0f
                    )
                )
            }
            rgVolume = 1f // ReplayGainAudioProcessor will apply volume for non-offload
        }
        if (lastRgVolume != rgVolume) {
            // Only call setVolume() if data changed to avoid needlessly resetting Volume effect
            setVolumeInternal()
        }
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        mySetAudioSessionId(audioSessionId)
        super.setAudioSessionId(audioSessionId)
    }

    private fun mySetAudioSessionId(id: Int?) {
        if (handler == null)
            handler = Handler(Looper.myLooper()!!)
        val offloadEnabled: Boolean
        synchronized(rgAp) {
            offloadEnabled = rgAp.offloadEnabled
        }
        if (id != null && id != audioSessionId || offloadEnabled != this.offloadEnabled) {
            Log.i(TAG, "set session id to $id")
            if (audioSessionId != 0) {
                if (volumeEffect != null) {
                    volumeEffect!!.let {
                        CoroutineScope(Dispatchers.Default).launch {
                            try {
                                it.enabled = false
                                it.release()
                            } catch (e: Throwable) {
                                Log.e(TAG, "failed to release Volume effect", e)
                            }
                        }
                    }
                    volumeEffect = null
                }
                // DPE must be released synchronously to avoid getting the old effect instance
                // again, with its old inUse values.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpeCanary != null) {
                    dpeCanary!!.let {
                        try {
                            it.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE canary", e)
                        }
                    }
                    dpeCanary = null
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpeEffect != null) {
                    dpeEffect!!.let {
                        try {
                            it.enabled = false
                            it.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE effect", e)
                        }
                    }
                    dpeEffect = null
                }
            }
            hasVolume = false
            hasDpe = false
            this.offloadEnabled = offloadEnabled
            audioSessionId = id ?: audioSessionId
            // Set a lower priority when creating effects - we are willing to share.
            // (User story "EQ is not working and I have to change a obscure setting to fix it"
            // is worse than user story "it's too quiet when I enable my EQ, but gets louder
            // when I disable it").
            if (audioSessionId != 0) {
                val hasOffloadDpe = isDpeAvailable && isDpeOffloadable && offloadEnabled
                val useDpeForVolume = !isVolumeAvailable && !offloadEnabled && isDpeAvailable
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    (hasOffloadDpe || useDpeForVolume)) {
                    createDpeEffect()
                } else {
                    Log.i(
                        TAG,
                        "didn't init DPE, e=$isDpeAvailable o=$isDpeOffloadable O=$offloadEnabled"
                    )
                    if (isVolumeAvailable && !offloadEnabled) {
                        try {
                            volumeEffect = Volume(-100000, audioSessionId)
                            volumeEffect!!.setControlStatusListener { _, hasControl ->
                                Log.i(TAG, "volume control state is now: $hasControl")
                                hasVolume = hasControl
                                updateVolumeEffect()
                            }
                            hasVolume = volumeEffect!!.hasControl()
                            Log.i(TAG, "init volume, control state is: $hasVolume")
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to init Volume effect", e)
                        }
                    } else {
                        Log.i(
                            TAG,
                            "didn't init volume, e=$isVolumeAvailable O=$offloadEnabled"
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun myOnRoutingChanged(routedDevice: AudioDeviceInfo?) {
        Log.d(
            TAG, "routed device is now ${routedDevice?.productName} " +
                "(${routedDevice?.type?.let { audioDeviceTypeToString(context, it) }})"
        )
        deviceType = routedDevice?.type
        if (hasDpe) {
            calculateGain() // device change may have changed available headroom, recalculate boost
        } else {
            updateVolumeEffect() // device change reset the Volume effect state, configure it again
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDpeEffect() {
        hasDpe = false
        // DPE has this behaviour which I can really only call a bug, where inUse values are carried
        // over from other apps and the only way to reset it is to entirely release all instances
        // of the effect in ALL apps in this session ID at the same time. Also, sometimes if the
        // effect is busy the constructor randomly throws because it doesn't support priority well
        // - it always tries to set values even if we don't have control. Amazing work, Google. For
        // the DynamicsProcessing DSP to be the best thing ever, the parameter implementation is so
        // stupid I can't even put it into words.
        try {
            try {
                dpeEffect = DynamicsProcessing(
                    -100000, audioSessionId,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        1, false, 0,
                        false, 0, false,
                        0, true
                    )
                        .setAllChannelsTo(
                            DynamicsProcessing.Channel(
                                0f, false,
                                0, false, 0,
                                false, 0, true
                            ).apply {
                                mbc = DynamicsProcessing.Mbc(
                                    false, false, 0
                                )
                                limiter = DynamicsProcessing.Limiter(
                                    true, false, 0,
                                    ReplayGainUtil.TAU_ATTACK * 1000f,
                                    ReplayGainUtil.TAU_RELEASE * 1000f,
                                    ReplayGainUtil.RATIO, 0f, 0f
                                )
                                preEq = DynamicsProcessing.Eq(
                                    false, false, 0
                                )
                                postEq = DynamicsProcessing.Eq(
                                    false, false, 0
                                )
                            })
                        .build()
                )
            } catch (t: Throwable) {
                // DynamicsProcessing does not release() the instance if illegal arguments are
                // passed to the constructor. We have to rely on finalize to avoid conflicts with
                // ourselves, hence do a GC. This API is so broken...
                val policy = StrictMode.getThreadPolicy()
                try {
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
                    System.gc()
                } finally {
                    StrictMode.setThreadPolicy(policy)
                }
                throw t
            }
            dpeEffect!!.setControlStatusListener { effect, hasControl ->
                if (effect != dpeEffect) {
                    try {
                        effect.release()
                    } catch (_: Throwable) {
                    }
                    return@setControlStatusListener // stale event
                }
                Log.i(TAG, "dpe control state is now: $hasControl")
                try {
                    effect.release()
                } catch (_: Throwable) {
                }
                dpeEffect = null
                hasDpe = false
                // create new DPE effect without contamination of inUse parameters from other apps
                if (hasControl) createDpeEffect()
                else { // user enabled external equalizer
                    createDpeCanary()
                    calculateGain() // switch from DPE to setVolume()
                }
            }
            hasDpe = dpeEffect!!.hasControl()
            // wow, we got here. very good.
            if (dpeCanary != null) {
                Log.i(TAG, "release dpe canary because we got real dpe")
                try {
                    dpeCanary!!.release()
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to release DPE canary", e)
                }
                dpeCanary = null
            }
            Log.i(TAG, "init dpe, control state is: $hasDpe")
        } catch (e: Throwable) {
            if (e is UnsupportedOperationException)
                Log.w(TAG, "failed to init DPE effect: $e")
            else
                Log.e(TAG, "failed to init DPE effect", e)
            try {
                dpeEffect?.release()
            } catch (_: Throwable) {
            }
            dpeEffect = null
            hasDpe = false
            createDpeCanary()
        }
        calculateGain() // switch from setVolume() to DPE (or don't if we did not succeed)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDpeCanary() {
        // The bug where constructor sets parameters (which crashes, and hence we can't register a
        // control listener) can be worked around by using AudioEffect class raw via reflection to
        // check when we regain control. (Set lower prio to avoid conflicting with ourselves.)
        if (dpeCanary == null) {
            try {
                dpeCanary = ReflectionAudioEffect(
                    AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING,
                    ReflectionAudioEffect.EFFECT_TYPE_NULL, -100001, audioSessionId
                )
                dpeCanary!!.setControlStatusListener { _, controlGranted ->
                    Log.i(TAG, "dpe canary control state is now: $controlGranted")
                    if (controlGranted) {
                        try {
                            dpeCanary!!.release()
                        } catch (e: Throwable) {
                            Log.e(TAG, "failed to release DPE canary", e)
                        }
                        dpeCanary = null
                        createDpeEffect()
                    } else {
                        Log.e(TAG, "DPE canary lost control, but why did it ever have it?")
                        // an invalid state transition occurred, ensure we are using setVolume()
                        calculateGain()
                    }
                }
                Log.i(TAG, "init dpe canary")
                if (dpeCanary!!.hasControl()) {
                    Log.w(TAG, "release dpe canary because we suddenly have control")
                    try {
                        dpeCanary!!.release()
                    } catch (e: Throwable) {
                        Log.e(TAG, "failed to release DPE canary", e)
                    }
                    dpeCanary = null
                    createDpeEffect()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "failed to init DPE canary", e)
                try {
                    dpeCanary?.release()
                } catch (_: Throwable) {
                }
                dpeCanary = null
                // whatever, I give up.
            }
        }
    }

    private fun updateVolumeEffect() {
        if (!hasVolume) return
        val boostGainDb: Int
        synchronized(rgAp) {
            boostGainDb = rgAp.boostGain
        }
        try {
            val curVolumeDb = getCurrentMixerVolume()
            try {
                volumeEffect!!.enabled = boostGainDb > 0 && curVolumeDb != null
            } catch (e: IllegalStateException) {
                Log.e(TAG, "volume enable failed", e)
            }
            if (curVolumeDb == null || boostGainDb <= 0) return
            val theVolume = min(
                volumeEffect!!.maxLevel.toInt().toFloat(),
                (curVolumeDb + ReplayGainUtil.amplToDb(volume) +
                        boostGainDb) * 100f
            ).toInt().toShort()
            repeat(20) { // yes, this is stupid.
                volumeEffect!!.level = theVolume
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to update volume effect state", e)
        }
    }

    override fun play() {
        updateVolumeEffect() // play() will have reset volume effect state, configure it again
        super.play()
    }

    override fun pause() {
        updateVolumeEffect() // pause() will have reset volume effect state, configure it again
        super.pause()
    }

    override fun flush() {
        updateVolumeEffect() // flush() will have reset volume effect state, configure it again
        super.flush()
    }

    override fun release() {
        context.unregisterReceiver(receiver)
        try {
            AudioSystemHiddenApi.removeVolumeCallback(context, this)
        } catch (e: Exception) {
            Log.w(TAG, "failed to remove volume cb", e)
        }
        super.release()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val prev = sink.isAudioTrackStopped()
        val ret = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (sink.isAudioTrackStopped() != prev) {
            updateVolumeEffect() // stop() will have reset volume effect state, configure it again
        }
        return ret
    }

    private val audioTrackStoppedField by lazy {
        DefaultAudioSink::class.java.getDeclaredField("stoppedAudioTrack").apply {
            isAccessible = true
        }
    }

    private fun DefaultAudioSink.isAudioTrackStopped(): Boolean {
        return audioTrackStoppedField.get(this) as Boolean
    }

    // TODO why do we have to reflect on app code, there must be a better solution
    private fun DefaultAudioSink.getAudioTrack(): AudioTrack? {
        val cls = javaClass
        val field = cls.getDeclaredField("audioTrack")
        field.isAccessible = true
        return field.get(this) as AudioTrack?
    }

    private fun getHeadroomDb(): Float {
        // The headroom is the negative master*stream*shaper volume. However as shapers are
        // inherently temporary, and completely controlled only by us, we can ignore them here.
        val masterVolume = AudioSystemHiddenApi.getMasterVolume()
        val masterBalance = AudioSystemHiddenApi.getMasterBalance()
        if (masterVolume != null && masterVolume != 1f &&
            masterBalance != null && masterBalance != 0.5f) {
            // Later, this could actually adjust computed headroom instead of bailing. But it'd need
            // good testing first.
            Log.w(TAG, "unsupported master config v=$masterVolume b=$masterBalance")
            return 0f
        }
        val headroomDb = -(getCurrentMixerVolume() ?: 0f)
        if (headroomDb !in 0f..16f) {
            Log.e(TAG, "had to limit headroom db $headroomDb")
            return headroomDb.coerceIn(0f, 16f) // avoid getting insanely loud due to a bug.
        }
        return headroomDb
    }

    // To get the real volume of mixer taking into account absolute volume:
    // - 15 QPR0 and earlier: use AudioFlinger.streamVolume() to get volume after any prescale or
    //                        force to max done in java (A2DP/HDMI/LEA/ASHA). Returns dB since M.
    //  also, just on 15 QPR0, getStreamVolumeDb(publicApiIndex) will return real volume (ie 0dB) for
    //  A2DP/LEA/ASHA, but not HDMI. but can't differentiate between 15 QPR0 and 15 QPR1 in public
    //  API so this is not useful fallback for case where private API bypass somehow ends up broken.
    // - 15 QPR1 and later: HDMI can no longer be detected at all, so got to be pessimistic.
    //   - 15 QPR1: have to apply adjustDeviceAttenuationForAbsVolume(), ie force 0dB except if the
    //              index is zero and device is not BLE broadcast, then min volume dB, in app code
    //              based on the result of isAbsoluteVolume().
    //   - 15 QPR2: getOutputForAttr() returns real volume as amplification, but it's reserved for
    //              AudioFlinger - no luck here. do same as QPR1.
    // Alternatively, to avoid pessimism on 15 QPR1 and later, if Volume is offloadable (or offload
    // is disabled) we can create a stopped mixed track (mustn't be offload to avoid wasting
    // resources) and Volume effect and read Volume.level property.
    // TODO: Poweramp does something like this in AbsVolDetectorViaVolume but I don't understand it.
    //  How does it work, and is it really worth it?
    // If hidden API is not available, we have to be pessimistic and assume no prescale and apply
    // force max based on result of isAbsoluteVolume().
    private fun getCurrentMixerVolume(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            val track = sink.getAudioTrack()
            var output: Int? = lastOutput
            if (track != null) {
                output = AudioTrackHiddenApi.getOutput(track)
                lastOutput = output
            }
            if (output != null) {
                val streamVolume = AudioSystemHiddenApi.getStreamVolume(
                    AudioManager.STREAM_MUSIC, output)
                if (streamVolume != null) {
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        streamVolume // streamVolume is -96f..0f dB
                    } else {
                        ReplayGainUtil.amplToDb(streamVolume) // streamVolume is 0f..1f
                    }
                }
            }
        }
        if (deviceType == null || isAbsoluteVolume(deviceType!!)) {
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // TODO: this could support O MR1 with AudioSystem java
            val maxIndex =
                AudioManagerCompat.getStreamMaxVolume(audioManager, C.STREAM_TYPE_MUSIC)
            val curIndex = AudioManagerCompat.getStreamVolume(audioManager, C.STREAM_TYPE_MUSIC)
            val minIndex =
                AudioManagerCompat.getStreamMinVolume(audioManager, C.STREAM_TYPE_MUSIC)
            val minVolumeDb =
                max(
                    audioManager.getStreamVolumeDb(
                        AudioManager.STREAM_MUSIC, minIndex,
                        deviceType!!
                    ), -96f
                )
            var maxVolumeDb = audioManager.getStreamVolumeDb(
                AudioManager.STREAM_MUSIC, maxIndex,
                deviceType!!
            )
            var curVolumeDb = max(
                audioManager.getStreamVolumeDb(
                    AudioManager.STREAM_MUSIC, curIndex,
                    deviceType!!
                ), -96f
            )
            if (maxVolumeDb - minVolumeDb == 1f && curVolumeDb <= 1f && curVolumeDb >= 0f) {
                maxVolumeDb = ReplayGainUtil.amplToDb(maxVolumeDb)
                curVolumeDb = ReplayGainUtil.amplToDb(curVolumeDb)
            }
            return -(maxVolumeDb - curVolumeDb)
        }
        // We have no way left to know.
        return null
    }

    private fun isAbsoluteVolume(
        deviceType: Int,
        isA2dpAbsoluteVolumeOff: Boolean = false,
        isHdmiCecVolumeOff: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IllegalStateException("isAbsoluteVolume($deviceType) before M")
        }
        // LEA having abs vol is a safe assumption, as LEA absolute volume is forced. Same for ASHA.
        return !isA2dpAbsoluteVolumeOff && deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                !isHdmiCecVolumeOff && (deviceType == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                deviceType == AudioDeviceInfo.TYPE_HDMI ||
                deviceType == AudioDeviceInfo.TYPE_HDMI_ARC) ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                deviceType == AudioDeviceInfo.TYPE_HEARING_AID ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && deviceType == AudioDeviceInfo.TYPE_BLE_BROADCAST) ||
                        deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        !isHdmiCecVolumeOff && deviceType == AudioDeviceInfo.TYPE_HDMI_EARC)
    }
}
