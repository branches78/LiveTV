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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.android.tv.R;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Assign channels" of an IPTV list: shows from which guide channel each channel takes its programs
 * (found automatically, chosen by hand, or none), and opens {@link IptvGuidePickerActivity} to
 * choose another. The changes apply when the screen closes, with the guides kept in the cache.
 */
public class IptvGuideAssignActivity extends Activity {
    static final String EXTRA_PLAYLIST_ID = "playlist_id";
    static final String EXTRA_TITLE = "title";
    private static final int REQUEST_CODE_PICK = 1;
    private static final int TAG_COLOR = 0x80FFFFFF;
    private static final int MANUAL_TAG_COLOR = 0xFF7FD3FF;

    private long mPlaylistId;
    private TextView mDescriptionView;
    private VerticalGridView mListView;
    private final RowsAdapter mAdapter = new RowsAdapter();
    private IptvGuide.GuideInfo mGuideInfo = new IptvGuide.GuideInfo();
    /** The channels of every list's guides, reference -> name: assignments can use any. */
    private Map<String, String> mAllGuideChannels = new HashMap<>();
    /** Whether an assignment changed, so the guide is applied again on leaving. */
    private boolean mChanged;
    /** The row that opened the picker, focused again on return. */
    private int mPickedPosition;
    /** Whether the guides were read because their channels were not saved yet. */
    private boolean mGuideRead;
    /** The lists whose guide must be read before their channels can be shown. */
    private List<Long> mMissingGuides = new ArrayList<>();

    /** Starts the screen for a list. */
    static void start(Context context, long playlistId, String title) {
        context.startActivity(
                new Intent(context, IptvGuideAssignActivity.class)
                        .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                        .putExtra(EXTRA_TITLE, title));
    }

    /** A channel of the list. */
    private static final class Row {
        final String name;
        final String key;

        Row(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_iptv_assign);
        mPlaylistId = getIntent().getLongExtra(EXTRA_PLAYLIST_ID, -1);
        ((TextView) findViewById(R.id.livetv_assign_title))
                .setText(
                        getString(
                                R.string.livetv_assign_title,
                                getIntent().getStringExtra(EXTRA_TITLE)));
        mDescriptionView = findViewById(R.id.livetv_assign_description);
        mDescriptionView.setText(R.string.livetv_assign_loading);
        mListView = findViewById(R.id.livetv_assign_list);
        mListView.setAdapter(mAdapter);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing() && mChanged) {
            // Applied in the background, after this screen closes.
            IptvGuide.reapply(this, mPlaylistId, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK && resultCode == RESULT_OK) {
            mChanged = true;
            mAdapter.notifyItemChanged(mPickedPosition);
            mListView.setSelectedPosition(mPickedPosition);
        }
    }

    private void load() {
        new Thread(
                        () -> {
                            List<Row> rows = readRows();
                            IptvGuide.GuideInfo info = IptvGuide.readGuideInfo(this, mPlaylistId);
                            Map<String, String> all =
                                    IptvGuide.readAllGuideChannels(this, mPlaylistId);
                            List<Long> missing =
                                    IptvGuide.getPlaylistsWithoutGuideInfo(this, mPlaylistId);
                            runOnUiThread(
                                    () -> {
                                        mMissingGuides = missing;
                                        show(rows, info, all);
                                    });
                        },
                        "IptvGuideAssign")
                .start();
    }

