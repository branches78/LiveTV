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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.tv.TvSingletons;
import com.android.tv.data.ChannelDataManager;
import com.livetv.tv.channels.ChannelOrder;
import com.livetv.tv.channels.Favorites;
import com.livetv.tv.multiview.MultiviewActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * LiveTV: a backup of the IPTV setup in a file, to set up another TV (or this one after its data
 * was cleared) without typing the lists again. It holds the lists with their credentials, their
 * channels, which ones are hidden, the favorites, the order of "All", the chosen list and the
 * guide settings. The tuners are not in it: they are always set up by hand.
 *
 * <p>The file is encrypted with a PIN: AES-256-GCM with a key derived by PBKDF2. A short PIN only
 * keeps the passwords from being read by whoever opens the file; it doesn't stand a computer
 * trying every PIN.
 */
public final class IptvBackup {
    private static final String TAG = "IptvBackup";

    public static final int MIN_PIN_LENGTH = 4;
    public static final int MAX_PIN_LENGTH = 8;
    static final String FILE_EXTENSION = ".livetv";

    private static final byte[] MAGIC = {'L', 'T', 'V', 'B'};
    private static final int FILE_VERSION = 1;
    private static final String FORMAT = "livetv-iptv-backup";
    private static final int ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 4 + SALT_BYTES + IV_BYTES;
    /** Largest file accepted: a backup of every channel is far smaller. */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_JSON_BYTES = 256L * 1024 * 1024;
    /** How long the restore waits for the channels to show up before applying the rest. */
    private static final long CHANNELS_TIMEOUT_MS = 60000;

    /** The file isn't a LiveTV backup, or it is damaged. */
    public static final class NotBackupException extends IOException {
        NotBackupException() {
            super("Not a LiveTV backup");
        }
    }

    /** The PIN doesn't open the file. */
    public static final class WrongPinException extends GeneralSecurityException {
        WrongPinException() {
            super("Wrong PIN");
        }
    }

    /** There are lists already: a backup is only restored on an empty setup. */
    public static final class AlreadySetUpException extends IllegalStateException {
        AlreadySetUpException() {
            super("Lists already imported");
        }
    }

    /** What an export or a restore did. */
    public static final class Result {
        public final int lists;
        public final int channels;
        /** The name of the saved file (export only). */
        @Nullable public final String fileName;

        Result(int lists, int channels, @Nullable String fileName) {
            this.lists = lists;
            this.channels = channels;
            this.fileName = fileName;
        }
    }

    private IptvBackup() {}

