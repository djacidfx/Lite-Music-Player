/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.akanework.gramophone.logic.utils.exoplayer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.constrainValue;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.MAX_PITCH;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.MAX_PLAYBACK_SPEED;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.MIN_PITCH;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.MIN_PLAYBACK_SPEED;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.media.AudioDeviceInfo;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.media3.extractor.ExtractorUtil;

import org.nift4.gramophone.hificore.NativeTrack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/** A default implementation of {@link AudioOutput} that wraps an {@link NativeTrack}. */
public final class NativeTrackAudioOutput implements AudioOutput {

  /** Listener for potential capability change events. */
  @UnstableApi
  public interface CapabilityChangeListener {

    /** The audio device routing changed. */
    void onRoutedDeviceChanged(AudioDeviceInfo routedDevice);

    /** A recoverable write error occurred. */
    void onRecoverableWriteError();
  }

  private static final String TAG = "NativeTrackAudioOutput";

  private static final int ERROR_NATIVE_DEAD_OBJECT = -32;

  /** The time it takes to ramp NativeTrack's volume up or down when pausing or starting to play. */
  private static final int AUDIO_TRACK_VOLUME_RAMP_TIME_MS = 20;

  private static final Object releaseExecutorLock = new Object();

  @SuppressWarnings("NonFinalStaticField") // Intentional statically shared mutable state
  @GuardedBy("releaseExecutorLock")
  @Nullable
  private static ScheduledExecutorService releaseExecutor;

  @GuardedBy("releaseExecutorLock")
  private static int pendingReleaseCount;

  private final NativeTrack nativeTrack;
  private final OutputConfig config;
  private final float maxPlaybackSpeed;
  @Nullable private final CapabilityChangeListener capabilityChangeListener;
  @Nullable private OnRoutingChangedListener onRoutingChangedListener;
  private final NativeTrackPositionTracker nativeTrackPositionTracker;
  private final boolean isOutputPcm;
  private final int pcmFrameSize;
  @Nullable private final StreamEventCallback offloadStreamEventCallback;
  private final ListenerSet<Listener> listeners;

  private boolean hasBeenStopped;
  private long writtenPcmBytes;
  private long writtenEncodedFrames;
  private long lastTunnelingAvSyncPresentationTimeUs;
  @Nullable private ByteBuffer avSyncHeader;
  private int bytesUntilNextAvSync;
  private int framesPerEncodedSample;
  private int lastUnderrunCount;
  private boolean hasData;

  /**
   * @deprecated Use {@link
   *     #NativeTrackAudioOutput(NativeTrack,OutputConfig,CapabilityChangeListener,float,Clock)}
   *     instead.
   */
  @UnstableApi
  @Deprecated
  public NativeTrackAudioOutput(
      NativeTrack nativeTrack,
      OutputConfig config,
      @Nullable CapabilityChangeListener capabilityChangeListener,
      Clock clock) {
    this(nativeTrack, config, capabilityChangeListener, MAX_PLAYBACK_SPEED, clock);
  }

  /**
   * Creates a new instance.
   *
   * @param nativeTrack The audio track to wrap.
   * @param config The output configuration.
   * @param capabilityChangeListener The {@link CapabilityChangeListener}.
   * @param maxPlaybackSpeed The maximum playback speed set on the track if {@link
   *     OutputConfig#usePlaybackParameters} is enabled.
   * @param clock The {@link Clock}.
   */
  @UnstableApi
  @SuppressWarnings("WrongConstant") // For config encoding to pcm encoding.
  public NativeTrackAudioOutput(
      NativeTrack nativeTrack,
      OutputConfig config,
      @Nullable CapabilityChangeListener capabilityChangeListener,
      float maxPlaybackSpeed,
      Clock clock) {
    this.nativeTrack = nativeTrack;
    this.config = config;
    this.maxPlaybackSpeed = maxPlaybackSpeed;
    this.capabilityChangeListener = capabilityChangeListener;
    listeners = new ListenerSet<>(Thread.currentThread());

    isOutputPcm = Util.isEncodingLinearPcm(config.encoding);
    if (isOutputPcm) {
      int channelCount = Integer.bitCount(config.channelMask);
      pcmFrameSize = Util.getPcmFrameSize(config.encoding, channelCount);
    } else {
      pcmFrameSize = C.LENGTH_UNSET;
    }

    nativeTrackPositionTracker =
        new NativeTrackPositionTracker(
            new PositionTrackerListener(),
            clock,
            nativeTrack,
            config.encoding,
            pcmFrameSize,
            config.bufferSize);

    if (capabilityChangeListener != null) {
      onRoutingChangedListener =
          new OnRoutingChangedListener(nativeTrack, capabilityChangeListener);
    }
    offloadStreamEventCallback = isOffloadedPlayback() ? new StreamEventCallback() : null;
  }

