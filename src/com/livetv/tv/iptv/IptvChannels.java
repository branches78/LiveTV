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
import android.content.ContentUris;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
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
    /** Whether the movies and episodes imported as channels by older builds were deleted. */
    private static final String KEY_VIDEOS_DELETED = "videos_deleted_2";
    /** URL of the single list imported before LiveTV supported several lists. */
    private static final String KEY_LEGACY_PLAYLIST_URL = "playlist_url";
    /** Start of the source of a list imported with a username and a password. */
    private static final String LOGIN_SOURCE_PREFIX = "login:";
    /**
     * Followed by the ID of a list imported with a username and a password: the address of its
     * Xtream API ({@code player_api.php} with the credentials), where its movies and series are.
     */
    private static final String KEY_VOD_API_PREFIX = "vod_api_";

    public static final String USER_AGENT = "LiveTV";
    /** Upper bound, for all the lists together, so the guide stays usable. */
    static final int MAX_CHANNELS = 3000;

    private static final int BATCH_SIZE = 200;
    /** Largest list accepted, so a huge file cannot run the app out of memory. */
    private static final long MAX_PLAYLIST_BYTES = 300L * 1024 * 1024;
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
        /** Movies and episodes of the list, which are not imported. */
        int videos;

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
        /** {@link #guideChannelCount} when the list's guides have no channels nor programs. */
        static final int GUIDE_EMPTY = -3;

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
            // Not logged with its message: it quotes the lists, with their passwords.
            Log.w(TAG, "Cannot read the lists: " + e.getClass().getSimpleName());
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
        // LiveTV: older builds gave an updated list a new ID and left the address of its movies
        // and series under the old one, with the password of then.
        SharedPreferences.Editor editor = null;
        for (String key : prefs(context).getAll().keySet()) {
            if (!key.startsWith(KEY_VOD_API_PREFIX)) {
                continue;
            }
            boolean listed;
            try {
                listed = ids.contains(Long.parseLong(key.substring(KEY_VOD_API_PREFIX.length())));
            } catch (NumberFormatException e) {
                listed = false;
            }
            if (!listed) {
                editor = editor != null ? editor : prefs(context).edit();
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private static void savePlaylists(Context context, List<Playlist> playlists) {
        JSONArray array = new JSONArray();
        try {
            for (Playlist playlist : playlists) {
                array.put(playlist.toJson());
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cannot save the lists: " + e.getClass().getSimpleName());
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
     * Returns, for each IPTV channel ID, a short name of its list: the name of
     * the file, the last part of the URL, or "user @ server". Must not be called on the main
     * thread.
     */
    public static Map<Long, String> getChannelListNames(Context context) {
        Map<Long, String> listNames = new HashMap<>();
        for (Playlist playlist : getPlaylists(context)) {
            listNames.put(playlist.id, getShortName(playlist));
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

    private static String getShortName(Playlist playlist) {
        String label = playlist.label;
        if (!playlist.hasLogin() && (label.startsWith("http://") || label.startsWith("https://"))) {
            Uri uri = Uri.parse(label);
            String last = uri.getLastPathSegment();
            return last != null ? last : uri.getHost();
        }
        return label;
    }

    public static String getInputId(Context context) {
        return TvContract.buildInputId(new ComponentName(context, IptvTvInputService.class));
    }

    /**
     * The player item for a live stream: HLS and DASH by the address, as most servers don't send
     * their type; anything else is detected from its content (MPEG-TS mostly).
     */
    public static MediaItem buildMediaItem(String streamUrl) {
        String lowerCaseUrl = streamUrl.toLowerCase(Locale.ROOT);
        MediaItem.Builder item = new MediaItem.Builder().setUri(streamUrl);
        if (lowerCaseUrl.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lowerCaseUrl.contains(".mpd")) {
            item.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        return item.build();
    }

    /**
     * Returns the stream URL saved for the channel, or {@code null}. Never log it: it can hold the
     * password of the list.
     */
    public static String getStreamUrl(Context context, Uri channelUri) {
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
        // The address can hold a password (e.g. "get.php?username=...&password=..."): the label,
        // which the user sees, has it hidden.
        return importPlaylist(
                context, playlistUrl, playlistUrl, maskPasswords(playlistUrl), null);
    }

    /** Parameters that carry a password in the addresses of lists, guides and APIs. */
    private static final String PASSWORD_PARAMETER = "(?i)([?&](?:password|pass|pwd)=)";

    /** Hides the value of "password=" (or "pass=", "pwd=") in the addresses. */
    static String maskPasswords(String urls) {
        return urls.replaceAll(PASSWORD_PARAMETER + "[^&\\s]*", "$1••••");
    }

    /** Whether {@code url} has a "password=" (or "pass=", "pwd=") parameter with a value. */
    private static boolean hasPasswordParameter(String url) {
        return Pattern.compile(PASSWORD_PARAMETER + "[^&\\s]").matcher(url).find();
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
                ImportResult result =
                        importPlaylist(
                                context,
                                base + "/get.php?" + credentials + "&type=m3u_plus&output=ts",
                                source,
                                username + " @ " + host,
                                // Xtream servers publish their XMLTV guide next to the list.
                                base + "/xmltv.php?" + credentials);
                if (result.playlistId >= 0) {
                    prefs(context)
                            .edit()
                            .putString(
                                    KEY_VOD_API_PREFIX + result.playlistId,
                                    base + "/player_api.php?" + credentials)
                            .apply();
                }
                return result;
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
            result =
                    M3uParser.parse(
                            new InputStreamReader(in, StandardCharsets.UTF_8), MAX_CHANNELS);
        } finally {
            connection.disconnect();
        }
        ImportResult imported =
                storeChannels(
                        context,
                        result.entries,
                        result.total,
                        source,
                        label,
                        guideUrl != null ? guideUrl : result.guideUrls);
        imported.videos = result.videos;
        return imported;
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
                                    StandardCharsets.UTF_8),
                            MAX_CHANNELS);
        }
        ImportResult imported =
                storeChannels(
                        context,
                        result.entries,
                        result.total,
                        getFileSource(fileUri),
                        getFileName(context, fileUri),
                        result.guideUrls);
        imported.videos = result.videos;
        return imported;
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
            // The URI is not logged: like the older saved ones, it can hold a password.
            Log.w(TAG, "Cannot read the name of the file", e);
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
        prefs(context).edit().remove(KEY_VOD_API_PREFIX + playlistId).apply();
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

    /**
     * Deletes, once, the movies and episodes that older builds imported as channels. Call when
     * the app starts.
     */
    public static void deleteVideoChannels(Context context) {
        if (prefs(context).getBoolean(KEY_VIDEOS_DELETED, false)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                deleteVideoChannelsNow(appContext);
                                prefs(appContext)
                                        .edit()
                                        .putBoolean(KEY_VIDEOS_DELETED, true)
                                        .apply();
                            } catch (Exception e) {
                                // Tried again the next time the app starts.
                                Log.w(TAG, "Cannot delete the movies and series", e);
                            }
                        },
                        TAG)
                .start();
    }

    private static synchronized void deleteVideoChannelsNow(Context context) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        // List ID -> channels left in the list.
        Map<Long, Integer> left = new HashMap<>();
        try (Cursor cursor =
                resolver.query(
                        TvContract.buildChannelsUriForInput(getInputId(context)),
                        new String[] {
                            Channels._ID,
                            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                            Channels.COLUMN_INTERNAL_PROVIDER_DATA
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                boolean video =
                        !cursor.isNull(2)
                                && M3uParser.isVideoFile(
                                        new String(cursor.getBlob(2), StandardCharsets.UTF_8));
                if (video) {
                    ops.add(
                            ContentProviderOperation.newDelete(
                                            TvContract.buildChannelUri(cursor.getLong(0)))
                                    .build());
                } else if (!cursor.isNull(1)) {
                    Integer count = left.get(cursor.getLong(1));
                    left.put(cursor.getLong(1), count == null ? 1 : count + 1);
                }
            }
        }
        if (ops.isEmpty()) {
            return;
        }
        for (int start = 0; start < ops.size(); start += BATCH_SIZE) {
            resolver.applyBatch(
                    TvContract.AUTHORITY,
                    new ArrayList<>(ops.subList(start, Math.min(start + BATCH_SIZE, ops.size()))));
        }
        Log.i(TAG, "Deleted " + ops.size() + " movies and episodes imported as channels");
        List<Playlist> playlists = getPlaylists(context);
        for (int i = 0; i < playlists.size(); i++) {
            Playlist p = playlists.get(i);
            Integer count = left.get(p.id);
            playlists.set(
                    i,
                    new Playlist(
                            p.id,
                            p.source,
                            p.label,
                            count == null ? 0 : count,
                            p.guideUrls,
                            p.guideChannelCount,
                            p.guideUpdatedMs));
        }
        savePlaylists(context, playlists);
    }

    /** The imported lists and the list of each channel, for "Customize channel list". */
    public static final class ChannelLists {
        /** List ID -> its short name (see {@link #getChannelListNames}), in import order. */
        public final Map<Long, String> names = new LinkedHashMap<>();
        /** Channel ID -> the ID of its list. */
        public final Map<Long, Long> listOfChannel = new HashMap<>();
    }

    /**
     * Returns the lists and which list each channel belongs to. Unlike {@link
     * #getChannelListNames}, it only reads the preferences and the channels, so it can be called
     * on the main thread.
     */
    public static ChannelLists getChannelLists(Context context) {
        ChannelLists lists = new ChannelLists();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_PLAYLISTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Playlist playlist = Playlist.fromJson(array.getJSONObject(i));
                lists.names.put(playlist.id, getShortName(playlist));
            }
        } catch (JSONException e) {
            // Not logged with its message: it quotes the lists, with their passwords.
            Log.w(TAG, "Cannot read the lists: " + e.getClass().getSimpleName());
        }
        if (lists.names.isEmpty()) {
            return lists;
        }
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
                if (!cursor.isNull(1)) {
                    lists.listOfChannel.put(cursor.getLong(0), cursor.getLong(1));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the lists of the channels", e);
        }
        return lists;
    }

    /** The IDs of the imported lists. Only reads the preferences: fine on the main thread. */
    public static List<Long> getPlaylistIds(Context context) {
        List<Long> ids = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_PLAYLISTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                ids.add(Playlist.fromJson(array.getJSONObject(i)).id);
            }
        } catch (JSONException e) {
            // Not logged with its message: it quotes the lists, with their passwords.
            Log.w(TAG, "Cannot read the lists: " + e.getClass().getSimpleName());
        }
        return ids;
    }

    /** The ID of the list of an IPTV channel, or -1. */
    public static long getPlaylistId(Context context, Uri channelUri) {
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                channelUri,
                                new String[] {Channels.COLUMN_INTERNAL_PROVIDER_FLAG1},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the list of a channel: " + e.getClass().getSimpleName());
        }
        return -1;
    }

    /** A list whose provider publishes movies and series through the Xtream API. */
    public static final class VodSource {
        public final long playlistId;
        /** What the user sees, such as "user @ example.com". */
        public final String label;
        /** {@code player_api.php} with the credentials; never shown, it holds the password. */
        public final String apiUrl;

        VodSource(long playlistId, String label, String apiUrl) {
            this.playlistId = playlistId;
            this.label = label;
            this.apiUrl = apiUrl;
        }
    }

    /**
     * Returns the lists with movies and series: the ones imported with a username and a password.
     * Only reads the preferences, so it can be called on the main thread.
     */
    public static List<VodSource> getVodSources(Context context) {
        List<VodSource> sources = new ArrayList<>();
        SharedPreferences prefs = prefs(context);
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_PLAYLISTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Playlist playlist = Playlist.fromJson(array.getJSONObject(i));
                if (!playlist.hasLogin()) {
                    continue;
                }
                String apiUrl = prefs.getString(KEY_VOD_API_PREFIX + playlist.id, null);
                if (apiUrl == null) {
                    apiUrl = getVodApiFromGuide(playlist.guideUrls);
                }
                if (apiUrl != null) {
                    sources.add(new VodSource(playlist.id, playlist.label, apiUrl));
                }
            }
        } catch (JSONException e) {
            // Not logged with its message: it quotes the lists, with their passwords.
            Log.w(TAG, "Cannot read the lists: " + e.getClass().getSimpleName());
        }
        return sources;
    }

    /**
     * Lists imported before the API address was kept: the Xtream guide, {@code xmltv.php} with
     * the credentials, is next to the API.
     */
    @Nullable
    private static String getVodApiFromGuide(String guideUrls) {
        for (String url : guideUrls.split("\\s+")) {
            int index = url.indexOf("/xmltv.php?");
            if (index > 0 && url.contains("username=") && url.contains("password=")) {
                return url.substring(0, index)
                        + "/player_api.php?"
                        + url.substring(index + "/xmltv.php?".length());
            }
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether any list is imported. Only reads a preference: fine on the main thread. */
    public static boolean hasPlaylists(Context context) {
        return prefs(context).getBoolean(KEY_CHANNELS_IMPORTED, false);
    }

    /**
     * The Xtream API address ({@code player_api.php} with the credentials) of a list imported
     * with a username and a password, or null. Never log it: it holds the password.
     */
    @Nullable
    static String getVodApiUrl(Context context, long playlistId) {
        return prefs(context).getString(KEY_VOD_API_PREFIX + playlistId, null);
    }

    static long getNextPlaylistId(Context context) {
        return prefs(context).getLong(KEY_NEXT_PLAYLIST_ID, 1);
    }

    /** A channel of a backup ({@link IptvBackup}), written back as it was. */
    static final class BackupChannel {
        final long playlistId;
        final String number;
        final String name;
        final String group;
        /** Never log it: it can hold the password of the list. */
        final String url;
        @Nullable final String guideId;

        BackupChannel(
                long playlistId,
                String number,
                String name,
                String group,
                String url,
                @Nullable String guideId) {
            this.playlistId = playlistId;
            this.number = number;
            this.name = name;
            this.group = group;
            this.url = url;
            this.guideId = guideId;
        }
    }

    /**
     * Reads the channels of every list, in the order of the TV provider, with their IDs in {@code
     * ids}. Must not be called on the main thread.
     */
    static List<BackupChannel> readBackupChannels(Context context, List<Long> ids) {
        List<BackupChannel> channels = new ArrayList<>();
        List<String> projection =
                new ArrayList<>(
                        Arrays.asList(
                                Channels._ID,
                                Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                                Channels.COLUMN_DISPLAY_NUMBER,
                                Channels.COLUMN_DISPLAY_NAME,
                                Channels.COLUMN_DESCRIPTION,
                                Channels.COLUMN_INTERNAL_PROVIDER_DATA));
        boolean hasGuideId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        if (hasGuideId) {
            projection.add(Channels.COLUMN_INTERNAL_PROVIDER_ID);
        }
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                TvContract.buildChannelsUriForInput(getInputId(context)),
                                projection.toArray(new String[0]),
                                null,
                                null,
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.isNull(1) || cursor.isNull(5)) {
                    continue;
                }
                ids.add(cursor.getLong(0));
                channels.add(
                        new BackupChannel(
                                cursor.getLong(1),
                                cursor.isNull(2) ? "" : cursor.getString(2),
                                cursor.isNull(3) ? "" : cursor.getString(3),
                                cursor.isNull(4) ? "" : cursor.getString(4),
                                new String(cursor.getBlob(5), StandardCharsets.UTF_8),
                                hasGuideId && !cursor.isNull(6) ? cursor.getString(6) : null));
            }
        }
        return channels;
    }

    /**
     * Writes back the lists and the channels of a backup, when no list is imported. Returns the
     * new ID of each channel, in the order of {@code channels} (-1 for those beyond {@link
     * #MAX_CHANNELS}). Nothing is kept if it fails. Must not be called on the main thread.
     *
     * @param vodApis the Xtream API address of the lists imported with a login, by list ID.
     */
    static synchronized long[] restoreBackup(
            Context context,
            List<Playlist> playlists,
            Map<Long, String> vodApis,
            long nextPlaylistId,
            List<BackupChannel> channels)
            throws Exception {
        if (!getPlaylists(context).isEmpty()) {
            throw new IllegalStateException("Lists already imported");
        }
        // The lists first: channels of no list are deleted when the lists are read.
        long next = nextPlaylistId;
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Playlist playlist : playlists) {
            next = Math.max(next, playlist.id + 1);
            String vodApi = vodApis.get(playlist.id);
            if (vodApi != null) {
                editor.putString(KEY_VOD_API_PREFIX + playlist.id, vodApi);
            }
        }
        editor.putLong(KEY_NEXT_PLAYLIST_ID, next).commit();
        savePlaylists(context, playlists);

        String inputId = getInputId(context);
        ContentResolver resolver = context.getContentResolver();
        int count = Math.min(channels.size(), MAX_CHANNELS);
        long[] ids = new long[channels.size()];
        Arrays.fill(ids, -1);
        List<Uri> inserted = new ArrayList<>();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>(BATCH_SIZE);
        try {
            for (int i = 0; i < count; i++) {
                BackupChannel channel = channels.get(i);
                ContentProviderOperation.Builder operation =
                        ContentProviderOperation.newInsert(Channels.CONTENT_URI)
                                .withValue(Channels.COLUMN_INPUT_ID, inputId)
                                .withValue(Channels.COLUMN_TYPE, Channels.TYPE_OTHER)
                                .withValue(
                                        Channels.COLUMN_SERVICE_TYPE,
                                        Channels.SERVICE_TYPE_AUDIO_VIDEO)
                                .withValue(Channels.COLUMN_DISPLAY_NUMBER, channel.number)
                                .withValue(Channels.COLUMN_DISPLAY_NAME, channel.name)
                                .withValue(Channels.COLUMN_DESCRIPTION, channel.group)
                                .withValue(
                                        Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                                        channel.url.getBytes(StandardCharsets.UTF_8))
                                .withValue(
                                        Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                                        channel.playlistId);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    operation.withValue(Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.guideId);
                }
                ops.add(operation.build());
                if (ops.size() == BATCH_SIZE || i == count - 1) {
                    ContentProviderResult[] results =
                            resolver.applyBatch(TvContract.AUTHORITY, ops);
                    int first = i - ops.size() + 1;
                    for (int j = 0; j < results.length; j++) {
                        if (results[j].uri != null) {
                            inserted.add(results[j].uri);
                            ids[first + j] = ContentUris.parseId(results[j].uri);
                        }
                    }
                    ops.clear();
                }
            }
        } catch (Exception e) {
            try {
                deleteChannelUris(resolver, inserted);
            } catch (RuntimeException cleanupError) {
                // Channels of no list: deleted the next time the lists are read.
                Log.w(TAG, "Cannot delete the channels of a failed restore", cleanupError);
            }
            SharedPreferences.Editor rollback = prefs(context).edit();
            for (Playlist playlist : playlists) {
                rollback.remove(KEY_VOD_API_PREFIX + playlist.id);
            }
            rollback.apply();
            savePlaylists(context, new ArrayList<>());
            throw e;
        }
        return ids;
    }

    /**
     * Downloads a list again from where it was imported, updating its channels in place (they
     * keep their IDs): after a backup is restored, it brings the logos and the channels added
     * since. Lists from a file can't be downloaded: returns null for them. Must not be called on
     * the main thread.
     */
    @Nullable
    static ImportResult refreshPlaylist(Context context, Playlist playlist) throws Exception {
        String guideUrl = playlist.guideUrls.isEmpty() ? null : playlist.guideUrls;
        if (playlist.hasLogin()) {
            String api = getVodApiUrl(context, playlist.id);
            int index = api == null ? -1 : api.indexOf("/player_api.php?");
            if (index < 0) {
                return null;
            }
            String base = api.substring(0, index);
            String credentials = api.substring(index + "/player_api.php?".length());
            return importPlaylist(
                    context,
                    base + "/get.php?" + credentials + "&type=m3u_plus&output=ts",
                    playlist.source,
                    playlist.label,
                    guideUrl != null ? guideUrl : base + "/xmltv.php?" + credentials);
        }
        if (isWebUrl(playlist.source)) {
            return importPlaylist(
                    context, playlist.source, playlist.source, playlist.label, guideUrl);
        }
        return null;
    }

    /**
     * @param guideUrls the XMLTV guide the list names, or empty: then a guide set before for the
     *     same list is kept.
     */
    private static synchronized ImportResult storeChannels(
            Context context,
            List<M3uParser.Entry> entries,
            int total,
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
        // LiveTV: the same list again keeps its ID, and its channels keep theirs (updated in
        // place). Favorites, the order of "All", hidden channels, scheduled recordings, the
        // progress of the movies and series... are kept by those IDs, and an update used to lose
        // them all.
        boolean update = index >= 0;
        long playlistId = update ? playlists.get(index).id : newPlaylistId(context);

        // The other lists keep their channels: the new ones are numbered after every channel.
        int otherChannels = 0;
        int lastNumber = 0;
        List<StoredChannel> oldChannels = new ArrayList<>();
        try (Cursor cursor =
                resolver.query(
                        TvContract.buildChannelsUriForInput(inputId),
                        new String[] {
                            Channels._ID,
                            Channels.COLUMN_DISPLAY_NUMBER,
                            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
                            Channels.COLUMN_DISPLAY_NAME,
                            Channels.COLUMN_INTERNAL_PROVIDER_DATA
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                try {
                    lastNumber = Math.max(lastNumber, Integer.parseInt(cursor.getString(1)));
                } catch (NumberFormatException e) {
                    // Not a number set by LiveTV.
                }
                if (update && !cursor.isNull(2) && cursor.getLong(2) == playlistId) {
                    oldChannels.add(
                            new StoredChannel(
                                    cursor.getLong(0),
                                    cursor.isNull(3) ? "" : cursor.getString(3),
                                    cursor.isNull(4)
                                            ? ""
                                            : new String(
                                                    cursor.getBlob(4), StandardCharsets.UTF_8)));
                } else {
                    otherChannels++;
                }
            }
        }
        int count = Math.max(0, Math.min(entries.size(), MAX_CHANNELS - otherChannels));
        if (count == 0) {
            // No room left: the lists stay as they are.
            return new ImportResult(0, total);
        }

        long[] channelIds = matchChannels(entries, count, oldChannels);
        List<Uri> channelUris = new ArrayList<>(count);
        List<Uri> inserted = new ArrayList<>();
        try {
            writeChannels(
                    context, entries, count, playlistId, lastNumber, channelIds, channelUris,
                    inserted);
        } catch (Exception e) {
            // A new list leaves no channel; an updated one keeps its channels (some may be
            // updated already) without the new ones.
            try {
                deleteChannelUris(resolver, inserted);
            } catch (RuntimeException cleanupError) {
                // Those of a new list belong to no list: deleted the next time the lists are read.
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
        if (update) {
            playlists.set(index, playlist);
        } else {
            playlists.add(playlist);
        }
        savePlaylists(context, playlists);

        // The channels gone from the list.
        List<Uri> gone = new ArrayList<>();
        for (StoredChannel channel : oldChannels) {
            if (!channel.matched) {
                gone.add(TvContract.buildChannelUri(channel.id));
            }
        }
        try {
            deleteChannelUris(resolver, gone);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot delete the channels gone from the list", e);
        }
        if (update) {
            Log.i(
                    TAG,
                    "List "
                            + playlistId
                            + " updated: "
                            + (count - inserted.size())
                            + " channels kept, "
                            + inserted.size()
                            + " added, "
                            + gone.size()
                            + " gone");
        }

        final Context appContext = context.getApplicationContext();
        for (int i = 0; i < count; i++) {
            final String logoUrl = entries.get(i).logoUrl;
            final Uri channelUri = channelUris.get(i);
            if (logoUrl != null && channelUri != null) {
                LOGO_EXECUTOR.execute(() -> storeLogo(appContext, channelUri, logoUrl));
            }
        }
        return new ImportResult(count, total, playlistId);
    }

    /** A channel of a list being updated. */
    private static final class StoredChannel {
        final long id;
        final String name;
        final String streamUrl;
        /** Whether a channel of the new list takes it. */
        boolean matched;

        StoredChannel(long id, String name, String streamUrl) {
            this.id = id;
            this.name = name;
            this.streamUrl = streamUrl;
        }
    }

    /**
     * Finds, for each of the first {@code count} entries, the channel of the list it updates:
     * the one with the same stream, or else (e.g. a new password changes every stream address)
     * the one with the same name. Returns their IDs, -1 for a new channel.
     */
    private static long[] matchChannels(
            List<M3uParser.Entry> entries, int count, List<StoredChannel> oldChannels) {
        long[] ids = new long[count];
        Arrays.fill(ids, -1);
        if (oldChannels.isEmpty()) {
            return ids;
        }
        Map<String, List<StoredChannel>> byUrl = new HashMap<>();
        Map<String, List<StoredChannel>> byName = new HashMap<>();
        for (StoredChannel channel : oldChannels) {
            addTo(byUrl, channel.streamUrl, channel);
            addTo(byName, channel.name.trim().toLowerCase(Locale.ROOT), channel);
        }
        for (int i = 0; i < count; i++) {
            StoredChannel channel = takeUnmatched(byUrl.get(entries.get(i).url));
            if (channel != null) {
                ids[i] = channel.id;
            }
        }
        for (int i = 0; i < count; i++) {
            if (ids[i] < 0) {
                StoredChannel channel =
                        takeUnmatched(
                                byName.get(entries.get(i).name.trim().toLowerCase(Locale.ROOT)));
                if (channel != null) {
                    ids[i] = channel.id;
                }
            }
        }
        return ids;
    }

    private static void addTo(
            Map<String, List<StoredChannel>> map, String key, StoredChannel channel) {
        List<StoredChannel> channels = map.get(key);
        if (channels == null) {
            channels = new ArrayList<>(1);
            map.put(key, channels);
        }
        channels.add(channel);
    }

    @Nullable
    private static StoredChannel takeUnmatched(@Nullable List<StoredChannel> channels) {
        if (channels != null) {
            for (StoredChannel channel : channels) {
                if (!channel.matched) {
                    channel.matched = true;
                    return channel;
                }
            }
        }
        return null;
    }

    /**
     * Writes the first {@code count} entries as channels of the list, in batches: updates the
     * channel of {@code channelIds} (keeping its number) or inserts a new one.
     *
     * @param channelUris receives the channel of each entry.
     * @param inserted receives the channels inserted, to delete them if a batch fails.
     */
    private static void writeChannels(
            Context context,
            List<M3uParser.Entry> entries,
            int count,
            long playlistId,
            int lastNumber,
            long[] channelIds,
            List<Uri> channelUris,
            List<Uri> inserted)
            throws Exception {
        String inputId = getInputId(context);
        ContentResolver resolver = context.getContentResolver();
        ArrayList<ContentProviderOperation> ops = new ArrayList<>(BATCH_SIZE);
        int newChannels = 0;
        for (int i = 0; i < count; i++) {
            M3uParser.Entry entry = entries.get(i);
            ContentProviderOperation.Builder operation;
            if (channelIds[i] >= 0) {
                Uri channelUri = TvContract.buildChannelUri(channelIds[i]);
                operation = ContentProviderOperation.newUpdate(channelUri);
                channelUris.add(channelUri);
            } else {
                newChannels++;
                operation =
                        ContentProviderOperation.newInsert(Channels.CONTENT_URI)
                                .withValue(Channels.COLUMN_INPUT_ID, inputId)
                                .withValue(Channels.COLUMN_TYPE, Channels.TYPE_OTHER)
                                .withValue(
                                        Channels.COLUMN_SERVICE_TYPE,
                                        Channels.SERVICE_TYPE_AUDIO_VIDEO)
                                .withValue(
                                        Channels.COLUMN_DISPLAY_NUMBER,
                                        String.valueOf(lastNumber + newChannels));
                // Its URI comes with the result of the batch.
                channelUris.add(null);
            }
            operation
                    .withValue(Channels.COLUMN_DISPLAY_NAME, entry.name)
                    .withValue(Channels.COLUMN_DESCRIPTION, entry.group)
                    .withValue(
                            Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                            entry.url.getBytes(StandardCharsets.UTF_8))
                    .withValue(Channels.COLUMN_INTERNAL_PROVIDER_FLAG1, playlistId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // The channel's id in the XMLTV guide, to find its programs.
                operation.withValue(Channels.COLUMN_INTERNAL_PROVIDER_ID, entry.guideId);
            }
            ops.add(operation.build());
            if (ops.size() == BATCH_SIZE || i == count - 1) {
                ContentProviderResult[] results = resolver.applyBatch(TvContract.AUTHORITY, ops);
                int first = i - ops.size() + 1;
                for (int j = 0; j < results.length; j++) {
                    if (results[j].uri != null && channelIds[first + j] < 0) {
                        channelUris.set(first + j, results[j].uri);
                        inserted.add(results[j].uri);
                    }
                }
                ops.clear();
            }
        }
    }

    /** Deletes the channels one by one (the TV provider allows no selection), in batches. */
    private static void deleteChannelUris(ContentResolver resolver, List<Uri> channelUris) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (Uri uri : channelUris) {
            ops.add(ContentProviderOperation.newDelete(uri).build());
        }
        for (int start = 0; start < ops.size(); start += BATCH_SIZE) {
            try {
                resolver.applyBatch(
                        TvContract.AUTHORITY,
                        new ArrayList<>(
                                ops.subList(start, Math.min(start + BATCH_SIZE, ops.size()))));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot delete the channels", e);
            }
        }
    }

    /** Whether {@code url} is a web address (http or https). */
    static boolean isWebUrl(String url) {
        String lowerCase = url.toLowerCase(Locale.ROOT);
        return lowerCase.startsWith("http://") || lowerCase.startsWith("https://");
    }

    /**
     * Downloads, in the background, a logo for a channel that has none: the icon of its channel
     * in the program guide.
     */
    static void storeLogoIfMissing(Context context, long channelId, String logoUrl) {
        Context appContext = context.getApplicationContext();
        Uri channelUri = TvContract.buildChannelUri(channelId);
        LOGO_EXECUTOR.execute(
                () -> {
                    try (InputStream in =
                            appContext
                                    .getContentResolver()
                                    .openInputStream(TvContract.buildChannelLogoUri(channelUri))) {
                        if (in != null && in.read() != -1) {
                            return;
                        }
                    } catch (Exception e) {
                        // No logo yet.
                    }
                    storeLogo(appContext, channelUri, logoUrl);
                });
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
            // Not logged with its message: FileNotFoundException quotes the URL of the logo.
            Log.d(TAG, "No logo for " + channelUri + ": " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Opens the URL following redirects, including http <-> https ones. */
    public static HttpURLConnection open(String url, int timeoutMs) throws IOException {
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
                URL target = new URL(parsedUrl, connection.getHeaderField("Location"));
                connection.disconnect();
                // LiveTV: an address with the password as a parameter (list, guide, movies and
                // series) asked over https is not sent again over http, in clear text.
                if ("https".equalsIgnoreCase(parsedUrl.getProtocol())
                        && "http".equalsIgnoreCase(target.getProtocol())
                        && hasPasswordParameter(target.toString())) {
                    throw new IOException("Redirected from https to http");
                }
                url = target.toString();
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
    public static final class LimitedInputStream extends FilterInputStream {
        private long mRemaining;

        public LimitedInputStream(InputStream in, long limit) {
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
