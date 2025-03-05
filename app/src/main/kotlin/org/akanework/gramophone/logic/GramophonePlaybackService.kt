/*
 *     Copyright (C) 2024 Akane Foundation
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

package org.akanework.gramophone.logic

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
import androidx.media3.common.Tracks
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.doUpdateNotification
import androidx.preference.PreferenceManager
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.akanework.gramophone.BuildConfig
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.MeiZuLyricsMediaNotificationProvider
import org.akanework.gramophone.logic.utils.AfFormatTracker
import org.akanework.gramophone.logic.utils.AudioTrackInfo
import org.akanework.gramophone.logic.utils.CircularShuffleOrder
import org.akanework.gramophone.logic.utils.LastPlayedManager
import org.akanework.gramophone.logic.utils.LrcUtils.LrcParserOptions
import org.akanework.gramophone.logic.utils.LrcUtils.extractAndParseLyrics
import org.akanework.gramophone.logic.utils.LrcUtils.extractAndParseLyricsLegacy
import org.akanework.gramophone.logic.utils.LrcUtils.loadAndParseLyricsFile
import org.akanework.gramophone.logic.utils.LrcUtils.loadAndParseLyricsFileLegacy
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.exoplayer.EndedWorkaroundPlayer
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneMediaSourceFactory
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneRenderFactory
import org.akanework.gramophone.ui.LyricWidgetProvider
import org.akanework.gramophone.ui.MainActivity
import kotlin.random.Random


/**
 * [GramophonePlaybackService] is a server service.
 * It's using exoplayer2 as its player backend.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GramophonePlaybackService : MediaLibraryService(), MediaSessionService.Listener,
    MediaLibraryService.MediaLibrarySession.Callback, Player.Listener, AnalyticsListener {

    companion object {
        private const val TAG = "GramoPlaybackService"
        private const val NOTIFY_CHANNEL_ID = "serviceFgsError"
        private const val NOTIFY_ID = 1
        private const val PENDING_INTENT_SESSION_ID = 0
        private const val PENDING_INTENT_NOTIFY_ID = 1
        const val PENDING_INTENT_WIDGET_ID = 2
        private const val PLAYBACK_SHUFFLE_ACTION_ON = "shuffle_on"
        private const val PLAYBACK_SHUFFLE_ACTION_OFF = "shuffle_off"
        private const val PLAYBACK_REPEAT_OFF = "repeat_off"
        private const val PLAYBACK_REPEAT_ALL = "repeat_all"
        private const val PLAYBACK_REPEAT_ONE = "repeat_one"
        const val SERVICE_SET_TIMER = "set_timer"
        const val SERVICE_QUERY_TIMER = "query_timer"
        const val SERVICE_GET_AUDIO_FORMAT = "get_audio_format"
        const val SERVICE_GET_LYRICS = "get_lyrics"
        const val SERVICE_GET_LYRICS_LEGACY = "get_lyrics_legacy"
        const val SERVICE_GET_SESSION = "get_session"
        const val SERVICE_TIMER_CHANGED = "changed_timer"
        var instanceForWidgetAndLyricsOnly: GramophonePlaybackService? = null
    }

    private var lastSessionId = 0
    private val internalPlaybackThread = HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
    private var mediaSession: MediaLibrarySession? = null
    val endedWorkaroundPlayer
        get() = mediaSession?.player as EndedWorkaroundPlayer?
    var controller: MediaController? = null
        private set
    private val sendLyrics = Runnable { scheduleSendingLyrics(false) }
    var lyrics: SemanticLyrics? = null
        private set
    val syncedLyrics
        get() = lyrics as? SemanticLyrics.SyncedLyrics
    var lyricsLegacy: MutableList<MediaStoreUtils.Lyric>? = null
        private set
    private lateinit var customCommands: List<CommandButton>
    private lateinit var handler: Handler
    private lateinit var playbackHandler: Handler
    private lateinit var nm: NotificationManagerCompat
    private lateinit var lastPlayedManager: LastPlayedManager
    private val lyricsLock = Semaphore(1)
    private lateinit var prefs: SharedPreferences
    private var lastSentHighlightedLyric: String? = null
    private lateinit var afFormatTracker: AfFormatTracker
    private var updatedLyricAtLeastOnce = false
    private var downstreamFormat: Format? = null
    private var audioSinkInputFormat: Format? = null
    private var audioTrackInfo: AudioTrackInfo? = null
    private var audioTrackInfoCounter = 0
    private var audioTrackReleaseCounter = 0

	private fun getRepeatCommand() =
        when (controller!!.repeatMode) {
            Player.REPEAT_MODE_OFF -> customCommands[2]
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> throw IllegalArgumentException()
        }

    private fun getShufflingCommand() =
        if (controller!!.shuffleModeEnabled)
            customCommands[1]
        else
            customCommands[0]

    private val timer: Runnable = Runnable {
        controller!!.pause()
        timerDuration = null
    }

    private var timerDuration: Long? = null
        set(value) {
            field = value
            if (value != null && value > 0) {
                handler.postDelayed(timer, value - System.currentTimeMillis())
            } else {
                handler.removeCallbacks(timer)
            }
            mediaSession!!.broadcastCustomCommand(
                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

    private val headSetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action.equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                controller?.pause()
            }
        }
    }

    private val seekReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val to =
                intent.extras?.getLong("seekTo", C.INDEX_UNSET.toLong()) ?: C.INDEX_UNSET.toLong()
            if (to != C.INDEX_UNSET.toLong())
                controller?.seekTo(to)
        }
    }

    override fun onCreate() {
        instanceForWidgetAndLyricsOnly = this
        internalPlaybackThread.start()
        playbackHandler = Handler(internalPlaybackThread.looper)
        handler = Handler(Looper.getMainLooper())
        super.onCreate()
        nm = NotificationManagerCompat.from(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        setListener(this)
        setMediaNotificationProvider(
            MeiZuLyricsMediaNotificationProvider(this) { lastSentHighlightedLyric }
        )
        if (mayThrowForegroundServiceStartNotAllowed()
            || mayThrowForegroundServiceStartNotAllowedMiui()
        ) {
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    NOTIFY_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH
                ).apply {
                    setName(getString(R.string.fgs_failed_channel))
                    setVibrationEnabled(true)
                    setVibrationPattern(longArrayOf(0L, 200L))
                    setLightsEnabled(false)
                    setShowBadge(false)
                    setSound(null, null)
                }.build()
            )
        } else if (nm.getNotificationChannel(NOTIFY_CHANNEL_ID) != null) {
            // for people who upgraded from S/S_V2 to newer version
            nm.deleteNotificationChannel(NOTIFY_CHANNEL_ID)
        }

        customCommands =
            listOf(
                CommandButton.Builder() // shuffle currently disabled, click will enable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_ON, Bundle.EMPTY)
                    )
                    .setIconResId(R.drawable.ic_shuffle)
                    .build(),
                CommandButton.Builder() // shuffle currently enabled, click will disable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_OFF, Bundle.EMPTY)
                    )
                    .setIconResId(R.drawable.ic_shuffle_on)
                    .build(),
                CommandButton.Builder() // repeat currently disabled, click will repeat all
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ALL, Bundle.EMPTY)
                    )
                    .setIconResId(R.drawable.ic_repeat)
                    .build(),
                CommandButton.Builder() // repeat all currently enabled, click will repeat one
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ONE, Bundle.EMPTY)
                    )
                    .setIconResId(R.drawable.ic_repeat_on)
                    .build(),
                CommandButton.Builder() // repeat one currently enabled, click will disable
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_OFF, Bundle.EMPTY)
                    )
                    .setIconResId(R.drawable.ic_repeat_one_on)
                    .build(),
            )
        afFormatTracker = AfFormatTracker(this, playbackHandler, handler)
        afFormatTracker.formatChangedCallback = {
            mediaSession?.broadcastCustomCommand(
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
        val player = EndedWorkaroundPlayer(
            ExoPlayer.Builder(
                this,
                GramophoneRenderFactory(this, this::onAudioSinkInputFormatChanged,
                    afFormatTracker::setAudioSink)
	                .setEnableAudioFloatOutput(
                        prefs.getBooleanStrict("floatoutput", false)
                    )
                    .setEnableDecoderFallback(true)
                    .setEnableAudioTrackPlaybackParams( // hardware/system-accelerated playback speed
                        prefs.getBooleanStrict("ps_hardware_acc", true)
                    )
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
                GramophoneMediaSourceFactory(this)
                /* .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING))
            TODO flag breaks playback of AcousticGuitar.mp3, report exo bug + add UI toggle*/
            )
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSkipSilenceEnabled(prefs.getBooleanStrict("skip_silence", false))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true
                )
                .setPlaybackLooper(internalPlaybackThread.looper)
                .build()
        )
        if (BuildConfig.DEBUG) {
            player.exoPlayer.addAnalyticsListener(EventLogger())
        }
        player.exoPlayer.addAnalyticsListener(afFormatTracker)
        player.exoPlayer.addAnalyticsListener(this)
        player.exoPlayer.audioSessionId = Util.generateAudioSessionIdV21(this)
        lastSessionId = player.exoPlayer.audioSessionId
        player.setShuffleOrder { CircularShuffleOrder(it, 0, 0, Random.nextLong()) }
        broadcastAudioSession()
        lastPlayedManager = LastPlayedManager(this, player)
        lastPlayedManager.allowSavingState = false

        mediaSession =
            MediaLibrarySession
                .Builder(this, player, this)
                // CacheBitmapLoader is required for MeiZuLyricsMediaNotificationProvider
                .setBitmapLoader(CacheBitmapLoader(object : BitmapLoader {
                    // Coil-based bitmap loader to reuse Coil's caching and to make sure we use
                    // the same cover art as the rest of the app, ie MediaStore's cover

                    override fun decodeBitmap(data: ByteArray) =
                        throw UnsupportedOperationException("decodeBitmap() not supported")

                    override fun loadBitmap(
                        uri: Uri
                    ): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@GramophonePlaybackService)
                                    .data(uri)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { _ ->
                                            completer.setException(
                                                Exception(
                                                    "coil onError called" +
                                                            " (normal if no album art exists)"
                                                )
                                            )
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        ContextCompat.getMainExecutor(
                                            this@GramophonePlaybackService
                                        )
                                    )
                                }
                            "coil load for $uri"
                        }
                    }

                    override fun supportsMimeType(mimeType: String): Boolean {
                        return isBitmapFactorySupportedMimeType(mimeType)
                    }

                    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
                        return metadata.artworkUri?.let { loadBitmap(it) }
                    }
                }))
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        PENDING_INTENT_SESSION_ID,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        controller = MediaController.Builder(this, mediaSession!!.token).buildAsync().get()
        onShuffleModeEnabledChanged(controller!!.shuffleModeEnabled) // refresh custom commands
        controller!!.addListener(this)
        registerReceiver(
            headSetReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        ContextCompat.registerReceiver(
            this,
            seekReceiver,
            IntentFilter("$packageName.SEEK_TO"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lastPlayedManager.restore { items, factory ->
            if (mediaSession == null) return@restore
            if (items != null) {
                if (endedWorkaroundPlayer?.nextShuffleOrder != null)
                    throw IllegalStateException("shuffleFactory was found orphaned")
                endedWorkaroundPlayer?.nextShuffleOrder = factory.toFactory()
                try {
                    mediaSession?.player?.setMediaItems(
                        items.mediaItems, items.startIndex, items.startPositionMs
                    )
                } catch (e: IllegalSeekPositionException) {
                    // song was edited to be shorter and playback position doesn't exist anymore
                    Log.e(TAG, "failed to restore with startPositionMs, trying without... "
                            + Log.getStackTraceString(e))
                    try {
                        mediaSession?.player?.setMediaItems(
                            items.mediaItems, items.startIndex, C.TIME_UNSET
                        )
                    } catch (e: IllegalSeekPositionException) {
                        // whatever...
                        Log.e(TAG, "failed to restore: " + Log.getStackTraceString(e))
                        endedWorkaroundPlayer?.nextShuffleOrder = null
                    }
                }
                if (endedWorkaroundPlayer?.nextShuffleOrder != null)
                    throw IllegalStateException("shuffleFactory was not consumed during restore")
                handler.post {
                    // Prepare Player after UI thread is less busy (loads tracks, required for lyric)
                    controller?.prepare()
                }
            }
            lastPlayedManager.allowSavingState = true
        }
    }

    // When destroying, we should release server side player
    // alongside with the mediaSession.
    override fun onDestroy() {
        instanceForWidgetAndLyricsOnly = null
        unregisterReceiver(headSetReceiver)
        unregisterReceiver(seekReceiver)
        // Important: this must happen before sending stop() as that changes state ENDED -> IDLE
        lastPlayedManager.save()
        mediaSession!!.player.stop()
        broadcastAudioSessionClose()
        controller!!.release()
        controller = null
        mediaSession!!.release()
        mediaSession!!.player.release()
        mediaSession = null
        internalPlaybackThread.quitSafely()
        LyricWidgetProvider.update(this)
        super.onDestroy()
    }

    // This onGetSession is a necessary method override needed by
    // MediaSessionService.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    // Configure commands available to the controller in onConnect()
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo)
            : MediaSession.ConnectionResult {
        val availableSessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (session.isMediaNotificationController(controller)
            || session.isAutoCompanionController(controller)
            || session.isAutomotiveController(controller)
        ) {
            // currently, all custom actions are only useful when used by notification
            // other clients hopefully have repeat/shuffle buttons like MCT does
            for (commandButton in customCommands) {
                // Add custom command to available session commands.
                commandButton.sessionCommand?.let { availableSessionCommands.add(it) }
            }
        }
        availableSessionCommands.add(SessionCommand(SERVICE_SET_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_SESSION, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QUERY_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_LYRICS_LEGACY, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY))

        handler.post {
            session.sendCustomCommand(
                controller,
                SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY),
                Bundle.EMPTY
            )

            session.sendCustomCommand(
                controller,
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(availableSessionCommands.build())
            .build()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        super<Player.Listener>.onAudioSessionIdChanged(audioSessionId)
        broadcastAudioSessionClose()
        lastSessionId = audioSessionId
        broadcastAudioSession()
    }

    private fun broadcastAudioSession() {
        if (lastSessionId != 0) {
            sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        } else {
            Log.e(TAG, "session id is 0? why????? THIS MIGHT BREAK EQUALIZER")
        }
    }

    private fun broadcastAudioSessionClose() {
        if (lastSessionId != 0) {
            sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
            })
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return Futures.immediateFuture(when (customCommand.customAction) {
            PLAYBACK_SHUFFLE_ACTION_ON -> {
                this.controller!!.shuffleModeEnabled = true
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_SHUFFLE_ACTION_OFF -> {
                this.controller!!.shuffleModeEnabled = false
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            SERVICE_SET_TIMER -> {
                // 0 = clear timer
                customCommand.customExtras.getInt("duration").let {
                    timerDuration = if (it > 0) System.currentTimeMillis() + it else null
                }
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            SERVICE_QUERY_TIMER -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    timerDuration?.let { td ->
                        it.extras.putInt("duration", (td - System.currentTimeMillis()).toInt())
                    }
                }
            }

            SERVICE_GET_AUDIO_FORMAT -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putBundle("file_format", downstreamFormat?.toBundle())
                    it.extras.putBundle("sink_format", audioSinkInputFormat?.toBundle())
                    it.extras.putParcelable("track_format", audioTrackInfo)
                    it.extras.putParcelable("hal_format", afFormatTracker.format)
                }
            }

            SERVICE_GET_LYRICS -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putParcelable("lyrics", lyrics)
                }
            }

            SERVICE_GET_LYRICS_LEGACY -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putParcelableArray("lyrics", lyricsLegacy?.toTypedArray())
                }
            }

            SERVICE_GET_SESSION -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putInt("session", lastSessionId)
                }
            }

            PLAYBACK_REPEAT_OFF -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_OFF
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_REPEAT_ONE -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_ONE
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_REPEAT_ALL -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_ALL
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            else -> {
                SessionResult(SessionError.ERROR_BAD_VALUE)
            }
        })
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val settable = SettableFuture.create<MediaItemsWithStartPosition>()
        lastPlayedManager.restore { items, factory ->
            if (items == null) {
                settable.setException(
                    NullPointerException(
                        "null MediaItemsWithStartPosition, see former logs for root cause"
                    ).also { Log.e(TAG, Log.getStackTraceString(it)) }
                )
            } else if (items.mediaItems.isNotEmpty()) {
                if (endedWorkaroundPlayer?.nextShuffleOrder != null)
                    throw IllegalStateException("shuffleFactory was found orphaned")
                endedWorkaroundPlayer?.nextShuffleOrder = factory.toFactory()
                // This call will only sometimes set the playlist on our controller (it won't if
                // the system is just asking for the last played song for display purposes)
                settable.set(items)
                endedWorkaroundPlayer?.nextShuffleOrder = null
            } else {
                settable.setException(
                    IndexOutOfBoundsException(
                        "LastPlayedManager restored empty MediaItemsWithStartPosition"
                    ).also { Log.e(TAG, Log.getStackTraceString(it)) }
                )
            }
        }
        return settable
    }

    override fun onTracksChanged(tracks: Tracks) {
        val mediaItem = controller!!.currentMediaItem

        lyricsLock.runInBg {
            val trim = prefs.getBoolean("trim_lyrics", true)
            val multiLine = prefs.getBoolean("lyric_multiline", false)
            val newParser = prefs.getBoolean("lyric_parser", false)
            val options = LrcParserOptions(
                trim = trim, multiLine = multiLine,
                errorText = getString(R.string.failed_to_parse_lyric)
            )
            if (newParser) {
                var lrc = loadAndParseLyricsFile(mediaItem?.getFile(), options)
                if (lrc == null) {
                    loop@ for (i in tracks.groups) {
                        for (j in 0 until i.length) {
                            if (!i.isTrackSelected(j)) continue
                            // note: wav files can have null metadata
                            val trackMetadata = i.getTrackFormat(j).metadata ?: continue
                            lrc = extractAndParseLyrics(trackMetadata, options) ?: continue
                            break@loop
                        }
                    }
                }
                CoroutineScope(Dispatchers.Main).launch {
                    mediaSession?.let {
                        lyrics = lrc
                        lyricsLegacy = null
                        it.broadcastCustomCommand(
                            SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY),
                            Bundle.EMPTY
                        )
                        scheduleSendingLyrics(true)
                    }
                }.join()
            } else {
                var lrc = loadAndParseLyricsFileLegacy(mediaItem?.getFile(), options)
                if (lrc == null) {
                    loop@ for (i in tracks.groups) {
                        for (j in 0 until i.length) {
                            if (!i.isTrackSelected(j)) continue
                            // note: wav files can have null metadata
                            val trackMetadata = i.getTrackFormat(j).metadata ?: continue
                            lrc = extractAndParseLyricsLegacy(trackMetadata, options) ?: continue
                            // add empty element at the beginning
                            lrc.add(0, MediaStoreUtils.Lyric())
                            break@loop
                        }
                    }
                }
                CoroutineScope(Dispatchers.Main).launch {
                    mediaSession?.let {
                        lyrics = null
                        lyricsLegacy = lrc
                        it.broadcastCustomCommand(
                            SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY),
                            Bundle.EMPTY
                        )
                        scheduleSendingLyrics(true)
                    }
                }.join()
            }
        }
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        audioTrackInfoCounter++
        audioTrackInfo = AudioTrackInfo.fromMedia3AudioTrackConfig(audioTrackConfig)
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        // Normally called after the replacement has been initialized, but if old track is released
        // without replacement, we want to instantly know that instead of keeping stale data.
        if (++audioTrackReleaseCounter == audioTrackInfoCounter) {
            audioTrackInfo = null
            mediaSession?.broadcastCustomCommand(
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    // TODO make sure this is invalidated if... when should it be invalidated? track change?
    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        /* TODO if downstream format bitrate is not set, calculate overall bitrate
        if (mediaLoadData.trackFormat?.bitrate == Format.NO_VALUE) {
            (and try what the details screen does before trying this)
            private fun calculateOverallBitrate(mediaItem: MediaItem?, duration: Long?): Int? {
                // TODO this really should avoid counting cover or container data
                //  or at least do a rough subtraction of cover size
                if (mediaItem == null || duration == null || duration <= 0) return null

                val uri = mediaItem.localConfiguration?.uri ?: return null

                try {
                    val file = File(uri.path!!)
                    val sizeInBits = file.length() * 8
                    return (sizeInBits / (duration / 1000.0)).toInt()
                } catch (_: Exception) {
                    return null
                }
            }
        } */
        downstreamFormat = mediaLoadData.trackFormat
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    private fun onAudioSinkInputFormatChanged(inputFormat: Format?) {
        audioSinkInputFormat = inputFormat
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        // TODO
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lyrics = null
        lyricsLegacy = null
        scheduleSendingLyrics(true)
        lastPlayedManager.save()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        scheduleSendingLyrics(false)
        lastPlayedManager.save()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super<Player.Listener>.onEvents(player, events)
        // if timeline changed, shuffle order is handled elsewhere instead (cloneAndInsert called by
        // ExoPlayer for common case and nextShuffleOrder for resumption case)
        if (events.contains(EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            && !events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            // when enabling shuffle, re-shuffle lists so that the first index is up to date
            Log.i(TAG, "re-shuffling playlist")
            endedWorkaroundPlayer?.setShuffleOrder {
                CircularShuffleOrder(
                    it,
                    controller!!.currentMediaItemIndex,
                    controller!!.mediaItemCount,
                    Random.nextLong()
                )
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        mediaSession!!.setCustomLayout(ImmutableList.of(getRepeatCommand(), getShufflingCommand()))
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        super<Player.Listener>.onRepeatModeChanged(repeatMode)
        mediaSession!!.setCustomLayout(ImmutableList.of(getRepeatCommand(), getShufflingCommand()))
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super<Player.Listener>.onPositionDiscontinuity(oldPosition, newPosition, reason)
        scheduleSendingLyrics(false)
    }

    private fun scheduleSendingLyrics(new: Boolean) {
        handler.removeCallbacks(sendLyrics)
        sendLyricNow(new || !updatedLyricAtLeastOnce)
        updatedLyricAtLeastOnce = true
        val isStatusBarLyricsEnabled = prefs.getBooleanStrict("status_bar_lyrics", false)
        val hnw = !LyricWidgetProvider.hasWidget(this)
        if (controller?.isPlaying != true || (!isStatusBarLyricsEnabled && hnw)) return
        val cPos = (controller?.contentPosition ?: 0).toULong()
        // TODO support lyrics going backwards in time (start of next line is before end of this line)
        val nextUpdate = if (syncedLyrics != null) {
            syncedLyrics?.text?.flatMap {
                if (hnw && it.start <= cPos) listOf() else if (hnw) listOf(it.start) else
                    (it.words?.map { it.timeRange.start }?.filter { it > cPos } ?: listOf())
                        .let { i -> if (it.start > cPos) i + it.start else i }
            }?.minOrNull()
        } else if (lyricsLegacy != null) {
            lyricsLegacy?.find {
                (it.timeStamp ?: -2) > cPos.toLong()
            }?.timeStamp?.toULong()
        } else null
        nextUpdate?.let { handler.postDelayed(sendLyrics, (it - cPos).toLong()) }
    }

    private fun sendLyricNow(new: Boolean) {
        if (new)
            LyricWidgetProvider.update(this)
        else
            LyricWidgetProvider.adapterUpdate(this)
        val isStatusBarLyricsEnabled = prefs.getBooleanStrict("status_bar_lyrics", false)
        val highlightedLyric = if (isStatusBarLyricsEnabled && controller?.playWhenReady == true)
            getCurrentLyricIndex(false)?.let {
                syncedLyrics?.text?.get(it)?.text ?: lyricsLegacy?.get(it)?.content
            }
        else null
        if (lastSentHighlightedLyric != highlightedLyric) {
            lastSentHighlightedLyric = highlightedLyric
            doUpdateNotification(mediaSession!!)
        }
    }
    // TODO support lyrics going backwards in time (start of next line is before end of this line)
    fun getCurrentLyricIndex(withTranslation: Boolean) =
        if (syncedLyrics != null) {
            syncedLyrics?.text?.indexOfLast {
                it.start <= (controller?.currentPosition ?: 0).toULong()
                        && (!it.isTranslated || withTranslation)
            }?.let { if (it == -1) null else it }
        } else if (lyricsLegacy != null) {
            lyricsLegacy?.indexOfLast {
                (it.timeStamp ?: Long.MAX_VALUE) <= (controller?.currentPosition ?: 0)
                        && (!it.isTranslation || withTranslation)
            }?.let { if (it == -1) null else it }
        } else null

    override fun onForegroundServiceStartNotAllowedException() {
        Log.w(TAG, "Failed to resume playback :/")
        if (mayThrowForegroundServiceStartNotAllowed()
            || mayThrowForegroundServiceStartNotAllowedMiui()
        ) {
            if (supportsNotificationPermission() && !hasNotificationPermission()) {
                Log.e(
                    TAG, Log.getStackTraceString(
                        IllegalStateException(
                            "onForegroundServiceStartNotAllowedException shouldn't be called on T+"
                        )
                    )
                )
                return
            }
            @SuppressLint("MissingPermission") // false positive
            nm.notify(NOTIFY_ID, NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID).apply {
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setAutoCancel(true)
                setCategory(NotificationCompat.CATEGORY_ERROR)
                setSmallIcon(R.drawable.ic_error)
                setContentTitle(this@GramophonePlaybackService.getString(R.string.fgs_failed_title))
                setContentText(this@GramophonePlaybackService.getString(R.string.fgs_failed_text))
                setContentIntent(
                    PendingIntent.getActivity(
                        this@GramophonePlaybackService,
                        PENDING_INTENT_NOTIFY_ID,
                        Intent(this@GramophonePlaybackService, MainActivity::class.java)
                            .putExtra(MainActivity.PLAYBACK_AUTO_START_FOR_FGS, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                setVibrate(longArrayOf(0L, 200L))
                setLights(0, 0, 0)
                setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                setSound(null)
            }.build())
        } else {
            handler.post {
                throw IllegalStateException("onForegroundServiceStartNotAllowedException shouldn't be called on T+")
            }
        }
    }
}
