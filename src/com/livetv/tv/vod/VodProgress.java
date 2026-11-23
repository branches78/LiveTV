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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.livetv.tv.iptv.IptvChannels;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each movie or episode was left, for "Continue watching" and to resume it. Kept in the
 * private preferences of the app, which are cleared together with its data.
 */
public final class VodProgress {
    private static final String TAG = "VodProgress";
    private static final String PREFS = "livetv_vod";
    private static final String KEY_PROGRESS = "progress";
    /** The titles left most recently; older ones are forgotten. */
    private static final int MAX_ENTRIES = 50;
    /** Less than this watched is not worth resuming. */
    private static final long MIN_POSITION_MS = 30 * 1000;
    /** With this much watched the title is finished. */
    private static final float FINISHED_FRACTION = 0.95f;
    /** The audio and subtitles chosen for each title, the most recent first. */
    private static final String KEY_TRACKS = "tracks";
    private static final int MAX_TRACK_ENTRIES = 100;

    /** For {@link #saveTracks}: keeps what was chosen before. */
    public static final int TRACK_UNCHANGED = -2;
    /** Subtitles turned off. */
    public static final int TRACK_OFF = -1;
    /** Nothing chosen: the default of the title. */
    public static final int TRACK_DEFAULT = -3;

    /** A title left before its end. */
    public static final class Entry {
        /** See {@link VodLibrary.Title#key}. */
        public final String key;
        /** {@link VodLibrary#KIND_MOVIES} or {@link VodLibrary#KIND_SERIES} (for an episode). */
        public final int kind;
        /** What "Continue watching" shows; for an episode, with its series. */
        public final String name;
        public final String imageUrl;
        /** It holds the password of the list: never shown nor logged. */
        public final String streamUrl;
        public final long positionMs;
        public final long durationMs;
        public final long updatedMs;

        public Entry(
                String key,
                int kind,
                String name,
                String imageUrl,
                String streamUrl,
                long positionMs,
                long durationMs,
                long updatedMs) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.imageUrl = imageUrl;
            this.streamUrl = streamUrl;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.updatedMs = updatedMs;
        }

        /** How much was watched, from 0 to 1. */
        public float getFraction() {
            return durationMs > 0 ? Math.min(1f, (float) positionMs / durationMs) : 0f;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("key", key)
                    .put("kind", kind)
                    .put("name", name)
                    .put("image", imageUrl)
                    .put("url", streamUrl)
                    .put("position", positionMs)
                    .put("duration", durationMs)
                    .put("updated", updatedMs);
        }

