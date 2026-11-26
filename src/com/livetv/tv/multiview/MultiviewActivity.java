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

package com.livetv.tv.multiview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.leanback.widget.VerticalGridView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.android.tv.MainActivity;
import com.android.tv.R;
import com.android.tv.TvSingletons;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.data.api.Channel;
import com.livetv.tv.audio.LiveRenderersFactory;
import com.livetv.tv.iptv.IptvChannels;
import com.livetv.tv.iptv.IptvRecording;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LiveTV: plays 2, 4 or 6 channels of the IPTV lists at once. OK on an empty tile opens the list
 * of channels in the side panel; on a tile with a channel, the channel in full screen (back there
 * returns here), and OK held, its options. Only the selected tile has sound.
 *
 * <p>Each tile has its own player, which reads the stream directly: no time shifting nor
 * recording here. The layout and the channels are kept for the next time.
 */
@OptIn(markerClass = UnstableApi.class)
public final class MultiviewActivity extends Activity {
    /**
     * Extra of the intent that opens a channel of the multiview in full screen: back in the TV
     * comes back to the multiview.
     */
    public static final String EXTRA_FROM_MULTIVIEW = "com.livetv.tv.extra.FROM_MULTIVIEW";
    /**
     * Whether the multiview is the start of the app, not opened over a channel: back there leaves
     * the app instead of going to the channel under it.
     */
    private static final String EXTRA_LEAVE_ON_BACK = "com.livetv.tv.extra.LEAVE_ON_BACK";

    private static final String PREFS = "livetv_multiview";
    private static final String KEY_LAYOUT = "layout";
    private static final String KEY_CHANNELS = "channels";
    /** The selected tile, so that coming back from a channel in full screen selects it again. */
    private static final String KEY_SELECTED = "selected";
    private static final int MAX_TILES = 6;
    /** How long a tile whose channel failed waits before trying again. */
    private static final long RETRY_DELAY_MS = 5000;
    /**
     * How long a tile may load, or stay stalled, before it tells so: often the provider allows
     * no more connections, and some keep them waiting instead of refusing them.
     */
    private static final long LOAD_TIMEOUT_MS = 20000;
    /** How long the digits typed in the list of channels wait for the next one. */
    private static final long TYPED_NUMBER_TIMEOUT_MS = 2000;
    private static final int MAX_TYPED_DIGITS = 6;
    private static final String KEY_OPEN_AT_START = "open_at_start";

    private static final int OPTION_CHANGE = 0;
    private static final int OPTION_FULL_SCREEN = 1;
    private static final int OPTION_REMOVE = 2;

    /** One tile: its views, its channel and its player. */
    private final class Tile implements Player.Listener {
        final int index;
        final View view;
        final SurfaceView surface;
        final View empty;
        final View buffering;
        final TextView error;
        final View sound;
        final TextView label;
        long channelId = C.INDEX_UNSET;
        /** The list of the channel, whose account limits the connections; -1 if unknown. */
        long playlistId = -1;
        ExoPlayer player;
        /** Not played: its account has no connection left for it. */
        boolean overLimit;
        final Runnable retry = this::startPlayer;
        final Runnable stalled = this::onStalled;

        Tile(int index, View view) {
            this.index = index;
            this.view = view;
            surface = view.findViewById(R.id.livetv_multiview_surface);
            empty = view.findViewById(R.id.livetv_multiview_empty);
            buffering = view.findViewById(R.id.livetv_multiview_buffering);
            error = view.findViewById(R.id.livetv_multiview_error);
            sound = view.findViewById(R.id.livetv_multiview_sound);
            label = view.findViewById(R.id.livetv_multiview_label);
        }

        boolean hasChannel() {
            return channelId != C.INDEX_UNSET;
        }

        boolean isShown() {
            return index < mLayout;
        }

