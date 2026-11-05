/*
 * Copyright (C) 2026 LiveTV
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

package com.livetv.tv.channels;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import com.android.tv.common.util.SharedPreferencesUtils;
import com.android.tv.data.ChannelImpl;
import com.android.tv.data.api.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The channel list chosen in "Customize channel list" decides the order and the numbers of the
 * channels in the program guide and when changing channels with the remote.
 *
 * <p>With "All", the channels follow the order the user set by moving them, and are numbered by
 * position (1, 2, 3…). "Favorites" keeps that order and those numbers, but the guide and the
 * remote only go through the favorite channels. The other lists keep the order and numbers of
 * their sources.
 */
public final class ChannelOrder {
    public static final int GROUP_BY_SOURCE = 0;
    public static final int GROUP_BY_HD_SD = 1;
    /** Every channel of every source in a single list, without group headers. */
    public static final int GROUP_BY_ALL = 2;
    /** Only the favorite channels, in the order and with the numbers of "All". */
    public static final int GROUP_BY_FAVORITES = 3;

    /** Key of the chosen list, in {@link SharedPreferencesUtils#SHARED_PREF_UI_SETTINGS}. */
    public static final String PREF_KEY_GROUP_SETTINGS = "pref_key_group_settigns";

    private static final String SHARED_PREF_CHANNEL_ORDER = "livetv_channel_order";
    private static final String PREF_KEY_ALL_ORDER = "all";

    private ChannelOrder() {}

    /** Returns the list chosen in "Customize channel list" ("All" until the user picks another). */
    public static int getGroupingType(Context context) {
        return getUiSettings(context).getInt(PREF_KEY_GROUP_SETTINGS, GROUP_BY_ALL);
    }

    public static void setGroupingType(Context context, int groupingType) {
        getUiSettings(context).edit().putInt(PREF_KEY_GROUP_SETTINGS, groupingType).apply();
    }

    /**
     * Returns the favorite channel IDs while "Favorites" is the chosen list, so the guide and the
     * remote only show them. Returns {@code null} to show every channel: another list is chosen,
     * or no channel is a favorite yet.
     */
    @Nullable
    public static Set<String> getChosenFavoriteIds(Context context) {
        if (getGroupingType(context) != GROUP_BY_FAVORITES) {
            return null;
        }
        Set<String> ids = Favorites.getIds(context);
        return ids.isEmpty() ? null : ids;
    }

    /**
     * Sorts {@code channels} for the chosen list and numbers them. Channels missing from the saved
     * order (for example, just imported) go at the end, in their default order.
     */
    public static void sort(
            Context context, List<Channel> channels, Comparator<Channel> defaultComparator) {
        // The default order compares the channels' own numbers.
        setPositionNumbers(channels, false);
        Collections.sort(channels, defaultComparator);
        int groupingType = getGroupingType(context);
        if (groupingType != GROUP_BY_ALL && groupingType != GROUP_BY_FAVORITES) {
            return;
        }
        Map<Long, Integer> positions = new HashMap<>();
        List<Long> savedOrder = loadOrder(context);
        for (int i = 0; i < savedOrder.size(); i++) {
            positions.put(savedOrder.get(i), i);
        }
        if (!positions.isEmpty()) {
            // The sort is stable: unsaved channels keep their default order.
            Collections.sort(
                    channels,
                    (lhs, rhs) -> {
                        Integer lhsPosition = positions.get(lhs.getId());
                        Integer rhsPosition = positions.get(rhs.getId());
                        int lhsValue = lhsPosition == null ? Integer.MAX_VALUE : lhsPosition;
                        int rhsValue = rhsPosition == null ? Integer.MAX_VALUE : rhsPosition;
                        return Integer.compare(lhsValue, rhsValue);
                    });
        }
        setPositionNumbers(channels, true);
    }

    /** Numbers the channels by their position, or restores their own numbers. */
    public static void setPositionNumbers(List<Channel> channels, boolean byPosition) {
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            if (channel instanceof ChannelImpl) {
                ((ChannelImpl) channel)
                        .setLiveTvDisplayNumber(byPosition ? String.valueOf(i + 1) : null);
            }
        }
    }

    /** Saves the order of the "All" list. */
    public static void saveOrder(Context context, List<Channel> channels) {
        StringBuilder builder = new StringBuilder();
        for (Channel channel : channels) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(channel.getId());
        }
        context.getSharedPreferences(SHARED_PREF_CHANNEL_ORDER, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_ALL_ORDER, builder.toString())
                .apply();
    }

    /** Forgets the channels of the saved order that are not in {@code channelIds}. */
    public static void retainChannels(Context context, Set<Long> channelIds) {
        List<Long> order = loadOrder(context);
        StringBuilder builder = new StringBuilder();
        int kept = 0;
        for (long id : order) {
            if (channelIds.contains(id)) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(id);
                kept++;
            }
        }
        if (kept != order.size()) {
            context.getSharedPreferences(SHARED_PREF_CHANNEL_ORDER, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_KEY_ALL_ORDER, builder.toString())
                    .apply();
        }
    }

    private static List<Long> loadOrder(Context context) {
        String order =
                context.getSharedPreferences(SHARED_PREF_CHANNEL_ORDER, Context.MODE_PRIVATE)
                        .getString(PREF_KEY_ALL_ORDER, "");
        List<Long> ids = new ArrayList<>();
        if (TextUtils.isEmpty(order)) {
            return ids;
        }
        for (String id : order.split(",")) {
            try {
                ids.add(Long.parseLong(id));
            } catch (NumberFormatException e) {
                // Skip a damaged entry.
            }
        }
        return ids;
    }

    private static SharedPreferences getUiSettings(Context context) {
        return context.getSharedPreferences(
                SharedPreferencesUtils.SHARED_PREF_UI_SETTINGS, Context.MODE_PRIVATE);
    }
}