        static Entry fromJson(JSONObject json) {
            return new Entry(
                    json.optString("key"),
                    json.optInt("kind"),
                    json.optString("name"),
                    json.optString("image"),
                    json.optString("url"),
                    json.optLong("position"),
                    json.optLong("duration"),
                    json.optLong("updated"));
        }
    }

    private VodProgress() {}

    /** The titles of {@code kind} left before their end, the most recent first. */
    public static List<Entry> getEntries(Context context, int kind) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : readAll(context)) {
            if (entry.kind == kind) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Every title left before its end, by {@link Entry#key}. */
    public static Map<String, Entry> getEntryMap(Context context) {
        Map<String, Entry> result = new HashMap<>();
        for (Entry entry : readAll(context)) {
            result.put(entry.key, entry);
        }
        return result;
    }

    /** Where to start {@code key}: where it was left, or 0. */
    public static long getResumePosition(Context context, String key) {
        for (Entry entry : readAll(context)) {
            if (entry.key.equals(key)) {
                return entry.positionMs;
            }
        }
        return 0;
    }

    /**
     * Remembers where a title was left. Barely started titles are not kept, and finished ones
     * are forgotten.
     */
    public static void save(Context context, Entry entry) {
        if (entry.key.isEmpty() || entry.durationMs <= 0) {
            return;
        }
        List<Entry> entries = readAll(context);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).key.equals(entry.key)) {
                entries.remove(i);
            }
        }
        if (entry.positionMs >= MIN_POSITION_MS && entry.getFraction() < FINISHED_FRACTION) {
            entries.add(0, entry);
        }
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        writeAll(context, entries);
    }

    /**
     * The audio and subtitles chosen for one title, as {audio, subtitles}: the index of the track
     * among the supported ones of its kind, {@link #TRACK_OFF} for subtitles turned off, or
     * {@link #TRACK_DEFAULT}. Only that title plays with them; the others keep their defaults.
     */
    public static int[] getTracks(Context context, String key) {
        JSONArray array = readTracks(context);
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json != null && key.equals(json.optString("key"))) {
                return new int[] {
                    json.optInt("audio", TRACK_DEFAULT), json.optInt("subtitles", TRACK_DEFAULT)
                };
            }
        }
        return new int[] {TRACK_DEFAULT, TRACK_DEFAULT};
    }

    /** Remembers the audio or subtitles chosen for one title; see {@link #getTracks}. */
    public static void saveTracks(Context context, String key, int audio, int subtitles) {
        if (key.isEmpty()) {
            return;
        }
        int[] old = getTracks(context, key);
        JSONArray array = readTracks(context);
        JSONArray updated = new JSONArray();
        try {
            updated.put(
                    new JSONObject()
                            .put("key", key)
                            .put("audio", audio == TRACK_UNCHANGED ? old[0] : audio)
                            .put("subtitles", subtitles == TRACK_UNCHANGED ? old[1] : subtitles));
            for (int i = 0; i < array.length() && updated.length() < MAX_TRACK_ENTRIES; i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null && !key.equals(json.optString("key"))) {
                    updated.put(json);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cannot store the tracks", e);
            return;
        }
        prefs(context).edit().putString(KEY_TRACKS, updated.toString()).apply();
    }

    private static JSONArray readTracks(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_TRACKS, "[]"));
        } catch (JSONException e) {
            Log.w(TAG, "Cannot read the tracks: " + e.getClass().getSimpleName());
            return new JSONArray();
        }
    }

    /** Forgets a title, e.g. once it was watched to the end. */
    public static void remove(Context context, String key) {
        List<Entry> entries = readAll(context);
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).key.equals(key)) {
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) {
            writeAll(context, entries);
        }
    }

    private static List<Entry> readAll(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_PROGRESS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    entries.add(Entry.fromJson(json));
                }
            }
        } catch (JSONException e) {
            // Not logged with its message: it quotes the addresses, with the password.
            Log.w(TAG, "Cannot read the progress: " + e.getClass().getSimpleName());
        }
        forgetDeletedLists(context, entries);
        return entries;
    }

    /**
     * LiveTV: forgets the titles of the lists deleted since, with their audio and subtitles.
     * They cannot be played any more, and their address holds the password of the list.
     */
    private static void forgetDeletedLists(Context context, List<Entry> entries) {
        Set<String> lists = new HashSet<>();
        for (IptvChannels.VodSource source : IptvChannels.getVodSources(context)) {
            lists.add(String.valueOf(source.playlistId));
        }
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!lists.contains(getListId(entries.get(i).key))) {
                entries.remove(i);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        writeAll(context, entries);
        JSONArray tracks = readTracks(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject json = tracks.optJSONObject(i);
            if (json != null && lists.contains(getListId(json.optString("key")))) {
                kept.put(json);
            }
        }
        prefs(context).edit().putString(KEY_TRACKS, kept.toString()).apply();
    }

    /** The list of a title's key ("list/movie/id"), see {@link VodLibrary.Title#key}. */
    private static String getListId(String key) {
        int slash = key.indexOf('/');
        return slash > 0 ? key.substring(0, slash) : "";
    }

    private static void writeAll(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cannot store the progress", e);
            return;
        }
        prefs(context).edit().putString(KEY_PROGRESS, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
