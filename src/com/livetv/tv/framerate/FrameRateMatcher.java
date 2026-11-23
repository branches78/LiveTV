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

package com.livetv.tv.framerate;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.media.MediaFormat;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Applies {@link FrameRateSettings} to a player's output: finds the content frame rate (from the
 * stream, estimated from frame timestamps, or remembered from the last time the channel played)
 * and passes it to {@code Surface#setFrameRate} and {@link DisplayModeRequester}.
 *
 * <p>In "Automatic" mode the output mode change blanks the screen on HDMI 2.0, so the caller is
 * told through {@link Listener#onOutputReady} when it can show the video: once the display has
 * switched, when no switch is needed, or after a timeout.
 *
 * <p>A quick estimate (a few frames) cannot tell 29.97 from 30 fps apart, because stream
 * timestamps jitter more than the 0.1% between them. So rates of the same family never switch the
 * output again while a channel plays; a longer measurement saves the exact rate for the next time.
 *
 * <p>Must be used from the main thread, except for the metadata callback.
 */
public final class FrameRateMatcher implements VideoFrameMetadataListener {
    private static final String TAG = "FrameRateMatcher";

    /** Called when the video can be shown. */
    public interface Listener {
        void onOutputReady();
    }

    /** Frame intervals collected for the quick estimate. */
    private static final int SAMPLE_COUNT = 10;
    /** Frames measured (about 10 s) for the exact rate that is saved for the next time. */
    private static final int PRECISE_SAMPLE_COUNT = 300;
    /** A gap this long between frames is a discontinuity; the exact measurement restarts. */
    private static final long MAX_FRAME_GAP_US = 1_000_000;
    private static final float[] STANDARD_RATES = {
        23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f
    };
    /**
     * Relative tolerance when snapping a frame rate to a standard one. IPTV timestamps jitter
     * (e.g. 34 ms instead of 33.37 ms gives 29.41 fps for 29.97 content).
     */
    private static final float SNAP_TOLERANCE = 0.03f;
    /** Intervals further than this from the median are frame drops or jumps, not jitter. */
    private static final float INTERVAL_OUTLIER_TOLERANCE = 0.25f;
    /** 29.97 and 30 (or 59.94 and 60, 23.976 and 24) are the same family: 0.1% apart. */
    private static final float SAME_FAMILY_TOLERANCE = 0.002f;
    /** Longest wait for the frame rate after the first frame of a channel never seen before. */
    private static final long DETECTION_TIMEOUT_MS = 2000;
    /** Longest wait for the display to switch, counted once the window is visible. */
    private static final long DISPLAY_SWITCH_TIMEOUT_MS = 3000;
    /** How often to check whether the window is visible yet (e.g. while the app starts). */
    private static final long WINDOW_CHECK_INTERVAL_MS = 200;
    /** Longest time the video is held, whatever happens (e.g. a stream without video). */
    private static final long MAX_HOLD_MS = 15000;
    /** Frame rates remembered per channel, so the next tune switches before showing video. */
    private static final String PREFS_FRAME_RATES = "livetv_frame_rates";

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final DisplayManager mDisplayManager;
    private final SharedPreferences mFrameRates;
    private final Runnable mOutputReadyTimeout = this::markOutputReady;
    private final Runnable mWindowShownCheck = this::checkWindowShown;
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayChanged(int displayId) {
                    if (mPendingModeId != 0 && DisplayModeRequester.isRequestedModeActive()) {
                        markOutputReady();
                    }
                }

                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}
            };

    // Main thread only.
    private Surface mSurface;
    /** Frame rate applied to the output, or 0 when none. */
    private float mAppliedFrameRate;
    /** Frame rate of the stream being played (also when the mode is "Off"), or 0 if unknown. */
    private float mContentFrameRate;
    /** The matcher of the stream being played, to apply a mode change right away. */
    private static FrameRateMatcher sActive;
    private String mStreamKey;
    private Listener mListener;
    private boolean mOutputReady = true;
    /** Display mode being switched to, {@link #WAITING_FOR_WINDOW}, or 0. */
    private int mPendingModeId;
    /** {@link #mPendingModeId} while there is no window to ask for a mode yet. */
    private static final int WAITING_FOR_WINDOW = -1;
    /** When the output started waiting for the window to be shown. */
    private long mWindowWaitStartMs;

    /** Set on the main thread so the playback thread restarts detection for a new stream. */
    private volatile boolean mDetectionResetPending;

    // Playback thread only.
    private final long[] mIntervalsUs = new long[SAMPLE_COUNT];
    private int mIntervalCount;
    private long mLastPresentationTimeUs = C.TIME_UNSET;
    private float mDetectedFrameRate;
    private boolean mFirstFrameReported;
    private long mPreciseFirstUs = C.TIME_UNSET;
    private long mPreciseLastUs;
    private int mPreciseCount;
    private boolean mPreciseReported;

    public FrameRateMatcher(Context context) {
        mContext = context.getApplicationContext();
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mFrameRates = mContext.getSharedPreferences(PREFS_FRAME_RATES, Context.MODE_PRIVATE);
    }

    /** Starts detecting the frame rate of {@code player}, which plays a new stream. */
    public void attach(ExoPlayer player) {
        // Media3 would otherwise set its own (seamless only) frame rate on the same surface.
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        player.setVideoFrameMetadataListener(this);
    }

    /**
     * Starts matching a new stream. The current rate stays applied until the new one is known, so
     * changing channels does not switch the output twice. {@code listener} may be called before
     * this method returns.
     *
     * @param streamKey identifies the channel, to remember its frame rate; null to not remember it.
     */
    public void startStream(String streamKey, Listener listener) {
        mMainHandler.removeCallbacksAndMessages(null);
        stopWaitingForDisplay();
        sActive = this;
        mDetectionResetPending = true;
        mStreamKey = streamKey;
        mListener = listener;
        mOutputReady = false;

        // Older builds could save a rate that is not a standard one; ignore those.
        float knownFrameRate =
                streamKey == null ? 0 : snapToStandardRate(mFrameRates.getFloat(streamKey, 0));
        mContentFrameRate = knownFrameRate;
        if (FrameRateSettings.getMode(mContext) != FrameRateSettings.MODE_AUTO) {
            // Seamless switches (or none) never blank the screen: no need to hold the video.
            markOutputReady();
            if (knownFrameRate > 0) {
                apply(knownFrameRate);
            }
            return;
        }
        // Slow channels can take a while to show their first frame: the detection timeout only
        // starts then (see onFirstFrame). This only bounds the whole wait.
        mMainHandler.postDelayed(mOutputReadyTimeout, MAX_HOLD_MS);
        if (knownFrameRate > 0) {
            apply(knownFrameRate);
        }
    }

    /** Forgets the frame rates of the channels that are not in {@code channelIds} (deleted). */
    public static void retainChannels(Context context, Set<Long> channelIds) {
        SharedPreferences frameRates =
                context.getSharedPreferences(PREFS_FRAME_RATES, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (String key : frameRates.getAll().keySet()) {
            // LiveTV: only the channels' rates. The movies and series ("vod:...") and the
            // recordings are kept: they are not channels, and used to be forgotten at every start.
            Uri uri = Uri.parse(key);
            List<String> segments = uri.getPathSegments();
            if (!TvContract.AUTHORITY.equals(uri.getAuthority())
                    || segments.size() != 2
                    || !"channel".equals(segments.get(0))) {
                continue;
            }
            boolean exists;
            try {
                exists = channelIds.contains(ContentUris.parseId(uri));
            } catch (RuntimeException e) {
                exists = false;
            }
            if (!exists) {
                if (editor == null) {
                    editor = frameRates.edit();
                }
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    /** Clears the applied frame rate when playback ends. */
    public void reset() {
        mMainHandler.removeCallbacksAndMessages(null);
        stopWaitingForDisplay();
        boolean active = sActive == this;
        if (active) {
            sActive = null;
        }
        mListener = null;
        mOutputReady = true;
        mContentFrameRate = 0;
        if (active) {
            apply(0);
        } else {
            // Another player started since (e.g. Video on Demand over the live channel): the
            // display mode is its own now, only this surface's rate is cleared.
            if (mAppliedFrameRate > 0) {
                setSurfaceFrameRate(0, FrameRateSettings.MODE_AUTO);
            }
            mAppliedFrameRate = 0;
            mDetectionResetPending = true;
        }
    }

    /**
     * Applies a new {@link FrameRateSettings} mode to the channel being played right away, instead
     * of on the next tune. Main thread only.
     */
    public static void onModeChanged() {
        if (sActive != null) {
            sActive.apply(sActive.mContentFrameRate);
        }
    }

    public void setSurface(Surface surface) {
        if (surface == mSurface) {
            return;
        }
        float frameRate = mAppliedFrameRate;
        setSurfaceFrameRate(0, FrameRateSettings.MODE_AUTO);
        mSurface = surface;
        if (frameRate > 0) {
            setSurfaceFrameRate(frameRate, FrameRateSettings.getMode(mContext));
        }
    }

    @Override
    public void onVideoFrameAboutToBeRendered(
            long presentationTimeUs,
            long releaseTimeNs,
            Format format,
            MediaFormat mediaFormat) {
        if (mDetectionResetPending) {
            mDetectionResetPending = false;
            mDetectedFrameRate = 0;
            mIntervalCount = 0;
            mLastPresentationTimeUs = C.TIME_UNSET;
            mFirstFrameReported = false;
            mPreciseFirstUs = C.TIME_UNSET;
            mPreciseCount = 0;
            mPreciseReported = false;
        }
        if (!mFirstFrameReported) {
            mFirstFrameReported = true;
            mMainHandler.post(this::onFirstFrame);
        }
        measurePrecisely(presentationTimeUs);
        float frameRate =
                format.frameRate != Format.NO_VALUE ? snapToStandardRate(format.frameRate) : 0;
        if (frameRate == 0) {
            frameRate = estimate(presentationTimeUs);
        }
        if (frameRate > 0 && Math.abs(frameRate - mDetectedFrameRate) > 0.01f) {
            mDetectedFrameRate = frameRate;
            final float detected = frameRate;
            mMainHandler.post(() -> onFrameRateDetected(detected));
        }
    }

    /**
     * Estimates the frame rate from the average frame interval, leaving out frame drops and jumps
     * (intervals far from the median); returns 0 while unknown or not a standard rate.
     */
    private float estimate(long presentationTimeUs) {
        if (mDetectedFrameRate > 0) {
            return mDetectedFrameRate;
        }
        long last = mLastPresentationTimeUs;
        mLastPresentationTimeUs = presentationTimeUs;
        if (last == C.TIME_UNSET || presentationTimeUs <= last) {
            return 0;
        }
        mIntervalsUs[mIntervalCount++] = presentationTimeUs - last;
        if (mIntervalCount < SAMPLE_COUNT) {
            return 0;
        }
        mIntervalCount = 0;
        long[] sorted = Arrays.copyOf(mIntervalsUs, SAMPLE_COUNT);
        Arrays.sort(sorted);
        long median = sorted[SAMPLE_COUNT / 2];
        long sum = 0;
        int count = 0;
        for (long interval : sorted) {
            if (Math.abs(interval - median) <= median * INTERVAL_OUTLIER_TOLERANCE) {
                sum += interval;
                count++;
            }
        }
        // Not a standard rate yet: keep measuring with the next frames.
        return snapToStandardRate(1_000_000f * count / sum);
    }

    /**
     * Measures the rate over {@link #PRECISE_SAMPLE_COUNT} frames, where the timestamp jitter
     * averages out enough to tell 29.97 from 30 fps, and saves it for the channel.
     */
    private void measurePrecisely(long presentationTimeUs) {
        if (mPreciseReported) {
            return;
        }
        if (mPreciseFirstUs == C.TIME_UNSET
                || presentationTimeUs <= mPreciseLastUs
                || presentationTimeUs - mPreciseLastUs > MAX_FRAME_GAP_US) {
            mPreciseFirstUs = presentationTimeUs;
            mPreciseLastUs = presentationTimeUs;
            mPreciseCount = 0;
            return;
        }
        mPreciseLastUs = presentationTimeUs;
        if (++mPreciseCount < PRECISE_SAMPLE_COUNT) {
            return;
        }
        mPreciseReported = true;
        float frameRate =
                snapToStandardRate(1_000_000f * mPreciseCount / (mPreciseLastUs - mPreciseFirstUs));
        if (frameRate > 0) {
            mMainHandler.post(() -> saveFrameRate(frameRate));
        }
    }

    /** Returns the nearest standard frame rate within {@link #SNAP_TOLERANCE}, or 0 if none. */
    private static float snapToStandardRate(float frameRate) {
        float nearest = 0;
        float nearestDistance = Float.MAX_VALUE;
        for (float standard : STANDARD_RATES) {
            float distance = Math.abs(frameRate - standard);
            if (distance <= standard * SNAP_TOLERANCE && distance < nearestDistance) {
                nearest = standard;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean isSameFamily(float a, float b) {
        return a > 0 && b > 0 && Math.abs(a - b) <= b * SAME_FAMILY_TOLERANCE;
    }

    private void onFirstFrame() {
        if (!mOutputReady && mPendingModeId == 0) {
            mMainHandler.removeCallbacks(mOutputReadyTimeout);
            mMainHandler.postDelayed(mOutputReadyTimeout, DETECTION_TIMEOUT_MS);
        }
    }

    private void onFrameRateDetected(float frameRate) {
        if (isSameFamily(frameRate, mAppliedFrameRate)) {
            // The quick estimate cannot tell 29.97 from 30: keep the output as it is (no second
            // switch while the channel plays). The precise measurement saves the exact rate.
            if (mStreamKey != null && !mFrameRates.contains(mStreamKey)) {
                saveFrameRate(mAppliedFrameRate);
            }
            mContentFrameRate = mAppliedFrameRate;
            if (!mOutputReady) {
                apply(mAppliedFrameRate);
            }
            return;
        }
        saveFrameRate(frameRate);
        mContentFrameRate = frameRate;
        apply(frameRate);
    }

    private void saveFrameRate(float frameRate) {
        if (mStreamKey != null) {
            mFrameRates.edit().putFloat(mStreamKey, frameRate).apply();
        }
    }

    private void apply(float frameRate) {
        int mode = FrameRateSettings.getMode(mContext);
        if (mode == FrameRateSettings.MODE_OFF) {
            frameRate = 0;
        }
        if (frameRate > 0 || mAppliedFrameRate > 0) {
            setSurfaceFrameRate(frameRate, mode);
        }
        // A window mode change is never seamless, so only "Automatic" asks for one.
        int modeId =
                DisplayModeRequester.requestFrameRate(
                        mode == FrameRateSettings.MODE_AUTO ? frameRate : 0);
        mAppliedFrameRate = frameRate;
        if (!mOutputReady && frameRate > 0) {
            if (mode == FrameRateSettings.MODE_AUTO && !DisplayModeRequester.hasWindow()) {
                // Tuned before the activity registered its window (app start): the request is
                // applied when it does.
                waitForDisplay(WAITING_FOR_WINDOW);
            } else if (modeId != 0 && !DisplayModeRequester.isCurrentMode(modeId)) {
                waitForDisplay(modeId);
            } else {
                markOutputReady();
            }
        }
        if (frameRate == 0) {
            // Lets the next stream be detected from scratch.
            mDetectionResetPending = true;
        }
    }

    private void waitForDisplay(int modeId) {
        if (mPendingModeId == 0 && mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mMainHandler);
        }
        mPendingModeId = modeId;
        mWindowWaitStartMs = SystemClock.elapsedRealtime();
        mMainHandler.removeCallbacks(mWindowShownCheck);
        checkWindowShown();
    }

    /**
     * The display only switches once the window is visible, which takes a few seconds when the
     * app starts: the switch timeout is counted from then.
     */
    private void checkWindowShown() {
        if (mOutputReady || mPendingModeId == 0) {
            return;
        }
        if (DisplayModeRequester.isRequestedModeActive()) {
            markOutputReady();
            return;
        }
        if (DisplayModeRequester.isWindowShown()
                || SystemClock.elapsedRealtime() - mWindowWaitStartMs >= MAX_HOLD_MS) {
            mMainHandler.removeCallbacks(mOutputReadyTimeout);
            mMainHandler.postDelayed(mOutputReadyTimeout, DISPLAY_SWITCH_TIMEOUT_MS);
        } else {
            mMainHandler.postDelayed(mWindowShownCheck, WINDOW_CHECK_INTERVAL_MS);
        }
    }

    private void stopWaitingForDisplay() {
        if (mPendingModeId != 0 && mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mPendingModeId = 0;
        mMainHandler.removeCallbacks(mWindowShownCheck);
    }

    private void markOutputReady() {
        if (mOutputReady) {
            return;
        }
        mOutputReady = true;
        stopWaitingForDisplay();
        mMainHandler.removeCallbacks(mOutputReadyTimeout);
        if (mListener != null) {
            mListener.onOutputReady();
        }
    }

    private void setSurfaceFrameRate(float frameRate, int mode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || mSurface == null
                || !mSurface.isValid()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mSurface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        mode == FrameRateSettings.MODE_SEAMLESS_ONLY
                                ? Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                                : Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else {
                mSurface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            Log.i(TAG, "Output frame rate " + (frameRate > 0 ? frameRate + " fps" : "cleared"));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot set frame rate " + frameRate, e);
        }
    }
}
