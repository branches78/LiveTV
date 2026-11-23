/*
 * Copyright (C) 2026 The LiveTV Authors
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

package com.livetv.tv.iptv;

import android.content.Context;
import android.media.PlaybackParams;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import com.livetv.tv.audio.LiveRenderersFactory;
import com.livetv.tv.framerate.FrameRateMatcher;
import com.livetv.tv.timeshift.LiveStorage;
import com.livetv.tv.timeshift.TimeshiftBuffer;
import com.livetv.tv.timeshift.TimeshiftDataSource;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV input that plays the channels imported from an M3U list by {@link IptvSetupActivity}.
 *
 * <p>LiveTV time shifting: with a USB drive, hard disk or memory card plugged in, the channel is
 * kept on it while it plays ({@link TimeshiftBuffer}, the last hour) and played back from there,
 * so it can be paused, rewound and fast forwarded with the play controls of the menu. Without
 * one, or for HLS and DASH streams, the channel plays from the network with no time shifting.
 */
public class IptvTvInputService extends TvInputService {
    private static final String TAG = "IptvTvInputService";
    /** Folder of the time shift buffers on the drive, one sub-folder per playing channel. */
    private static final String TIMESHIFT_FOLDER = "timeshift";
    /** Rewinding and fast forwarding jump this often, by the speed times this long. */
    private static final long TRICK_PLAY_INTERVAL_MS = 1000;
    /** Closer than this to the present, a position is "live". */
    private static final long LIVE_MARGIN_MS = 3000;
    /** The buffer folders in use, so leftovers (e.g. after a crash) can be deleted. */
    private static final Set<File> sActiveBufferFolders = new HashSet<>();
    /** Looks up the drive and makes the buffer folders, off the main thread. */
    private static final ExecutorService STORAGE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "TimeshiftStorage"));

    @Override
    public Session onCreateSession(String inputId) {
        return new IptvSession(this);
    }

    /** LiveTV: records a channel to a removable drive (only offered while one is plugged in). */
    @Override
    public RecordingSession onCreateRecordingSession(String inputId) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return null;
        }
        return new IptvRecordingSession(this, inputId);
    }

    private static final class IptvSession extends Session implements Player.Listener {
        private final Context mContext;
        private final FrameRateMatcher mFrameRateMatcher;
        private ExoPlayer mPlayer;
        private Surface mSurface;
        private float mVolume = 1f;
        /**
         * False while the output switches to the channel's frame rate: the video stays hidden
         * (Live TV shows its loading screen) and muted, so the switch happens before it shows.
         */
        private boolean mOutputReady = true;

        private final Handler mHandler = new Handler(Looper.getMainLooper());
        /** The stream of the channel, also to fall back to it if the drive fails. */
        private String mStreamUrl;
        /** The channel kept on the drive for time shifting, or null without time shifting. */
        private TimeshiftBuffer mBuffer;
        private File mBufferFolder;
        /** Where the player's source starts in the buffer, and the time of that offset (0: ask). */
        private long mSourceStartOffset;
        private long mSourceStartTimeMs;
        /** Rewind (negative) or fast forward speed, or 0 while playing normally. */
        private float mTrickSpeed;
        /** The position rewinding or fast forwarding has got to, in ms since the epoch. */
        private long mTrickPositionMs;
        /** Whether playback continues after rewinding or fast forwarding (else paused). */
        private boolean mPlayAfterTrick = true;
        private final Runnable mTrickPlayRunnable = this::trickPlayStep;
        /**
         * Whether a recording is played (from its file) instead of a channel. Its positions are
         * then counted from its start (0), not as times of the day.
         */
        private boolean mPlayingRecording;
        /** Counts the tunes, so a channel whose buffer folder arrives late is not started. */
        private int mTuneCount;

        IptvSession(Context context) {
            super(context);
            mContext = context;
            mFrameRateMatcher = new FrameRateMatcher(context);
        }

        @Override
        public boolean onTune(Uri channelUri) {
            mTuneCount++;
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            // Rewinding or fast forwarding the previous channel must not go on with this one.
            stopTrickPlay();
            releasePlayer();
            releaseBuffer();
            String streamUrl = IptvChannels.getStreamUrl(mContext, channelUri);
            mStreamUrl = streamUrl;
            String lowerCaseUrl = streamUrl == null ? "" : streamUrl.toLowerCase(Locale.ROOT);
            // Only web streams: an older import may have kept other addresses.
            if (!lowerCaseUrl.startsWith("http://") && !lowerCaseUrl.startsWith("https://")) {
                Log.w(TAG, "No web stream URL for " + channelUri);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            mPlayingRecording = false;
            boolean adaptive = lowerCaseUrl.contains(".m3u8") || lowerCaseUrl.contains(".mpd");
            if (adaptive) {
                // Time shifting keeps the stream on a drive: one stream of bytes, not HLS or DASH.
                return startChannel(channelUri, null);
            }
            // LiveTV: the drive is looked up, and the buffer folder made, off the main thread: a
            // busy drive can take seconds, and Android closes the app when a tune takes over 2.
            int tune = mTuneCount;
            STORAGE_EXECUTOR.execute(
                    () -> {
                        File folder = createBufferFolder(mContext);
                        mHandler.post(
                                () -> {
                                    if (tune != mTuneCount) {
                                        // Tuned again, or released, meanwhile.
                                        forgetBufferFolder(folder);
                                        return;
                                    }
                                    startChannel(channelUri, folder);
                                });
                    });
            return true;
        }

        /**
         * Plays the channel of {@link #mStreamUrl}, kept in {@code bufferFolder} for time shifting
         * or, when it is null, straight from the network.
         */
        private boolean startChannel(Uri channelUri, File bufferFolder) {
            createPlayer(channelUri.toString());
            try {
                if (bufferFolder != null) {
                    startBuffer(bufferFolder);
                    playFromBuffer(mBuffer.getStartOffset(), 0);
                } else {
                    playFromNetwork();
                }
                mPlayer.setPlayWhenReady(true);
            } catch (RuntimeException e) {
                // A stream the player cannot handle (e.g. an unsupported format) must not crash the
                // app, which re-tunes the last channel on every launch.
                // Not logged with its message: it can hold the stream URL, with a password.
                Log.w(TAG, "Cannot play " + channelUri + ": " + e.getClass().getSimpleName());
                releasePlayer();
                releaseBuffer();
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            notifyContentAllowed();
            notifyTimeShiftStatusChanged(
                    mBuffer != null
                            ? TvInputManager.TIME_SHIFT_STATUS_AVAILABLE
                            : TvInputManager.TIME_SHIFT_STATUS_UNSUPPORTED);
            return true;
        }

        /**
         * Creates the player, muted and hidden until the output has the frame rate of the stream.
         *
         * @param streamKey identifies the stream, to remember its frame rate.
         */
        private void createPlayer(String streamKey) {
            DefaultHttpDataSource.Factory httpFactory =
                    new DefaultHttpDataSource.Factory()
                            .setUserAgent(IptvChannels.USER_AGENT)
                            .setAllowCrossProtocolRedirects(true);
            mPlayer =
                    new ExoPlayer.Builder(mContext, new LiveRenderersFactory(mContext))
                            .setMediaSourceFactory(
                                    new DefaultMediaSourceFactory(
                                            new DefaultDataSource.Factory(mContext, httpFactory)))
                            .build();
            mPlayer.addListener(this);
            mFrameRateMatcher.attach(mPlayer);
            mPlayer.setVideoSurface(mSurface);
            mOutputReady = false;
            mPlayer.setVolume(0);
            mFrameRateMatcher.startStream(streamKey, this::onOutputReady);
        }

        /**
         * LiveTV: plays a recording of the IPTV lists ({@link IptvRecordingSession}) from its
         * file on the drive, with pause, rewind and fast forward.
         */
        @Override
        public void onTimeShiftPlay(Uri recordedProgramUri) {
            // A channel still waiting for its buffer folder must not start over the recording.
            mTuneCount++;
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            stopTrickPlay();
            releasePlayer();
            releaseBuffer();
            String dataUri = null;
            try (android.database.Cursor cursor =
                    mContext.getContentResolver()
                            .query(
                                    recordedProgramUri,
                                    new String[] {
                                        android.media.tv.TvContract.RecordedPrograms
                                                .COLUMN_RECORDING_DATA_URI
                                    },
                                    null,
                                    null,
                                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    dataUri = cursor.getString(0);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read the recording", e);
            }
            if (dataUri == null || !new File(Uri.parse(dataUri).getPath()).exists()) {
                // E.g. the drive with the recording is not plugged in.
                Log.w(TAG, "The file of the recording is missing");
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return;
            }
            mPlayingRecording = true;
            createPlayer(recordedProgramUri.toString());
            try {
                mPlayer.setMediaItem(MediaItem.fromUri(dataUri));
                mPlayer.prepare();
                mPlayer.setPlayWhenReady(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot play the recording", e);
                releasePlayer();
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return;
            }
            notifyContentAllowed();
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE);
        }

        /** Plays the channel straight from the network, without time shifting. */
        private void playFromNetwork() {
            mPlayer.setMediaItem(IptvChannels.buildMediaItem(mStreamUrl));
            mPlayer.prepare();
        }

        /**
         * Makes an empty folder for a time shift buffer on a removable drive, or returns null when
         * there is none. Runs on {@link #STORAGE_EXECUTOR}: the drive can be slow.
         */
        private static File createBufferFolder(Context context) {
            File root = LiveStorage.getDirectory(context, TIMESHIFT_FOLDER);
            if (root == null) {
                return null;
            }
            deleteUnusedBufferFolders(root);
            File folder = new File(root, "channel" + SystemClock.elapsedRealtimeNanos());
            synchronized (sActiveBufferFolders) {
                sActiveBufferFolders.add(folder);
            }
            if (!folder.mkdirs()) {
                forgetBufferFolder(folder);
                return null;
            }
            return folder;
        }

        /** A buffer folder no longer used: deleted with the leftovers of the next channel. */
        private static void forgetBufferFolder(File folder) {
            if (folder != null) {
                synchronized (sActiveBufferFolders) {
                    sActiveBufferFolders.remove(folder);
                }
            }
        }

        /** Starts keeping the channel in {@code folder} on a removable drive. */
        private void startBuffer(File folder) {
            mBufferFolder = folder;
            mBuffer =
                    new TimeshiftBuffer(
                            folder, mStreamUrl, () -> mHandler.post(this::onBufferFailed));
            mBuffer.start();
        }

        /** Buffers left by an earlier run (e.g. a crash) are deleted. */
        private static void deleteUnusedBufferFolders(File root) {
            File[] folders = root.listFiles();
            if (folders == null) {
                return;
            }
            synchronized (sActiveBufferFolders) {
                for (File folder : folders) {
                    if (sActiveBufferFolders.contains(folder)) {
                        continue;
                    }
                    File[] files = folder.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            file.delete();
                        }
                    }
                    folder.delete();
                }
            }
        }

        /**
         * Plays the buffer from {@code offset}, the position at {@code timeMs} (0 when not known
         * yet: the time the offset arrives).
         */
        private void playFromBuffer(long offset, long timeMs) {
            mSourceStartOffset = offset;
            mSourceStartTimeMs = timeMs;
            mPlayer.setMediaSource(
                    new ProgressiveMediaSource.Factory(
                                    TimeshiftDataSource.factory(mBuffer, offset),
                                    new DefaultExtractorsFactory())
                            .createMediaSource(MediaItem.fromUri(Uri.parse("timeshift://live"))));
            mPlayer.prepare();
        }

        /** The drive failed (e.g. unplugged): the channel continues from the network. */
        private void onBufferFailed() {
            if (mBuffer == null || mPlayer == null) {
                return;
            }
            Log.w(TAG, "Time shifting stopped: the drive cannot be written");
            stopTrickPlay();
            releaseBuffer();
            playFromNetwork();
            mPlayer.setPlayWhenReady(true);
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_UNAVAILABLE);
        }

        @Override
        public void onTimeShiftPause() {
            if (mPlayer == null) {
                return;
            }
            if (mTrickSpeed != 0) {
                // Paused where rewinding or fast forwarding got to.
                stopTrickPlay();
                seekBufferTo(mTrickPositionMs);
            }
            mPlayer.setPlayWhenReady(false);
        }

        @Override
        public void onTimeShiftResume() {
            if (mPlayer == null) {
                return;
            }
            if (mTrickSpeed != 0) {
                stopTrickPlay();
                seekBufferTo(mTrickPositionMs);
            }
            mPlayer.setPlayWhenReady(true);
        }

        @Override
        public void onTimeShiftSeekTo(long timeMs) {
            if (mPlayer == null || (mBuffer == null && !mPlayingRecording)) {
                return;
            }
            boolean trick = mTrickSpeed != 0;
            stopTrickPlay();
            seekBufferTo(timeMs);
            if (trick) {
                mPlayer.setPlayWhenReady(mPlayAfterTrick);
            }
        }

        @Override
        public void onTimeShiftSetPlaybackParams(PlaybackParams params) {
            if (mPlayer == null || (mBuffer == null && !mPlayingRecording)) {
                return;
            }
            float speed = params.getSpeed();
            if (speed == 1f) {
                if (mTrickSpeed != 0) {
                    stopTrickPlay();
                    seekBufferTo(mTrickPositionMs);
                }
                mPlayer.setPlayWhenReady(true);
                return;
            }
            // Rewind and fast forward show a picture every second, further each time.
            if (mTrickSpeed == 0) {
                mTrickPositionMs = getCurrentTimeMs();
            }
            mTrickSpeed = speed;
            mPlayAfterTrick = true;
            mPlayer.setPlayWhenReady(false);
            mHandler.removeCallbacks(mTrickPlayRunnable);
            mHandler.postDelayed(mTrickPlayRunnable, TRICK_PLAY_INTERVAL_MS);
        }

        private void trickPlayStep() {
            if (mTrickSpeed == 0 || mPlayer == null || (mBuffer == null && !mPlayingRecording)) {
                return;
            }
            long target = mTrickPositionMs + (long) (mTrickSpeed * TRICK_PLAY_INTERVAL_MS);
            if (mPlayingRecording) {
                trickPlayRecordingStep(target);
                return;
            }
            long start = mBuffer.getStartTimeMs();
            long now = System.currentTimeMillis();
            if (target <= start) {
                // Back at the oldest part kept: play from there.
                stopTrickPlay();
                seekBufferTo(start);
                mPlayer.setPlayWhenReady(true);
                return;
            }
            if (target >= now - LIVE_MARGIN_MS) {
                // Caught up with the present: play live.
                stopTrickPlay();
                seekBufferTo(now);
                mPlayer.setPlayWhenReady(true);
                return;
            }
            mTrickPositionMs = target;
            seekBufferTo(target);
            mHandler.postDelayed(mTrickPlayRunnable, TRICK_PLAY_INTERVAL_MS);
        }

        /** Rewinding or fast forwarding a recording: within its length. */
        private void trickPlayRecordingStep(long target) {
            long duration = mPlayer.getDuration();
            if (target <= 0) {
                stopTrickPlay();
                mPlayer.seekTo(0);
                mPlayer.setPlayWhenReady(true);
                return;
            }
            if (duration > 0 && target >= duration) {
                // At the end: shown paused on the last pictures.
                stopTrickPlay();
                mPlayer.seekTo(Math.max(0, duration - 1000));
                return;
            }
            mTrickPositionMs = target;
            mPlayer.seekTo(target);
            mHandler.postDelayed(mTrickPlayRunnable, TRICK_PLAY_INTERVAL_MS);
        }

        private void stopTrickPlay() {
            mTrickSpeed = 0;
            mHandler.removeCallbacks(mTrickPlayRunnable);
        }

        /** Plays the buffer from the position at {@code timeMs}, or live when it is the present. */
        private void seekBufferTo(long timeMs) {
            if (mPlayingRecording) {
                // A recording: the position from its start.
                mPlayer.seekTo(Math.max(0, timeMs));
                return;
            }
            long[] position = mBuffer.findPosition(timeMs);
            playFromBuffer(position[0], position[1]);
        }

        /** The time being shown, in ms since the epoch. */
        private long getCurrentTimeMs() {
            if (mTrickSpeed != 0) {
                return mTrickPositionMs;
            }
            if (mPlayingRecording) {
                return Math.max(0, mPlayer.getCurrentPosition());
            }
            if (mSourceStartTimeMs == 0) {
                mSourceStartTimeMs = mBuffer.getTimeAt(mSourceStartOffset);
            }
            long time = mSourceStartTimeMs + Math.max(0, mPlayer.getCurrentPosition());
            long start = mBuffer.getStartTimeMs();
            return Math.max(start, Math.min(time, System.currentTimeMillis()));
        }

        @Override
        public long onTimeShiftGetStartPosition() {
            if (mPlayingRecording) {
                return 0;
            }
            if (mBuffer == null || mBuffer.getStartTimeMs() == 0) {
                return TvInputManager.TIME_SHIFT_INVALID_TIME;
            }
            return mBuffer.getStartTimeMs();
        }

        @Override
        public long onTimeShiftGetCurrentPosition() {
            if (mPlayingRecording && mPlayer != null) {
                return getCurrentTimeMs();
            }
            if (mBuffer == null || mPlayer == null || mBuffer.getStartTimeMs() == 0) {
                return TvInputManager.TIME_SHIFT_INVALID_TIME;
            }
            long current = getCurrentTimeMs();
            if (mTrickSpeed == 0 && current < mBuffer.getStartTimeMs() + 1000
                    && !mPlayer.getPlayWhenReady()) {
                // Paused for longer than the buffer: the oldest part kept is shown next.
                return mBuffer.getStartTimeMs();
            }
            return current;
        }

        /** The output frame rate is settled (or did not need to change): show the video. */
        private void onOutputReady() {
            mOutputReady = true;
            if (mPlayer != null) {
                mPlayer.setVolume(mVolume);
                if (mPlayer.getPlaybackState() == Player.STATE_READY) {
                    notifyVideoAvailable();
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
                if (mOutputReady) {
                    notifyVideoAvailable();
                }
            } else if (playbackState == Player.STATE_BUFFERING) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "Playback failed", error);
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            mSurface = surface;
            mFrameRateMatcher.setSurface(surface);
            if (mPlayer != null) {
                mPlayer.setVideoSurface(surface);
            }
            return true;
        }

        @Override
        public void onSetStreamVolume(float volume) {
            mVolume = volume;
            if (mPlayer != null && mOutputReady) {
                mPlayer.setVolume(volume);
            }
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {}

        @Override
        public void onRelease() {
            // A channel still waiting for its buffer folder must not start any more.
            mTuneCount++;
            stopTrickPlay();
            releasePlayer();
            releaseBuffer();
            mFrameRateMatcher.reset();
            mFrameRateMatcher.setSurface(null);
        }

        private void releaseBuffer() {
            // The folder stays "in use" until its files are deleted, so the next channel's
            // cleanup of leftovers does not delete them at the same time.
            File folder = mBufferFolder;
            mBufferFolder = null;
            if (mBuffer != null) {
                mBuffer.release(() -> forgetBufferFolder(folder));
                mBuffer = null;
            } else {
                forgetBufferFolder(folder);
            }
        }

        private void releasePlayer() {
            if (mPlayer != null) {
                mPlayer.removeListener(this);
                mPlayer.release();
                mPlayer = null;
            }
        }
    }
}
