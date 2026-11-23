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

import android.net.Uri;
import android.support.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * Reads a {@link TimeshiftBuffer} for the player, from a starting offset. At the end of what was
 * written it waits for more, like a live stream; it ends only when the buffer stops.
 */
@UnstableApi
public final class TimeshiftDataSource extends BaseDataSource {
    /** Longest wait for new bytes before telling the player the stream stalled. */
    private static final long READ_TIMEOUT_MS = 20000;
    /** The waits are short, so a released player is not kept waiting. */
    private static final long READ_WAIT_MS = 500;

    private final TimeshiftBuffer mBuffer;
    private final long mStartOffset;
    @Nullable private Uri mUri;
    private long mOffset;

    /** A factory of data sources reading {@code buffer} from {@code startOffset}. */
    public static DataSource.Factory factory(TimeshiftBuffer buffer, long startOffset) {
        return () -> new TimeshiftDataSource(buffer, startOffset);
    }

    private TimeshiftDataSource(TimeshiftBuffer buffer, long startOffset) {
        super(/* isNetwork= */ false);
        mBuffer = buffer;
        mStartOffset = startOffset;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        mUri = dataSpec.uri;
        transferInitializing(dataSpec);
        // The player's positions are counted from where this source starts.
        mOffset = Math.max(mStartOffset + dataSpec.position, mBuffer.getStartOffset());
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long waited = 0;
        while (true) {
            // Bytes already deleted (paused for over an hour): continue from the oldest kept.
            mOffset = Math.max(mOffset, mBuffer.getStartOffset());
            int read = mBuffer.read(mOffset, buffer, offset, length, READ_WAIT_MS);
            if (read < 0) {
                return C.RESULT_END_OF_INPUT;
            }
            if (read > 0) {
                mOffset += read;
                bytesTransferred(read);
                return read;
            }
            waited += READ_WAIT_MS;
            if (waited >= READ_TIMEOUT_MS) {
                throw new IOException("No data from the stream");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted");
            }
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mUri;
    }

    @Override
    public void close() {
        if (mUri != null) {
            mUri = null;
            transferEnded();
        }
    }
}
