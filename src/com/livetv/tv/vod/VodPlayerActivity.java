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

package com.livetv.tv.vod;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.leanback.widget.VerticalGridView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.android.tv.R;
import com.android.tv.data.DisplayMode;
import com.android.tv.menu.ActionCardView;
import com.android.tv.menu.MenuAction;
import com.android.tv.menu.PlayControlsButton;
import com.android.tv.menu.PlaybackProgressBar;
import com.android.tv.util.Utils;
import com.livetv.tv.audio.LiveRenderersFactory;
import com.livetv.tv.framerate.DisplayModeRequester;
import com.livetv.tv.framerate.FrameRateMatcher;
import com.livetv.tv.iptv.IptvChannels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays a movie or an episode. Its controls are two rows of the menu: the play controls row shown
 * with time shifting (the same layout, buttons and progress bar, driven by the player), and the
 * TV options row, whose subtitles, display mode and audio open in the side panel of the TV.
 */
public class VodPlayerActivity extends Activity implements Player.Listener {
    private static final String TAG = "VodPlayerActivity";

    /** Names of the titles that can be played, for the controls. */
    public static final String EXTRA_NAMES = "names";
    /** What each title of {@link #EXTRA_NAMES} plays. */
    public static final String EXTRA_URLS = "urls";
    /** {@link VodLibrary.Title#key} of each title, to remember where it was left. */
    public static final String EXTRA_KEYS = "keys";
    /** Poster or picture of each title, for "Continue watching". */
    public static final String EXTRA_IMAGES = "images";
    /** {@link VodLibrary#KIND_MOVIES} or {@link VodLibrary#KIND_SERIES}. */
    public static final String EXTRA_KIND = "kind";
    /** The title played first. */
    public static final String EXTRA_POSITION = "position";
    /** The series of the episodes, or none for a movie. */
    public static final String EXTRA_SERIES_NAME = "series";

    /** How long the controls stay while nothing is pressed. */
    private static final long OSD_HIDE_MS = 5000;
    private static final long UPDATE_INTERVAL_MS = 500;
    /** How often the position is remembered while playing. */
    private static final long SAVE_INTERVAL_MS = 15000;
    /** Left and right, while the controls are hidden. */
    private static final long SEEK_STEP_MS = 10000;
    /** Rewind and fast forward jump this often, by the speed times this long. */
    private static final long TRICK_PLAY_INTERVAL_MS = 500;
    private static final int[] SPEEDS = {2, 4, 8, 16, 32};
    /** Key repeats (about half a second held) before rewinding or fast forwarding starts. */
    private static final int HOLD_REPEAT_COUNT = 1;
    /** Key repeats (about a second held) before the next speed. */
    private static final int SPEED_UP_REPEAT_COUNT = 20;
    /** Like the play controls row of the menu. */
    private static final int NORMAL_WIDTH_MAX_BUTTON_COUNT = 5;

    private static final int OPTION_CLOSED_CAPTIONS = 0;
    private static final int OPTION_DISPLAY_MODE = 1;
    private static final int OPTION_MULTI_AUDIO = 2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer mPlayer;
    private SurfaceView mSurfaceView;
    private TextView mSubtitlesView;
    private View mBufferingView;
    private View mOsdView;
    private TextView mOsdTitleView;
    private View mPlayControlsView;
    private TextView mOptionsTitleView;
    private LinearLayout mOptionsView;
    private final List<MenuAction> mOptionActions = new ArrayList<>();
    private final List<ActionCardView> mOptionCards = new ArrayList<>();
    private boolean mOptionsRowSelected;
    private TextView mBackgroundView;
    private View mTimeIndicator;
    private PlaybackProgressBar mProgress;
    private TextView mStartTimeText;
    private TextView mEndTimeText;
    private PlayControlsButton mJumpPreviousButton;
    private PlayControlsButton mRewindButton;
    private PlayControlsButton mPlayPauseButton;
    private PlayControlsButton mFastForwardButton;
    private PlayControlsButton mJumpNextButton;
    private PlayControlsButton mRecordButton;
    private PlayControlsButton mRestartButton;

    private View mSidePanel;
    private TextView mSidePanelTitle;
    private VerticalGridView mSidePanelList;

    private int mTimelineWidth;
    private int mTimeIndicatorLeftMargin;
    private int mNormalButtonMargin;
    private int mCompactButtonMargin;
    private float mTitleSizeSelected;
    private float mTitleSizeDeselected;
    private float mTitleAlphaDeselected;

    private ArrayList<String> mNames;
    private ArrayList<String> mUrls;
    private ArrayList<String> mKeys;
    private ArrayList<String> mImages;
    private int mKind;
    private String mSeriesName;
    private int mPosition;
    private int mDisplayMode = DisplayMode.MODE_NORMAL;
    private VideoSize mVideoSize = VideoSize.UNKNOWN;