    /** Whether {@code pin} is 4 to 8 digits. */
    public static boolean isValidPin(String pin) {
        if (pin.length() < MIN_PIN_LENGTH || pin.length() > MAX_PIN_LENGTH) {
            return false;
        }
        for (int i = 0; i < pin.length(); i++) {
            if (pin.charAt(i) < '0' || pin.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Saves the backup in the Downloads folder. Must not be called on the main thread.
     *
     * @param hiddenChannelIds the channels the user hid, from the {@link ChannelDataManager}.
     */
    public static Result export(Context context, String pin, Set<Long> hiddenChannelIds)
            throws Exception {
        List<IptvChannels.Playlist> playlists = IptvChannels.getPlaylists(context);
        if (playlists.isEmpty()) {
            throw new IllegalStateException("No lists");
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", FILE_VERSION);
        root.put("created", System.currentTimeMillis());
        root.put("nextPlaylistId", IptvChannels.getNextPlaylistId(context));

        JSONArray lists = new JSONArray();
        for (IptvChannels.Playlist playlist : playlists) {
            JSONObject list = playlist.toJson();
            String vodApi = IptvChannels.getVodApiUrl(context, playlist.id);
            if (vodApi != null) {
                list.put("vodApi", vodApi);
            }
            lists.put(list);
        }
        root.put("lists", lists);

        List<Long> ids = new ArrayList<>();
        List<IptvChannels.BackupChannel> channels = IptvChannels.readBackupChannels(context, ids);
        Set<String> favorites = Favorites.getIds(context);
        Map<Long, Integer> order = new HashMap<>();
        List<Long> savedOrder = ChannelOrder.getSavedOrder(context);
        for (int i = 0; i < savedOrder.size(); i++) {
            order.put(savedOrder.get(i), i);
        }
        long[] multiviewIds = MultiviewActivity.getSavedChannelIds(context);
        JSONArray channelArray = new JSONArray();
        for (int i = 0; i < channels.size(); i++) {
            IptvChannels.BackupChannel channel = channels.get(i);
            long id = ids.get(i);
            JSONObject json =
                    new JSONObject()
                            .put("list", channel.playlistId)
                            .put("number", channel.number)
                            .put("name", channel.name)
                            .put("group", channel.group)
                            .put("url", channel.url);
            if (channel.guideId != null) {
                json.put("guideId", channel.guideId);
            }
            if (hiddenChannelIds.contains(id)) {
                json.put("hidden", true);
            }
            if (favorites.contains(String.valueOf(id))) {
                json.put("favorite", true);
            }
            Integer position = order.get(id);
            if (position != null) {
                json.put("order", position);
            }
            // The places of the multiview that show it.
            JSONArray places = new JSONArray();
            for (int place = 0; place < multiviewIds.length; place++) {
                if (multiviewIds[place] == id) {
                    places.put(place);
                }
            }
            if (places.length() > 0) {
                json.put("multiview", places);
            }
            channelArray.put(json);
        }
        root.put("channels", channelArray);
        root.put("multiviewLayout", MultiviewActivity.getSavedLayout(context));
        root.put("grouping", ChannelOrder.getGroupingType(context));

        JSONObject assignments = new JSONObject();
        for (Map.Entry<String, String> entry : IptvGuideAssignments.getAll(context).entrySet()) {
            assignments.put(entry.getKey(), entry.getValue());
        }
        root.put("guideAssignments", assignments);

        byte[] file = encrypt(gzip(root.toString().getBytes(StandardCharsets.UTF_8)), pin);
        String fileName =
                "LiveTV-IPTV-"
                        + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date())
                        + FILE_EXTENSION;
        fileName = writeToDownloads(context, fileName, file);
        return new Result(playlists.size(), channels.size(), fileName);
    }

    /**
     * Restores a backup on an empty setup. The channels come back at once from the file; then, in
     * the background, the lists are downloaded again (for their logos and the channels added
     * since) and their guides read. Must not be called on the main thread.
     */
    public static Result restore(Context context, Uri fileUri, String pin) throws Exception {
        if (IptvChannels.hasPlaylists(context)) {
            throw new AlreadySetUpException();
        }
        JSONObject root;
        try {
            byte[] json = gunzip(decrypt(readFile(context, fileUri), pin));
            root = new JSONObject(new String(json, StandardCharsets.UTF_8));
        } catch (JSONException | IOException e) {
            if (e instanceof NotBackupException) {
                throw e;
            }
            throw new NotBackupException();
        }
        if (!FORMAT.equals(root.optString("format")) || root.optInt("version") > FILE_VERSION) {
            throw new NotBackupException();
        }

        List<IptvChannels.Playlist> playlists = new ArrayList<>();
        Map<Long, String> vodApis = new HashMap<>();
        Set<Long> listIds = new HashSet<>();
        JSONArray lists = root.optJSONArray("lists");
        for (int i = 0; lists != null && i < lists.length(); i++) {
            JSONObject list = lists.getJSONObject(i);
            IptvChannels.Playlist saved = IptvChannels.Playlist.fromJson(list);
            if (saved.id <= 0 || !listIds.add(saved.id)) {
                continue;
            }
            // The guide is read again.
            playlists.add(saved.withGuideUrls(saved.guideUrls));
            String vodApi = list.optString("vodApi", "");
            if (IptvChannels.isWebUrl(vodApi)) {
                vodApis.put(saved.id, vodApi);
            }
        }
        if (playlists.isEmpty()) {
            throw new NotBackupException();
        }

        List<IptvChannels.BackupChannel> channels = new ArrayList<>();
        List<JSONObject> channelJsons = new ArrayList<>();
        JSONArray channelArray = root.optJSONArray("channels");
        for (int i = 0; channelArray != null && i < channelArray.length(); i++) {
            JSONObject json = channelArray.getJSONObject(i);
            long listId = json.optLong("list", -1);
            String url = json.optString("url", "");
            if (!listIds.contains(listId) || url.isEmpty()) {
                continue;
            }
            channels.add(
                    new IptvChannels.BackupChannel(
                            listId,
                            json.optString("number", ""),
                            json.optString("name", ""),
                            json.optString("group", ""),
                            url,
                            json.has("guideId") ? json.optString("guideId") : null));
            channelJsons.add(json);
        }

        long[] ids;
        try {
            ids =
                    IptvChannels.restoreBackup(
                            context,
                            playlists,
                            vodApis,
                            root.optLong("nextPlaylistId", 1),
                            channels);
        } catch (IllegalStateException e) {
            if (IptvChannels.hasPlaylists(context)) {
                throw new AlreadySetUpException();
            }
            throw e;
        }
        int restored = 0;
        for (long id : ids) {
            if (id >= 0) {
                restored++;
            }
        }

        // Kept by a hash of the stream URL, so they apply as they are.
        JSONObject assignments = root.optJSONObject("guideAssignments");
        if (assignments != null) {
            Map<String, String> map = new HashMap<>();
            for (Iterator<String> keys = assignments.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, assignments.optString(key));
            }
            IptvGuideAssignments.putAll(context, map);
        }
        if (root.has("grouping")) {
            ChannelOrder.setGroupingType(context, root.optInt("grouping"));
        }

        // Kept by channel ID: applied once the TV knows the new channels.
        List<Long> favorites = new ArrayList<>();
        List<Long> hidden = new ArrayList<>();
        List<long[]> ordered = new ArrayList<>();
        long[] multiviewIds = new long[MultiviewActivity.getSavedChannelIds(context).length];
        Arrays.fill(multiviewIds, -1);
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0) {
                continue;
            }
            JSONObject json = channelJsons.get(i);
            if (json.optBoolean("favorite")) {
                favorites.add(ids[i]);
            }
            if (json.optBoolean("hidden")) {
                hidden.add(ids[i]);
            }
            if (json.has("order")) {
                ordered.add(new long[] {json.optLong("order"), ids[i]});
            }
            JSONArray places = json.optJSONArray("multiview");
            for (int j = 0; places != null && j < places.length(); j++) {
                int place = places.optInt(j, -1);
                if (place >= 0 && place < multiviewIds.length) {
                    multiviewIds[place] = ids[i];
                }
            }
        }
        // Files saved before the multiview was kept leave it as it is.
        if (root.has("multiviewLayout")) {
            MultiviewActivity.saveState(context, root.optInt("multiviewLayout"), multiviewIds);
        }
        Collections.sort(ordered, (lhs, rhs) -> Long.compare(lhs[0], rhs[0]));
        List<Long> order = new ArrayList<>();
        for (long[] entry : ordered) {
            order.add(entry[1]);
        }
        Context appContext = context.getApplicationContext();
        List<Long> waitFor = new ArrayList<>();
        for (long id : ids) {
            if (id >= 0) {
                waitFor.add(id);
            }
        }
        new Handler(Looper.getMainLooper())
                .post(
                        () ->
                                applyWhenLoaded(
                                        appContext,
                                        waitFor,
                                        favorites,
                                        hidden,
                                        order,
                                        () -> refreshLists(appContext)));
        return new Result(playlists.size(), restored, null);
    }

