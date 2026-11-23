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
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The channels the user marked as favorites with the menu. With "Favorites" chosen in "Customize
 * channel list > Group by", the guide and the remote only go through them (see {@link
 * ChannelOrder#getChosenFavoriteIds}).
 */
public final class Favorites {
    private static final String SHARED_PREF_FAVORITES = "livetv_favorites";
    private static final String PREF_KEY_CHANNEL_IDS = "channel_ids";

    private Favorites() {}

    public static boolean isFavorite(Context context, long channelId) {
        return getIds(context).contains(String.valueOf(channelId));
    }

    public static void setFavorite(Context context, long channelId, boolean favorite) {
        // The returned set must not be modified.
        Set<String> ids = new HashSet<>(getIds(context));
        if (favorite) {
            ids.add(String.valueOf(channelId));
        } else {
            ids.remove(String.valueOf(channelId));
        }
        getPrefs(context).edit().putStringSet(PREF_KEY_CHANNEL_IDS, ids).apply();
    }

    /** Marks several channels as favorites at once (a restored backup). */
    public static void addAll(Context context, Collection<Long> channelIds) {
        Set<String> ids = new HashSet<>(getIds(context));
        for (long id : channelIds) {
            ids.add(String.valueOf(id));
        }
        getPrefs(context).edit().putStringSet(PREF_KEY_CHANNEL_IDS, ids).apply();
    }

    /** Forgets the favorites that are not in {@code channelIds} (deleted channels). */
    public static void retainChannels(Context context, Set<Long> channelIds) {
        Set<String> ids = new HashSet<>();
        for (String id : getIds(context)) {
            try {
                if (channelIds.contains(Long.parseLong(id))) {
                    ids.add(id);
                }
            } catch (NumberFormatException e) {
                // Dropped.
            }
        }
        if (ids.size() != getIds(context).size()) {
            getPrefs(context).edit().putStringSet(PREF_KEY_CHANNEL_IDS, ids).apply();
        }
    }

    /** Returns the IDs of the favorite channels, as strings. Must not be modified. */
    public static Set<String> getIds(Context context) {
        return getPrefs(context).getStringSet(PREF_KEY_CHANNEL_IDS, new HashSet<>());
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(SHARED_PREF_FAVORITES, Context.MODE_PRIVATE);
    }
}
