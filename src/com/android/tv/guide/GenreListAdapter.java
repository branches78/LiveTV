/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.guide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.support.annotation.MainThread;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.android.tv.R;
import com.android.tv.data.GenreItems;
import java.util.List;

/**
 * Adapts the genre items obtained from {@link GenreItems} to the program guide side panel. LiveTV:
 * a "Search" item comes first, above "All channels".
 */
class GenreListAdapter extends RecyclerView.Adapter<GenreListAdapter.GenreRowHolder> {
    private static final String TAG = "GenreListAdapter";
    private static final boolean DEBUG = false;
    /** LiveTV: genre ID of the "Search" item, which opens the channel search. */
    private static final int GENRE_ID_SEARCH = -1;
    /** LiveTV: number of items before the genres. */
    static final int ITEMS_BEFORE_GENRES = 1;
    /** LiveTV: icons of the genres, in the order of {@link GenreItems} ids. */
    private static final int[] GENRE_ICONS = {
        R.drawable.ic_livetv_all_channels,
        R.drawable.ic_livetv_genre_family_kids,
        R.drawable.ic_livetv_genre_sports,
        R.drawable.ic_livetv_genre_shopping,
        R.drawable.ic_livetv_genre_movies,
        R.drawable.ic_livetv_genre_comedy,
        R.drawable.ic_livetv_genre_travel,
        R.drawable.ic_livetv_genre_drama,
        R.drawable.ic_livetv_genre_education,
        R.drawable.ic_livetv_genre_animal_wildlife,
        R.drawable.ic_livetv_genre_news,
        R.drawable.ic_livetv_genre_gaming,
        R.drawable.ic_livetv_genre_arts,
        R.drawable.ic_livetv_genre_entertainment,
        R.drawable.ic_livetv_genre_life_style,
        R.drawable.ic_livetv_genre_music,
        R.drawable.ic_livetv_genre_premier,
        R.drawable.ic_livetv_genre_tech_science,
    };

    private final Context mContext;
    private final ProgramManager mProgramManager;
    private final ProgramGuide mProgramGuide;
    private String[] mGenreLabels;

    GenreListAdapter(Context context, ProgramManager programManager, ProgramGuide guide) {
        mContext = context;
        mProgramManager = programManager;
        mProgramManager.addListener(
                new ProgramManager.ListenerAdapter() {
                    @Override
                    public void onGenresUpdated() {
                        mGenreLabels = GenreItems.getLabels(mContext);
                        notifyDataSetChanged();
                    }
                });
        mProgramGuide = guide;
    }

    @Override
    public int getItemCount() {
        List<Integer> filteredGenreIds = mProgramManager.getFilteredGenreIds();
        if (filteredGenreIds == null) {
            // Genre item would be available after program manager builds genre filter.
            return 0;
        }
        return filteredGenreIds.size() + ITEMS_BEFORE_GENRES;
    }

    @Override
    public int getItemViewType(int position) {
        return R.layout.program_guide_side_panel_row;
    }

    @Override
    public void onBindViewHolder(GenreRowHolder holder, int position) {
        if (position == 0) {
            holder.onBindSearch(mContext.getString(R.string.livetv_search));
            return;
        }
        List<Integer> filteredGenreIds = mProgramManager.getFilteredGenreIds();
        int genreId = filteredGenreIds.get(position - ITEMS_BEFORE_GENRES);
        holder.onBind(genreId, mGenreLabels[genreId]);
    }

    @Override
    public GenreRowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
        itemView.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        // Animation is not meaningful now, skip it.
                        view.getStateListAnimator().jumpToCurrentState();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        // Do nothing
                    }
                });
        return new GenreRowHolder(itemView, mProgramGuide);
    }

    static class GenreRowHolder extends RecyclerView.ViewHolder
            implements View.OnFocusChangeListener {
        private final ProgramGuide mProgramGuide;
        private int mGenreId;

        @MainThread
        GenreRowHolder(View itemView, ProgramGuide programGuide) {
            super(itemView);
            mProgramGuide = programGuide;
        }

        public void onBind(int genreId, String genreLabel) {
            mGenreId = genreId;

            TextView textView = (TextView) itemView;
            textView.setText(genreLabel);
            // LiveTV: an icon for "All channels" and for each genre.
            setIcon(
                    textView,
                    genreId >= 0 && genreId < GENRE_ICONS.length ? GENRE_ICONS[genreId] : 0);
            itemView.setOnClickListener(null);

            itemView.setOnFocusChangeListener(this);
        }

        /** LiveTV: the "Search" item opens the channel search when clicked, not when focused. */
        void onBindSearch(String label) {
            mGenreId = GENRE_ID_SEARCH;

            TextView textView = (TextView) itemView;
            textView.setText(label);
            setIcon(textView, R.drawable.ic_livetv_search);
            itemView.setOnClickListener(v -> mProgramGuide.showChannelSearch());

            itemView.setOnFocusChangeListener(this);
        }

        /** LiveTV: shows an icon left of the label, a bit taller than the text; 0 for none. */
        private static void setIcon(TextView textView, int iconResId) {
            if (iconResId == 0) {
                textView.setCompoundDrawablesRelative(null, null, null, null);
                return;
            }
            Drawable icon =
                    new TextColorIcon(textView.getContext().getDrawable(iconResId), textView);
            int size = Math.round(textView.getTextSize() * 1.2f);
            icon.setBounds(0, 0, size, size);
            textView.setCompoundDrawablesRelative(icon, null, null, null);
            textView.setCompoundDrawablePadding(size / 3);
        }

        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            if (hasFocus && mGenreId != GENRE_ID_SEARCH) {
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "onFocusChanged "
                                    + ((TextView) view).getText()
                                    + "("
                                    + mGenreId
                                    + ") hasFocus");
                }
                mProgramGuide.requestGenreChange(mGenreId);
            }
        }
    }

    /**
     * LiveTV: an icon drawn in the current text color of its row, which an animator changes when
     * the row gains or loses the focus.
     */
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
}