    /**
     * Marks the favorites, the hidden channels and the order once the {@link ChannelDataManager}
     * has the restored channels (an earlier reload would forget them), then runs {@code then}.
     */
    private static void applyWhenLoaded(
            Context context,
            List<Long> channelIds,
            List<Long> favorites,
            List<Long> hidden,
            List<Long> order,
            Runnable then) {
        ChannelDataManager manager = TvSingletons.getSingletons(context).getChannelDataManager();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable apply =
                () -> {
                    Favorites.addAll(context, favorites);
                    if (!order.isEmpty()) {
                        List<Long> newOrder = new ArrayList<>(order);
                        for (long id : ChannelOrder.getSavedOrder(context)) {
                            if (!newOrder.contains(id)) {
                                newOrder.add(id);
                            }
                        }
                        ChannelOrder.saveOrderIds(context, newOrder);
                    }
                    for (int i = 0; i < hidden.size(); i++) {
                        // Only the last one tells the listeners.
                        manager.updateBrowsable(hidden.get(i), false, i < hidden.size() - 1);
                    }
                    if (!hidden.isEmpty()) {
                        manager.applyUpdatedValuesToDb();
                    }
                    // As after the setup screen: the input is set up, and the TV starts on its
                    // first visible channel.
                    TvSingletons.getSingletons(context)
                            .getSetupUtils()
                            .onTvInputSetupFinished(IptvChannels.getInputId(context), null);
                    then.run();
                };
        if (hasAll(manager, channelIds)) {
            apply.run();
            return;
        }
        ChannelDataManager.Listener listener =
                new ChannelDataManager.Listener() {
                    private boolean mDone;

                    private void check(boolean timedOut) {
                        if (mDone || (!timedOut && !hasAll(manager, channelIds))) {
                            return;
                        }
                        mDone = true;
                        manager.removeListener(this);
                        handler.removeCallbacksAndMessages(null);
                        apply.run();
                    }

                    @Override
                    public void onLoadFinished() {
                        check(false);
                    }

                    @Override
                    public void onChannelListUpdated() {
                        check(false);
                    }

                    @Override
                    public void onChannelBrowsableChanged() {}

                    {
                        handler.postDelayed(() -> check(true), CHANNELS_TIMEOUT_MS);
                    }
                };
        manager.addListener(listener);
    }