    /** 0 while playing normally; else the index in {@link #SPEEDS} plus one. */
    private int mSpeedLevel;
    /** Whether rewinding (else fast forwarding) while {@link #mSpeedLevel} is not 0. */
    private boolean mRewinding;
    /** Where rewinding or fast forwarding has got to. */
    private long mTrickPositionMs;
    private FrameRateMatcher mFrameRateMatcher;
    /** Whether the title waits for the display to switch to its frame rate. */
    private boolean mWaitingForOutput;
    /** Whether the audio and subtitles chosen for the title wait for its tracks. */
    private boolean mRestoreTracksPending;

    private final Runnable mHideOsdRunnable = this::hideOsd;
    private final Runnable mUpdateRunnable =
            new Runnable() {
                @Override
                public void run() {
                    updateControls();
                    if (mOsdView.getVisibility() == View.VISIBLE) {
                        mHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                    }
                }
            };
    private final Runnable mSaveRunnable =
            new Runnable() {
                @Override
                public void run() {
                    saveProgress();
                    mHandler.postDelayed(this, SAVE_INTERVAL_MS);
                }
            };
    private final Runnable mTrickPlayRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (mSpeedLevel == 0 || mPlayer == null) {
                        return;
                    }
                    long step = SPEEDS[mSpeedLevel - 1] * TRICK_PLAY_INTERVAL_MS;
                    // From the position aimed at, not the one played: seeks land on key frames,
                    // which would keep rewinding on the same frame.
                    long position = mTrickPositionMs + (mRewinding ? -step : step);
                    long duration = mPlayer.getDuration();
                    if (position <= 0) {
                        mTrickPositionMs = 0;
                        play();
                        return;
                    }
                    if (duration != C.TIME_UNSET && position >= duration) {
                        mTrickPositionMs = Math.max(0, duration - 1000);
                        play();
                        return;
                    }
                    mTrickPositionMs = position;
                    mPlayer.seekTo(position);
                    updateControls();
                    mHandler.postDelayed(this, TRICK_PLAY_INTERVAL_MS);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_vod_player);
        mNames = getIntent().getStringArrayListExtra(EXTRA_NAMES);
        mUrls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        mKeys = getIntent().getStringArrayListExtra(EXTRA_KEYS);
        mImages = getIntent().getStringArrayListExtra(EXTRA_IMAGES);
        mKind = getIntent().getIntExtra(EXTRA_KIND, VodLibrary.KIND_MOVIES);
        mSeriesName = getIntent().getStringExtra(EXTRA_SERIES_NAME);
        mPosition = getIntent().getIntExtra(EXTRA_POSITION, 0);
        if (mNames == null
                || mUrls == null
                || mUrls.isEmpty()
                || mNames.size() != mUrls.size()) {
            finish();
            return;
        }
        if (mKeys == null || mKeys.size() != mUrls.size()) {
            mKeys = null;
        }
        if (mImages == null || mImages.size() != mUrls.size()) {
            mImages = null;
        }
        mPosition = Math.max(0, Math.min(mPosition, mUrls.size() - 1));

        Resources res = getResources();
        mTimelineWidth = res.getDimensionPixelSize(R.dimen.play_controls_width);
        mTimeIndicatorLeftMargin =
                -res.getDimensionPixelSize(R.dimen.play_controls_time_indicator_width) / 2;
        mNormalButtonMargin = res.getDimensionPixelSize(R.dimen.play_controls_button_normal_margin);
        mCompactButtonMargin =
                res.getDimensionPixelSize(R.dimen.play_controls_button_compact_margin);
        mTitleSizeSelected = res.getDimension(R.dimen.menu_row_title_text_size_selected);
        mTitleSizeDeselected = res.getDimension(R.dimen.menu_row_title_text_size_deselected);
        android.util.TypedValue alpha = new android.util.TypedValue();
        res.getValue(R.dimen.menu_row_title_alpha_deselected, alpha, true);
        mTitleAlphaDeselected = alpha.getFloat();

        mSurfaceView = findViewById(R.id.livetv_vod_surface);
        mSurfaceView.setKeepScreenOn(true);
        mSubtitlesView = findViewById(R.id.livetv_vod_subtitles);
        mBufferingView = findViewById(R.id.livetv_vod_buffering);
        mOsdView = findViewById(R.id.livetv_vod_osd);
        mOsdTitleView = findViewById(R.id.livetv_vod_osd_title);
        mPlayControlsView = findViewById(R.id.livetv_vod_play_controls);
        mOptionsTitleView = findViewById(R.id.livetv_vod_options_title);
        mOptionsView = findViewById(R.id.livetv_vod_options);
        findViewById(R.id.body).setClipToOutline(true);
        mBackgroundView = findViewById(R.id.background);
        mTimeIndicator = findViewById(R.id.time_indicator);
        // The time played is shown inside the bar, where the start of the program is shown with
        // time shifting, so the time above the bar is not needed. It keeps its room, which the
        // moving time indicator needs above the bar.
        findViewById(R.id.time_text).setVisibility(View.INVISIBLE);
        mProgress = findViewById(R.id.progress);
        mStartTimeText = findViewById(R.id.program_start_time);
        mEndTimeText = findViewById(R.id.program_end_time);
        mJumpPreviousButton = findViewById(R.id.jump_previous);
        mRewindButton = findViewById(R.id.rewind);
        mPlayPauseButton = findViewById(R.id.play_pause);
        mFastForwardButton = findViewById(R.id.fast_forward);
        mJumpNextButton = findViewById(R.id.jump_next);
        mRecordButton = findViewById(R.id.record);
        mRecordButton.setVisibility(View.GONE);
        mRestartButton = findViewById(R.id.livetv_vod_restart);

        initializeButton(
                mJumpPreviousButton,
                R.drawable.lb_ic_skip_previous,
                R.string.play_controls_description_skip_previous,
                () -> playTitle(mPosition - 1));
        initializeButton(
                mRestartButton,
                R.drawable.ic_livetv_vod_restart,
                R.string.livetv_vod_restart,
                this::restart);
        initializeSeekButton(
                mRewindButton,
                R.drawable.lb_ic_fast_rewind,
                R.string.play_controls_description_fast_rewind,
                true);
        initializeButton(
                mPlayPauseButton,
                R.drawable.lb_ic_pause,
                R.string.play_controls_description_play_pause,
                this::togglePlayPause);
        initializeSeekButton(
                mFastForwardButton,
                R.drawable.lb_ic_fast_forward,
                R.string.play_controls_description_fast_forward,
                false);
        initializeButton(
                mJumpNextButton,
                R.drawable.lb_ic_skip_next,
                R.string.play_controls_description_skip_next,
                () -> playTitle(mPosition + 1));
        // Like the play controls row: leaving rewind or fast forward plays normally again.
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .addOnGlobalFocusChangeListener(
                        (oldFocus, newFocus) -> {
                            if (mSpeedLevel != 0
                                    && oldFocus != null
                                    && (isInside(oldFocus, mRewindButton)
                                            || isInside(oldFocus, mFastForwardButton))
                                    && newFocus != null
                                    && !isInside(newFocus, mRewindButton)
                                    && !isInside(newFocus, mFastForwardButton)) {
                                play();
                            }
                        });
        mBackgroundView.setEnabled(true);
        mBackgroundView.setText(null);

        createOptionCards();
        mSidePanel = findViewById(R.id.side_panel);
        View sideFragment =
                LayoutInflater.from(this)
                        .inflate(
                                R.layout.option_fragment,
                                (ViewGroup) findViewById(R.id.side_fragment_container),
                                true);
        mSidePanelTitle = sideFragment.findViewById(R.id.side_panel_title);
        mSidePanelList = sideFragment.findViewById(R.id.side_panel_list);

        DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent(IptvChannels.USER_AGENT)
                        .setAllowCrossProtocolRedirects(true);
        mPlayer =
                new ExoPlayer.Builder(this, new LiveRenderersFactory(this))
                        .setMediaSourceFactory(
                                new DefaultMediaSourceFactory(
                                        new DefaultDataSource.Factory(this, httpFactory)))
                        .build();
        mPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                true);
        mPlayer.setVideoSurfaceView(mSurfaceView);
        mPlayer.addListener(this);
        // Match framerate, like the IPTV channels: the display follows the title (23.976 Hz for
        // most movies) through the surface and the window of this activity.
        mFrameRateMatcher = new FrameRateMatcher(this);
        mFrameRateMatcher.attach(mPlayer);
        mSurfaceView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                mFrameRateMatcher.setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(
                                    SurfaceHolder holder, int format, int width, int height) {}

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                mFrameRateMatcher.setSurface(null);
                            }
                        });
        playTitle(mPosition);
        mHandler.postDelayed(mSaveRunnable, SAVE_INTERVAL_MS);
    }

    private static boolean isInside(View view, View parent) {
        for (View v = view; v != null; ) {
            if (v == parent) {
                return true;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private void initializeButton(
            PlayControlsButton button, int imageResId, int descriptionId, Runnable clickAction) {
        button.setImageResId(imageResId);
        button.setAction(
                () -> {
                    clickAction.run();
                    showOsd(false);
                });
        button.findViewById(R.id.button).setContentDescription(getString(descriptionId));
    }

    /**
     * Rewind and fast forward: a press jumps {@link #SEEK_STEP_MS}; held, they go at 2X, then
     * faster the longer they are held, until released.
     */
    private void initializeSeekButton(
            PlayControlsButton button, int imageResId, int descriptionId, boolean rewind) {
        initializeButton(
                button,
                imageResId,
                descriptionId,
                () -> seekBy(rewind ? -SEEK_STEP_MS : SEEK_STEP_MS));
        button.findViewById(R.id.button)
                .setOnKeyListener(
                        (v, keyCode, event) ->
                                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                                || keyCode == KeyEvent.KEYCODE_ENTER
                                                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                        && onSeekKey(event, rewind));
    }

    /**
     * Handles a key that rewinds or fast forwards: jumps when released quickly, or plays fast
     * while held. Returns true: the key is used.
     */
    private boolean onSeekKey(KeyEvent event, boolean rewind) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int repeat = event.getRepeatCount();
            if (repeat == HOLD_REPEAT_COUNT) {
                startTrickPlay(rewind);
            } else if (repeat > HOLD_REPEAT_COUNT
                    && mSpeedLevel != 0
                    && (repeat - HOLD_REPEAT_COUNT) % SPEED_UP_REPEAT_COUNT == 0) {
                // Held longer: faster.
                mSpeedLevel = Math.min(SPEEDS.length, mSpeedLevel + 1);
                updateControls();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (mSpeedLevel != 0) {
                play();
            } else {
                seekBy(rewind ? -SEEK_STEP_MS : SEEK_STEP_MS);
            }
            showOsd(false);
        }
        return true;
    }

    /** Starts the title again from its beginning. */
    private void restart() {
        if (mPlayer == null) {
            return;
        }
        mSpeedLevel = 0;
        mHandler.removeCallbacks(mTrickPlayRunnable);
        mPlayer.setSeekParameters(SeekParameters.DEFAULT);
        mPlayer.seekTo(0);
        mPlayer.setPlayWhenReady(true);
        updateControls();
    }

    /** The cards of the TV options row, like the ones of the menu. */
    private void createOptionCards() {
        mOptionActions.add(
                new MenuAction(
                        R.string.options_item_closed_caption,
                        OPTION_CLOSED_CAPTIONS,
                        R.drawable.ic_tvoption_cc));
        mOptionActions.add(
                new MenuAction(
                        R.string.options_item_display_mode,
                        OPTION_DISPLAY_MODE,
                        R.drawable.ic_tvoption_aspect));
        mOptionActions.add(
                new MenuAction(
                        R.string.options_item_multi_audio,
                        OPTION_MULTI_AUDIO,
                        R.drawable.ic_tvoption_multi_track));
        int spacing = getResources().getDimensionPixelSize(R.dimen.menu_inter_card_spacing);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MenuAction action : mOptionActions) {
            ActionCardView card =
                    (ActionCardView)
                            inflater.inflate(R.layout.menu_card_action, mOptionsView, false);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
            params.setMarginEnd(spacing);
            mOptionsView.addView(card, params);
            card.setOnClickListener(v -> showOption(action.getType()));
            mOptionCards.add(card);
        }
    }

    /** Shows the options that can be used now, with their current choices. */
    private void updateOptionCards() {
        List<String> subtitles = new ArrayList<>();
        int subtitle = getTrackLabels(C.TRACK_TYPE_TEXT, subtitles);
        List<String> audios = new ArrayList<>();
        int audio = getTrackLabels(C.TRACK_TYPE_AUDIO, audios);
        for (int i = 0; i < mOptionActions.size(); i++) {
            MenuAction action = mOptionActions.get(i);
            ActionCardView card = mOptionCards.get(i);
            boolean visible;
            String description;
            switch (action.getType()) {
                case OPTION_CLOSED_CAPTIONS:
                    // Like the TV options row: hidden while there is nothing to choose.
                    visible = !subtitles.isEmpty();
                    description =
                            subtitle >= 0
                                    ? subtitles.get(subtitle)
                                    : getString(R.string.closed_caption_option_item_off);
                    break;
                case OPTION_MULTI_AUDIO:
                    visible = audios.size() > 1;
                    description = audio >= 0 ? audios.get(audio) : null;
                    break;
                case OPTION_DISPLAY_MODE:
                default:
                    visible = true;
                    description = DisplayMode.getLabel(mDisplayMode, this);
                    break;
            }
            MenuAction.setActionDescription(action, description);
            card.onBind(action, card.hasFocus());
            card.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Fills {@code labels} with the tracks of {@code trackType} that can be played; returns the
     * index of the selected one, or -1.
     */
    private int getTrackLabels(int trackType, List<String> labels) {
        if (mPlayer == null) {
            return -1;
        }
        int selected = -1;
        for (Tracks.Group group : mPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != trackType) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                if (group.isTrackSelected(i)) {
                    selected = labels.size();
                }
                labels.add(getTrackLabel(group.getTrackFormat(i), trackType, labels.size() + 1));
            }
        }
        return selected;
    }

    private String getTrackLabel(Format format, int trackType, int number) {
        String language = null;
        if (!TextUtils.isEmpty(format.language) && !"und".equals(format.language)) {
            language = new Locale(format.language).getDisplayName();
            if (!language.isEmpty()) {
                language = language.substring(0, 1).toUpperCase(Locale.getDefault())
                        + language.substring(1);
            }
        }
        String label = !TextUtils.isEmpty(format.label) ? format.label : language;
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (label == null) {
                label = getString(R.string.multi_audio_unknown_language);
            }
            if (format.channelCount > 0) {
                label += " " + Utils.getAudioChannelString(this, format.channelCount);
            }
            return label;
        }
        return label != null
                ? label
                : getString(R.string.closed_caption_unknown_language, number);
    }

    /** Opens the choices of an option in the side panel, like the TV options of the menu. */
    private void showOption(int type) {
        List<String> labels = new ArrayList<>();
        int checked;
        int titleRes;
        switch (type) {
            case OPTION_CLOSED_CAPTIONS:
                titleRes = R.string.side_panel_title_closed_caption;
                labels.add(getString(R.string.closed_caption_option_item_off));
                checked = getTrackLabels(C.TRACK_TYPE_TEXT, labels);
                checked = checked < 0 ? 0 : checked;
                break;
            case OPTION_MULTI_AUDIO:
                titleRes = R.string.side_panel_title_multi_audio;
                checked = Math.max(0, getTrackLabels(C.TRACK_TYPE_AUDIO, labels));
                break;
            case OPTION_DISPLAY_MODE:
            default:
                titleRes = R.string.side_panel_title_display_mode;
                for (int mode = 0; mode < DisplayMode.SIZE_OF_RATIO_TYPES; mode++) {
                    labels.add(DisplayMode.getLabel(mode, this));
                }
                checked = mDisplayMode;
                break;
        }
        hideOsd();
        mSidePanelTitle.setText(titleRes);
        mSidePanelList.setAdapter(new OptionAdapter(type, labels, checked));
        mSidePanelList.setSelectedPosition(checked);
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
        mSidePanel.animate().cancel();
        mSidePanel
                .animate()
                .alpha(0f)
                .translationX(getResources().getDimension(R.dimen.side_panel_enter_offset_x))
                .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                .withEndAction(() -> mSidePanel.setVisibility(View.GONE))
                .start();
        mSidePanelList.clearFocus();
    }

    private void onOptionChosen(int type, int index) {
        if (mPlayer == null) {
            return;
        }
        switch (type) {
            case OPTION_CLOSED_CAPTIONS:
                if (index == 0) {
                    disableSubtitles();
                } else {
                    selectTrack(C.TRACK_TYPE_TEXT, index - 1);
                }
                // Only for this title, to resume it with them.
                saveTracks(VodProgress.TRACK_UNCHANGED, index - 1);
                break;
            case OPTION_MULTI_AUDIO:
                selectTrack(C.TRACK_TYPE_AUDIO, index);
                saveTracks(index, VodProgress.TRACK_UNCHANGED);
                break;
            case OPTION_DISPLAY_MODE:
            default:
                mDisplayMode = index;
                layoutSurface();
                break;
        }
        updateOptionCards();
    }

    /** Plays the {@code index}-th supported track of {@code trackType}. */
    private void selectTrack(int trackType, int index) {
        int count = 0;
        for (Tracks.Group group : mPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != trackType) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                if (count++ == index) {
                    mPlayer.setTrackSelectionParameters(
                            mPlayer.getTrackSelectionParameters()
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, false)
                                    .setOverrideForType(
                                            new TrackSelectionOverride(
                                                    group.getMediaTrackGroup(), i))
                                    .build());
                    return;
                }
            }
        }
    }

    private void disableSubtitles() {
        mPlayer.setTrackSelectionParameters(
                mPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build());
        mSubtitlesView.setVisibility(View.GONE);
    }

    /**
     * Keeps the audio or subtitles chosen for the title being played, so it resumes with them.
     * {@link VodProgress#TRACK_UNCHANGED} keeps the other one as it was.
     */
    private void saveTracks(int audio, int subtitles) {
        if (mKeys != null) {
            VodProgress.saveTracks(this, mKeys.get(mPosition), audio, subtitles);
        }
    }

    /**
     * Each title starts with its own default audio and subtitles: the choices made for another
     * title are cleared.
     */
    private void resetTracks() {
        mPlayer.setTrackSelectionParameters(
                mPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .clearOverrides()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build());
    }

    /** Once the tracks of the title are known, the audio and subtitles chosen for it, if any. */
    private void restoreTracks() {
        if (mKeys == null) {
            return;
        }
        int[] tracks = VodProgress.getTracks(this, mKeys.get(mPosition));
        if (tracks[0] >= 0) {
            selectTrack(C.TRACK_TYPE_AUDIO, tracks[0]);
        }
        if (tracks[1] == VodProgress.TRACK_OFF) {
            disableSubtitles();
        } else if (tracks[1] >= 0) {
            selectTrack(C.TRACK_TYPE_TEXT, tracks[1]);
        }
    }

    private void playTitle(int position) {
        if (position < 0 || position >= mUrls.size() || mPlayer == null) {
            return;
        }
        if (mPlayer.getMediaItemCount() > 0) {
            saveProgress();
        }
        mPosition = position;
        mSpeedLevel = 0;
        mHandler.removeCallbacks(mTrickPlayRunnable);
        String name = mNames.get(position);
        mOsdTitleView.setText(getDisplayName());
        mSubtitlesView.setVisibility(View.GONE);
        mPlayer.setSeekParameters(SeekParameters.DEFAULT);
        resetTracks();
        mRestoreTracksPending = true;
        // Where the title was left, if it was.
        long startMs =
                mKeys != null ? VodProgress.getResumePosition(this, mKeys.get(position)) : 0;
        try {
            mPlayer.setMediaItem(MediaItem.fromUri(mUrls.get(position)), startMs);
            mPlayer.prepare();
            // Held on its first picture until the display has switched to the frame rate of
            // the title, so nothing is missed while the screen goes blank.
            mWaitingForOutput = true;
            mPlayer.setPlayWhenReady(false);
            // Without a key the frame rate is not remembered: the URL holds the password.
            mFrameRateMatcher.startStream(
                    mKeys != null ? "vod:" + mKeys.get(position) : null, this::onOutputReady);
        } catch (RuntimeException e) {
            // Not logged with its message: it can hold the URL, with the password of the list.
            Log.w(TAG, "Cannot play " + name + ": " + e.getClass().getSimpleName());
            onPlayError();
            return;
        }
        // The controls appear only with OK; if they are shown (previous or next), they follow.
        if (mOsdView.getVisibility() == View.VISIBLE) {
            updateControls();
            scheduleHideOsd();
        }
    }

    /** The display shows the frame rate of the title (or needs no switch): play. */
    private void onOutputReady() {
        if (mWaitingForOutput && mPlayer != null) {
            mWaitingForOutput = false;
            mPlayer.setPlayWhenReady(true);
        }
    }

    /** The title shown in the controls and in "Continue watching". */
    private String getDisplayName() {
        String name = mNames.get(mPosition);
        return TextUtils.isEmpty(mSeriesName)
                ? name
                : getString(R.string.livetv_vod_title_episode, mSeriesName, name);
    }

    /** Remembers where the title is, for "Continue watching". */
    private void saveProgress() {
        if (mPlayer == null || mKeys == null) {
            return;
        }
        long duration = mPlayer.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) {
            return;
        }
        VodProgress.save(
                this,
                new VodProgress.Entry(
                        mKeys.get(mPosition),
                        mKind,
                        getDisplayName(),
                        mImages != null ? mImages.get(mPosition) : "",
                        mUrls.get(mPosition),
                        Math.max(0, mPlayer.getCurrentPosition()),
                        duration,
                        System.currentTimeMillis()));
    }

    private void togglePlayPause() {
        if (mPlayer == null) {
            return;
        }
        if (mSpeedLevel != 0 || !mPlayer.getPlayWhenReady()) {
            play();
        } else {
            mPlayer.setPlayWhenReady(false);
            saveProgress();
            updateControls();
        }
    }

    /** Plays at normal speed, also after rewinding or fast forwarding. */
    private void play() {
        if (mPlayer == null) {
            return;
        }
        boolean wasTrickPlay = mSpeedLevel != 0;
        mSpeedLevel = 0;
        mWaitingForOutput = false;
        mHandler.removeCallbacks(mTrickPlayRunnable);
        if (wasTrickPlay) {
            mPlayer.setSeekParameters(SeekParameters.DEFAULT);
            mPlayer.seekTo(mTrickPositionMs);
        }
        mPlayer.setPlayWhenReady(true);
        updateControls();
        scheduleHideOsd();
    }

    /** Rewinds or fast forwards at 2X, like time shifting, while the key is held. */
    private void startTrickPlay(boolean rewind) {
        if (mPlayer == null || mPlayer.getDuration() == C.TIME_UNSET) {
            return;
        }
        if (mSpeedLevel == 0) {
            mTrickPositionMs = Math.max(0, mPlayer.getCurrentPosition());
        }
        mSpeedLevel = 1;
        mRewinding = rewind;
        mPlayer.setPlayWhenReady(false);
        mPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        mHandler.removeCallbacks(mTrickPlayRunnable);
        mHandler.postDelayed(mTrickPlayRunnable, TRICK_PLAY_INTERVAL_MS);
        updateControls();
    }

    private void seekBy(long deltaMs) {
        if (mPlayer == null || mPlayer.getDuration() == C.TIME_UNSET) {
            return;
        }
        long position = mPlayer.getCurrentPosition() + deltaMs;
        mPlayer.seekTo(Math.max(0, Math.min(position, mPlayer.getDuration() - 1000)));
        updateControls();
    }

    /**
     * Shows the controls; with {@code focusPlayPause} the play controls row is selected and its
     * play/pause button gets the focus.
     */
    private void showOsd(boolean focusPlayPause) {
        boolean wasVisible = mOsdView.getVisibility() == View.VISIBLE;
        mOsdView.setVisibility(View.VISIBLE);
        updateOptionCards();
        if (focusPlayPause || !wasVisible || mOsdView.findFocus() == null) {
            selectRow(false);
        }
        mHandler.removeCallbacks(mUpdateRunnable);
        mHandler.post(mUpdateRunnable);
        scheduleHideOsd();
    }

    /**
     * Like the menu, only the selected row shows its contents; the other one shows its title.
     *
     * @param options the TV options row, else the play controls row.
     */
    private void selectRow(boolean options) {
        boolean hasOptions = false;
        for (ActionCardView card : mOptionCards) {
            hasOptions |= card.getVisibility() == View.VISIBLE;
        }
        if (options && !hasOptions) {
            return;
        }
        mOptionsRowSelected = options;
        mPlayControlsView.setVisibility(options ? View.GONE : View.VISIBLE);
        mOptionsView.setVisibility(options ? View.VISIBLE : View.GONE);
        setRowTitleSelected(mOsdTitleView, !options);
        setRowTitleSelected(mOptionsTitleView, options);
        if (options) {
            for (ActionCardView card : mOptionCards) {
                if (card.getVisibility() == View.VISIBLE) {
                    card.requestFocus();
                    break;
                }
            }
        } else {
            mPlayPauseButton.requestFocusWithAccessibility();
        }
    }

    private void setRowTitleSelected(TextView title, boolean selected) {
        title.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                selected ? mTitleSizeSelected : mTitleSizeDeselected);
        title.setAlpha(selected ? 1f : mTitleAlphaDeselected);
    }

    /** Hides the controls after a while, unless paused, rewinding or fast forwarding. */
    private void scheduleHideOsd() {
        mHandler.removeCallbacks(mHideOsdRunnable);
        if (mPlayer != null && mPlayer.getPlayWhenReady() && mSpeedLevel == 0) {
            mHandler.postDelayed(mHideOsdRunnable, OSD_HIDE_MS);
        }
    }

    private void hideOsd() {
        mHandler.removeCallbacks(mHideOsdRunnable);
        mHandler.removeCallbacks(mUpdateRunnable);
        mOsdView.setVisibility(View.GONE);
    }

    private void updateControls() {
        if (mPlayer == null) {
            return;
        }
        long duration = mPlayer.getDuration();
        boolean known = duration != C.TIME_UNSET && duration > 0;
        long position = Math.max(0, mPlayer.getCurrentPosition());
        if (known) {
            position = Math.min(position, duration);
            mProgress.setMax(duration);
            mProgress.setProgressRange(0, Math.min(duration, mPlayer.getBufferedPosition()));
            mProgress.setProgress(position);
            int pixel = (int) (position * mTimelineWidth / duration);
            mTimeIndicator.setVisibility(View.VISIBLE);
            mTimeIndicator.setTranslationX(pixel + mTimeIndicatorLeftMargin);
            // The time played, then the length of the title.
            mStartTimeText.setVisibility(View.VISIBLE);
            setTextIfNeeded(mStartTimeText, formatTime(position));
            mEndTimeText.setVisibility(View.VISIBLE);
            setTextIfNeeded(mEndTimeText, formatTime(duration));
        } else {
            mProgress.setProgressRange(0, 0);
            mTimeIndicator.setVisibility(View.GONE);
            mStartTimeText.setVisibility(View.GONE);
            mEndTimeText.setVisibility(View.GONE);
        }
        updateButtons(known);
    }

    private void updateButtons(boolean seekable) {
        boolean paused = !mPlayer.getPlayWhenReady() && mSpeedLevel == 0;
        mPlayPauseButton.setImageResId(paused ? R.drawable.lb_ic_play : R.drawable.lb_ic_pause);
        mRewindButton.setEnabled(seekable);
        mFastForwardButton.setEnabled(seekable);
        mRestartButton.setEnabled(seekable);
        // Previous and next only between the episodes of a season.
        int jumpVisibility = mUrls.size() > 1 ? View.VISIBLE : View.GONE;
        mJumpPreviousButton.setVisibility(jumpVisibility);
        mJumpNextButton.setVisibility(jumpVisibility);
        mJumpPreviousButton.setEnabled(mPosition > 0);
        mJumpNextButton.setEnabled(mPosition < mUrls.size() - 1);
        if (mSpeedLevel == 0) {
            mRewindButton.setLabel(null);
            mFastForwardButton.setLabel(null);
        } else {
            String label = getString(R.string.play_controls_speed, SPEEDS[mSpeedLevel - 1]);
            mRewindButton.setLabel(mRewinding ? label : null);
            mFastForwardButton.setLabel(mRewinding ? null : label);
        }
        updateButtonMargin();
    }

    private void updateButtonMargin() {
        int visible = 4 + (mJumpPreviousButton.getVisibility() == View.VISIBLE ? 2 : 0);
        int margin = visible > NORMAL_WIDTH_MAX_BUTTON_COUNT
                ? mCompactButtonMargin : mNormalButtonMargin;
        for (View button :
                new View[] {
                    mJumpPreviousButton,
                    mRestartButton,
                    mRewindButton,
                    mPlayPauseButton,
                    mFastForwardButton,
                    mJumpNextButton
                }) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) button.getLayoutParams();
            if (params.leftMargin != margin || params.rightMargin != margin) {
                params.setMargins(margin, 0, margin, 0);
                button.setLayoutParams(params);
            }
        }
    }

    private static String formatTime(long ms) {
        return DateUtils.formatElapsedTime(ms / 1000);
    }

    private static void setTextIfNeeded(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isSidePanelShown()) {
            // The side panel has the keys, like in the TV; BACK closes it.
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    hideSidePanel();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean osdVisible = mOsdView.getVisibility() == View.VISIBLE;
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            // Like the buttons: a press jumps, holding plays fast.
            return onSeekKey(event, keyCode == KeyEvent.KEYCODE_MEDIA_REWIND);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePlayPause();
                    showOsd(false);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    play();
                    showOsd(false);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (mPlayer != null) {
                        mSpeedLevel = 0;
                        mHandler.removeCallbacks(mTrickPlayRunnable);
                        mPlayer.setPlayWhenReady(false);
                        saveProgress();
                    }
                    showOsd(false);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    playTitle(mPosition + 1);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    playTitle(mPosition - 1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (osdVisible) {
                        hideOsd();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!osdVisible) {
                        // Jumps without showing the controls, which only OK shows.
                        seekBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -SEEK_STEP_MS : SEEK_STEP_MS);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!osdVisible) {
                        return true;
                    }
                    if (!mOptionsRowSelected) {
                        // To the TV options row, like DOWN in the menu.
                        selectRow(true);
                        scheduleHideOsd();
                        return true;
                    }
                    scheduleHideOsd();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!osdVisible) {
                        return true;
                    }
                    if (mOptionsRowSelected) {
                        selectRow(false);
                    }
                    scheduleHideOsd();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!osdVisible) {
                        showOsd(true);
                        return true;
                    }
                    break;
                default:
                    break;
            }
            if (osdVisible) {
                // Something was pressed: keep the controls a while longer.
                scheduleHideOsd();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP
                && keyCode == KeyEvent.KEYCODE_BACK
                && osdVisible) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        mBufferingView.setVisibility(
                playbackState == Player.STATE_BUFFERING && mSpeedLevel == 0
                        ? View.VISIBLE
                        : View.GONE);
        if (playbackState == Player.STATE_ENDED) {
            // Watched to the end: no more in "Continue watching".
            if (mKeys != null) {
                VodProgress.remove(this, mKeys.get(mPosition));
            }
            if (mPosition < mUrls.size() - 1) {
                playTitle(mPosition + 1);
            } else {
                finish();
            }
            return;
        }
        updateControls();
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        updateControls();
        if (!playWhenReady
                && mSpeedLevel == 0
                && !mWaitingForOutput
                && mOsdView.getVisibility() != View.VISIBLE
                && !isSidePanelShown()) {
            // Paused by something else, such as the headphones: show it.
            showOsd(true);
        }
        scheduleHideOsd();
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (mRestoreTracksPending && !tracks.isEmpty()) {
            mRestoreTracksPending = false;
            restoreTracks();
        }
        if (mOsdView.getVisibility() == View.VISIBLE) {
            updateOptionCards();
        }
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        StringBuilder text = new StringBuilder();
        for (Cue cue : cueGroup.cues) {
            if (cue.text != null) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(cue.text);
            }
        }
        mSubtitlesView.setText(text);
        mSubtitlesView.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        mVideoSize = videoSize;
        layoutSurface();
    }

    /**
     * Sizes the picture for the display mode: whole and centered (normal), stretched to the
     * screen (full), or filling the screen with its sides cut (zoom).
     */
    private void layoutSurface() {
        if (mVideoSize.width == 0 || mVideoSize.height == 0) {
            return;
        }
        View parent = (View) mSurfaceView.getParent();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (parentWidth == 0 || parentHeight == 0) {
            parent.post(this::layoutSurface);
            return;
        }
        int width;
        int height;
        if (mDisplayMode == DisplayMode.MODE_FULL) {
            width = parentWidth;
            height = parentHeight;
        } else {
            float aspect =
                    mVideoSize.width * mVideoSize.pixelWidthHeightRatio / mVideoSize.height;
            width = parentWidth;
            height = Math.round(width / aspect);
            boolean tooTall = height > parentHeight;
            if (mDisplayMode == DisplayMode.MODE_ZOOM ? !tooTall : tooTall) {
                height = parentHeight;
                width = Math.round(height * aspect);
            }
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            mSurfaceView.setLayoutParams(params);
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.w(TAG, "Playback failed: " + error.getErrorCodeName());
        onPlayError();
    }

    private void onPlayError() {
        Toast.makeText(this, R.string.livetv_vod_play_error, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Match framerate asks the display for modes through this window while it plays.
        DisplayModeRequester.setWindow(getWindow());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the player (HOME, another app) ends the playback, remembering where it was.
        saveProgress();
        releasePlayer();
        if (mFrameRateMatcher != null) {
            mFrameRateMatcher.reset();
        }
        DisplayModeRequester.clearWindow(getWindow());
        finish();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        mHandler.removeCallbacksAndMessages(null);
        if (mPlayer != null) {
            mPlayer.removeListener(this);
            mPlayer.release();
            mPlayer = null;
        }
    }

    /** The choices of an option, as radio buttons like the side panel of the TV. */
    private class OptionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final int mType;
        private final List<String> mLabels;
        private int mChecked;

        OptionAdapter(int type, List<String> labels, int checked) {
            mType = type;
            mLabels = labels;
            mChecked = checked;
        }

        @Override
        public int getItemCount() {
            return mLabels.size();
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
                        if (position == RecyclerView.NO_POSITION) {
                            return;
                        }
                        int old = mChecked;
                        mChecked = position;
                        notifyItemChanged(old);
                        notifyItemChanged(position);
                        onOptionChosen(mType, position);
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView.findViewById(R.id.title)).setText(mLabels.get(position));
            holder.itemView.findViewById(R.id.description).setVisibility(View.GONE);
            ((RadioButton) holder.itemView.findViewById(R.id.radio_button))
                    .setChecked(position == mChecked);
        }
    }
}
