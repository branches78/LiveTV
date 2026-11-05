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
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.android.tv.R;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses the guide channel that gives its programs to an IPTV channel: automatic (LiveTV
 * decides), none, or a channel of any list's guide (those of the channel's list first), found with
 * the search field. Other lists' guides are only used this way, by hand. The choice is saved in
 * {@link IptvGuideAssignments}; the result is {@code RESULT_OK} when it changed.
 */
public class IptvGuidePickerActivity extends Activity {
    static final String EXTRA_PLAYLIST_ID = "playlist_id";
    static final String EXTRA_CHANNEL_KEY = "channel_key";
    static final String EXTRA_CHANNEL_NAME = "channel_name";

    /** What a row sets, besides guide channel ids. */
    private static final String CHOICE_AUTOMATIC = null;

    private String mChannelKey;
    private EditText mSearchView;
    private VerticalGridView mListView;
    private final ChoicesAdapter mAdapter = new ChoicesAdapter();
    /** The guides' channels as reference, shown text and searchable text. */
    private final List<String[]> mGuideChannels = new ArrayList<>();

    /** A row: its text and what it assigns ({@code null}: automatic, {@code ""}: none). */
    private static final class Choice {
        final CharSequence text;
        final String guideId;

        Choice(CharSequence text, String guideId) {
            this.text = text;
            this.guideId = guideId;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_iptv_assign);
        // The keyboard opens when the search field is clicked.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        mChannelKey = getIntent().getStringExtra(EXTRA_CHANNEL_KEY);
        ((TextView) findViewById(R.id.livetv_assign_title))
                .setText(
                        getString(
                                R.string.livetv_assign_picker_title,
                                getIntent().getStringExtra(EXTRA_CHANNEL_NAME)));
        ((TextView) findViewById(R.id.livetv_assign_description))
                .setText(R.string.livetv_assign_picker_description);
        mSearchView = findViewById(R.id.livetv_assign_search);
        mSearchView.setVisibility(View.VISIBLE);
        mListView = findViewById(R.id.livetv_assign_list);
        mListView.setAdapter(mAdapter);
        // Up from the first row goes back to the search field.
        mListView.setOnKeyInterceptListener(
                event -> {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                            && mListView.getSelectedPosition() == 0) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            mSearchView.requestFocus();
                        }
                        return true;
                    }
                    return false;
                });
        mSearchView.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        filter(s.toString());
                    }
                });
        long playlistId = getIntent().getLongExtra(EXTRA_PLAYLIST_ID, -1);
        new Thread(
                        () -> {
                            Map<String, String> channels =
                                    IptvGuide.readAllGuideChannels(this, playlistId);
                            runOnUiThread(() -> show(channels));
                        },
                        "IptvGuidePicker")
                .start();
    }

    private void show(Map<String, String> guideChannels) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        for (Map.Entry<String, String> channel : guideChannels.entrySet()) {
            String reference = channel.getKey();
            String file =
                    IptvGuide.getGuideFileName(IptvGuideAssignments.referenceUrl(reference));
            mGuideChannels.add(
                    new String[] {
                        reference,
                        // "CO - Caracol  ·  epg-co.xml": the same channel can be in two guides.
                        channel.getValue() + "  \u00B7  " + file,
                        normalize(
                                channel.getValue()
                                        + " "
                                        + IptvGuideAssignments.referenceId(reference))
                    });
        }
        filter(mSearchView.getText().toString());
        mListView.requestFocus();
    }

    private void filter(String query) {
        String current = IptvGuideAssignments.get(this, mChannelKey);
        String normalizedQuery = normalize(query).trim();
        List<Choice> choices = new ArrayList<>();
        choices.add(
                new Choice(
                        mark(getString(R.string.livetv_assign_choice_automatic), current == null),
                        CHOICE_AUTOMATIC));
        choices.add(
                new Choice(
                        mark(
                                getString(R.string.livetv_assign_none),
                                IptvGuideAssignments.isNoGuide(current)),
                        ""));
        for (String[] channel : mGuideChannels) {
            if (normalizedQuery.isEmpty() || channel[2].contains(normalizedQuery)) {
                choices.add(new Choice(mark(channel[1], channel[0].equals(current)), channel[0]));
            }
        }
        mAdapter.setChoices(choices);
    }

    /** Marks the current choice with a check. */
    private static CharSequence mark(String text, boolean current) {
        return current ? "✓  " + text : text;
    }

    private void choose(Choice choice) {
        String current = IptvGuideAssignments.get(this, mChannelKey);
        boolean changed =
                choice.guideId == null ? current != null : !choice.guideId.equals(current);
        if (changed) {
            IptvGuideAssignments.set(this, mChannelKey, choice.guideId);
            setResult(RESULT_OK);
        }
        finish();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private final class ChoicesAdapter extends RecyclerView.Adapter<ChoiceHolder> {
        private List<Choice> mChoices = new ArrayList<>();

        void setChoices(List<Choice> choices) {
            mChoices = choices;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mChoices.size();
        }

        @Override
        public ChoiceHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.livetv_iptv_assign_item, parent, false);
            view.findViewById(R.id.livetv_assign_item_arrow).setVisibility(View.GONE);
            view.findViewById(R.id.livetv_assign_item_guide).setVisibility(View.GONE);
            return new ChoiceHolder(view);
        }

        @Override
        public void onBindViewHolder(ChoiceHolder holder, int position) {
            Choice choice = mChoices.get(position);
            holder.mTextView.setText(choice.text);
            holder.itemView.setOnClickListener(v -> choose(choice));
        }
    }

    private static final class ChoiceHolder extends RecyclerView.ViewHolder {
        final TextView mTextView;

        ChoiceHolder(View itemView) {
            super(itemView);
            mTextView = itemView.findViewById(R.id.livetv_assign_item_channel);
        }
    }
}
