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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.util.Log;

import com.livetv.tv.timeshift.LiveStorage;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Whether the IPTV lists can record, and how many recordings at the same time.
 *
 * <p>Recording is offered only while a removable drive (USB drive, hard disk, memory card) is
 * plugged in. The input tells the TV framework, so the record button and the scheduled
 * recordings of Live Channels appear and disappear with the drive.
 *
 * <p>Each recording uses a connection to the provider, like watching a channel. Xtream providers
 * limit the connections of an account ({@code max_connections}): the recordings at the same time
 * are that limit minus one, kept for watching TV, so Live Channels warns about schedules that
 * would need more. Lists without that information allow {@link #DEFAULT_RECORDINGS}.
 */
public final class IptvRecording {
    private static final String TAG = "IptvRecording";
    /** Recordings at the same time when the provider does not tell its limit. */
    private static final int DEFAULT_RECORDINGS = 4;
    /** Upper bound, whatever the provider allows. */
    private static final int MAX_RECORDINGS = 8;
    private static final String PREFS = "livetv_iptv_recording";
    /** Followed by a list ID: the connections its account allows (0: unlimited). */
    private static final String KEY_MAX_CONNECTIONS_PREFIX = "max_connections_";
    private static final int TIMEOUT_MS = 15000;

    private static Boolean sCanRecord;
    private static int sRecordings;

    private IptvRecording() {}

    /** Tells the TV framework whether the IPTV input can record now, and how many at once. */
    public static synchronized void updateInputInfo(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        boolean canRecord = LiveStorage.isAvailable(context);
        int recordings = getSimultaneousRecordings(context);
        if (sCanRecord != null && sCanRecord == canRecord && sRecordings == recordings) {
            return;
        }
        try {
            TvInputInfo info =
                    new TvInputInfo.Builder(
                                    context,
                                    new ComponentName(context, IptvTvInputService.class))
                            .setCanRecord(canRecord)
                            .setTunerCount(recordings)
                            .build();
            context.getSystemService(TvInputManager.class).updateTvInputInfo(info);
            sCanRecord = canRecord;
            sRecordings = recordings;
            Log.i(
                    TAG,
                    canRecord
                            ? "Recording available, " + recordings + " at the same time"
                            : "Recording unavailable: no drive");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot update the input", e);
        }
    }

    /**
     * The recordings at the same time: the smallest connection limit of the Xtream accounts,
     * minus the one kept for watching TV.
     */
    private static int getSimultaneousRecordings(Context context) {
        SharedPreferences prefs = prefs(context);
        int recordings = -1;
        for (IptvChannels.VodSource source : IptvChannels.getVodSources(context)) {
            int connections = prefs.getInt(KEY_MAX_CONNECTIONS_PREFIX + source.playlistId, -1);
            if (connections <= 0) {
                // Unknown yet, or unlimited.
                continue;
            }
            int forAccount = Math.max(1, connections - 1);
            recordings = recordings < 0 ? forAccount : Math.min(recordings, forAccount);
        }
        return recordings < 0 ? DEFAULT_RECORDINGS : Math.min(recordings, MAX_RECORDINGS);
    }

    /**
     * Reads, in the background, the connection limit of each account imported with a username
     * and a password, then updates the input. Called when the app starts and after an import.
     */
    public static void refreshConnectionLimits(Context context) {
        Context appContext = context.getApplicationContext();
        new Thread(
                        () -> {
                            List<IptvChannels.VodSource> sources =
                                    IptvChannels.getVodSources(appContext);
                            SharedPreferences.Editor editor = prefs(appContext).edit();
                            for (IptvChannels.VodSource source : sources) {
                                int connections = readMaxConnections(source.apiUrl);
                                if (connections >= 0) {
                                    editor.putInt(
                                            KEY_MAX_CONNECTIONS_PREFIX + source.playlistId,
                                            connections);
                                }
                            }
                            editor.apply();
                            updateInputInfo(appContext);
                        },
                        TAG)
                .start();
    }

    /** The {@code max_connections} of an Xtream account (0: unlimited), or -1 if unknown. */
    private static int readMaxConnections(String apiUrl) {
        HttpURLConnection connection = null;
        try {
            connection = IptvChannels.open(apiUrl, TIMEOUT_MS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1 && out.size() < 1024 * 1024) {
                    out.write(buffer, 0, read);
                }
            }
            JSONObject userInfo =
                    new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8))
                            .optJSONObject("user_info");
            if (userInfo == null) {
                return -1;
            }
            return Integer.parseInt(userInfo.optString("max_connections", "-1").trim());
        } catch (Exception e) {
            // The URL is not logged: it holds the password.
            Log.w(TAG, "Cannot read the connection limit: " + e.getClass().getSimpleName());
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A drive was plugged in or removed. */
    public static final class StorageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateInputInfo(context.getApplicationContext());
        }
    }
}
