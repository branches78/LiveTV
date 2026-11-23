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
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Guide channels the user assigned by hand to IPTV channels ("Assign channels"), which win over
 * the automatic matching of {@link IptvGuide}.
 *
 * <p>They are kept by a hash of the channel's stream URL, not by its ID, so they survive importing
 * the list again (which creates the channels again) and do not keep the URL, which can hold a
 * password.
 */
final class IptvGuideAssignments {
    private static final String PREFS = "livetv_guide_assignments";
    /** Saved for a channel the user wants without programs. */
    private static final String NO_GUIDE = "";
    /** Separates the guide URL from the guide channel id in a reference. */
    private static final char SEPARATOR = '\t';
    /** The guide URL of references saved before they had one: any guide of the list. */
    static final String ANY_GUIDE = "*";

    private IptvGuideAssignments() {}

    /** The key of a channel: a hash of its stream URL. */
    static String channelKey(String streamUrl) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(streamUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A reference to a channel of a guide: the guide's URL and the channel's id in it. A channel
     * can be given the programs of a guide of another list this way.
     */
    static String reference(String guideUrl, String guideChannelId) {
        return guideUrl + SEPARATOR + guideChannelId;
    }

    /** The guide URL of a reference, or {@link #ANY_GUIDE}. */
    static String referenceUrl(String reference) {
        int separator = reference.indexOf(SEPARATOR);
        return separator < 0 ? ANY_GUIDE : reference.substring(0, separator);
    }

    /** The guide channel id of a reference. */
    static String referenceId(String reference) {
        return reference.substring(reference.indexOf(SEPARATOR) + 1);
    }

    /** All the references the user chose, of every list. */
    static Collection<String> getAllReferences(Context context) {
        List<String> references = new ArrayList<>();
        for (Object value : prefs(context).getAll().values()) {
            if (value instanceof String && !isNoGuide((String) value)) {
                references.add((String) value);
            }
        }
        return references;
    }

    /**
     * The {@link #reference} of the guide channel the user chose, {@code ""} for no programs, or
     * {@code null} when the matching is automatic.
     */
    @Nullable
    static String get(Context context, String channelKey) {
        return prefs(context).getString(channelKey, null);
    }

    /**
     * @param guideChannelId the {@link #reference} of the chosen guide channel, {@code ""} for no
     *     programs, or {@code null} to go back to the automatic matching.
     */
    static void set(Context context, String channelKey, @Nullable String guideChannelId) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (guideChannelId == null) {
            editor.remove(channelKey);
        } else {
            editor.putString(channelKey, guideChannelId);
        }
        editor.apply();
    }

    /** Every assignment, by channel key (for a backup). */
    static Map<String, String> getAll(Context context) {
        Map<String, String> all = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                all.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return all;
    }

    /** Adds the assignments of a restored backup. */
    static void putAll(Context context, Map<String, String> assignments) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            editor.putString(entry.getKey(), entry.getValue());
        }
        editor.apply();
    }

    static boolean isNoGuide(@Nullable String guideChannelId) {
        return NO_GUIDE.equals(guideChannelId);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