    private List<Row> readRows() {
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                TvContract.buildChannelsUriForInput(
                                        IptvChannels.getInputId(this)),
                                new String[] {
                                    Channels.COLUMN_DISPLAY_NAME,
                                    Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                                    Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                                    Channels.COLUMN_DISPLAY_NUMBER
                                },
                                null,
                                null,
                                null)) {
            List<Object[]> found = new ArrayList<>();
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.isNull(1) || cursor.getLong(1) != mPlaylistId || cursor.isNull(2)) {
                    continue;
                }
                int number;
                try {
                    number = Integer.parseInt(cursor.getString(3));
                } catch (NumberFormatException e) {
                    number = Integer.MAX_VALUE;
                }
                found.add(
                        new Object[] {
                            number,
                            new Row(
                                    cursor.getString(0),
                                    IptvGuideAssignments.channelKey(
                                            new String(
                                                    cursor.getBlob(2), StandardCharsets.UTF_8)))
                        });
            }
            // In the order of the list.
            Collections.sort(found, (lhs, rhs) -> Integer.compare((int) lhs[0], (int) rhs[0]));
            for (Object[] row : found) {
                rows.add((Row) row[1]);
            }
        } catch (RuntimeException e) {
            // Shown as an empty list.
        }
        return rows;
    }

    private void show(List<Row> rows, IptvGuide.GuideInfo info, Map<String, String> all) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mGuideInfo = info;
        mAllGuideChannels = all;
        if (!mMissingGuides.isEmpty() && !mGuideRead) {
            // Guides read by an older build, of this list or of others (any list's guide can be
            // assigned): read them now, one after the other, then show them.
            mGuideRead = true;
            mDescriptionView.setText(R.string.livetv_assign_reading_guide);
            mAdapter.setRows(rows);
            for (int i = 0; i < mMissingGuides.size(); i++) {
                boolean last = i == mMissingGuides.size() - 1;
                IptvGuide.update(
                        this,
                        mMissingGuides.get(i),
                        !last
                                ? null
                                : () -> {
                                    if (!isFinishing() && !isDestroyed()) {
                                        load();
                                    }
                                });
            }
            return;
        }
        // Without a guide of its own, a list can still use other lists' guides.
        mDescriptionView.setText(
                all.isEmpty()
                        ? R.string.livetv_assign_no_guide
                        : R.string.livetv_assign_description);
        mAdapter.setRows(rows);
        mListView.requestFocus();
    }

    /** "CO - Caracol · automatic", "CO - RCN · chosen by hand", or "No programs". */
    private CharSequence describeGuide(Row row) {
        String assigned = IptvGuideAssignments.get(this, row.key);
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (assigned != null && !IptvGuideAssignments.isNoGuide(assigned)) {
            appendGuideName(text, assigned);
            appendTag(text, getString(R.string.livetv_assign_manual), MANUAL_TAG_COLOR);
        } else if (assigned != null) {
            appendTag(text, getString(R.string.livetv_assign_none), TAG_COLOR);
            appendTag(text, getString(R.string.livetv_assign_manual), MANUAL_TAG_COLOR);
        } else if (mGuideInfo.automaticMatches.containsKey(row.key)) {
            appendGuideName(text, mGuideInfo.automaticMatches.get(row.key));
            appendTag(text, getString(R.string.livetv_assign_automatic), TAG_COLOR);
        } else {
            appendTag(text, getString(R.string.livetv_assign_none), TAG_COLOR);
        }
        return text;
    }

    /** The name of a guide channel; one of another list's guide also tells which guide. */
    private void appendGuideName(SpannableStringBuilder text, String reference) {
        String name = mAllGuideChannels.get(reference);
        text.append(name != null ? name : IptvGuideAssignments.referenceId(reference));
        if (!mGuideInfo.guideChannels.containsKey(reference)
                && !IptvGuideAssignments.ANY_GUIDE.equals(
                        IptvGuideAssignments.referenceUrl(reference))) {
            appendTag(
                    text,
                    IptvGuide.getGuideFileName(IptvGuideAssignments.referenceUrl(reference)),
                    TAG_COLOR);
        }
    }

    private static void appendTag(SpannableStringBuilder text, String tag, int color) {
        int start = text.length();
        text.append(start > 0 ? "  ·  " : "").append(tag);
        text.setSpan(
                new ForegroundColorSpan(color),
                start,
                text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void pick(int position, Row row) {
        if (mAllGuideChannels.isEmpty()) {
            return;
        }
        mPickedPosition = position;
        startActivityForResult(
                new Intent(this, IptvGuidePickerActivity.class)
                        .putExtra(IptvGuidePickerActivity.EXTRA_PLAYLIST_ID, mPlaylistId)
                        .putExtra(IptvGuidePickerActivity.EXTRA_CHANNEL_KEY, row.key)
                        .putExtra(IptvGuidePickerActivity.EXTRA_CHANNEL_NAME, row.name),
                REQUEST_CODE_PICK);
    }

    private final class RowsAdapter extends RecyclerView.Adapter<RowHolder> {
        private List<Row> mRows = new ArrayList<>();

        void setRows(List<Row> rows) {
            mRows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RowHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.livetv_iptv_assign_item, parent, false));
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            Row row = mRows.get(position);
            holder.mChannelView.setText(row.name);
            holder.mGuideView.setText(describeGuide(row));
            holder.itemView.setOnClickListener(v -> pick(holder.getAdapterPosition(), row));
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView mChannelView;
        final TextView mGuideView;

        RowHolder(View itemView) {
            super(itemView);
            mChannelView = itemView.findViewById(R.id.livetv_assign_item_channel);
            mGuideView = itemView.findViewById(R.id.livetv_assign_item_guide);
        }
    }
}
