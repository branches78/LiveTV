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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the last hour of a live stream on a removable drive, for time shifting. A thread reads
 * the stream from the network and writes it to files of one minute each, deleting the ones older
 * than an hour (or sooner when the drive runs out of space). The player reads it back through
 * {@link TimeshiftDataSource}, which waits at the end for the bytes still to come.
 *
 * <p>Positions are "offsets": bytes counted from the start of the stream, which keep growing
 * while older files are deleted. An index maps the time each part arrived to its offset, for
 * seeking and for the position shown in the play controls.
 */
public final class TimeshiftBuffer {
    private static final String TAG = "TimeshiftBuffer";

    /** How far back time shifting goes. */
    public static final long MAX_DURATION_MS = 60 * 60 * 1000;
    private static final long SEGMENT_DURATION_MS = 60 * 1000;
    /** How often the index records the time and offset of the stream. */
    private static final long INDEX_INTERVAL_MS = 500;
    /** Free space kept on the drive: older parts are deleted to keep it. */
    private static final long RESERVED_BYTES = 256L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final long RETRY_DELAY_MS = 2000;
    private static final int BUFFER_BYTES = 64 * 1024;
    /** How long the cleanup waits for the writing thread to end before deleting the files. */
    private static final long WRITER_STOP_TIMEOUT_MS = 5000;

