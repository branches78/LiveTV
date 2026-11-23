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

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.android.tv.R;
import com.android.tv.util.images.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The movies or the series of the IPTV lists: the categories in a side panel like the one of the
 * program guide, the titles of the focused category in a grid. A series shows its seasons in the
 * side panel and its episodes in the grid.
 *
 * <p>As in the guide, the side panel shows only its edge while the grid has the focus: LEFT from
 * the first column opens it, RIGHT (or BACK) closes it.
 */
public class VodActivity extends Activity {
    /** {@link VodLibrary#KIND_MOVIES} or {@link VodLibrary#KIND_SERIES}. */
    public static final String EXTRA_KIND = "kind";

    /** How long the focus stays on a category before its titles are shown. */
    private static final long SIDE_ITEM_SETTLE_MS = 250;

    private int mKind;
    private View mSidePanel;
    private VerticalGridView mCategoryView;
    private View mContentView;
    private VerticalGridView mGridView;
    private TextView mTitleView;
    private View mProgressView;
    private TextView mMessageView;
    private final SideAdapter mSideAdapter = new SideAdapter();
    private final TitleAdapter mTitleAdapter = new TitleAdapter();

    private boolean mSidePanelOpen;
    @Nullable private Animator mSidePanelAnimator;
    private int mSidePanelShift;
    private int mColumns = 1;

