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

import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.support.annotation.Nullable;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.android.tv.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stores the channels of M3U lists in the TV provider, as channels of {@link IptvTvInputService}.
 * Several lists can be imported; each channel keeps the ID of its list in {@link
 * Channels#COLUMN_INTERNAL_PROVIDER_FLAG1}, so a list can be updated or deleted on its own.
 */
public final class IptvChannels {
    private static final String TAG = "IptvChannels";
    /** Shared with {@link IptvSetupActivity}; cleared together with the app data. */
    static final String PREFS = "livetv_iptv";
    /** Whether channels imported by this app should be in the TV provider. */
    private static final String KEY_CHANNELS_IMPORTED = "channels_imported";
    /** The imported lists, as a JSON array of {@link Playlist}. */
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_NEXT_PLAYLIST_ID = "next_playlist_id";
    /** URL of the single list imported before LiveTV supported several lists. */
    private static final String KEY_LEGACY_PLAYLIST_URL = "playlist_url";
    /** Start of the source of a list imported with a username and a password. */
    private static final String LOGIN_SOURCE_PREFIX = "login:";

    static final String USER_AGENT = "LiveTV";
    /** Upper bound, for all the lists together, so the guide stays usable. */
    static final int MAX_CHANNELS = 3000;

    private static final int BATCH_SIZE = 200;
    /** Largest list accepted, so a huge file cannot run the app out of memory. */
    private static final long MAX_PLAYLIST_BYTES = 20L * 1024 * 1024;
    /** Largest logo accepted, so a huge image cannot fill the storage. */
    private static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    /** Start of the source of a list imported from a file (followed by a hash of its URI). */
    private static final String FILE_SOURCE_PREFIX = "file:";
    private static final int TIMEOUT_MS = 15000;
    private static final int LOGO_TIMEOUT_MS = 5000;
    private static final int MAX_REDIRECTS = 5;
    private static final ExecutorService LOGO_EXECUTOR = Executors.newFixedThreadPool(4);

    /** Result of {@link #importPlaylist}. */
    static final class ImportResult {
        final int imported;
        final int total;
        /** The imported list, or -1 if nothing was imported. */
        final long playlistId;

        ImportResult(int imported, int total) {
            this(imported, total, -1);
        }

        ImportResult(int imported, int total, long playlistId) {
            this.imported = imported;
            this.total = total;
            this.playlistId = playlistId;
        }
    }

    /** An imported list. */
    static final class Playlist {
        /** {@link #guideChannelCount} while the guide was never read. */
        static final int GUIDE_NOT_READ = -1;
        /** {@link #guideChannelCount} when reading the guide failed. */
        static final int GUIDE_FAILED = -2;

        final long id;
        /** The URL, or the URI of the file; importing the same source again updates the list. */
        final String source;
        /** What the user sees: the URL, or the name of the file. */
        final String label;
        final int channelCount;
        /** Addresses of the XMLTV program guide, separated by spaces; empty if none. */
        final String guideUrls;
        /** Channels with programs after the last guide update, or a GUIDE_ constant. */
        final int guideChannelCount;
        /** When the guide was last read, in ms since the epoch, or 0. */
        final long guideUpdatedMs;

        /** Whether the list was imported with a username and password. */
        boolean hasLogin() {
            return source.startsWith(LOGIN_SOURCE_PREFIX);
        }

        Playlist(long id, String source, String label, int channelCount) {
            this(id, source, label, channelCount, "", GUIDE_NOT_READ, 0);
        }

        Playlist(
                long id,
                String source,
                String label,
                int channelCount,
                String guideUrls,
                int guideChannelCount,
                long guideUpdatedMs) {
            this.id = id;
            this.source = source;
            this.label = label;
            this.channelCount = channelCount;
            this.guideUrls = guideUrls;
            this.guideChannelCount = guideChannelCount;
            this.guideUpdatedMs = guideUpdatedMs;
        }

        Playlist withGuideUrls(String urls) {
            return new Playlist(id, source, label, channelCount, urls, GUIDE_NOT_READ, 0);
        }

        Playlist withGuideResult(int channelsWithPrograms, long updatedMs) {
            return new Playlist(
                    id, source, label, channelCount, guideUrls, channelsWithPrograms, updatedMs);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("source", source)
                    .put("label", label)
                    .put("channels", channelCount)
                    .put("guide", guideUrls)
                    .put("guideChannels", guideChannelCount)
                    .put("guideUpdated", guideUpdatedMs);
        }

        static Playlist fromJson(JSONObject json) {
            return new Playlist(
                    json.optLong("id"),
                    json.optString("source"),
                    json.optString("label"),
                    json.optInt("channels"),
                    json.optString("guide"),
                    json.optInt("guideChannels", GUIDE_NOT_READ),
                    json.optLong("guideUpdated"));
        }
    }

    /** Changes one list, if it still exists. */
    interface PlaylistChange {
        Playlist apply(Playlist playlist);
    }

    /** Applies {@code change} to the list {@code playlistId}; returns the changed list or null. */
    @Nullable
    static synchronized Playlist updatePlaylist(
            Context context, long playlistId, PlaylistChange change) {
        List<Playlist> playlists = getPlaylists(context);
        for (int i = 0; i < playlists.size(); i++) {
            if (playlists.get(i).id == playlistId) {
                Playlist changed = change.apply(playlists.get(i));
                playlists.set(i, changed);
                savePlaylists(context, playlists);
                return changed;
            }
        }
        return null;
    }

    /**
     * Returns the imported lists, in the order they were first imported. Must not be called on the
     * main thread: the channels of an import made before LiveTV supported several lists are moved
     * to a list of their own the first time.
     */
    static synchronized List<Playlist> getPlaylists(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(KEY_PLAYLISTS)) {
            migrateLegacyChannels(context);
        }
        List<Playlist> playlists = new ArrayList<>();
        boolean changed = false;
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_PLAYLISTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Playlist playlist = Playlist.fromJson(array.getJSONObject(i));
                // Older builds kept the URI of a file, which can hold a password.
                if (playlist.source.startsWith("content:")) {
                    playlist =
                            new Playlist(
                                    playlist.id,
                                    getFileSource(Uri.parse(playlist.source)),
                                    playlist.label,
                                    playlist.channelCount,
                                    playlist.guideUrls,
                                    playlist.guideChannelCount,
                                    playlist.guideUpdatedMs);
                    changed = true;
                }
                playlists.add(playlist);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cannot read the lists", e);
        }
        if (changed) {
            savePlaylists(context, playlists);
        }
        deleteUnlistedChannels(context, playlists);
        return playlists;
    }

    /**
     * Deletes the channels of no list, which an import interrupted by an older build could leave:
     * they could not be deleted from the setup screen otherwise.
     */
    private static void deleteUnlistedChannels(Context context, List<Playlist> playlists) {
        Set<Long> ids = new HashSet<>();
        for (Playlist playlist : playlists) {
            ids.add(playlist.id);
        }
        int deleted = deleteChannelsOf(context, playlistId -> !ids.contains(playlistId));
        if (deleted > 0) {
            Log.i(TAG, "Deleted " + deleted + " channels of no list");
        }
    }

    private static void savePlaylists(Context context, List<Playlist> playlists) {
        JSONArray array = new JSONArray();
        try {
            for (Playlist playlist : playlists) {
                array.put(playlist.toJson());
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cannot save the lists", e);
        }
        prefs(context)
                .edit()
                .putString(KEY_PLAYLISTS, array.toString())
                .putBoolean(KEY_CHANNELS_IMPORTED, !playlists.isEmpty())
                .apply();
    }

    /** Puts the channels of the single list LiveTV used to keep into a list of their own. */
    private static void migrateLegacyChannels(Context context) {
        List<Playlist> playlists = new ArrayList<>();
        if (prefs(context).getBoolean(KEY_CHANNELS_IMPORTED, false)) {
            long id = newPlaylistId(context);
            ContentValues values = new ContentValues();
            values.put(Channels.COLUMN_INTERNAL_PROVIDER_FLAG1, id);
            int count =
                    context.getContentResolver()
                            .update(
                                    TvContract.buildChannelsUriForInput(getInputId(context)),
                                    values,
                                    null,
                                    null);
            if (count > 0) {
                String url = prefs(context).getString(KEY_LEGACY_PLAYLIST_URL, "");
                playlists.add(new Playlist(id, url, url, count));
            }
        }
        savePlaylists(context, playlists);
        prefs(context).edit().remove(KEY_LEGACY_PLAYLIST_URL).apply();
    }

    private static long newPlaylistId(Context context) {
        long id = prefs(context).getLong(KEY_NEXT_PLAYLIST_ID, 1);
        prefs(context).edit().putLong(KEY_NEXT_PLAYLIST_ID, id + 1).apply();
        return id;
    }

    /**
     * Returns, for each IPTV channel ID, a short name of its list: "Free channels", the name of
     * the file, the last part of the URL, or "user @ server". Must not be called on the main
     * thread.
     */
    public static Map<Long, String> getChannelListNames(Context context) {
        Map<Long, String> listNames = new HashMap<>();
        for (Playlist playlist : getPlaylists(context)) {
            listNames.put(playlist.id, getShortName(context, playlist));
        }
        Map<Long, String> channelNames = new HashMap<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                TvContract.buildChannelsUriForInput(getInputId(context)),
                                new String[] {
                                    Channels._ID, Channels.COLUMN_INTERNAL_PROVIDER_FLAG1
                                },
                                null,
                                null,
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.isNull(1) ? null : listNames.get(cursor.getLong(1));
                if (name != null) {
                    channelNames.put(cursor.getLong(0), name);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the lists of the channels", e);
        }
        return channelNames;
    }

    private static String getShortName(Context context, Playlist playlist) {
        if (IptvSetupActivity.DEFAULT_PLAYLIST_URL.equals(playlist.source)) {
            return context.getString(R.string.livetv_iptv_free_channels);
        }
        String label = playlist.label;
        if (!playlist.hasLogin() && (label.startsWith("http://") || label.startsWith("https://"))) {
            Uri uri = Uri.parse(label);
            String last = uri.getLastPathSegment();
            return last != null ? last : uri.getHost();
        }
        return label;
    }

    static String getInputId(Context context) {
        return TvContract.buildInputId(new ComponentName(context, IptvTvInputService.class));
    }

    /** Returns the stream URL saved for the channel, or {@code null}. */
    static String getStreamUrl(Context context, Uri channelUri) {
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                channelUri,
                                new String[] {Channels.COLUMN_INTERNAL_PROVIDER_DATA},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return new String(cursor.getBlob(0), StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to read " + channelUri, e);
        }
        return null;
    }

    /**
     * Downloads the playlist and adds its channels, replacing those of the same list if it was
     * imported before. Logos are downloaded afterwards in the background. Must not be called on the
     * main thread.
     */
    static ImportResult importPlaylist(Context context, String playlistUrl) throws Exception {
        return importPlaylist(context, playlistUrl, playlistUrl, playlistUrl, null);
    }

    /**
     * Like {@link #importPlaylist(Context, String)} for a provider that asks for a username and a
     * password (the Xtream Codes API that most IPTV providers use). The password is only kept in
     * the URL of the list and in the channels' stream URLs, never shown.
     *
     * @param server the address of the provider, such as {@code http://example.com:8080}.
     */
    static ImportResult importPlaylistWithLogin(
            Context context, String server, String username, String password) throws Exception {
        String typed = server.trim();
        while (typed.endsWith("/")) {
            typed = typed.substring(0, typed.length() - 1);
        }
        // Without "http://" or "https://", the encrypted https comes first: the password should
        // not travel in clear text when the server can avoid it.
        String[] bases =
                typed.contains("://")
                        ? new String[] {typed}
                        : new String[] {"https://" + typed, "http://" + typed};
        // The same server and username update the list, even with a new password or scheme.
        String source =
                LOGIN_SOURCE_PREFIX + typed.replaceFirst("^[a-zA-Z]+://", "") + "|" + username;
        String credentials =
                "username="
                        + URLEncoder.encode(username, "UTF-8")
                        + "&password="
                        + URLEncoder.encode(password, "UTF-8");
        IOException firstError = null;
        for (String base : bases) {
            Uri baseUri = Uri.parse(base);
            String host =
                    baseUri.getHost() + (baseUri.getPort() > 0 ? ":" + baseUri.getPort() : "");
            try {
                return importPlaylist(
                        context,
                        base + "/get.php?" + credentials + "&type=m3u_plus&output=ts",
                        source,
                        username + " @ " + host,
                        // Xtream servers publish their XMLTV guide next to the list.
                        base + "/xmltv.php?" + credentials);
            } catch (HttpStatusException e) {
                if (e.code == HttpURLConnection.HTTP_UNAUTHORIZED
                        || e.code == HttpURLConnection.HTTP_FORBIDDEN) {
                    // The server answered: the username or the password is wrong.
                    throw e;
                }
                firstError = firstError == null ? e : firstError;
            } catch (IOException e) {
                // No https on that server (or no answer): try the next address.
                firstError = firstError == null ? e : firstError;
            }
        }
        throw firstError;
    }

    /**
     * @param guideUrl the XMLTV guide of the list, or null to use the one the list names in its
     *     header (url-tvg), if any.
     */
    private static ImportResult importPlaylist(
            Context context,
            String playlistUrl,
            String source,
            String label,
            @Nullable String guideUrl)
            throws Exception {
        M3uParser.Result result;
        HttpURLConnection connection = open(playlistUrl, TIMEOUT_MS);
        try (InputStream in =
                new LimitedInputStream(connection.getInputStream(), MAX_PLAYLIST_BYTES)) {
            result = M3uParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
        return storeChannels(
                context,
                result.entries,
                source,
                label,
                guideUrl != null ? guideUrl : result.guideUrls);
    }

    /** Like {@link #importPlaylist} for an M3U file picked with a file manager. */
    static ImportResult importPlaylistFile(Context context, Uri fileUri) throws Exception {
        M3uParser.Result result;
        try (InputStream in = context.getContentResolver().openInputStream(fileUri)) {
            if (in == null) {
                throw new IOException("Cannot open the file");
            }
            result =
                    M3uParser.parse(
                            new InputStreamReader(
                                    new LimitedInputStream(in, MAX_PLAYLIST_BYTES),
                                    StandardCharsets.UTF_8));
        }
        return storeChannels(
                context,
                result.entries,
                getFileSource(fileUri),
                getFileName(context, fileUri),
                result.guideUrls);
    }

    /**
     * The source of a list from a file: a hash of its URI, which identifies the same file again
     * without keeping the URI, since file managers can put a network password in it.
     */
    private static String getFileSource(Uri fileUri) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(fileUri.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(FILE_SOURCE_PREFIX);
            for (byte b : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String getFileName(Context context, Uri fileUri) {
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                fileUri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the name of " + fileUri, e);
        }
        String name = fileUri.getLastPathSegment();
        return name != null ? name : fileUri.toString();
    }

    /**
     * Deletes a list and its channels (their logos go with them). Must not be called on the main
     * thread.
     *
     * @return the number of deleted channels.
     */
    static synchronized int deletePlaylist(Context context, long playlistId) {
        int deleted = deletePlaylistChannels(context, playlistId);
        IptvGuide.deleteFiles(context, playlistId);
        List<Playlist> playlists = getPlaylists(context);
        for (int i = 0; i < playlists.size(); i++) {
            if (playlists.get(i).id == playlistId) {
                playlists.remove(i);
                break;
            }
        }
        savePlaylists(context, playlists);
        return deleted;
    }

    /**
     * The TV provider only lets apps without the system permissions delete whole inputs or single
     * channels (a selection throws "Selection not allowed"): find the list's channels and delete
     * them one by one.
     */
    private static int deletePlaylistChannels(Context context, long playlistId) {
        return deleteChannelsOf(context, id -> id != null && id == playlistId);
    }

    /** Chooses channels by the ID of their list. */
    private interface PlaylistFilter {
        /** @param playlistId the list of the channel, or null if it has none. */
        boolean matches(Long playlistId);
    }

    /** Deletes the channels whose list ID matches {@code playlistFilter}. */
    private static int deleteChannelsOf(Context context, PlaylistFilter playlistFilter) {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        try (Cursor cursor =
                resolver.query(
                        TvContract.buildChannelsUriForInput(getInputId(context)),
                        new String[] {Channels._ID, Channels.COLUMN_INTERNAL_PROVIDER_FLAG1},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (playlistFilter.matches(cursor.isNull(1) ? null : cursor.getLong(1))) {
                    ops.add(
                            ContentProviderOperation.newDelete(
                                            TvContract.buildChannelUri(cursor.getLong(0)))
                                    .build());
                }
            }
        }
        int deleted = 0;
        for (int start = 0; start < ops.size(); start += BATCH_SIZE) {
            ArrayList<ContentProviderOperation> batch =
                    new ArrayList<>(ops.subList(start, Math.min(start + BATCH_SIZE, ops.size())));
            try {
                for (ContentProviderResult result :
                        resolver.applyBatch(TvContract.AUTHORITY, batch)) {
                    deleted += result.count != null ? result.count : 0;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot delete the channels", e);
            }
        }
        return deleted;
    }

    /** Deletes every channel imported by this input (their logos go with them). */
    private static int deleteChannels(Context context) {
        return context.getContentResolver()
                .delete(TvContract.buildChannelsUriForInput(getInputId(context)), null, null);
    }

    /**
     * Deletes channels left in the TV provider after the app data was cleared. Android only
     * removes an app's channels when it is uninstalled, so after "Clear data" the old import would
     * show up again although the user has not loaded any source. Call when the app starts.
     */
    public static void deleteOrphanedChannels(Context context) {
        if (prefs(context).getBoolean(KEY_CHANNELS_IMPORTED, false)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                int deleted = deleteChannels(appContext);
                                if (deleted > 0) {
                                    Log.i(TAG, "Deleted " + deleted + " orphaned channels");
                                }
                            } catch (RuntimeException e) {
                                Log.w(TAG, "Cannot delete orphaned channels", e);
                            }
                        },
                        TAG)
                .start();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * @param guideUrls the XMLTV guide the list names, or empty: then a guide set before for the
     *     same list is kept.
     */
    private static synchronized ImportResult storeChannels(
            Context context,
            List<M3uParser.Entry> entries,
            String source,
            String label,
            String guideUrls)
            throws Exception {
        if (entries.isEmpty()) {
            // An empty answer (an expired account, a server error page) must not wipe a list
            // imported before.
            return new ImportResult(0, 0);
        }
        String inputId = getInputId(context);
        ContentResolver resolver = context.getContentResolver();
        List<Playlist> playlists = getPlaylists(context);
        int index = -1;
        for (int i = 0; i < playlists.size(); i++) {
            if (playlists.get(i).source.equals(source)) {
                index = i;
                break;
            }
        }
        // The same list again gets a new ID: its old channels stay until the new ones are stored,
        // so a failed update leaves the list as it was.
        Long oldPlaylistId = index >= 0 ? playlists.get(index).id : null;
        long playlistId = newPlaylistId(context);

        // The other lists keep their channels: number the new ones after them.
        int otherChannels = 0;
        int lastNumber = 0;
        try (Cursor cursor =
                resolver.query(
                        TvContract.buildChannelsUriForInput(inputId),
                        new String[] {
                            Channels.COLUMN_DISPLAY_NUMBER, Channels.COLUMN_INTERNAL_PROVIDER_FLAG1
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (oldPlaylistId != null
                        && !cursor.isNull(1)
                        && cursor.getLong(1) == oldPlaylistId) {
                    continue;
                }
                otherChannels++;
                try {
                    lastNumber = Math.max(lastNumber, Integer.parseInt(cursor.getString(0)));
                } catch (NumberFormatException e) {
                    // Not a number set by LiveTV.
                }
            }
        }
        int count = Math.max(0, Math.min(entries.size(), MAX_CHANNELS - otherChannels));
        if (count == 0) {
            // No room left: the lists stay as they are.
            return new ImportResult(0, entries.size());
        }

        List<Uri> channelUris = new ArrayList<>(count);
        try {
            insertChannels(context, entries, count, playlistId, lastNumber, channelUris);
        } catch (Exception e) {
            // Leave no channel of a list that is not saved.
            try {
                deletePlaylistChannels(context, playlistId);
            } catch (RuntimeException cleanupError) {
                Log.w(TAG, "Cannot delete the channels of a failed import", cleanupError);
            }
            throw e;
        }

        String keptGuideUrls =
                !guideUrls.isEmpty() || index < 0 ? guideUrls : playlists.get(index).guideUrls;
        Playlist playlist =
                new Playlist(
                        playlistId,
                        source,
                        label,
                        count,
                        keptGuideUrls,
                        Playlist.GUIDE_NOT_READ,
                        0);
        if (index >= 0) {
            playlists.set(index, playlist);
        } else {
            playlists.add(playlist);
        }
        savePlaylists(context, playlists);
        if (oldPlaylistId != null) {
            IptvGuide.deleteFiles(context, oldPlaylistId);
            try {
                deletePlaylistChannels(context, oldPlaylistId);
            } catch (RuntimeException e) {
                // They are of no list now: deleted the next time the lists are read.
                Log.w(TAG, "Cannot delete the old channels of the list", e);
            }
        }

        final Context appContext = context.getApplicationContext();
        for (int i = 0; i < count; i++) {
            final String logoUrl = entries.get(i).logoUrl;
            final Uri channelUri = channelUris.get(i);
            if (logoUrl != null && channelUri != null) {
                LOGO_EXECUTOR.execute(() -> storeLogo(appContext, channelUri, logoUrl));
            }
        }
        return new ImportResult(count, entries.size(), playlistId);
    }

    /** Inserts the first {@code count} entries as channels of the list, in batches. */
    private static void insertChannels(
            Context context,
            List<M3uParser.Entry> entries,
            int count,
            long playlistId,
            int lastNumber,
            List<Uri> channelUris)
            throws Exception {
        String inputId = getInputId(context);
        ContentResolver resolver = context.getContentResolver();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < count; i++) {
            M3uParser.Entry entry = entries.get(i);
            ContentProviderOperation.Builder insert =
                    ContentProviderOperation.newInsert(Channels.CONTENT_URI)
                            .withValue(Channels.COLUMN_INPUT_ID, inputId)
                            .withValue(Channels.COLUMN_TYPE, Channels.TYPE_OTHER)
                            .withValue(
                                    Channels.COLUMN_SERVICE_TYPE,
                                    Channels.SERVICE_TYPE_AUDIO_VIDEO)
                            .withValue(
                                    Channels.COLUMN_DISPLAY_NUMBER,
                                    String.valueOf(lastNumber + i + 1))
                            .withValue(Channels.COLUMN_DISPLAY_NAME, entry.name)
                            .withValue(Channels.COLUMN_DESCRIPTION, entry.group)
                            .withValue(
                                    Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                                    entry.url.getBytes(StandardCharsets.UTF_8))
                            .withValue(Channels.COLUMN_INTERNAL_PROVIDER_FLAG1, playlistId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // The channel's id in the XMLTV guide, to find its programs.
                insert.withValue(Channels.COLUMN_INTERNAL_PROVIDER_ID, entry.guideId);
            }
            ops.add(insert.build());
            if (ops.size() == BATCH_SIZE || i == count - 1) {
                for (ContentProviderResult result : resolver.applyBatch(TvContract.AUTHORITY, ops)) {
                    channelUris.add(result.uri);
                }
                ops.clear();
            }
        }
    }

    private static void storeLogo(Context context, Uri channelUri, String logoUrl) {
        HttpURLConnection connection = null;
        try {
            connection = open(logoUrl, LOGO_TIMEOUT_MS);
            // Read whole first: a logo over the limit is not stored at all.
            ByteArrayOutputStream logo = new ByteArrayOutputStream();
            try (InputStream in =
                    new LimitedInputStream(connection.getInputStream(), MAX_LOGO_BYTES)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    logo.write(buffer, 0, read);
                }
            }
            try (OutputStream out =
                    context.getContentResolver()
                            .openOutputStream(TvContract.buildChannelLogoUri(channelUri))) {
                logo.writeTo(out);
            }
        } catch (Exception e) {
            Log.d(TAG, "No logo for " + channelUri + ": " + e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Opens the URL following redirects, including http <-> https ones. */
    static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            URL parsedUrl = new URL(url);
            // Only the web: not files of this device or other schemes, even through a redirect.
            if (!"http".equalsIgnoreCase(parsedUrl.getProtocol())
                    && !"https".equalsIgnoreCase(parsedUrl.getProtocol())) {
                throw new IOException("Unsupported URL scheme");
            }
            HttpURLConnection connection = (HttpURLConnection) parsedUrl.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && connection.getHeaderField("Location") != null) {
                url = new URL(new URL(url), connection.getHeaderField("Location")).toString();
                connection.disconnect();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                // The URL is left out of the message: it can hold a password.
                throw new HttpStatusException(code);
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    /** Fails once more than {@code limit} bytes are read. */
    static final class LimitedInputStream extends FilterInputStream {
        private long mRemaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            mRemaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                consume(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                consume(read);
            }
            return read;
        }

        private void consume(long bytes) throws IOException {
            mRemaining -= bytes;
            if (mRemaining < 0) {
                throw new TooLargeException();
            }
        }
    }

    /** A list or a logo over the size limit. */
    static final class TooLargeException extends IOException {
        TooLargeException() {
            super("Too large");
        }
    }

    /** An HTTP error, such as 401 when the username or the password is wrong. */
    static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private IptvChannels() {}
}