  /** Returns the {@link NativeTrack} instance used for audio output. */
  public NativeTrack getNativeTrack() {
    return nativeTrack;
  }

  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public boolean isOffloadedPlayback() {
    return false; //nativeTrack.isOffloadedPlayback(); TODO
  }

  @Override
  public int getAudioSessionId() {
    return nativeTrack.getAudioSessionId();
  }

  @Override
  public int getSampleRate() {
    return nativeTrack.getOriginalSampleRate();
  }

  @Override
  public long getBufferSizeInFrames() {
    return nativeTrack.getBufferSizeInFrames();
  }

  @Override
  public long getPositionUs() {
    return nativeTrackPositionTracker.getCurrentPositionUs(getWrittenFrames());
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    PlaybackParams playbackParams = nativeTrack.getPlaybackParams();
    return new PlaybackParameters(playbackParams.getSpeed(), playbackParams.getPitch());
  }

  @Override
  public void play() {
    nativeTrackPositionTracker.start();
    if (!hasBeenStopped || isOffloadedPlayback()) {
      nativeTrack.play();
    }
  }

  @Override
  public void pause() {
    nativeTrackPositionTracker.pause();
    if (!hasBeenStopped || isOffloadedPlayback()) {
      nativeTrack.pause();
    }
  }

  @Override
  public boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
      throws WriteException {
    if (!isOutputPcm && framesPerEncodedSample == 0) {
      // If this is the first encoded sample, calculate the sample size in frames.
      framesPerEncodedSample = ExtractorUtil.getFramesPerEncodedSample(config.encoding, buffer);
    }
    maybeReportUnderrun();
    int bytesRemaining = buffer.remaining();
    int bytesWrittenOrError;
    if (config.isTunneling) {
      if (presentationTimeUs == C.TIME_END_OF_SOURCE) {
        // Audio processors during tunneling are required to produce buffers immediately when
        // queuing, so we can assume the timestamp during draining at the end of the stream is the
        // same as the timestamp of the last sample we processed.
        presentationTimeUs = lastTunnelingAvSyncPresentationTimeUs;
      } else {
        lastTunnelingAvSyncPresentationTimeUs = presentationTimeUs;
      }
      bytesWrittenOrError = writeWithAvSync(nativeTrack, buffer, presentationTimeUs);
    } else {
      bytesWrittenOrError =
          nativeTrack.write(buffer, null, buffer.remaining(), false);
    }

    if (bytesWrittenOrError < 0) {
      int error = bytesWrittenOrError;
      boolean isRecoverable = isNativeTrackDeadObject(error);
      if (isRecoverable && capabilityChangeListener != null) {
        capabilityChangeListener.onRecoverableWriteError();
      }
      throw new WriteException(error, isRecoverable);
    }
    int bytesWritten = bytesWrittenOrError;
    boolean fullyHandled = bytesWritten == bytesRemaining;

    if (isOutputPcm) {
      writtenPcmBytes += bytesWritten;
    } else if (fullyHandled) {
      // For non-PCM we can only be sure about the number of written frames once the entire buffer
      // is submitted.
      writtenEncodedFrames += (long) framesPerEncodedSample * encodedAccessUnitCount;
    }
    return fullyHandled;
  }

