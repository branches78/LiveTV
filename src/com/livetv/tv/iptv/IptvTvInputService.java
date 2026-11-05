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
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.livetv.tv.framerate.FrameRateMatcher;
import java.util.Locale;

/** TV input that plays the channels imported from an M3U list by {@link IptvSetupActivity}. */
public class IptvTvInputService extends TvInputService {
    private static final String TAG = "IptvTvInputService";

    @Override
    public Session onCreateSession(String inputId) {
        return new IptvSession(this);
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

        IptvSession(Context context) {
            super(context);
            mContext = context;
            mFrameRateMatcher = new FrameRateMatcher(context);
        }

        @Override
        public boolean onTune(Uri channelUri) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            releasePlayer();
            String streamUrl = IptvChannels.getStreamUrl(mContext, channelUri);
            String lowerCaseUrl = streamUrl == null ? "" : streamUrl.toLowerCase(Locale.ROOT);
            // Only web streams: an older import may have kept other addresses.
            if (!lowerCaseUrl.startsWith("http://") && !lowerCaseUrl.startsWith("https://")) {
                Log.w(TAG, "No web stream URL for " + channelUri);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            DefaultHttpDataSource.Factory httpFactory =
                    new DefaultHttpDataSource.Factory()
                            .setUserAgent(IptvChannels.USER_AGENT)
                            .setAllowCrossProtocolRedirects(true);
            mPlayer =
                    new ExoPlayer.Builder(mContext)
                            .setMediaSourceFactory(
                                    new DefaultMediaSourceFactory(
                                            new DefaultDataSource.Factory(mContext, httpFactory)))
                            .build();
            mPlayer.addListener(this);
            mFrameRateMatcher.attach(mPlayer);
            mPlayer.setVideoSurface(mSurface);
            mOutputReady = false;
            mPlayer.setVolume(0);
            mFrameRateMatcher.startStream(channelUri.toString(), this::onOutputReady);
            MediaItem.Builder item = new MediaItem.Builder().setUri(streamUrl);
            if (lowerCaseUrl.contains(".m3u8")) {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            } else if (lowerCaseUrl.contains(".mpd")) {
                item.setMimeType(MimeTypes.APPLICATION_MPD);
            }
            try {
                mPlayer.setMediaItem(item.build());
                mPlayer.setPlayWhenReady(true);
                mPlayer.prepare();
            } catch (RuntimeException e) {
                // A stream the player cannot handle (e.g. an unsupported format) must not crash the
                // app, which re-tunes the last channel on every launch.
                // The stream URL is not logged: it can hold a password.
                Log.w(TAG, "Cannot play " + channelUri, e);
                releasePlayer();
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            notifyContentAllowed();
            return true;
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
            releasePlayer();
            mFrameRateMatcher.reset();
            mFrameRateMatcher.setSurface(null);
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