    @Nullable private VodLibrary.Catalog mCatalog;
    /** "Continue watching", if some title was left before its end, then the catalog's. */
    private final List<VodLibrary.Category> mCategories = new ArrayList<>();
    private int mCategoryPosition;
    /** The series whose episodes are shown, or null while the catalog is shown. */
    @Nullable private VodLibrary.Title mSeries;
    @Nullable private VodLibrary.Episodes mEpisodes;
    private int mSeasonPosition;
    /** Where the series was in the grid, to go back to it. */
    private int mSeriesGridPosition;
    /** Number of the episode loads, so a late answer for a series left is ignored. */
    private int mEpisodesRequest;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Animator mContentFadeOut;
    private Animator mContentFadeIn;
    private long mFadeDurationMs;
    /** The category (or season) focused last, shown once the titles have faded out. */
    private int mRequestedSideItem = -1;
    /**
     * Shows the category focused last once the focus stays on it: the titles are rebuilt only
     * then, not for every category passed while scrolling.
     */
    private final Runnable mApplySideItemRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (mContentFadeOut.isRunning()) {
                        mHandler.postDelayed(this, SIDE_ITEM_SETTLE_MS / 2);
                        return;
                    }
                    applySideItemChange();
                    mContentFadeIn.start();
                }
            };
    /** Where each title was left, to show it on its card. */
    private Map<String, VodProgress.Entry> mProgress = Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_vod);
        mKind = getIntent().getIntExtra(EXTRA_KIND, VodLibrary.KIND_MOVIES);
        mSidePanel = findViewById(R.id.livetv_vod_side_panel);
        mCategoryView = findViewById(R.id.livetv_vod_categories);
        mContentView = findViewById(R.id.livetv_vod_content);
        mGridView = findViewById(R.id.livetv_vod_grid);
        mTitleView = findViewById(R.id.livetv_vod_title);
        mProgressView = findViewById(R.id.livetv_vod_progress);
        mMessageView = findViewById(R.id.livetv_vod_message);
        mSidePanelShift =
                getResources().getDimensionPixelOffset(R.dimen.program_guide_shift_start_to_end_x);

        // Like the side panel of the program guide.
        mCategoryView.setAdapter(mSideAdapter);
        mCategoryView.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);
        mCategoryView.setWindowAlignmentOffset(
                getResources().getDimensionPixelOffset(R.dimen.program_guide_side_panel_alignment_y));
        mCategoryView.setWindowAlignmentOffsetPercent(
                VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);

        int spacing = getResources().getDimensionPixelSize(R.dimen.livetv_vod_grid_spacing);
        mGridView.setHorizontalSpacing(spacing);
        mGridView.setVerticalSpacing(spacing);
        mGridView.setAdapter(mTitleAdapter);

        mFadeDurationMs = getResources().getInteger(R.integer.program_guide_table_fade_anim_duration);
        mContentFadeOut =
                AnimatorInflater.loadAnimator(this, R.animator.program_guide_table_fade_out);
        // No hardware layer, unlike the guide's table: building one of a grid full of posters
        // stalls the screen while the side panel scrolls.
        mContentFadeOut.setTarget(mContentView);
        mContentFadeIn = AnimatorInflater.loadAnimator(this, R.animator.program_guide_table_fade_in);
        mContentFadeIn.setTarget(mContentView);

        // Closed, the side panel cannot take the focus, so it only opens with LEFT; it closes when
        // the grid gets the focus back. Like the guide, it opens once the titles are read
        // (openSidePanel in loadCatalog).
        setSidePanelFocusable(false);
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .addOnGlobalFocusChangeListener(
                        (oldFocus, newFocus) -> {
                            if (newFocus != null && isInside(newFocus, mGridView)) {
                                setSidePanelOpen(false);
                            }
                        });

        loadCatalog();
    }

    @Override
    protected void onDestroy() {
        // The category chosen last is not shown on a closed screen.
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the player: the titles left before their end may have changed.
        mProgress = VodProgress.getEntryMap(this);
        if (mCatalog == null) {
            return;
        }
        if (mSeries != null) {
            mTitleAdapter.notifyDataSetChanged();
            return;
        }
        String selectedKey = mCategories.get(mCategoryPosition).key;
        int gridPosition = mGridView.getSelectedPosition();
        boolean gridFocused = mGridView.hasFocus();
        buildCategories();
        int position = findCategory(selectedKey);
        if (VodLibrary.CATEGORY_CONTINUE.equals(selectedKey) || position < 0) {
            // The order of "Continue watching" changes: start from its first title again.
            gridPosition = 0;
        }
        showCatalog(Math.max(0, position), gridPosition);
        if (gridFocused && mTitleAdapter.getItemCount() > 0) {
            mGridView.requestFocus();
        }
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

    private void loadCatalog() {
        showProgress();
        VodLibrary.loadCatalog(
                this,
                mKind,
                catalog -> {
                    if (isDestroyed()) {
                        return;
                    }
                    mProgressView.setVisibility(View.GONE);
                    if (catalog == null) {
                        showMessage(getString(R.string.livetv_vod_error));
                        return;
                    }
                    mCatalog = catalog;
                    buildCategories();
                    showCatalog(0, 0);
                    // Like the guide: it opens with the side panel open on the first category.
                    openSidePanel();
                });
    }

    private void buildCategories() {
        mCategories.clear();
        if (!VodProgress.getEntries(this, mKind).isEmpty()) {
            mCategories.add(
                    new VodLibrary.Category(
                            VodLibrary.CATEGORY_CONTINUE, getString(R.string.livetv_vod_continue)));
        }
        mCategories.addAll(mCatalog.categories);
    }

    private int findCategory(String key) {
        for (int i = 0; i < mCategories.size(); i++) {
            if (mCategories.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The grid has the focus, with the side panel closed, unless it has no titles. */
    private void focusGridOrSidePanel() {
        if (mTitleAdapter.getItemCount() > 0) {
            setSidePanelOpen(false);
            mGridView.requestFocus();
            // The cards may not be laid out yet.
            mGridView.post(
                    () -> {
                        if (!mSidePanelOpen && !mGridView.hasFocus()) {
                            mGridView.requestFocus();
                        }
                    });
        } else {
            openSidePanel();
        }
    }

    private void setSidePanelFocusable(boolean focusable) {
        ((ViewGroup) mSidePanel)
                .setDescendantFocusability(
                        focusable
                                ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                                : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** Shows the categories in the side panel and the titles of one of them in the grid. */
    private void showCatalog(int categoryPosition, int gridPosition) {
        mSeries = null;
        mEpisodes = null;
        List<String> names = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        for (VodLibrary.Category category : mCategories) {
            names.add(category.name);
            icons.add(getCategoryIcon(category));
        }
        mSideAdapter.setItems(names, icons);
        mCategoryView.setSelectedPosition(categoryPosition);
        showCategory(categoryPosition);
        if (gridPosition < mTitleAdapter.getItemCount()) {
            mGridView.setSelectedPosition(gridPosition);
        }
    }

    private void showCategory(int position) {
        mCategoryPosition = position;
        mRequestedSideItem = position;
        VodLibrary.Category category = mCategories.get(position);
        String kindName =
                getString(
                        mKind == VodLibrary.KIND_SERIES
                                ? R.string.livetv_vod_series
                                : R.string.livetv_vod_movies);
        // "Movies" (or "Series") for all of them, else only the name of the category.
        mTitleView.setText(
                VodLibrary.CATEGORY_ALL.equals(category.key) ? kindName : category.name);
        showTitles(
                VodLibrary.CATEGORY_CONTINUE.equals(category.key)
                        ? VodLibrary.getContinueTitles(this, mKind)
                        : mCatalog.getTitles(category.key),
                false);
    }

    private void showTitles(List<VodLibrary.Title> titles, boolean episodes) {
        mTitleAdapter.setTitles(titles, episodes);
        updateColumns();
        mGridView.setSelectedPosition(0);
        if (titles.isEmpty()) {
            showMessage(getString(R.string.livetv_vod_empty));
        } else {
            mMessageView.setVisibility(View.GONE);
        }
    }

    /**
     * As many columns as fit, for posters or for the wider pictures of episodes. Counted from the
     * screen, so it does not depend on whether the grid was laid out yet.
     */
    private void updateColumns() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width =
                screenWidth
                        - getResources().getDimensionPixelSize(R.dimen.livetv_vod_grid_margin_start)
                        - getResources().getDimensionPixelSize(R.dimen.livetv_vod_grid_padding_start)
                        - getResources().getDimensionPixelSize(R.dimen.livetv_vod_grid_margin_end);
        int cardWidth =
                getResources()
                        .getDimensionPixelSize(
                                mTitleAdapter.mEpisodes
                                        ? R.dimen.livetv_vod_still_width
                                        : R.dimen.livetv_vod_poster_width);
        int spacing = getResources().getDimensionPixelSize(R.dimen.livetv_vod_grid_spacing);
        int columns = Math.max(1, (width + spacing) / (cardWidth + spacing));
        if (columns != mColumns) {
            mColumns = columns;
            mGridView.setNumColumns(columns);
            mGridView.requestLayout();
        }
    }

    /** Opens or closes the side panel, with the animation of the program guide. */
    private void setSidePanelOpen(boolean open) {
        if (mSidePanelOpen == open) {
            return;
        }
        mSidePanelOpen = open;
        setSidePanelFocusable(open);
        if (mSidePanelAnimator != null) {
            mSidePanelAnimator.cancel();
        }
        float shift = open ? mSidePanelShift : 0;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mSidePanel, View.TRANSLATION_X, shift),
                ObjectAnimator.ofFloat(mCategoryView, View.ALPHA, open ? 1f : 0f),
                ObjectAnimator.ofFloat(mContentView, View.TRANSLATION_X, shift));
        set.setDuration(getResources().getInteger(R.integer.program_guide_anim_duration));
        set.setInterpolator(
                AnimationUtils.loadInterpolator(
                        this,
                        open
                                ? android.R.interpolator.fast_out_linear_in
                                : android.R.interpolator.linear_out_slow_in));
        mSidePanelAnimator = set;
        set.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    && focus != null
                    && isInside(focus, mGridView)
                    && mGridView.getSelectedPosition() % mColumns == 0) {
                // From the first column, LEFT opens the side panel on the shown category.
                openSidePanel();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                    && focus != null
                    && isInside(focus, mSidePanel)) {
                if (mTitleAdapter.getItemCount() > 0) {
                    mGridView.requestFocus();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openSidePanel() {
        setSidePanelOpen(true);
        int position = mSeries != null ? mSeasonPosition : mCategoryPosition;
        mCategoryView.setSelectedPosition(position);
        RecyclerView.ViewHolder holder = mCategoryView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
        } else {
            mCategoryView.requestFocus();
        }
    }

    private void onTitleClicked(int position) {
        VodLibrary.Title title = mTitleAdapter.mTitles.get(position);
        if (title.isSeries()) {
            openSeries(title, position);
            return;
        }
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> images = new ArrayList<>();
        Intent intent =
                new Intent(this, VodPlayerActivity.class)
                        .putExtra(VodPlayerActivity.EXTRA_KIND, mKind);
        if (mSeries != null) {
            // The episodes of the season, so the player can go to the previous and next ones.
            for (VodLibrary.Title episode : mTitleAdapter.mTitles) {
                names.add(getEpisodeName(episode));
                urls.add(episode.streamUrl);
                keys.add(episode.key);
                images.add(episode.imageUrl);
            }
            intent.putExtra(VodPlayerActivity.EXTRA_SERIES_NAME, mSeries.name)
                    .putExtra(VodPlayerActivity.EXTRA_POSITION, position);
        } else {
            names.add(title.name);
            urls.add(title.streamUrl);
            keys.add(title.key);
            images.add(title.imageUrl);
        }
        intent.putStringArrayListExtra(VodPlayerActivity.EXTRA_NAMES, names)
                .putStringArrayListExtra(VodPlayerActivity.EXTRA_URLS, urls)
                .putStringArrayListExtra(VodPlayerActivity.EXTRA_KEYS, keys)
                .putStringArrayListExtra(VodPlayerActivity.EXTRA_IMAGES, images);
        startActivity(intent);
    }

    private void openSeries(VodLibrary.Title series, int gridPosition) {
        mSeries = series;
        mSeriesGridPosition = gridPosition;
        mTitleView.setText(series.name);
        mSideAdapter.setItems(new ArrayList<>(), new ArrayList<>());
        mTitleAdapter.setTitles(new ArrayList<>(), true);
        updateColumns();
        showProgress();
        int request = ++mEpisodesRequest;
        VodLibrary.loadEpisodes(
                series,
                episodes -> {
                    if (isDestroyed() || request != mEpisodesRequest || mSeries != series) {
                        return;
                    }
                    mProgressView.setVisibility(View.GONE);
                    if (episodes == null || episodes.episodes.isEmpty()) {
                        showMessage(
                                getString(
                                        episodes == null
                                                ? R.string.livetv_vod_error
                                                : R.string.livetv_vod_no_episodes));
                        return;
                    }
                    mEpisodes = episodes;
                    List<String> seasons = new ArrayList<>();
                    List<Integer> icons = new ArrayList<>();
                    for (int season : episodes.seasons) {
                        seasons.add(getString(R.string.livetv_vod_season, season));
                        icons.add(R.drawable.ic_livetv_vod_series);
                    }
                    mSideAdapter.setItems(seasons, icons);
                    mSeasonPosition = 0;
                    mCategoryView.setSelectedPosition(0);
                    showSeason(0);
                    focusGridOrSidePanel();
                });
    }

    private void showSeason(int position) {
        mSeasonPosition = position;
        mRequestedSideItem = position;
        showTitles(mEpisodes.getEpisodes(mEpisodes.seasons.get(position)), true);
    }

    /** The name of an episode without the name of its series, often repeated before it. */
    private String getEpisodeName(VodLibrary.Title episode) {
        String name = episode.name;
        if (mSeries != null) {
            String series = mSeries.name.replaceAll("\\s*\\(\\d{4}\\)$", "");
            for (String prefix : new String[] {mSeries.name, series}) {
                if (name.startsWith(prefix + " - ")) {
                    return name.substring(prefix.length() + 3);
                }
            }
        }
        return name;
    }

    private void onSideItemFocused(int position) {
        if (mSeries != null ? mEpisodes != null : mCatalog != null) {
            requestSideItemChange(position);
        }
    }

    /**
     * Like a genre change in the guide: the titles fade out, change, then fade in. Moving quickly
     * through the categories keeps them faded until the focus stops, so the grid is not rebuilt
     * for every category passed.
     */
    private void requestSideItemChange(int position) {
        if (mRequestedSideItem == position) {
            return;
        }
        mRequestedSideItem = position;
        mHandler.removeCallbacks(mApplySideItemRunnable);
        if (mContentFadeIn.isStarted()) {
            mContentFadeIn.cancel();
        }
        if (!mContentFadeOut.isStarted() && mContentView.getAlpha() > 0) {
            mContentFadeOut.start();
        }
        mHandler.postDelayed(
                mApplySideItemRunnable, Math.max(SIDE_ITEM_SETTLE_MS, mFadeDurationMs));
    }

    private void applySideItemChange() {
        int position = mRequestedSideItem;
        if (mSeries != null) {
            if (mEpisodes != null
                    && position >= 0
                    && position < mEpisodes.seasons.size()
                    && position != mSeasonPosition) {
                showSeason(position);
            }
        } else if (mCatalog != null
                && position >= 0
                && position < mCategories.size()
                && position != mCategoryPosition) {
            showCategory(position);
        }
    }

    @Override
    public void onBackPressed() {
        // Like the guide, BACK closes the screen; from the episodes of a series, it goes back to
        // the series first.
        if (mSeries != null && mCatalog != null) {
            // Back to the series of the category.
            mEpisodesRequest++;
            mProgressView.setVisibility(View.GONE);
            showCatalog(mCategoryPosition, mSeriesGridPosition);
            focusGridOrSidePanel();
            return;
        }
        super.onBackPressed();
    }

    private void showProgress() {
        mMessageView.setVisibility(View.GONE);
        mProgressView.setVisibility(View.VISIBLE);
    }

    private void showMessage(String message) {
        mMessageView.setText(message);
        mMessageView.setVisibility(View.VISIBLE);
    }

    /** Words of category names, and the genre icon of the guide (or of LiveTV) they get. */
    private static final Object[][] CATEGORY_ICONS = {
        {new String[] {"anim", "infantil", "kids", "nin", "famil", "child"},
            R.drawable.ic_livetv_genre_family_kids},
        {new String[] {"comed", "humor"}, R.drawable.ic_livetv_genre_comedy},
        {new String[] {"terror", "horror", "miedo", "suspens", "thriller"},
            R.drawable.ic_livetv_vod_horror},
        {new String[] {"accion", "action", "belic", "guerra", "war"},
            R.drawable.ic_livetv_vod_action},
        {new String[] {"ciencia", "sci", "ficcion", "fantas"},
            R.drawable.ic_livetv_genre_tech_science},
        {new String[] {"crimen", "crime", "polic", "misterio", "mystery"},
            R.drawable.ic_livetv_vod_crime},
        {new String[] {"aventur", "adventure", "viaje", "travel"},
            R.drawable.ic_livetv_genre_travel},
        {new String[] {"document", "historia", "history", "educa"},
            R.drawable.ic_livetv_genre_education},
        {new String[] {"music", "concierto"}, R.drawable.ic_livetv_genre_music},
        {new String[] {"deport", "sport"}, R.drawable.ic_livetv_genre_sports},
        {new String[] {"drama", "roman"}, R.drawable.ic_livetv_genre_drama},
        {new String[] {"estreno", "premier", "nuev"}, R.drawable.ic_livetv_genre_premier},
    };

    /** The icon shown left of a category, like the genres of the guide. */
    private int getCategoryIcon(VodLibrary.Category category) {
        if (VodLibrary.CATEGORY_CONTINUE.equals(category.key)) {
            return R.drawable.ic_livetv_vod_continue;
        }
        int kindIcon =
                mKind == VodLibrary.KIND_SERIES
                        ? R.drawable.ic_livetv_vod_series
                        : R.drawable.ic_livetv_vod_movies;
        if (VodLibrary.CATEGORY_ALL.equals(category.key)) {
            return kindIcon;
        }
        String name = VodLibrary.normalize(category.name);
        for (Object[] entry : CATEGORY_ICONS) {
            for (String word : (String[]) entry[0]) {
                for (String nameWord : name.split("[^a-z0-9]+")) {
                    if (nameWord.startsWith(word)) {
                        return (Integer) entry[1];
                    }
                }
            }
        }
        return kindIcon;
    }

    /** The categories, or the seasons of a series. Each row is like a genre of the guide. */
    private class SideAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<String> mItems = new ArrayList<>();
        private List<Integer> mIcons = new ArrayList<>();

        void setItems(List<String> items, List<Integer> icons) {
            mItems = items;
            mIcons = icons;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.program_guide_side_panel_row, parent, false);
            view.addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            v.getStateListAnimator().jumpToCurrentState();
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {}
                    });
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {};
            view.setOnFocusChangeListener(
                    (v, hasFocus) -> {
                        int position = holder.getAdapterPosition();
                        if (hasFocus && position != RecyclerView.NO_POSITION) {
                            onSideItemFocused(position);
                        }
                    });
            // OK goes to the titles.
            view.setOnClickListener(
                    v -> {
                        if (mTitleAdapter.getItemCount() > 0) {
                            mGridView.requestFocus();
                        }
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextView textView = (TextView) holder.itemView;
            textView.setText(mItems.get(position));
            setIcon(textView, mIcons.get(position));
        }
    }

    /** Like the side panel of the guide: an icon left of the label, a bit taller than the text. */
    private static void setIcon(TextView textView, int iconResId) {
        Drawable icon = new TextColorIcon(textView.getContext().getDrawable(iconResId), textView);
        int size = Math.round(textView.getTextSize() * 1.2f);
        icon.setBounds(0, 0, size, size);
        textView.setCompoundDrawablesRelative(icon, null, null, null);
        textView.setCompoundDrawablePadding(size / 3);
    }

    /** An icon drawn in the current text color of its row, which an animator changes on focus. */
    private static final class TextColorIcon extends DrawableWrapper {
        private final TextView mTextView;
        private int mColor;

        TextColorIcon(Drawable icon, TextView textView) {
            super(icon.mutate());
            mTextView = textView;
        }

        @Override
        public void draw(Canvas canvas) {
            int color = mTextView.getCurrentTextColor();
            if (color != mColor) {
                mColor = color;
                getDrawable().setTint(color);
            }
            super.draw(canvas);
        }
    }

    /** The titles of the grid. */
    private class TitleAdapter extends RecyclerView.Adapter<CardHolder> {
        private List<VodLibrary.Title> mTitles = new ArrayList<>();
        private boolean mEpisodes;

        void setTitles(List<VodLibrary.Title> titles, boolean episodes) {
            mTitles = titles;
            mEpisodes = episodes;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mTitles.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mEpisodes ? 1 : 0;
        }

        @Override
        public CardHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.livetv_vod_card, parent, false);
            if (viewType == 1) {
                int width = getResources().getDimensionPixelSize(R.dimen.livetv_vod_still_width);
                View frame = view.findViewById(R.id.livetv_vod_card_frame);
                ViewGroup.LayoutParams params = frame.getLayoutParams();
                params.width = width;
                params.height =
                        getResources().getDimensionPixelSize(R.dimen.livetv_vod_still_height);
                frame.setLayoutParams(params);
                View name = view.findViewById(R.id.livetv_vod_card_name);
                ViewGroup.LayoutParams nameParams = name.getLayoutParams();
                nameParams.width = width;
                name.setLayoutParams(nameParams);
            }
            CardHolder holder = new CardHolder(view);
            view.setOnClickListener(
                    v -> {
                        int position = holder.getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            onTitleClicked(position);
                        }
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(CardHolder holder, int position) {
            VodLibrary.Title title = mTitles.get(position);
            String name = mEpisodes ? getEpisodeName(title) : title.name;
            VodProgress.Entry progress = mProgress.get(title.key);
            holder.bind(name, title.imageUrl, progress != null ? progress.getFraction() : -1);
        }
    }

    private static class CardHolder extends RecyclerView.ViewHolder {
        private final ImageView mImageView;
        private final TextView mPlaceholderView;
        private final TextView mNameView;
        private final ProgressBar mProgressBar;
        private final int mImageWidth;
        private final int mImageHeight;
        private String mImageUrl;

        CardHolder(View view) {
            super(view);
            View frame = view.findViewById(R.id.livetv_vod_card_frame);
            frame.setClipToOutline(true);
            mImageView = view.findViewById(R.id.livetv_vod_card_image);
            mPlaceholderView = view.findViewById(R.id.livetv_vod_card_placeholder);
            mNameView = view.findViewById(R.id.livetv_vod_card_name);
            mProgressBar = view.findViewById(R.id.livetv_vod_card_progress);
            mImageWidth = frame.getLayoutParams().width;
            mImageHeight = frame.getLayoutParams().height;
        }

        /** @param watched how much was watched, from 0 to 1, or a negative number for nothing. */
        void bind(String name, String imageUrl, float watched) {
            mNameView.setText(name);
            mPlaceholderView.setText(name);
            mPlaceholderView.setVisibility(View.VISIBLE);
            if (watched >= 0) {
                mProgressBar.setProgress(Math.round(watched * mProgressBar.getMax()));
                mProgressBar.setVisibility(View.VISIBLE);
            } else {
                mProgressBar.setVisibility(View.GONE);
            }
            mImageView.setImageDrawable(null);
            mImageUrl = imageUrl;
            if (TextUtils.isEmpty(imageUrl)
                    || !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                return;
            }
            ImageLoader.loadBitmap(
                    itemView.getContext(),
                    imageUrl,
                    mImageWidth,
                    mImageHeight,
                    new ImageLoader.ImageLoaderCallback<CardHolder>(this) {
                        @Override
                        public void onBitmapLoaded(CardHolder holder, @Nullable Bitmap bitmap) {
                            // The card may show another title by now.
                            if (bitmap != null && TextUtils.equals(holder.mImageUrl, imageUrl)) {
                                holder.mImageView.setImageBitmap(bitmap);
                                holder.mPlaceholderView.setVisibility(View.INVISIBLE);
                            }
                        }
                    });
        }
    }
}