  @Override
  public void flush() {
    avSyncHeader = null;
    bytesUntilNextAvSync = 0;
    writtenPcmBytes = 0;
    writtenEncodedFrames = 0;
    hasBeenStopped = false;
    framesPerEncodedSample = 0;
    nativeTrack.flush();
    nativeTrackPositionTracker.reset();
  }

  @Override
  public void stop() {
    if (hasBeenStopped) {
      return;
    }
    hasBeenStopped = true;
    nativeTrackPositionTracker.handleEndOfStream(getWrittenFrames());
    nativeTrack.stop();
    bytesUntilNextAvSync = 0;
  }

  @Override
  public void release() {
    if (nativeTrackPositionTracker.isPlaying()) {
      nativeTrack.pause();
    }
    if (SDK_INT >= 29 && isOffloadedPlayback()) {
      checkNotNull(offloadStreamEventCallback).unregister();
    }
    if (onRoutingChangedListener != null) {
      onRoutingChangedListener.release();
      onRoutingChangedListener = null;
    }
    releaseNativeTrackAsync(nativeTrack, listeners);
  }

  @Override
  public void setVolume(float volume) {
    nativeTrack.setVolume(volume);
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    PlaybackParams playbackParams =
        new PlaybackParams()
            .allowDefaults()
            .setSpeed(
                constrainValue(playbackParameters.speed, MIN_PLAYBACK_SPEED, maxPlaybackSpeed))
            .setPitch(constrainValue(playbackParameters.pitch, MIN_PITCH, MAX_PITCH))
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL);
    try {
      nativeTrack.setPlaybackParams(playbackParams);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "Failed to set playback params", e);
    }
    nativeTrackPositionTracker.setNativeTrackPlaybackSpeed(nativeTrack.getPlaybackParams().getSpeed());
  }

  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    if (SDK_INT < 29) {
      return;
    }

    //nativeTrack.setOffloadDelayPadding(delayInFrames, paddingInFrames); TODO
  }

  @Override
  public void setOffloadEndOfStream() {
    if (SDK_INT < 29) {
      return;
    }
    if (nativeTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
      // If the first track is very short (typically <1s), the offload NativeTrack might
      // not have started yet. Do not call setOffloadEndOfStream as it would throw.
      return;
    }
    //nativeTrack.setOffloadEndOfStream(); TODO
    //nativeTrackPositionTracker.expectRawPlaybackHeadReset();
  }

  @UnstableApi
  @Override
  public void setPlayerId(PlayerId playerId) {
    if (SDK_INT < 31) {
      return;
    }
    LogSessionId logSessionId = playerId.getLogSessionId();
    if (!logSessionId.equals(LogSessionId.LOG_SESSION_ID_NONE)) {
      nativeTrack.setLogSessionId(logSessionId);
    }
  }

  @Override
  public void attachAuxEffect(int effectId) {
    nativeTrack.attachAuxEffect(effectId);
  }

  @Override
  public void setAuxEffectSendLevel(float level) {
    nativeTrack.setAuxEffectSendLevel(level);
  }

  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
    nativeTrack.setPreferredDevice(preferredDevice);
  }

  @Override
  public boolean isStalled() {
    return nativeTrackPositionTracker.isStalled(getWrittenFrames());
  }

  /** Returns whether there are any pending asynchronous releases. */
  /* package */ static boolean hasPendingReleases() {
    synchronized (releaseExecutorLock) {
      return pendingReleaseCount > 0;
    }
  }

  private long getWrittenFrames() {
    return isOutputPcm ? Util.ceilDivide(writtenPcmBytes, pcmFrameSize) : writtenEncodedFrames;
  }

  private int writeWithAvSync(NativeTrack nativeTrack, ByteBuffer buffer, long presentationTimeUs) {
    int size = buffer.remaining();
    if (SDK_INT >= 26) {
      // The underlying platform NativeTrack writes AV sync headers directly.
      return nativeTrack.write(
          buffer, null, size, false, presentationTimeUs * 1000);
    }
    if (avSyncHeader == null) {
      avSyncHeader = ByteBuffer.allocate(16);
      avSyncHeader.order(ByteOrder.BIG_ENDIAN);
      avSyncHeader.putInt(0x55550001);
    }
    if (bytesUntilNextAvSync == 0) {
      avSyncHeader.putInt(4, size);
      avSyncHeader.putLong(8, presentationTimeUs * 1000);
      avSyncHeader.position(0);
      bytesUntilNextAvSync = size;
    }
    int avSyncHeaderBytesRemaining = avSyncHeader.remaining();
    if (avSyncHeaderBytesRemaining > 0) {
      int result =
          nativeTrack.write(avSyncHeader, null, avSyncHeaderBytesRemaining, false);
      if (result < 0) {
        bytesUntilNextAvSync = 0;
        return result;
      }
      if (result < avSyncHeaderBytesRemaining) {
        return 0;
      }
    }
    int result = nativeTrack.write(buffer, null, size, false);
    if (result < 0) {
      bytesUntilNextAvSync = 0;
      return result;
    }
    bytesUntilNextAvSync -= result;
    return result;
  }

  private void maybeReportUnderrun() {
    if (listeners.isRunningOnCorrectThread() && hasPendingNativeTrackUnderruns(getWrittenFrames())) {
      listeners.sendEvent(Listener::onUnderrun);
    }
  }

  private boolean hasPendingNativeTrackUnderruns(long writtenFrames) {
    int underrunCount = getAudioOutputUnderrunCount(writtenFrames);
    boolean result = underrunCount > lastUnderrunCount;

    // If the NativeTrack unexpectedly resets the underrun count, we should update it silently.
    lastUnderrunCount = underrunCount;

    return result;
  }

  private int getAudioOutputUnderrunCount(long writtenFrames) {
    if (SDK_INT >= 24) {
      return nativeTrack.getUnderrunCount();
    }
    boolean hadData = hasData;
    long currentPositionFrames = Util.durationUsToSampleCount(getPositionUs(), getSampleRate());
    hasData = writtenFrames > currentPositionFrames;
    // For API 23- NativeTrack has no underrun API so we need to infer underruns heuristically.
    boolean emitUnderrun =
        hadData && !hasData && nativeTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED;
    return emitUnderrun ? lastUnderrunCount + 1 : lastUnderrunCount;
  }

  private static void releaseNativeTrackAsync(
      NativeTrack nativeTrack, ListenerSet<Listener> listeners) {
    // NativeTrack.release can take some time, so we call it on a background thread. The background
    // thread is shared statically to avoid creating many threads when multiple players are released
    // at the same time.
    Handler nativeTrackThreadHandler = Util.createHandlerForCurrentLooper();
    synchronized (releaseExecutorLock) {
      if (releaseExecutor == null) {
        releaseExecutor =
            Util.newSingleThreadScheduledExecutor("ExoPlayer:NativeTrackReleaseThread");
      }
      pendingReleaseCount++;
      Future<?> ignored =
          releaseExecutor.schedule(
              () -> {
                try {
                  // We need to flush the audio track as some devices are known to keep state from
                  // previous playbacks if the track is not flushed at all (see b/22967293).
                  nativeTrack.flush();
                  nativeTrack.release();
                } finally {
                  if (nativeTrackThreadHandler.getLooper().getThread().isAlive()) {
                    nativeTrackThreadHandler.post(
                        () -> {
                          if (listeners.isRunningOnCorrectThread()) {
                            listeners.sendEvent(Listener::onReleased);
                          }
                        });
                  }
                  synchronized (releaseExecutorLock) {
                    pendingReleaseCount--;
                    if (pendingReleaseCount == 0) {
                      checkNotNull(releaseExecutor).shutdown();
                      releaseExecutor = null;
                    }
                  }
                }
              },
              // We need to schedule the flush and release with a delay to ensure the audio system
              // can completely ramp down the audio output after the preceding pause.
              AUDIO_TRACK_VOLUME_RAMP_TIME_MS,
              MILLISECONDS);
    }
  }

  private static boolean isNativeTrackDeadObject(int status) {
    return status == ERROR_NATIVE_DEAD_OBJECT;
  }

  private final class PositionTrackerListener implements NativeTrackPositionTracker.Listener {

    @Override
    public void onPositionFramesMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (frame position mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getWrittenFrames();

      if (!MediaLibraryInfo.enableWorkarounds()) {
        throw new InvalidNativeTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onSystemTimeUsMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (system clock mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getWrittenFrames();

      if (!MediaLibraryInfo.enableWorkarounds()) {
        throw new InvalidNativeTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onInvalidLatency(long latencyUs) {
      Log.w(TAG, "Ignoring impossibly large audio latency: " + latencyUs);
    }

    @Override
    public void onPositionAdvancing(long playoutStartSystemTimeMs) {
      if (listeners.isRunningOnCorrectThread()) {
        listeners.sendEvent(listener -> listener.onPositionAdvancing(playoutStartSystemTimeMs));
      }
    }
  }

  /**
   * Thrown when the audio track has provided a spurious timestamp, if {@link
   * MediaLibraryInfo#enableWorkarounds()} is false.
   */
  @UnstableApi
  public static final class InvalidNativeTrackTimestampException extends RuntimeException {

    /**
     * Creates a new invalid timestamp exception with the specified message.
     *
     * @param message The detail message for this exception.
     */
    private InvalidNativeTrackTimestampException(String message) {
      super(message);
    }
  }

  private static final class OnRoutingChangedListener {

    private final NativeTrack nativeTrack;
    private final CapabilityChangeListener capabilityChangeListener;
    private final Handler playbackThreadHandler;

    @Nullable private NativeTrack.OnRoutingChangedListener listener;

    private OnRoutingChangedListener(
        NativeTrack nativeTrack, CapabilityChangeListener capabilityChangeListener) {
      this.nativeTrack = nativeTrack;
      this.capabilityChangeListener = capabilityChangeListener;
      this.playbackThreadHandler = Util.createHandlerForCurrentLooper();
      this.listener = this::onRoutingChanged;
      nativeTrack.addOnRoutingChangedListener(listener, playbackThreadHandler);
    }

    private void release() {
      nativeTrack.removeOnRoutingChangedListener(checkNotNull(listener));
      listener = null;
    }

    private void onRoutingChanged(NativeTrack router) {
      if (listener == null) {
        // Stale event.
        return;
      }
      BackgroundExecutor.get()
          .execute(
              () -> {
                @Nullable AudioDeviceInfo routedDevice = router.getRoutedDevice();
                if (routedDevice != null) {
                  playbackThreadHandler.post(
                      () -> {
                        if (listener == null) {
                          // Stale event.
                          return;
                        }
                        capabilityChangeListener.onRoutedDeviceChanged(routedDevice);
                      });
                }
              });
    }
  }

  private final class StreamEventCallback {
    private final Handler handler;
    private final NativeTrack.StreamEventCallback callback;

    private StreamEventCallback() {
      handler = Util.createHandlerForCurrentLooper();
      callback =
          new NativeTrack.StreamEventCallback() {
            @Override
            public void onDataRequest(NativeTrack track, long sizeFrames, long sizeBytes) {
              listeners.sendEvent(Listener::onOffloadDataRequest);
            }

            @Override
            public void onPresentationEnded(NativeTrack track) {
              listeners.sendEvent(Listener::onOffloadPresentationEnded);
            }

            @Override
            public void onTearDown(NativeTrack track) {
              // The audio track was destroyed while in use. Thus a new NativeTrack needs to be
              // created and its buffer filled. Request this call explicitly in case ExoPlayer is
              // sleeping waiting for a data request.
              listeners.sendEvent(Listener::onOffloadDataRequest);
            }
          };
      nativeTrack.registerStreamEventCallback(callback, handler);
    }

    private void unregister() {
      nativeTrack.unregisterStreamEventCallback(callback);
      handler.removeCallbacksAndMessages(/* token= */ null);
    }
  }
}