    private static boolean hasAll(ChannelDataManager manager, List<Long> channelIds) {
        if (!manager.isDbLoadFinished()) {
            return false;
        }
        for (long id : channelIds) {
            if (manager.getChannel(id) == null) {
                return false;
            }
        }
        return true;
    }

    /** Downloads the lists again and reads their guides, in the background. */
    private static void refreshLists(Context context) {
        new Thread(
                        () -> {
                            for (IptvChannels.Playlist playlist :
                                    IptvChannels.getPlaylists(context)) {
                                try {
                                    IptvChannels.refreshPlaylist(context, playlist);
                                } catch (Exception e) {
                                    // Not logged with its message: it can hold the URL.
                                    Log.w(
                                            TAG,
                                            "Cannot download list "
                                                    + playlist.id
                                                    + " again: "
                                                    + e.getClass().getSimpleName());
                                }
                                IptvGuide.update(context, playlist.id, null);
                            }
                            IptvRecording.refreshConnectionLimits(context);
                        },
                        TAG)
                .start();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in =
                new IptvChannels.LimitedInputStream(
                        new GZIPInputStream(new ByteArrayInputStream(data)), MAX_JSON_BYTES)) {
            return readAll(in);
        }
    }

    private static byte[] encrypt(byte[] data, String pin) throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.put(MAGIC).put((byte) FILE_VERSION).putInt(ITERATIONS).put(salt).put(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKey(pin, salt, ITERATIONS),
                new GCMParameterSpec(TAG_BITS, iv));
        // The header can't be changed without the PIN failing.
        cipher.updateAAD(header.array());
        byte[] encrypted = cipher.doFinal(data);
        byte[] file = new byte[HEADER_BYTES + encrypted.length];
        System.arraycopy(header.array(), 0, file, 0, HEADER_BYTES);
        System.arraycopy(encrypted, 0, file, HEADER_BYTES, encrypted.length);
        return file;
    }

    private static byte[] decrypt(byte[] file, String pin)
            throws GeneralSecurityException, NotBackupException {
        if (file.length <= HEADER_BYTES
                || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new NotBackupException();
        }
        ByteBuffer header = ByteBuffer.wrap(file, 0, HEADER_BYTES);
        header.position(MAGIC.length);
        int version = header.get();
        int iterations = header.getInt();
        if (version != FILE_VERSION || iterations < 1000 || iterations > 10000000) {
            throw new NotBackupException();
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        header.get(salt).get(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(pin, salt, iterations),
                new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(file, 0, HEADER_BYTES);
        try {
            return cipher.doFinal(file, HEADER_BYTES, file.length - HEADER_BYTES);
        } catch (AEADBadTagException e) {
            // Also a damaged file, which can't be told apart.
            throw new WrongPinException();
        }
    }

    private static SecretKeySpec deriveKey(String pin, byte[] salt, int iterations)
            throws GeneralSecurityException {
        // SHA-1 is in every Android version; as an HMAC inside PBKDF2 it is still sound.
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] readFile(Context context, Uri fileUri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(fileUri)) {
            if (in == null) {
                throw new IOException("Cannot open the file");
            }
            return readAll(new IptvChannels.LimitedInputStream(in, MAX_FILE_BYTES));
        } catch (IptvChannels.TooLargeException e) {
            throw new NotBackupException();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Writes the file in the Downloads folder and returns its name, which the system may change
     * (e.g. "name (1).livetv") if one is already there.
     */
    private static String writeToDownloads(Context context, String fileName, byte[] data)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Cannot create the file");
            }
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("Cannot write the file");
                    }
                    out.write(data);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            try (Cursor cursor =
                    resolver.query(
                            uri,
                            new String[] {MediaStore.Downloads.DISPLAY_NAME},
                            null,
                            null,
                            null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            }
            return fileName;
        }
        // Before Android 10: the storage permission, asked by the screen.
        File folder =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Cannot create the Downloads folder");
        }
        String base = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
        File file = new File(folder, fileName);
        for (int i = 1; file.exists(); i++) {
            file = new File(folder, base + " (" + i + ")" + FILE_EXTENSION);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return file.getName();
    }
}
