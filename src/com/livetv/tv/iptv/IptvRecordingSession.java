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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Programs;
import android.media.tv.TvContract.RecordedPrograms;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.livetv.tv.timeshift.LiveStorage;
import com.livetv.tv.timeshift.StreamRecorder;

import java.io.File;
import java.util.Locale;

/**
 * Records an IPTV channel to a removable drive, for the recordings of Live Channels (record
 * button, scheduled and series recordings). The stream is written as it comes to a file in the
 * "recordings" folder of the drive; when the recording stops it is added to the TV provider's
 * recorded programs, with the program's title, picture and description, and played back by
 * {@link IptvTvInputService}.
 */
@RequiresApi(Build.VERSION_CODES.N)
final class IptvRecordingSession extends TvInputService.RecordingSession {
    private static final String TAG = "IptvRecordingSession";
    /** Folder of the recordings on the drive. */
    static final String RECORDINGS_FOLDER = "recordings";

    private final Context mContext;
    private final String mInputId;
    private Uri mChannelUri;
    private String mStreamUrl;
    private File mDirectory;
    @Nullable private Uri mProgramUri;
    private StreamRecorder mRecorder;
    private File mFile;
    private long mStartTimeMs;

    IptvRecordingSession(Context context, String inputId) {
        super(context);
        mContext = context;
        mInputId = inputId;
    }

    @Override
    public void onTune(Uri channelUri) {
        mChannelUri = channelUri;
        mStreamUrl = IptvChannels.getStreamUrl(mContext, channelUri);
        String url = mStreamUrl == null ? "" : mStreamUrl.toLowerCase(Locale.ROOT);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN);
            return;
        }
        // HLS and DASH are lists of pieces, not one stream that can be written to a file.
        if (url.contains(".m3u8") || url.contains(".mpd")) {
            Log.w(TAG, "Cannot record an HLS or DASH channel");
            notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN);
            return;
        }
        mDirectory = LiveStorage.getDirectory(mContext, RECORDINGS_FOLDER);
        if (mDirectory == null) {
            notifyError(TvInputManager.RECORDING_ERROR_INSUFFICIENT_SPACE);
            return;
        }
        notifyTuned(channelUri);
    }

    @Override
    public void onStartRecording(@Nullable Uri programUri) {
        if (mDirectory == null || mRecorder != null) {
            notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN);
            return;
        }
        mProgramUri = programUri;
        mStartTimeMs = System.currentTimeMillis();
        mFile =
                new File(
                        mDirectory,
                        String.format(
                                Locale.ROOT,
                                "LiveTV_%d_%d.ts",
                                ContentUris.parseId(mChannelUri),
                                mStartTimeMs));
        mRecorder = new StreamRecorder(mStreamUrl, mFile, this::onRecorderFailed);
        mRecorder.start();
    }

    /** The drive was unplugged or filled up: what was recorded is kept. */
    private void onRecorderFailed(boolean noSpace) {
        Log.w(TAG, noSpace ? "The drive is full" : "The drive cannot be written");
        new Thread(
                        () -> {
                            Uri recorded = finishRecording();
                            if (recorded != null) {
                                notifyRecordingStopped(recorded);
                            } else {
                                notifyError(
                                        noSpace
                                                ? TvInputManager.RECORDING_ERROR_INSUFFICIENT_SPACE
                                                : TvInputManager.RECORDING_ERROR_UNKNOWN);
                            }
                        },
                        TAG)
                .start();
    }

    @Override
    public void onStopRecording() {
        if (mRecorder == null) {
            return;
        }
        // Closing the file and adding the recording do not block the main thread.
        new Thread(
                        () -> {
                            Uri recorded = finishRecording();
                            if (recorded != null) {
                                notifyRecordingStopped(recorded);
                            } else {
                                notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN);
                            }
                        },
                        TAG)
                .start();
    }

    /**
     * Stops writing and adds the recording to the TV provider. Returns its URI, or null if
     * nothing was recorded (the empty file is deleted).
     */
    @Nullable
    private synchronized Uri finishRecording() {
        StreamRecorder recorder = mRecorder;
        if (recorder == null) {
            return null;
        }
        mRecorder = null;
        recorder.stop();
        long endTimeMs = System.currentTimeMillis();
        long bytes = mFile.length();
        if (bytes == 0) {
            mFile.delete();
            return null;
        }
        ContentValues values = new ContentValues();
        values.put(RecordedPrograms.COLUMN_INPUT_ID, mInputId);
        values.put(RecordedPrograms.COLUMN_CHANNEL_ID, ContentUris.parseId(mChannelUri));
        values.put(RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS, mStartTimeMs);
        values.put(RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS, endTimeMs);
        values.put(RecordedPrograms.COLUMN_RECORDING_DURATION_MILLIS, endTimeMs - mStartTimeMs);
        values.put(RecordedPrograms.COLUMN_RECORDING_DATA_URI, Uri.fromFile(mFile).toString());
        values.put(RecordedPrograms.COLUMN_RECORDING_DATA_BYTES, bytes);
        if (!copyProgram(values)) {
            // No program (e.g. a channel without guide): the channel's name.
            values.put(RecordedPrograms.COLUMN_TITLE, getChannelName());
        }
        try {
            return mContext.getContentResolver().insert(RecordedPrograms.CONTENT_URI, values);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot add the recording", e);
            return null;
        }
    }

    /** Copies the title, picture, description... of the program recorded; false if none. */
    private boolean copyProgram(ContentValues values) {
        if (mProgramUri == null) {
            return false;
        }
        String[] columns = {
            Programs.COLUMN_TITLE,
            Programs.COLUMN_EPISODE_TITLE,
            Programs.COLUMN_SEASON_DISPLAY_NUMBER,
            Programs.COLUMN_EPISODE_DISPLAY_NUMBER,
            Programs.COLUMN_SHORT_DESCRIPTION,
            Programs.COLUMN_LONG_DESCRIPTION,
            Programs.COLUMN_POSTER_ART_URI,
            Programs.COLUMN_THUMBNAIL_URI,
            Programs.COLUMN_CANONICAL_GENRE,
            Programs.COLUMN_CONTENT_RATING,
        };
        try (Cursor cursor =
                mContext.getContentResolver().query(mProgramUri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) {
                return false;
            }
            // The same column names in both tables.
            for (int i = 0; i < columns.length; i++) {
                if (!cursor.isNull(i)) {
                    values.put(columns[i], cursor.getString(i));
                }
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the program", e);
            return false;
        }
    }

    private String getChannelName() {
        try (Cursor cursor =
                mContext.getContentResolver()
                        .query(
                                mChannelUri,
                                new String[] {TvContract.Channels.COLUMN_DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the channel", e);
        }
        return "LiveTV";
    }

    @Override
    public void onRelease() {
        // Released while recording (e.g. the app stops): what was recorded is kept.
        if (mRecorder != null) {
            new Thread(this::finishRecording, TAG).start();
        }
    }
}
