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

package com.android.tv.menu;

import android.content.Context;
import android.content.Intent;
import android.widget.ImageView;

import com.android.tv.R;
import com.livetv.tv.vod.VodActivity;
import com.livetv.tv.vod.VodLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LiveTV: the "Video on Demand" row, with the movies and the series of the IPTV lists. Its cards
 * look like the guide card of the channels row: the icon in the middle, the name below. Shown
 * only while some list has movies and series.
 */
public class VodRowAdapter extends ItemListRowView.ItemListAdapter<Integer> {

    public VodRowAdapter(Context context) {
        super(context);
    }

    @Override
    public void update() {
        List<Integer> kinds = new ArrayList<>();
        if (VodLibrary.hasSources(getMainActivity())) {
            kinds.add(VodLibrary.KIND_MOVIES);
            kinds.add(VodLibrary.KIND_SERIES);
        }
        if (!kinds.equals(getItemList())) {
            setItemList(kinds.isEmpty() ? Collections.emptyList() : kinds);
        }
    }

    @Override
    protected int getLayoutResId(int viewType) {
        return R.layout.livetv_menu_card_vod;
    }

    @Override
    public void onBindViewHolder(MyViewHolder viewHolder, int position) {
        int kind = getItemList().get(position);
        boolean series = kind == VodLibrary.KIND_SERIES;
        SimpleCardView card = (SimpleCardView) viewHolder.itemView;
        ((ImageView) card.findViewById(R.id.livetv_vod_menu_icon))
                .setImageResource(
                        series ? R.drawable.ic_livetv_vod_series : R.drawable.ic_livetv_vod_movies);
        card.setText(series ? R.string.livetv_vod_series : R.string.livetv_vod_movies);
        card.setOnClickListener(v -> open(kind));
        super.onBindViewHolder(viewHolder, position);
    }

    private void open(int kind) {
        getMainActivity().getOverlayManager().getMenu().hide(false);
        getMainActivity()
                .startActivity(
                        new Intent(getMainActivity(), VodActivity.class)
                                .putExtra(VodActivity.EXTRA_KIND, kind));
    }
}