        /** Shows the channel of the tile, or the "+" when it has none. */
        void bind() {
            Channel channel = hasChannel() ? mChannelDataManager.getChannel(channelId) : null;
            empty.setVisibility(hasChannel() ? View.GONE : View.VISIBLE);
            surface.setVisibility(hasChannel() ? View.VISIBLE : View.GONE);
            label.setVisibility(hasChannel() ? View.VISIBLE : View.GONE);
            label.setText(channel == null ? null : getChannelLabel(channel));
            playlistId =
                    channel == null
                            ? -1
                            : IptvChannels.getPlaylistId(MultiviewActivity.this, channel.getUri());
            if (!hasChannel()) {
                buffering.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
            }
            updateSound();
        }

        void updateSound() {
            boolean audible = index == mAudioTile && hasChannel();
            sound.setVisibility(audible ? View.VISIBLE : View.GONE);
            if (player != null) {
                player.setVolume(audible ? 1f : 0f);
                // The silent tiles do not decode their audio: it would take CPU and an audio
                // output, and their video would wait for an audio clock that stutters.
                TrackSelectionParameters parameters = player.getTrackSelectionParameters();
                if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) == audible) {
                    player.setTrackSelectionParameters(
                            parameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
                                    .build());
                }
            }
        }

        /**
         * The connections the account of the channel allows, if the tiles before this one, of the
         * same account, use them all; 0 otherwise.
         */
        int getExceededLimit() {
            if (!hasChannel() || !isShown() || playlistId < 0) {
                return 0;
            }
            int max = IptvRecording.getMaxConnections(MultiviewActivity.this, playlistId);
            if (max <= 0) {
                return 0;
            }
            int before = 0;
            for (int i = 0; i < index; i++) {
                Tile tile = mTiles[i];
                if (tile.isShown() && tile.hasChannel() && tile.playlistId == playlistId) {
                    before++;
                }
            }
            return before >= max ? max : 0;
        }

        /** Plays the channel of the tile, if it has one, is shown and the screen is visible. */
        void startPlayer() {
            mHandler.removeCallbacks(retry);
            releasePlayer();
            overLimit = false;
            if (!hasChannel() || !isShown() || !mStarted) {
                return;
            }
            Channel channel = mChannelDataManager.getChannel(channelId);
            String streamUrl =
                    channel == null ? null : IptvChannels.getStreamUrl(MultiviewActivity.this,
                            channel.getUri());
            if (TextUtils.isEmpty(streamUrl)) {
                // The channel was deleted with its list.
                channelId = C.INDEX_UNSET;
                bind();
                saveState();
                return;
            }
            int limit = getExceededLimit();
            if (limit > 0) {
                // No connection is left: the provider would refuse it, or keep it loading.
                overLimit = true;
                showError(getResources().getQuantityString(
                        R.plurals.livetv_multiview_limit, limit, limit));
                return;
            }
            error.setVisibility(View.GONE);
            buffering.setVisibility(View.VISIBLE);
            mHandler.postDelayed(stalled, LOAD_TIMEOUT_MS);
            DefaultHttpDataSource.Factory httpFactory =
                    new DefaultHttpDataSource.Factory()
                            .setUserAgent(IptvChannels.USER_AGENT)
                            .setAllowCrossProtocolRedirects(true);
            player =
                    new ExoPlayer.Builder(
                                    MultiviewActivity.this,
                                    new LiveRenderersFactory(MultiviewActivity.this))
                            .setMediaSourceFactory(
                                    new DefaultMediaSourceFactory(
                                            new DefaultDataSource.Factory(
                                                    MultiviewActivity.this, httpFactory)))
                            .build();
            // Several players: none takes the audio focus, which would pause the others.
            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                    false);
            // Adaptive streams (HLS) choose a quality for the size of the tile, not of the screen.
            int columns = MultiviewGrid.getColumns(mLayout);
            int width = getResources().getDisplayMetrics().widthPixels / columns;
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters()
                            .buildUpon()
                            .setViewportSize(width, width * 9 / 16, false)
                            .build());
            player.addListener(this);
            player.setVideoSurfaceView(surface);
            updateSound();
            player.setMediaItem(IptvChannels.buildMediaItem(streamUrl));
            player.prepare();
            player.setPlayWhenReady(true);
        }

        void releasePlayer() {
            mHandler.removeCallbacks(retry);
            mHandler.removeCallbacks(stalled);
            if (player != null) {
                player.removeListener(this);
                player.release();
                player = null;
            }
            buffering.setVisibility(View.GONE);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            mHandler.removeCallbacks(stalled);
            if (state == Player.STATE_BUFFERING) {
                mHandler.postDelayed(stalled, LOAD_TIMEOUT_MS);
            } else if (state == Player.STATE_READY) {
                error.setVisibility(View.GONE);
            } else if (state == Player.STATE_ENDED) {
                // A live stream should not end: the server closed it. Connect again.
                mHandler.postDelayed(retry, RETRY_DELAY_MS);
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            if (player != null && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // HLS fell behind the live edge: go back to it.
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            // Not logged: the message can hold the stream URL, with the password of the list.
            mHandler.removeCallbacks(stalled);
            buffering.setVisibility(View.GONE);
            showError(getString(
                    isRefused(e)
                            ? R.string.livetv_multiview_refused
                            : R.string.livetv_multiview_error));
            mHandler.postDelayed(retry, RETRY_DELAY_MS);
        }

        /**
         * Whether the provider refused the connection, as it does over the limit of the account:
         * not a channel that is down or gone (404).
         */
        private boolean isRefused(PlaybackException e) {
            Throwable cause = e.getCause();
            if (!(cause instanceof HttpDataSource.InvalidResponseCodeException)) {
                return false;
            }
            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            // 458 and 509 are what some IPTV panels send for too many connections.
            return code == 401 || code == 403 || code == 429 || code == 458 || code == 509;
        }

        /** Loading or stalled too long: tells so and connects again. */
        private void onStalled() {
            releasePlayer();
            showError(getString(R.string.livetv_multiview_stalled));
            mHandler.postDelayed(retry, RETRY_DELAY_MS);
        }

        private void showError(String message) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Tile[] mTiles = new Tile[MAX_TILES];
    private ChannelDataManager mChannelDataManager;
    private MultiviewGrid mGrid;
    private View mSidePanel;
    private TextView mSidePanelTitle;
    private VerticalGridView mSidePanelList;
    /** Number of tiles shown: 2 to 6, from the connection limit of the accounts. */
    private int mLayout;
    /** The tile with sound: the selected one, or the last selected while the panel is open. */
    private int mAudioTile;
    /** The tile whose channels or options the side panel shows. */
    private int mPanelTile = -1;
    private boolean mStarted;
    /** The digits typed in the list of channels, until they time out or the panel closes. */
    private final StringBuilder mTypedNumber = new StringBuilder();
    private final Runnable mClearTypedNumber = this::clearTypedNumber;

    private final ChannelDataManager.Listener mChannelListener =
            new ChannelDataManager.Listener() {
                @Override
                public void onLoadFinished() {
                    // Opened before the channels were read (should not happen): start now.
                    for (Tile tile : mTiles) {
                        tile.bind();
                        if (tile.player == null) {
                            tile.startPlayer();
                        }
                    }
                }

                @Override
                public void onChannelListUpdated() {
                    for (Tile tile : mTiles) {
                        tile.bind();
                    }
                }

                @Override
                public void onChannelBrowsableChanged() {}
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.livetv_multiview);
        mChannelDataManager = TvSingletons.getSingletons(this).getChannelDataManager();

        mGrid = findViewById(R.id.livetv_multiview_grid);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < MAX_TILES; i++) {
            View view = inflater.inflate(R.layout.livetv_multiview_tile, mGrid, false);
            mGrid.addView(view);
            Tile tile = new Tile(i, view);
            mTiles[i] = tile;
            view.setOnClickListener(v -> onTileClicked(tile));
            view.setOnLongClickListener(
                    v -> {
                        onTileLongClicked(tile);
                        return true;
                    });
            view.setOnFocusChangeListener(
                    (v, hasFocus) -> {
                        if (hasFocus) {
                            setAudioTile(tile.index);
                        }
                    });
        }

        mSidePanel = findViewById(R.id.side_panel);
        View sideFragment =
                inflater.inflate(
                        R.layout.option_fragment,
                        (ViewGroup) findViewById(R.id.side_fragment_container),
                        true);
        mSidePanelTitle = sideFragment.findViewById(R.id.side_panel_title);
        mSidePanelList = sideFragment.findViewById(R.id.side_panel_list);

        loadState();
        TextView layoutLabel = findViewById(R.id.livetv_multiview_layout);
        layoutLabel.setText(getString(R.string.livetv_multiview_layout, mLayout));
        // Drawn as the chosen chip.
        layoutLabel.setActivated(true);
        applyLayout();
        for (Tile tile : mTiles) {
            tile.bind();
        }
        mTiles[mAudioTile].view.requestFocus();
        mChannelDataManager.addListener(mChannelListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Brought to the front again: whether back leaves the app is the new caller's.
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        if (mChannelDataManager.isDbLoadFinished()) {
            for (Tile tile : mTiles) {
                tile.startPlayer();
            }
        }
    }

    @Override
    protected void onStop() {
        // The players hold network connections and decoders: free them for the TV.
        mStarted = false;
        for (Tile tile : mTiles) {
            tile.releasePlayer();
        }
        saveState();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mChannelDataManager.removeListener(mChannelListener);
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isSidePanelShown()) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        // While the side panel is open, left and right must not move the focus to the tiles
        // behind it.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true;
        }
        int digit = getDigit(keyCode);
        if (digit >= 0 && mSidePanelList.getAdapter() instanceof ChannelAdapter) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onDigitTyped(digit);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // Up on the first item goes to the last one, down on the last goes to the first. Only
            // on a new press: holding the key stops at the end of the list.
            RecyclerView.Adapter<?> adapter = mSidePanelList.getAdapter();
            int count = adapter == null ? 0 : adapter.getItemCount();
            int position = mSidePanelList.getSelectedPosition();
            if (count > 1) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    mSidePanelList.setSelectedPosition(count - 1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == count - 1) {
                    mSidePanelList.setSelectedPosition(0);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** The digit of a number key of the remote, or -1. */
    private static int getDigit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    /**
     * A number key in the list of channels: the digits typed so far go to the channel with that
     * number or, if none has it, to the first one whose number starts with them.
     */
    private void onDigitTyped(int digit) {
        mHandler.removeCallbacks(mClearTypedNumber);
        if (mTypedNumber.length() >= MAX_TYPED_DIGITS) {
            mTypedNumber.setLength(0);
        }
        mTypedNumber.append(digit);
        String typed = mTypedNumber.toString();
        mSidePanelTitle.setText(getString(R.string.livetv_multiview_pick_channel) + "  " + typed);
        mHandler.postDelayed(mClearTypedNumber, TYPED_NUMBER_TIMEOUT_MS);

        List<Channel> channels = ((ChannelAdapter) mSidePanelList.getAdapter()).mChannels;
        // Display numbers are compared as text: "5" and "05" are different channels.
        int match = -1;
        for (int i = 0; i < channels.size(); i++) {
            String number = channels.get(i).getDisplayNumber();
            if (number == null) {
                continue;
            }
            if (number.equals(typed)) {
                match = i;
                break;
            }
            if (match < 0 && number.startsWith(typed)) {
                match = i;
            }
        }
        if (match >= 0) {
            mSidePanelList.setSelectedPosition(match);
        }
    }

    @Override
    public void onBackPressed() {
        if (isSidePanelShown()) {
            hideSidePanel();
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_LEAVE_ON_BACK, false)) {
            // The whole app goes to the background, as back does in the TV. The channel under
            // this screen is not resumed, so it does not play for a moment. Opened again from
            // the launcher, the app starts in the multiview.
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    private void loadState() {
        mLayout = getLayout(getChannelLimit(this));
        long[] ids = getSavedChannelIds(this);
        for (int i = 0; i < MAX_TILES; i++) {
            mTiles[i].channelId = ids[i];
        }
        int selected = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SELECTED, 0);
        mAudioTile = selected >= 0 && selected < mLayout ? selected : 0;
    }

    private void saveState() {
        long[] ids = new long[MAX_TILES];
        for (int i = 0; i < MAX_TILES; i++) {
            ids[i] = mTiles[i].channelId;
        }
        saveState(this, mLayout, ids);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SELECTED, mAudioTile).apply();
    }

    /** The number of channels the multiview shows: 2 to 6. */
    public static int getSavedLayout(Context context) {
        int layout = context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LAYOUT, 4);
        return layout >= 2 && layout <= MAX_TILES ? layout : 4;
    }

    /**
     * The most channels the multiview can play at once: the largest connection limit of the
     * accounts of the lists, or {@link #MAX_TILES} if a list has no limit or does not tell it.
     */
    static int getChannelLimit(Context context) {
        int limit = 0;
        for (long playlistId : IptvChannels.getPlaylistIds(context)) {
            int max = IptvRecording.getMaxConnections(context, playlistId);
            if (max <= 0) {
                return MAX_TILES;
            }
            limit = Math.max(limit, max);
        }
        return limit == 0 ? MAX_TILES : limit;
    }

    /**
     * The layout for a connection limit: that many channels, at least 2 and at most {@link
     * #MAX_TILES}. The user fills as many of its places as wanted.
     */
    static int getLayout(int limit) {
        return Math.max(2, Math.min(limit, MAX_TILES));
    }

    /**
     * After the channels or the layout changed: the tiles whose account has no connection left
     * for them stop, and those that have one again start.
     */
    private void applyConnectionLimits() {
        for (Tile tile : mTiles) {
            if (tile.isShown()
                    && tile.hasChannel()
                    && tile.overLimit != (tile.getExceededLimit() > 0)) {
                tile.startPlayer();
            }
        }
    }

    /**
     * The channel of each place of the multiview, for its 6 places (a layout of fewer channels
     * shows the first ones), or {@link C#INDEX_UNSET} for an empty place.
     */
    public static long[] getSavedChannelIds(Context context) {
        long[] ids = new long[MAX_TILES];
        Arrays.fill(ids, C.INDEX_UNSET);
        String[] saved =
                context.getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_CHANNELS, "")
                        .split(",");
        for (int i = 0; i < MAX_TILES && i < saved.length; i++) {
            try {
                ids[i] = Long.parseLong(saved[i].trim());
            } catch (NumberFormatException e) {
                // An empty place.
            }
        }
        return ids;
    }

    /** Saves the layout and the channels of the multiview, as {@link #getSavedChannelIds}. */
    public static void saveState(Context context, int layout, long[] channelIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < MAX_TILES; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append(i < channelIds.length ? channelIds[i] : C.INDEX_UNSET);
        }
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAYOUT, layout)
                .putString(KEY_CHANNELS, ids.toString())
                .apply();
    }

    /**
     * Whether a place the multiview shows has a channel. The places past its layout keep theirs
     * but are not shown, as when the connection limit of the account went down.
     */
    public static boolean hasSavedChannels(Context context) {
        long[] ids = getSavedChannelIds(context);
        int layout = getLayout(getChannelLimit(context));
        for (int i = 0; i < layout; i++) {
            if (ids[i] != C.INDEX_UNSET) {
                return true;
            }
        }
        return false;
    }

    private void applyLayout() {
        for (Tile tile : mTiles) {
            tile.view.setVisibility(tile.isShown() ? View.VISIBLE : View.GONE);
        }
        mGrid.requestLayout();
    }

    private void setAudioTile(int index) {
        mAudioTile = index;
        for (Tile tile : mTiles) {
            tile.updateSound();
        }
    }

    /** OK on a tile: its channel in full screen, or the channels to add when it is empty. */
    private void onTileClicked(Tile tile) {
        if (tile.hasChannel()) {
            openFullScreen(tile);
        } else {
            showChannelPicker(tile);
        }
    }

    /** OK held on a tile: its options, or the channels to add when it is empty. */
    private void onTileLongClicked(Tile tile) {
        if (tile.hasChannel()) {
            showTileOptions(tile);
        } else {
            showChannelPicker(tile);
        }
    }

    /**
     * Leaves the multiview and the TV tunes to the channel of the tile. Back there comes back here,
     * with the same channels.
     */
    private void openFullScreen(Tile tile) {
        Channel channel = mChannelDataManager.getChannel(tile.channelId);
        if (channel == null) {
            return;
        }
        // Free the decoders and connections before the TV tunes, not after this screen stops.
        mStarted = false;
        for (Tile t : mTiles) {
            t.releasePlayer();
        }
        // Back from the channel, its tile is selected again.
        mAudioTile = tile.index;
        saveState();
        startActivity(
                new Intent(Intent.ACTION_VIEW, channel.getUri(), this, MainActivity.class)
                        .putExtra(EXTRA_FROM_MULTIVIEW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    /**
     * The intent that opens the multiview. If one is already open, it comes to the front instead
     * of a new one: otherwise back would have to close each of them.
     */
    public static Intent newIntent(Context context, boolean leaveOnBack) {
        return new Intent(context, MultiviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_LEAVE_ON_BACK, leaveOnBack);
    }

    /** Whether the app opens in the multiview, a switch of the settings. */
    public static boolean isOpenAtStart(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_OPEN_AT_START, false);
    }

    public static void setOpenAtStart(Context context, boolean openAtStart) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_OPEN_AT_START, openAtStart)
                .apply();
    }

    private static String getChannelLabel(Channel channel) {
        String number = channel.getDisplayNumber();
        String name = channel.getDisplayName();
        if (TextUtils.isEmpty(number)) {
            return name;
        }
        return TextUtils.isEmpty(name) ? number : number + "  " + name;
    }

    /** The channels of the IPTV lists shown in the TV, in the order of the TV. */
    private List<Channel> getIptvChannels() {
        String inputId = IptvChannels.getInputId(this);
        List<Channel> channels = new ArrayList<>();
        for (Channel channel : mChannelDataManager.getBrowsableChannelList()) {
            if (inputId.equals(channel.getInputId())) {
                channels.add(channel);
            }
        }
        return channels;
    }

    private void showChannelPicker(Tile tile) {
        List<Channel> channels = getIptvChannels();
        int checked = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getId() == tile.channelId) {
                checked = i;
                break;
            }
        }
        showSidePanel(
                tile,
                R.string.livetv_multiview_pick_channel,
                new ChannelAdapter(tile, channels, checked),
                Math.max(0, checked));
    }

    private void showTileOptions(Tile tile) {
        Channel channel = mChannelDataManager.getChannel(tile.channelId);
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.livetv_multiview_change));
        labels.add(getString(R.string.livetv_multiview_full_screen));
        labels.add(getString(R.string.livetv_multiview_remove));
        mSidePanelTitle.setText(channel == null ? null : getChannelLabel(channel));
        showSidePanel(tile, 0, new OptionAdapter(tile, labels), 0);
    }

    private void clearTypedNumber() {
        mHandler.removeCallbacks(mClearTypedNumber);
        if (mTypedNumber.length() > 0
                && isSidePanelShown()
                && mSidePanelList.getAdapter() instanceof ChannelAdapter) {
            mSidePanelTitle.setText(R.string.livetv_multiview_pick_channel);
        }
        mTypedNumber.setLength(0);
    }

    private void showSidePanel(
            Tile tile, int titleRes, RecyclerView.Adapter<?> adapter, int selected) {
        clearTypedNumber();
        mPanelTile = tile.index;
        if (titleRes != 0) {
            mSidePanelTitle.setText(titleRes);
        }
        mSidePanelList.setAdapter(adapter);
        mSidePanelList.setSelectedPosition(selected);
        mSidePanel.animate().cancel();
        mSidePanel.setVisibility(View.VISIBLE);
        mSidePanel
                .animate()
                .alpha(1f)
                .translationX(0)
                .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                .start();
        mSidePanelList.requestFocus();
    }

    private boolean isSidePanelShown() {
        return mSidePanel.getVisibility() == View.VISIBLE;
    }

    private void hideSidePanel() {
        clearTypedNumber();
        mSidePanel.animate().cancel();
        mSidePanel
                .animate()
                .alpha(0f)
                .translationX(getResources().getDimension(R.dimen.side_panel_enter_offset_x))
                .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                .withEndAction(() -> mSidePanel.setVisibility(View.GONE))
                .start();
        mSidePanelList.clearFocus();
        if (mPanelTile >= 0) {
            mTiles[mPanelTile].view.requestFocus();
        }
        mPanelTile = -1;
    }

    private void onChannelChosen(Tile tile, Channel channel) {
        hideSidePanel();
        if (tile.channelId == channel.getId() && tile.player != null) {
            return;
        }
        tile.channelId = channel.getId();
        tile.bind();
        tile.startPlayer();
        applyConnectionLimits();
        saveState();
    }

    private void onOptionChosen(Tile tile, int option) {
        switch (option) {
            case OPTION_CHANGE:
                showChannelPicker(tile);
                break;
            case OPTION_FULL_SCREEN:
                hideSidePanel();
                openFullScreen(tile);
                break;
            case OPTION_REMOVE:
            default:
                hideSidePanel();
                tile.releasePlayer();
                tile.channelId = C.INDEX_UNSET;
                tile.bind();
                applyConnectionLimits();
                saveState();
                break;
        }
    }

    /** The channels to put in a tile, as radio buttons; the tile's channel is checked. */
    private final class ChannelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Tile mTile;
        private final List<Channel> mChannels;
        private final int mChecked;

        ChannelAdapter(Tile tile, List<Channel> channels, int checked) {
            mTile = tile;
            mChannels = channels;
            mChecked = checked;
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.option_item_radio_button, parent, false);
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {};
            view.setOnClickListener(
                    v -> {
                        int position = holder.getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            onChannelChosen(mTile, mChannels.get(position));
                        }
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView.findViewById(R.id.title))
                    .setText(getChannelLabel(mChannels.get(position)));
            holder.itemView.findViewById(R.id.description).setVisibility(View.GONE);
            ((RadioButton) holder.itemView.findViewById(R.id.radio_button))
                    .setChecked(position == mChecked);
        }
    }

    /** The options of a tile with a channel. */
    private final class OptionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Tile mTile;
        private final List<String> mLabels;

        OptionAdapter(Tile tile, List<String> labels) {
            mTile = tile;
            mLabels = labels;
        }

        @Override
        public int getItemCount() {
            return mLabels.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.option_item_action, parent, false);
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {};
            view.setOnClickListener(
                    v -> {
                        int position = holder.getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            onOptionChosen(mTile, position);
                        }
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView.findViewById(R.id.title)).setText(mLabels.get(position));
            holder.itemView.findViewById(R.id.description).setVisibility(View.GONE);
        }
    }
}
