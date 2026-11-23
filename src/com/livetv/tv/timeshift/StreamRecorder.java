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

package com.livetv.tv.timeshift;

import android.os.SystemClock;
import android.util.Log;

import com.livetv.tv.iptv.IptvChannels;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Records a live stream to a file on a removable drive: reads it from the network (connecting
 * again when it drops) and writes it as it comes, until stopped.
 */
public final class StreamRecorder {
    private static final String TAG = "StreamRecorder";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final long RETRY_DELAY_MS = 2000;
    private static final int BUFFER_BYTES = 64 * 1024;
    /** Less free space than this stops the recording, so the drive is never filled up. */
    private static final long RESERVED_BYTES = 128L * 1024 * 1024;

    /** Called on the recording thread when the drive cannot be written any more. */
    public interface Listener {
        void onRecordingFailed(boolean noSpace);
    }

    private final String mStreamUrl;
    private final File mFile;
    private final Listener mListener;
    private Thread mThread;
    private volatile boolean mStopped;
    private volatile long mBytes;

    /**
     * @param streamUrl the live stream; never logged, it can hold a password.
     * @param file the file to write, on a removable drive.
     */
    public StreamRecorder(String streamUrl, File file, Listener listener) {
        mStreamUrl = streamUrl;
        mFile = file;
        mListener = listener;
    }

    public void start() {
        mThread = new Thread(this::run, TAG);
        mThread.start();
    }

    /** Stops recording and waits (a little) for the file to be closed. */
    public void stop() {
        mStopped = true;
        if (mThread != null) {
            mThread.interrupt();
            try {
                mThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            while (!mStopped) {
                HttpURLConnection connection = null;
                try {
                    connection = IptvChannels.open(mStreamUrl, CONNECT_TIMEOUT_MS);
                    try (InputStream in = connection.getInputStream()) {
                        int count;
                        while (!mStopped && (count = in.read(buffer)) >= 0) {
                            try {
                                out.write(buffer, 0, count);
                            } catch (IOException e) {
                                throw new DriveException(e);
                            }
                            mBytes += count;
                            if ((mBytes & 0xFFFFF) < count
                                    && mFile.getUsableSpace() < RESERVED_BYTES) {
                                // Checked about once per MB: the drive is (almost) full.
                                fail(true);
                                return;
                            }
                        }
                    }
                } catch (DriveException e) {
                    Log.w(TAG, "Cannot write the recording", e.getCause());
                    fail(false);
                    return;
                } catch (IOException e) {
                    // The stream ended or the network failed: connect again.
                    Log.w(TAG, "Stream interrupted: " + e.getClass().getSimpleName());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
                if (!mStopped) {
                    SystemClock.sleep(RETRY_DELAY_MS);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot create the recording", e);
            fail(false);
        }
    }

    private void fail(boolean noSpace) {
        if (!mStopped) {
            mListener.onRecordingFailed(noSpace);
        }
    }

    /** A write to the drive failed: unplugged, full or read-only. */
    private static final class DriveException extends IOException {
        DriveException(IOException cause) {
            super(cause);
        }
    }
}
