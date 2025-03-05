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

package org.akanework.gramophone.ui

import android.annotation.SuppressLint
import android.app.ComponentCaller
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import coil3.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.BuildConfig
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgeProperly
import org.akanework.gramophone.logic.gramophoneApplication
import org.akanework.gramophone.logic.hasScopedStorageV2
import org.akanework.gramophone.logic.hasScopedStorageWithMediaTypes
import org.akanework.gramophone.logic.needsMissingOnDestroyCallWorkarounds
import org.akanework.gramophone.logic.postAtFrontOfQueueAsync
import org.akanework.gramophone.ui.components.PlayerBottomSheet
import org.akanework.gramophone.ui.fragments.BaseFragment
import androidx.core.net.toUri
import androidx.media3.common.C
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.hasAudioPermission

/**
 * MainActivity:
 *   Core of gramophone, one and the only activity
 * used across the application.
 *
 * @author AkaneTan, nift4
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_READ_MEDIA_AUDIO = 100
        const val PLAYBACK_AUTO_START_FOR_FGS = "AutoStartFgs"
        const val PLAYBACK_AUTO_PLAY_ID = "AutoStartId"
        const val PLAYBACK_AUTO_PLAY_POSITION = "AutoStartPos"
    }

    // Import our viewModels.
    val controllerViewModel: MediaControllerViewModel by viewModels()
    val startingActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private val handler = Handler(Looper.getMainLooper())
    private val reportFullyDrawnRunnable = Runnable { if (!ready) reportFullyDrawn() }
    private var ready = false
    private var autoPlay = false
    lateinit var playerBottomSheet: PlayerBottomSheet
        private set
    lateinit var intentSender: ActivityResultLauncher<IntentSenderRequest>
        private set

    fun updateLibrary(then: (() -> Unit)? = null) {
        // If library load takes more than 3s, exit splash to avoid ANR
        if (!ready) handler.postDelayed(reportFullyDrawnRunnable, 3000)
        CoroutineScope(Dispatchers.Default).launch {
            this@MainActivity.gramophoneApplication.reader.refresh()
            withContext(Dispatchers.Main) {
                onLibraryLoaded()
                then?.let { it() }
            }
        }
    }

    /**
     * onCreate - core of MainActivity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(controllerViewModel)
        enableEdgeToEdgeProperly()
        intentSender =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

        supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                super.onFragmentStarted(fm, f)
                if (fm.fragments.lastOrNull() != f) return
                // this won't be called in case we show()/hide() so
                // we handle that case in BaseFragment
                if (f is BaseFragment && f.wantsPlayer != null) {
                    playerBottomSheet.visible = f.wantsPlayer
                }
            }
        }, false)

        // Set content Views.
        setContentView(R.layout.activity_main)
        if (BuildConfig.DEBUG) {
            @SuppressLint("SetTextI18n")
            findViewById<ViewGroup>(R.id.rootView).addView(TextView(this).apply {
                text = "DEBUG"
                setTextColor(Color.RED)
                translationZ = 9999999f
                translationX = 50f
            })
        }
        playerBottomSheet = findViewById(R.id.player_layout)

        // Check all permissions.
        if (!hasAudioPermission()) {
            // Ask if was denied.
            ActivityCompat.requestPermissions(
                this,
                if (hasScopedStorageWithMediaTypes())
                    arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                else if (hasScopedStorageV2())
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                else
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                PERMISSION_READ_MEDIA_AUDIO,
            )
        } else {
            // If all permissions are granted, we can update library now.
            if (!this@MainActivity.reader.hadFirstRefresh) {
                updateLibrary()
            } else onLibraryLoaded() // <-- when recreating activity due to rotation
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autoPlay = intent.extras?.getBoolean(PLAYBACK_AUTO_START_FOR_FGS, false) == true
        if (ready) {
            intent.extras?.getString(PLAYBACK_AUTO_PLAY_ID)?.let { id ->
                val pos = intent.extras?.getLong(PLAYBACK_AUTO_PLAY_POSITION, C.TIME_UNSET) ?: C.TIME_UNSET
                controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->
                    runBlocking { reader.songListFlow
                        .map { it.find { it.mediaId == id } }.first() }?.let { mediaItem ->
                        controller.setMediaItem(mediaItem)
                        controller.prepare()
                        controller.seekTo(pos)
                        controller.play()
                    }
                    dispose()
                }
            }
        }
    }

    // https://twitter.com/Piwai/status/1529510076196630528
    override fun reportFullyDrawn() {
        handler.removeCallbacks(reportFullyDrawnRunnable)
        if (ready) throw IllegalStateException("ready is already true")
        ready = true
        Choreographer.getInstance().postFrameCallback {
            handler.postAtFrontOfQueueAsync {
                super.reportFullyDrawn()
            }
        }
    }

    fun onLibraryLoaded() {
        if (!ready) reportFullyDrawn()
        intent?.extras?.getLong(PLAYBACK_AUTO_PLAY_ID, 0L).let { it ->
            if (it != 0L) {
                val id = it.toString()
                controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->
                    val songs = runBlocking { this@MainActivity.gramophoneApplication.reader.songListFlow.first() }
                    songs.find { it.mediaId == id }?.let { mediaItem ->
                        controller.setMediaItem(mediaItem)
                        controller.prepare()
                        controller.play()
                    }
                    dispose()
                }
            }
        }
    }

    /**
     * onRequestPermissionResult:
     *   Update library after permission is granted.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_READ_MEDIA_AUDIO) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                updateLibrary()
            } else {
                reportFullyDrawn()
                Toast.makeText(this, getString(R.string.grant_audio), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.setData("package:$packageName".toUri())
                startActivity(intent)
                finish()
            }
        }
    }

    /**
     * startFragment:
     *   Used by child fragments / drawer to start
     * a fragment inside MainActivity's fragment
     * scope.
     *
     * @param frag: Target fragment.
     */
    fun startFragment(frag: Fragment, args: (Bundle.() -> Unit)? = null) {
        supportFragmentManager.commit {
            addToBackStack(System.currentTimeMillis().toString())
            hide(supportFragmentManager.fragments.last())
            add(R.id.container, frag.apply { args?.let { arguments = Bundle().apply(it) } })
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        // https://github.com/androidx/media/issues/805
        if (needsMissingOnDestroyCallWorkarounds()
            && (getPlayer()?.playWhenReady != true || getPlayer()?.mediaItemCount == 0)
        ) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
        super.onDestroy()
        // we don't ever want covers to be the cause of service being killed by too high mem usage
        // (this is placed after super.onDestroy() to make sure all ImageViews are dead)
        imageLoader.memoryCache?.clear()
    }

    /**
     * getPlayer:
     *   Returns a media controller.
     */
    fun getPlayer() = controllerViewModel.get()

    fun consumeAutoPlay(): Boolean {
        return autoPlay.also { autoPlay = false }
    }

    inline val reader
        get() = gramophoneApplication.reader
}
