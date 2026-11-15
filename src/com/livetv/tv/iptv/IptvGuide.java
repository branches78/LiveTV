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

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.media.tv.TvContract.Programs;
import android.media.tv.TvContract.Programs.Genres;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Xml;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Reads the XMLTV program guide of the IPTV lists and stores its programs, so the program guide,
 * the channel banner and the genre filters show them.
 *
 * <p>A list's guide comes from its header (url-tvg), from its Xtream server, or is set by the
 * user. A guide channel matches a list channel by its id (tvg-id; the "@feed" part is ignored), or
 * else by its name, unless the user assigned the channel by hand ({@link IptvGuideAssignments}).
 * Only the programs from {@link #PAST_MS} ago to {@link #FUTURE_MS} ahead are kept. Guides are
 * read one at a time, in the background; gzip files (.xml.gz) are supported.
 *
 * <p>The downloaded guides are kept in the cache, so a change of assignments applies without
 * downloading them again, and the guide's channels and the automatic matches are saved for the
 * "Assign channels" screen ({@link #readGuideInfo}).
 */
public final class IptvGuide {
    private static final String TAG = "IptvGuide";

    /** A guide is read again after this time, when the app is used. */
    private static final long UPDATE_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final long PAST_MS = 2L * 60 * 60 * 1000;
    private static final long FUTURE_MS = 2L * 24 * 60 * 60 * 1000;
    /** Largest guide read (once uncompressed); country guides are a few tens of MB. */
    private static final long MAX_GUIDE_BYTES = 300L * 1024 * 1024;
    /** Upper bound of the programs stored for one list. */
    private static final int MAX_PROGRAMS = 150_000;
    private static final int TIMEOUT_MS = 30000;
    private static final int BATCH_SIZE = 500;
    /** Start of the names of the cached guides. */
    private static final String CACHE_PREFIX = "livetv_guide_cache_";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private IptvGuide() {}

    /**
     * Reads the guide of a list in the background, then runs {@code onDone} on the main thread.
     */
    static void update(Context context, long playlistId, @Nullable Runnable onDone) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(
                () -> {
                    try {
                        updateNow(appContext, playlistId, false);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot update the guide", e);
                    }
                    if (onDone != null) {
                        MAIN_HANDLER.post(onDone);
                    }
                });
    }

    /**
     * Applies the assignments again with the guides kept in the cache (downloading those that are
     * not), in the background.
     */
    static void reapply(Context context, long playlistId, @Nullable Runnable onDone) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(
                () -> {
                    try {
                        updateNow(appContext, playlistId, true);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot apply the guide again", e);
                    }
                    if (onDone != null) {
                        MAIN_HANDLER.post(onDone);
                    }
                });
    }

    /**
     * Deletes the saved guide channels of a deleted list. Its cached guides go with the next
     * clean-up, unless another list still uses them.
     */
    static void deleteFiles(Context context, long playlistId) {
        getInfoFile(context, playlistId).delete();
    }

    /** Deletes the cached guides no list nor assignment uses any more. */
    private static void deleteUnusedCache(Context context) {
        Set<String> used = new HashSet<>();
        for (IptvChannels.Playlist playlist : IptvChannels.getPlaylists(context)) {
            for (String url : playlist.guideUrls.trim().split("\\s+")) {
                used.add(getCacheFile(context, url).getName());
            }
        }
        for (String reference : IptvGuideAssignments.getAllReferences(context)) {
            used.add(getCacheFile(context, IptvGuideAssignments.referenceUrl(reference)).getName());
        }
        File[] files = context.getCacheDir().listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                // Also the copies of the previous build, cached per list ("livetv_guide_2_0").
                if (name.startsWith("livetv_guide_")
                        && !(name.startsWith(CACHE_PREFIX) && used.contains(name))) {
                    file.delete();
                }
            }
        }
    }

    /** Reads again, in the background, the guides not read for {@link #UPDATE_INTERVAL_MS}. */
    public static void updateAllIfStale(Context context) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(
                () -> {
                    try {
                        long now = System.currentTimeMillis();
                        for (IptvChannels.Playlist playlist :
                                IptvChannels.getPlaylists(appContext)) {
                            if (!playlist.guideUrls.isEmpty()
                                    && Math.abs(now - playlist.guideUpdatedMs)
                                            >= UPDATE_INTERVAL_MS) {
                                updateNow(appContext, playlist.id, false);
                            }
                        }
                        deleteUnusedCache(appContext);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot update the guides", e);
                    }
                });
    }

    /** The list's channels, found by their guide id and by their name. */
    private static final class ListChannels {
        final List<Long> ids = new ArrayList<>();
        /** Channel id -> {@link IptvGuideAssignments#channelKey}. */
        final Map<Long, String> keys = new HashMap<>();
        /** Guide channel reference -> the channels the user assigned to it. */
        final Map<String, List<Long>> assignedByReference = new HashMap<>();
        /** The guides of the assignments, which can be guides of other lists. */
        final Set<String> assignedGuideUrls = new LinkedHashSet<>();
        final Map<String, List<Long>> byGuideId = new HashMap<>();
        final Map<String, List<Long>> byName = new HashMap<>();
        /** By {@link #looseName}: the last resort. */
        final Map<String, List<Long>> byLooseName = new HashMap<>();
    }

    /** Programs found in the guides for the list's channels. */
    private static final class Collected {
        final List<ContentValues> programs = new ArrayList<>();
        final Set<Long> channelsWithPrograms = new HashSet<>();
        /** Channels given programs by an earlier guide of the list: later guides skip them. */
        Set<Long> filledBefore = new HashSet<>();
        /** The list's guides' channels, reference -> name, for "Assign channels". */
        final Map<String, String> guideChannels = new LinkedHashMap<>();
        /** Channel key -> guide channel reference, of the automatic matches. */
        final Map<String, String> automaticMatches = new HashMap<>();
        /** The channels and programs of the list's own guides, matched or not. */
        int ownGuideItems;
        /** Channel id -> the icon of its guide channel, for the channels without a logo. */
        final Map<Long, String> icons = new HashMap<>();
    }

    /** What the last guide update found, for the "Assign channels" screen. */
    static final class GuideInfo {
        /**
         * The channels of the list's guides, {@link IptvGuideAssignments#reference} -> name, in
         * the order of the guides.
         */
        final Map<String, String> guideChannels = new LinkedHashMap<>();
        /** Channel key -> guide channel reference, of the automatic matches. */
        final Map<String, String> automaticMatches = new HashMap<>();
    }

    /**
     * Returns the channels of the guides of every list, reference -> name, those of {@code
     * firstPlaylistId} first. Other lists' guides can be assigned by hand.
     */
    static Map<String, String> readAllGuideChannels(Context context, long firstPlaylistId) {
        Map<String, String> all = new LinkedHashMap<>(
                readGuideInfo(context, firstPlaylistId).guideChannels);
        for (IptvChannels.Playlist playlist : IptvChannels.getPlaylists(context)) {
            if (playlist.id != firstPlaylistId) {
                for (Map.Entry<String, String> channel :
                        readGuideInfo(context, playlist.id).guideChannels.entrySet()) {
                    if (!all.containsKey(channel.getKey())) {
                        all.put(channel.getKey(), channel.getValue());
                    }
                }
            }
        }
        return all;
    }

    /**
     * Returns the lists with a guide whose channels are not saved (read by an older build, or not
     * read yet), {@code playlistId} first if it is one of them.
     */
    static List<Long> getPlaylistsWithoutGuideInfo(Context context, long playlistId) {
        List<Long> missing = new ArrayList<>();
        for (IptvChannels.Playlist playlist : IptvChannels.getPlaylists(context)) {
            if (!playlist.guideUrls.trim().isEmpty()
                    && readGuideInfo(context, playlist.id).guideChannels.isEmpty()) {
                if (playlist.id == playlistId) {
                    missing.add(0, playlist.id);
                } else {
                    missing.add(playlist.id);
                }
            }
        }
        return missing;
    }

    /** The file name of a guide URL ("epg-co.xml"), to tell guides apart. */
    static String getGuideFileName(String url) {
        Uri uri = Uri.parse(url);
        String name = uri.getLastPathSegment();
        return name != null ? name : (uri.getHost() != null ? uri.getHost() : url);
    }

    /** Returns what the last guide update of the list found; empty if it never ran. */
    static GuideInfo readGuideInfo(Context context, long playlistId) {
        GuideInfo info = new GuideInfo();
        File file = getInfoFile(context, playlistId);
        if (!file.exists()) {
            return info;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
            JSONArray channels = json.optJSONArray("channels");
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONArray channel = channels.getJSONArray(i);
                // [url, id, name]; files of older builds had no URL: read the guide again.
                if (channel.length() < 3) {
                    return new GuideInfo();
                }
                info.guideChannels.put(
                        IptvGuideAssignments.reference(channel.getString(0), channel.getString(1)),
                        channel.getString(2));
            }
            JSONObject matches = json.optJSONObject("automatic");
            if (matches != null) {
                Iterator<String> keys = matches.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    info.automaticMatches.put(key, matches.getString(key));
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Cannot read the guide channels of list " + playlistId, e);
        }
        return info;
    }

    private static void saveGuideInfo(Context context, long playlistId, Collected collected) {
        try {
            JSONArray channels = new JSONArray();
            for (Map.Entry<String, String> channel : collected.guideChannels.entrySet()) {
                channels.put(
                        new JSONArray()
                                .put(IptvGuideAssignments.referenceUrl(channel.getKey()))
                                .put(IptvGuideAssignments.referenceId(channel.getKey()))
                                .put(channel.getValue()));
            }
            JSONObject matches = new JSONObject();
            for (Map.Entry<String, String> match : collected.automaticMatches.entrySet()) {
                matches.put(match.getKey(), match.getValue());
            }
            String json =
                    new JSONObject()
                            .put("channels", channels)
                            .put("automatic", matches)
                            .toString();
            try (OutputStream out = new FileOutputStream(getInfoFile(context, playlistId))) {
                out.write(json.getBytes("UTF-8"));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Cannot save the guide channels of list " + playlistId, e);
        }
    }

    private static File getInfoFile(Context context, long playlistId) {
        return new File(context.getFilesDir(), "livetv_guide_" + playlistId + ".json");
    }

    /** A guide is cached once, by a hash of its URL, for every list that uses it. */
    private static File getCacheFile(Context context, String url) {
        return new File(
                context.getCacheDir(), CACHE_PREFIX + IptvGuideAssignments.channelKey(url));
    }

    /** @param useCache read the guides kept in the cache instead of downloading them. */
    private static void updateNow(Context context, long playlistId, boolean useCache) {
        IptvChannels.Playlist playlist = null;
        for (IptvChannels.Playlist p : IptvChannels.getPlaylists(context)) {
            if (p.id == playlistId) {
                playlist = p;
            }
        }
        if (playlist == null) {
            return;
        }
        ListChannels channels = readListChannels(context, playlistId);
        Collected collected = new Collected();
        boolean read = false;
        long now = System.currentTimeMillis();
        Set<String> ownUrls = new LinkedHashSet<>();
        for (String url : playlist.guideUrls.trim().split("\\s+")) {
            if (!url.isEmpty()) {
                ownUrls.add(url);
            }
        }
        for (String url : ownUrls) {
            collected.filledBefore = new HashSet<>(collected.channelsWithPrograms);
            File cache = getCacheFile(context, url);
            try {
                if (!useCache || !cache.exists()) {
                    download(url, cache);
                }
                readGuide(cache, url, true, channels, collected, now - PAST_MS, now + FUTURE_MS);
                read = true;
            } catch (IOException | XmlPullParserException | RuntimeException e) {
                // The URL is not logged: it can hold a password.
                Log.w(TAG, "Cannot read a guide of list " + playlistId + ": " + e);
            }
        }
        // Guides of other lists, only for the channels assigned to them by hand. Their own list
        // keeps them up to date: the cached copy is enough.
        for (String url : channels.assignedGuideUrls) {
            if (ownUrls.contains(url) || IptvGuideAssignments.ANY_GUIDE.equals(url)) {
                continue;
            }
            collected.filledBefore = new HashSet<>(collected.channelsWithPrograms);
            File cache = getCacheFile(context, url);
            try {
                if (!cache.exists()) {
                    download(url, cache);
                }
                readGuide(cache, url, false, channels, collected, now - PAST_MS, now + FUTURE_MS);
            } catch (IOException | XmlPullParserException | RuntimeException e) {
                Log.w(TAG, "Cannot read another list's guide for list " + playlistId + ": " + e);
            }
        }
        if (!read && !playlist.guideUrls.trim().isEmpty()) {
            // Keep the programs of the last update; show the error.
            IptvChannels.updatePlaylist(
                    context,
                    playlistId,
                    p -> p.withGuideResult(IptvChannels.Playlist.GUIDE_FAILED, now));
            return;
        }
        try {
            storePrograms(context, channels.ids, collected.programs);
        } catch (Exception e) {
            Log.w(TAG, "Cannot store the programs of list " + playlistId, e);
            IptvChannels.updatePlaylist(
                    context,
                    playlistId,
                    p -> p.withGuideResult(IptvChannels.Playlist.GUIDE_FAILED, now));
            return;
        }
        saveGuideInfo(context, playlistId, collected);
        // The logo of the list comes first: the guide only gives one to the channels without.
        for (Map.Entry<Long, String> icon : collected.icons.entrySet()) {
            IptvChannels.storeLogoIfMissing(context, icon.getKey(), icon.getValue());
        }
        int channelCount = collected.channelsWithPrograms.size();
        // Servers can offer a guide and send it empty: say so, instead of "0 channels".
        int guideResult =
                channelCount == 0 && collected.ownGuideItems == 0
                        ? IptvChannels.Playlist.GUIDE_EMPTY
                        : channelCount;
        Log.i(
                TAG,
                "List "
                        + playlistId
                        + ": "
                        + collected.programs.size()
                        + " programs for "
                        + channelCount
                        + " of "
                        + channels.ids.size()
                        + " channels");
        IptvChannels.updatePlaylist(
                context,
                playlistId,
                p ->
                        p.guideUrls.trim().isEmpty()
                                ? p.withGuideResult(IptvChannels.Playlist.GUIDE_NOT_READ, 0)
                                // Applying assignments again does not make the guide newer.
                                : p.withGuideResult(
                                        guideResult, useCache ? p.guideUpdatedMs : now));
    }

    /** Downloads a guide as it comes (maybe compressed) to {@code file}. */
    private static void download(String url, File file) throws IOException {
        HttpURLConnection connection = IptvChannels.open(url, TIMEOUT_MS);
        File partial = new File(file.getPath() + ".part");
        try (InputStream in =
                        new IptvChannels.LimitedInputStream(
                                connection.getInputStream(), MAX_GUIDE_BYTES);
                OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        if (!partial.renameTo(file)) {
            partial.delete();
            throw new IOException("Cannot keep the guide");
        }
    }

    private static ListChannels readListChannels(Context context, long playlistId) {
        ListChannels channels = new ListChannels();
        boolean hasGuideIds = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        String[] projection =
                hasGuideIds
                        ? new String[] {
                            Channels._ID,
                            Channels.COLUMN_DISPLAY_NAME,
                            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                            Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                            Channels.COLUMN_INTERNAL_PROVIDER_ID
                        }
                        : new String[] {
                            Channels._ID,
                            Channels.COLUMN_DISPLAY_NAME,
                            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                            Channels.COLUMN_INTERNAL_PROVIDER_DATA
                        };
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                TvContract.buildChannelsUriForInput(
                                        IptvChannels.getInputId(context)),
                                projection,
                                null,
                                null,
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.isNull(2) || cursor.getLong(2) != playlistId) {
                    continue;
                }
                long id = cursor.getLong(0);
                channels.ids.add(id);
                if (!cursor.isNull(3)) {
                    String key =
                            IptvGuideAssignments.channelKey(
                                    new String(cursor.getBlob(3), StandardCharsets.UTF_8));
                    channels.keys.put(id, key);
                    String assigned = IptvGuideAssignments.get(context, key);
                    if (assigned != null) {
                        // Chosen by hand: no automatic matching for this channel.
                        if (!IptvGuideAssignments.isNoGuide(assigned)) {
                            String url = IptvGuideAssignments.referenceUrl(assigned);
                            addTo(
                                    channels.assignedByReference,
                                    IptvGuideAssignments.reference(
                                            url, IptvGuideAssignments.referenceId(assigned)),
                                    id);
                            channels.assignedGuideUrls.add(url);
                        }
                        continue;
                    }
                }
                addTo(channels.byName, normalizeName(cursor.getString(1)), id);
                addTo(channels.byLooseName, looseName(cursor.getString(1)), id);
                if (hasGuideIds && !cursor.isNull(4)) {
                    addTo(channels.byGuideId, normalizeGuideId(cursor.getString(4)), id);
                }
            }
        }
        return channels;
    }

    private static void addTo(Map<String, List<Long>> map, String key, long id) {
        if (key.isEmpty()) {
            return;
        }
        List<Long> ids = map.get(key);
        if (ids == null) {
            ids = new ArrayList<>(1);
            map.put(key, ids);
        }
        ids.add(id);
    }

    /**
     * @param own whether the guide is one of the list's: other lists' guides only give programs to
     *     the channels assigned to them by hand.
     */
    private static void readGuide(
            File guide,
            String url,
            boolean own,
            ListChannels channels,
            Collected collected,
            long fromMs,
            long toMs)
            throws IOException, XmlPullParserException {
        try (InputStream in = openGuideStream(new FileInputStream(guide))) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            try {
                // Guides often have HTML entities (&nbsp;) the strict parser would stop at.
                parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true);
            } catch (XmlPullParserException e) {
                // Strict, then.
            }
            parser.setInput(in, null);
            // Guide channel id -> list channels.
            Map<String, Set<Long>> guideChannels = new HashMap<>();
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                if (own
                        && ("channel".equals(parser.getName())
                                || "programme".equals(parser.getName()))) {
                    collected.ownGuideItems++;
                }
                if ("channel".equals(parser.getName())) {
                    readChannel(parser, url, own, channels, guideChannels, collected);
                } else if ("programme".equals(parser.getName())) {
                    readProgramme(
                            parser, url, own, channels, guideChannels, collected, fromMs, toMs);
                }
            }
        }
    }

    /** Uncompresses gzip guides and limits the size read. */
    private static InputStream openGuideStream(InputStream raw) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw, 64 * 1024);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream data =
                first == 0x1f && second == 0x8b
                        ? new GZIPInputStream(buffered, 64 * 1024)
                        : buffered;
        return new IptvChannels.LimitedInputStream(data, MAX_GUIDE_BYTES);
    }

    private static void readChannel(
            XmlPullParser parser,
            String url,
            boolean own,
            ListChannels channels,
            Map<String, Set<Long>> guideChannels,
            Collected collected)
            throws IOException, XmlPullParserException {
        String guideId = parser.getAttributeValue(null, "id");
        Set<Long> matches = new LinkedHashSet<>();
        if (guideId != null && own) {
            List<Long> byId = channels.byGuideId.get(normalizeGuideId(guideId));
            if (byId != null) {
                matches.addAll(byId);
            }
        }
        List<String> names = new ArrayList<>();
        String icon = null;
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && !(event == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            if (event == XmlPullParser.START_TAG && "display-name".equals(parser.getName())) {
                names.add(parser.nextText());
            } else if (event == XmlPullParser.START_TAG
                    && "icon".equals(parser.getName())
                    && icon == null) {
                String src = parser.getAttributeValue(null, "src");
                icon = src != null && IptvChannels.isWebUrl(src.trim()) ? src.trim() : null;
            }
        }
        // By id, else by exact name, else by loose name; only with the list's own guides.
        for (String name : names) {
            List<Long> byName = own ? channels.byName.get(normalizeName(name)) : null;
            if (matches.isEmpty() && byName != null) {
                matches.addAll(byName);
            }
        }
        for (String name : names) {
            List<Long> byLooseName = own ? channels.byLooseName.get(looseName(name)) : null;
            if (matches.isEmpty() && byLooseName != null) {
                matches.addAll(byLooseName);
            }
        }
        if (guideId == null) {
            return;
        }
        String reference = IptvGuideAssignments.reference(url, guideId);
        if (own) {
            if (!collected.guideChannels.containsKey(reference)) {
                collected.guideChannels.put(
                        reference, names.isEmpty() ? guideId : names.get(0).trim());
            }
            for (long channelId : matches) {
                String key = channels.keys.get(channelId);
                if (key != null && !collected.automaticMatches.containsKey(key)) {
                    collected.automaticMatches.put(key, reference);
                }
            }
        }
        addAssigned(matches, channels, url, own, guideId);
        if (!matches.isEmpty()) {
            guideChannels.put(guideId, matches);
            if (icon != null) {
                for (long channelId : matches) {
                    // The first guide of the list wins, as with the programs.
                    if (!collected.icons.containsKey(channelId)) {
                        collected.icons.put(channelId, icon);
                    }
                }
            }
        }
    }

    /** Adds the channels the user assigned to this guide channel. */
    private static void addAssigned(
            Set<Long> targets, ListChannels channels, String url, boolean own, String guideId) {
        List<Long> assigned =
                channels.assignedByReference.get(IptvGuideAssignments.reference(url, guideId));
        if (assigned != null) {
            targets.addAll(assigned);
        }
        // Assignments saved without a guide URL: any of the list's own guides.
        List<Long> anyGuide =
                own
                        ? channels.assignedByReference.get(
                                IptvGuideAssignments.reference(
                                        IptvGuideAssignments.ANY_GUIDE, guideId))
                        : null;
        if (anyGuide != null) {
            targets.addAll(anyGuide);
        }
    }

    private static void readProgramme(
            XmlPullParser parser,
            String url,
            boolean own,
            ListChannels channels,
            Map<String, Set<Long>> guideChannels,
            Collected collected,
            long fromMs,
            long toMs)
            throws IOException, XmlPullParserException {
        String guideId = parser.getAttributeValue(null, "channel");
        Set<Long> targets = guideId == null ? null : guideChannels.get(guideId);
        if (targets == null && guideId != null) {
            // A guide without <channel> elements: the id alone.
            Set<Long> found = new LinkedHashSet<>();
            List<Long> byId = own ? channels.byGuideId.get(normalizeGuideId(guideId)) : null;
            if (byId != null) {
                found.addAll(byId);
            }
            addAssigned(found, channels, url, own, guideId);
            if (!found.isEmpty()) {
                targets = found;
                guideChannels.put(guideId, targets);
            }
        }
        long startMs = parseTime(parser.getAttributeValue(null, "start"));
        long endMs = parseTime(parser.getAttributeValue(null, "stop"));
        if (targets == null
                || startMs <= 0
                || endMs <= startMs
                || endMs < fromMs
                || startMs > toMs
                || collected.programs.size() >= MAX_PROGRAMS) {
            skip(parser);
            return;
        }
        String title = null;
        String episodeTitle = null;
        String description = null;
        // The picture of the program, shown on the channel cards of the menu.
        String image = null;
        List<String> genres = new ArrayList<>();
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && !(event == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            switch (parser.getName()) {
                case "title":
                    String text = parser.nextText().trim();
                    title = title == null ? text : title;
                    break;
                case "sub-title":
                    episodeTitle = episodeTitle == null ? parser.nextText().trim() : episodeTitle;
                    break;
                case "desc":
                    description = description == null ? parser.nextText().trim() : description;
                    break;
                case "icon":
                    String src = parser.getAttributeValue(null, "src");
                    if (image == null && src != null && IptvChannels.isWebUrl(src.trim())) {
                        image = src.trim();
                    }
                    break;
                case "category":
                    String genre = toCanonicalGenre(parser.nextText());
                    if (genre != null && !genres.contains(genre)) {
                        genres.add(genre);
                    }
                    break;
                default:
                    break;
            }
        }
        // Some public guides fill the channels they know nothing about with "No Data".
        if (title == null || title.isEmpty() || title.equalsIgnoreCase("No Data")) {
            return;
        }
        for (long channelId : targets) {
            if (collected.filledBefore.contains(channelId)) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.put(Programs.COLUMN_CHANNEL_ID, channelId);
            values.put(Programs.COLUMN_TITLE, title);
            if (episodeTitle != null && !episodeTitle.isEmpty()) {
                values.put(Programs.COLUMN_EPISODE_TITLE, episodeTitle);
            }
            if (description != null && !description.isEmpty()) {
                values.put(Programs.COLUMN_SHORT_DESCRIPTION, description);
            }
            if (image != null) {
                values.put(Programs.COLUMN_POSTER_ART_URI, image);
            }
            values.put(Programs.COLUMN_START_TIME_UTC_MILLIS, startMs);
            values.put(Programs.COLUMN_END_TIME_UTC_MILLIS, endMs);
            if (!genres.isEmpty()) {
                values.put(
                        Programs.COLUMN_CANONICAL_GENRE,
                        Genres.encode(genres.toArray(new String[0])));
            }
            collected.programs.add(values);
            collected.channelsWithPrograms.add(channelId);
        }
    }

    private static void skip(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && !(event == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            // Skipped.
        }
    }

    /**
     * Replaces the programs of the list's channels. The TV provider only lets this app delete
     * programs channel by channel, without a selection.
     */
    private static void storePrograms(
            Context context, List<Long> channelIds, List<ContentValues> programs)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<ContentProviderOperation> deletes = new ArrayList<>();
        for (long channelId : channelIds) {
            deletes.add(
                    ContentProviderOperation.newDelete(
                                    TvContract.buildProgramsUriForChannel(channelId))
                            .build());
            if (deletes.size() == BATCH_SIZE) {
                resolver.applyBatch(TvContract.AUTHORITY, deletes);
                deletes.clear();
            }
        }
        if (!deletes.isEmpty()) {
            resolver.applyBatch(TvContract.AUTHORITY, deletes);
        }
        for (int start = 0; start < programs.size(); start += BATCH_SIZE) {
            List<ContentValues> batch =
                    programs.subList(start, Math.min(start + BATCH_SIZE, programs.size()));
            resolver.bulkInsert(Programs.CONTENT_URI, batch.toArray(new ContentValues[0]));
        }
    }

    /**
     * Parses an XMLTV time, "yyyyMMddHHmmss +zzzz" (seconds and offset optional; UTC without
     * offset). Returns 0 if it cannot be read.
     */
    static long parseTime(@Nullable String time) {
        if (time == null) {
            return 0;
        }
        String text = time.trim();
        int digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        if (digits < 12) {
            return 0;
        }
        try {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            calendar.clear();
            calendar.set(
                    Integer.parseInt(text.substring(0, 4)),
                    Integer.parseInt(text.substring(4, 6)) - 1,
                    Integer.parseInt(text.substring(6, 8)),
                    Integer.parseInt(text.substring(8, 10)),
                    Integer.parseInt(text.substring(10, 12)),
                    digits >= 14 ? Integer.parseInt(text.substring(12, 14)) : 0);
            long millis = calendar.getTimeInMillis();
            String offset = text.substring(digits).trim();
            if (offset.length() >= 5 && (offset.charAt(0) == '+' || offset.charAt(0) == '-')) {
                int hours = Integer.parseInt(offset.substring(1, 3));
                int minutes = Integer.parseInt(offset.substring(3, 5));
                long offsetMs = (hours * 60L + minutes) * 60 * 1000;
                millis -= offset.charAt(0) == '+' ? offsetMs : -offsetMs;
            }
            return millis;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "Caracol.co@SD" and "caracol.co" are the same channel: the feed after "@" is ignored. */
    static String normalizeGuideId(@Nullable String guideId) {
        if (guideId == null) {
            return "";
        }
        int at = guideId.indexOf('@');
        return (at >= 0 ? guideId.substring(0, at) : guideId).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A channel name without accents, case, country prefix ("CO - "), notes in brackets
     * ("(1080p)", "[Geo-blocked]"), quality words or punctuation: "CO - Caracol TV" and "Caracol
     * TV (1080p)" are both "caracoltv".
     */
    static String normalizeName(@Nullable String name) {
        return cleanName(name).replaceAll("[^a-z0-9]", "");
    }

    /**
     * {@link #normalizeName} without the generic words "TV", "television", "canal" and "channel":
     * "Caracol TV" matches "Caracol", and "Canal RCN" matches "RCN".
     */
    static String looseName(@Nullable String name) {
        return cleanName(name)
                .replaceAll("\\b(tv|television|canal|channel)\\b", " ")
                .replaceAll("[^a-z0-9]", "");
    }

    private static String cleanName(@Nullable String name) {
        if (name == null) {
            return "";
        }
        String text =
                Normalizer.normalize(name, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT);
        text = text.replaceAll("^[a-z]{2,3}\\s*[-:|]\\s+", "");
        text = text.replaceAll("[(\\[][^)\\]]*[)\\]]", " ");
        return text.replaceAll("\\b(hd|sd|fhd|uhd|4k|1080p|720p|480p)\\b", " ");
    }

    /** Maps an XMLTV category (Spanish or English) to a canonical genre, or null. */
    @Nullable
    static String toCanonicalGenre(String category) {
        String text =
                Normalizer.normalize(category, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT);
        if (containsAny(text, "news", "noticia", "informativ", "noticiero")) {
            return Genres.NEWS;
        } else if (containsAny(text, "sport", "deport", "futbol", "football", "soccer")) {
            return Genres.SPORTS;
        } else if (containsAny(text, "movie", "film", "pelicula", "cine")) {
            return Genres.MOVIES;
        } else if (containsAny(
                text, "kid", "child", "infantil", "nino", "animation", "animacion", "family",
                "familia", "cartoon", "dibujo")) {
            return Genres.FAMILY_KIDS;
        } else if (containsAny(text, "comed")) {
            return Genres.COMEDY;
        } else if (containsAny(text, "drama", "telenovela", "novela", "soap")) {
            return Genres.DRAMA;
        } else if (containsAny(text, "music", "musica")) {
            return Genres.MUSIC;
        } else if (containsAny(text, "documentar", "educa")) {
            return Genres.EDUCATION;
        } else if (containsAny(text, "nature", "naturaleza", "animal", "wildlife")) {
            return Genres.ANIMAL_WILDLIFE;
        } else if (containsAny(text, "travel", "viaje", "turismo")) {
            return Genres.TRAVEL;
        } else if (containsAny(text, "shopping", "compras", "televenta")) {
            return Genres.SHOPPING;
        } else if (containsAny(text, "game", "juego", "concurso", "quiz")) {
            return Genres.GAMING;
        } else if (containsAny(text, "science", "ciencia", "tecnolog")) {
            return Genres.TECH_SCIENCE;
        } else if (containsAny(text, "lifestyle", "estilo de vida", "cocina", "cooking", "salud")) {
            return Genres.LIFE_STYLE;
        } else if (containsAny(text, "arts", "cultur") || text.trim().matches("arte?")) {
            return Genres.ARTS;
        } else if (containsAny(
                text, "entertainment", "entretenimiento", "reality", "variedad", "talk show")) {
            return Genres.ENTERTAINMENT;
        }
        return null;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
