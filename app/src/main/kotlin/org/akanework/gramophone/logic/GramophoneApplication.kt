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

import android.app.Application
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.media.ThumbnailUtils
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.NullRequestDataException
import coil3.size.pxOrElse
import coil3.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import org.akanework.gramophone.BuildConfig
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.BugHandlerActivity
import org.akanework.gramophone.ui.LyricWidgetProvider
import org.lsposed.hiddenapibypass.LSPass
import uk.akane.libphonograph.Constants
import uk.akane.libphonograph.reader.FlowReader
import uk.akane.libphonograph.utils.MiscUtils
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

class GramophoneApplication : Application(), SingletonImageLoader.Factory,
    Thread.UncaughtExceptionHandler, SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "GramophoneApplication"
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		    LSPass.setHiddenApiExemptions("")
	    }
    }

    val minSongLengthSecondsFlow = MutableSharedFlow<Long>(replay = 1)
    val blackListSetFlow = MutableSharedFlow<Set<String>>(replay = 1)
    val shouldUseEnhancedCoverReadingFlow = if (hasScopedStorageWithMediaTypes()) null else
        MutableSharedFlow<Boolean?>(replay = 1)
    val recentlyAddedFilterSecondFlow = MutableStateFlow(1_209_600L)
    lateinit var reader: FlowReader
        private set

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Use StrictMode to find anti-pattern issues
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectAll()
                    .permitDiskReads() // permit disk reads due to media3 setMetadata() TODO extra player thread
                    .penaltyLog()
                    .penaltyDialog()
                    .build()
            )
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog().penaltyDeath().build()
            )
        }
        reader = FlowReader(this,
            if (BuildConfig.DISABLE_MEDIA_STORE_FILTER) MutableStateFlow(0) else
                minSongLengthSecondsFlow,
            blackListSetFlow,
            if (hasScopedStorageWithMediaTypes()) MutableStateFlow(null) else
                shouldUseEnhancedCoverReadingFlow!!,
            recentlyAddedFilterSecondFlow,
            MutableStateFlow(true), "gramophoneAlbumCover")
        // This is a separate thread to avoid disk read on main thread and improve startup time
        CoroutineScope(Dispatchers.Default).launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@GramophoneApplication)
            // Set application theme when launching.
            when (prefs.getString("theme_mode", "0")) {
                "0" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }

                "1" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }

                "2" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
            }

            onSharedPreferenceChanged(prefs, null) // reload all values
            prefs.registerOnSharedPreferenceChangeListener(this@GramophoneApplication)

            // https://github.com/androidx/media/issues/805
            if (needsMissingOnDestroyCallWorkarounds()) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            }

            LyricWidgetProvider.update(this@GramophoneApplication)
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        runBlocking {
            if (key == null || key == "mediastore_filter") {
                minSongLengthSecondsFlow.emit(prefs.getInt("mediastore_filter",
                        resources.getInteger(R.integer.filter_default_sec)).toLong())
            }
            if (key == null || key == "folderFilter") {
                blackListSetFlow.emit(prefs.getStringSet("folderFilter", setOf()) ?: setOf())
            }
            if ((key == null || key == "album_covers") && !hasScopedStorageWithMediaTypes()) {
                shouldUseEnhancedCoverReadingFlow!!.emit(prefs.getBoolean("album_covers", false))
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .diskCache(null)
            .components {
                if (hasScopedStorageV1()) {
                    add(Fetcher.Factory { data, options, _ ->
                        if (data !is Pair<*, *>) return@Factory null
                        val size = data.second
                        if (size !is Size?) return@Factory null
                        val file = data.first as? File ?: return@Factory null
                        return@Factory Fetcher {
                            ImageFetchResult(
                                ThumbnailUtils.createAudioThumbnail(file, options.size.let {
                                    // TODO 10000 is a bad fallback
                                    Size(it.width.pxOrElse { size?.width.let { if (it == 0) null else it } ?: 10000 },
                                        it.height.pxOrElse { size?.height.let { if (it == 0) null else it } ?: 10000 })
                                }, null).asImage(), true, DataSource.DISK
                            )
                        }
                    })
                }
                add(Fetcher.Factory { data, options, _ ->
                    if (data !is Uri) return@Factory null
                    if (data.scheme != "gramophoneAlbumCover") return@Factory null
                    return@Factory Fetcher {
                        val cover = MiscUtils.findBestCover(File(data.path!!))
                        if (cover == null) {
                            val uri = ContentUris.withAppendedId(Constants.baseAlbumCoverUri, data.authority!!.toLong())
                            val contentResolver = options.context.contentResolver
                            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                            checkNotNull(afd) { "Unable to open '$uri'." }
                            return@Fetcher SourceFetchResult(
                                source = ImageSource(
                                    source = afd.createInputStream().source().buffer(),
                                    fileSystem = options.fileSystem,
                                    metadata = ContentMetadata(data, afd),
                                ),
                                mimeType = contentResolver.getType(uri),
                                dataSource = DataSource.DISK,
                            )
                        }
                        return@Fetcher SourceFetchResult(
                            ImageSource(cover.toOkioPath(), options.fileSystem, null, null, null),
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(cover.extension),
                            DataSource.DISK
                        )
                    }
                })
            }
            .run {
                if (!BuildConfig.DEBUG) this else
                    logger(object : Logger {
                        override var minLevel = Logger.Level.Verbose
                        override fun log(
                            tag: String,
                            level: Logger.Level,
                            message: String?,
                            throwable: Throwable?
                        ) {
                            if (level < minLevel) return
                            val priority = level.ordinal + 2 // obviously the best way to do it
                            if (message != null) {
                                Log.println(priority, tag, message)
                            }
                            // Let's keep the log readable and ignore normal events' stack traces.
                            if (throwable != null && throwable !is NullRequestDataException
                                && (throwable !is IOException
                                        || throwable.message != "No album art found")
                            ) {
                                Log.println(priority, tag, Log.getStackTraceString(throwable))
                            }
                        }
                    })
            }
            .build()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // TODO convert to notification that opens BugHandlerActivity on click, and let JVM
        //  go through the normal exception process.
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = Thread.currentThread().name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")
        val intent = Intent(this, BugHandlerActivity::class.java)
        intent.putExtra("exception_message", exceptionMessage)
        intent.putExtra("thread", threadName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        exitProcess(10)
    }
}