    /** Deletes the files of released buffers, off the main thread. */
    private static final ExecutorService CLEANUP_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "TimeshiftCleanup"));

    /** Called on the writing thread when the drive cannot be written any more. */
    public interface Listener {
        void onBufferFailed();
    }

    /** One file of the buffer. */
    private static final class Segment {
        final File file;
        final long startOffset;
        final long startTimeMs;
        /** Bytes written so far. */
        long length;

        Segment(File file, long startOffset, long startTimeMs) {
            this.file = file;
            this.startOffset = startOffset;
            this.startTimeMs = startTimeMs;
        }

        long endOffset() {
            return startOffset + length;
        }
    }

    private final File mDirectory;
    private final String mStreamUrl;
    private final Listener mListener;
    private final List<Segment> mSegments = new ArrayList<>();
    /** Pairs of (time in ms since the epoch, offset), in order. */
    private final List<long[]> mIndex = new ArrayList<>();
    private Thread mThread;
    private volatile boolean mStopped;
    private boolean mFailed;
    private int mNextSegmentNumber;

    /**
     * @param directory an empty folder on a removable drive, deleted by {@link #release}.
     * @param streamUrl the live stream; never logged, it can hold a password.
     */
    public TimeshiftBuffer(File directory, String streamUrl, Listener listener) {
        mDirectory = directory;
        mStreamUrl = streamUrl;
        mListener = listener;
    }

    /** Starts reading the stream. */
    public void start() {
        mThread = new Thread(this::run, TAG);
        mThread.start();
    }

    /**
     * Stops reading and deletes the files. The data source then ends.
     *
     * <p>LiveTV: the files are deleted on {@link #CLEANUP_EXECUTOR}, after the writing thread
     * ends. A busy drive can take seconds to delete them, and on the main thread that made Android
     * close the app while changing channels ("Too much time to handle tune request").
     *
     * @param onDeleted run on the cleanup thread once the folder is deleted, or null.
     */
    public void release(Runnable onDeleted) {
        mStopped = true;
        Thread thread = mThread;
        if (thread != null) {
            thread.interrupt();
        }
        CLEANUP_EXECUTOR.execute(
                () -> {
                    if (thread != null) {
                        try {
                            // The thread may still be writing a part: wait so it is deleted too.
                            thread.join(WRITER_STOP_TIMEOUT_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    synchronized (this) {
                        notifyAll();
                        for (Segment segment : mSegments) {
                            segment.file.delete();
                        }
                        mSegments.clear();
                        mIndex.clear();
                    }
                    mDirectory.delete();
                    if (onDeleted != null) {
                        onDeleted.run();
                    }
                });
    }

    /** The time of the oldest part kept, in ms since the epoch, or 0 while empty. */
    public synchronized long getStartTimeMs() {
        return mIndex.isEmpty() ? 0 : mIndex.get(0)[0];
    }

    /** The offset of the oldest byte kept. */
    public synchronized long getStartOffset() {
        return mSegments.isEmpty() ? 0 : mSegments.get(0).startOffset;
    }

    /** The offset after the last byte written. */
    public synchronized long getEndOffset() {
        return mSegments.isEmpty() ? 0 : mSegments.get(mSegments.size() - 1).endOffset();
    }

    /**
     * Returns {offset, time} of the indexed position at or before {@code timeMs}: where to start
     * playing to show that time. Before the start, the start.
     */
    public synchronized long[] findPosition(long timeMs) {
        if (mIndex.isEmpty()) {
            return new long[] {0, 0};
        }
        long[] found = mIndex.get(0);
        for (long[] entry : mIndex) {
            if (entry[0] > timeMs) {
                break;
            }
            found = entry;
        }
        return new long[] {Math.max(found[1], getStartOffset()), found[0]};
    }

    /** The time the byte at {@code offset} arrived, in ms since the epoch. */
    public synchronized long getTimeAt(long offset) {
        if (mIndex.isEmpty()) {
            return 0;
        }
        long time = mIndex.get(0)[0];
        for (long[] entry : mIndex) {
            if (entry[1] > offset) {
                break;
            }
            time = entry[0];
        }
        return time;
    }

    /**
     * Reads bytes at {@code offset}, waiting up to {@code timeoutMs} for them to be written.
     * Returns the number of bytes read, 0 if none arrived in time, or -1 once the buffer stopped
     * and has nothing more at that offset.
     */
    int read(long offset, byte[] buffer, int bufferOffset, int length, long timeoutMs)
            throws IOException {
        Segment segment;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (this) {
            while (true) {
                segment = findSegment(offset);
                if (segment != null && offset < segment.endOffset()) {
                    break;
                }
                if (mStopped || mFailed) {
                    return -1;
                }
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) {
                    return 0;
                }
                try {
                    wait(Math.min(wait, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
        }
        int count = (int) Math.min(length, segment.endOffset() - offset);
        try (RandomAccessFile file = new RandomAccessFile(segment.file, "r")) {
            file.seek(offset - segment.startOffset);
            return Math.max(0, file.read(buffer, bufferOffset, count));
        } catch (FileNotFoundException e) {
            // LiveTV: the oldest part was deleted (over an hour, or no space) just now. Nothing
            // read: the data source goes on from the oldest part kept.
            synchronized (this) {
                if (!mSegments.contains(segment)) {
                    return 0;
                }
            }
            throw e;
        }
    }

    /** The segment holding {@code offset}, or the oldest one if it was already deleted. */
    private Segment findSegment(long offset) {
        if (mSegments.isEmpty()) {
            return null;
        }
        for (int i = mSegments.size() - 1; i >= 0; i--) {
            Segment segment = mSegments.get(i);
            if (offset >= segment.startOffset) {
                return segment;
            }
        }
        return null;
    }

    private void run() {
        byte[] buffer = new byte[BUFFER_BYTES];
        while (!mStopped) {
            HttpURLConnection connection = null;
            try {
                connection = IptvChannels.open(mStreamUrl, CONNECT_TIMEOUT_MS);
                try (InputStream in = connection.getInputStream()) {
                    copy(in, buffer);
                }
            } catch (DriveException e) {
                Log.w(TAG, "Cannot write to the drive", e.getCause());
                synchronized (this) {
                    mFailed = true;
                    notifyAll();
                }
                if (!mStopped) {
                    mListener.onBufferFailed();
                }
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
    }

    /** A write to the drive failed: unplugged, full or read-only. */
    private static final class DriveException extends IOException {
        DriveException(IOException cause) {
            super(cause);
        }
    }

    private void copy(InputStream in, byte[] buffer) throws IOException {
        FileOutputStream out = null;
        Segment segment = null;
        long lastIndexMs = 0;
        try {
            while (!mStopped) {
                int count = in.read(buffer);
                if (count < 0) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (segment == null || now - segment.startTimeMs >= SEGMENT_DURATION_MS) {
                    closeQuietly(out);
                    segment = newSegment(now);
                    try {
                        out = new FileOutputStream(segment.file);
                    } catch (IOException e) {
                        throw new DriveException(e);
                    }
                    deleteOldSegments(now);
                }
                try {
                    out.write(buffer, 0, count);
                } catch (IOException e) {
                    throw new DriveException(e);
                }
                synchronized (this) {
                    if (now - lastIndexMs >= INDEX_INTERVAL_MS) {
                        mIndex.add(new long[] {now, segment.endOffset()});
                        lastIndexMs = now;
                    }
                    segment.length += count;
                    notifyAll();
                }
            }
        } finally {
            closeQuietly(out);
        }
    }

    private synchronized Segment newSegment(long now) {
        long offset = getEndOffset();
        Segment segment =
                new Segment(
                        new File(mDirectory, "part" + (mNextSegmentNumber++) + ".ts"), offset, now);
        mSegments.add(segment);
        return segment;
    }

    /** Deletes the parts older than an hour, or more of them while the drive is short of space. */
    private void deleteOldSegments(long now) {
        while (true) {
            Segment oldest;
            synchronized (this) {
                if (mSegments.size() <= 2) {
                    return;
                }
                Segment second = mSegments.get(1);
                boolean tooOld = now - second.startTimeMs > MAX_DURATION_MS;
                boolean noSpace = mDirectory.getUsableSpace() < RESERVED_BYTES;
                if (!tooOld && !noSpace) {
                    return;
                }
                oldest = mSegments.remove(0);
                long start = mSegments.get(0).startOffset;
                while (!mIndex.isEmpty() && mIndex.get(0)[1] < start) {
                    mIndex.remove(0);
                }
            }
            oldest.file.delete();
        }
    }

    private static void closeQuietly(FileOutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                // Nothing to do.
            }
        }
    }
}
