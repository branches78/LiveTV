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

import android.content.Context;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;

/**
 * The removable storage where LiveTV keeps time shifting and recordings: a USB drive, a hard
 * disk or a memory card. The internal storage of the TV is never used, so without one of them
 * there is no time shifting nor recording.
 *
 * <p>The folders are the app's own folders on each drive ({@link Context#getExternalFilesDirs}),
 * which need no permission and are deleted with the app.
 */
public final class LiveStorage {
    private static final String TAG = "LiveStorage";
    /** Less free space than this is not enough for time shifting nor recording. */
    public static final long MIN_FREE_BYTES = 512L * 1024 * 1024;

    private LiveStorage() {}

    /**
     * Returns the app's folder {@code name} on the removable drive with the most free space, or
     * null when no drive is plugged in (or none has {@link #MIN_FREE_BYTES} free).
     */
    @Nullable
    public static File getDirectory(Context context, String name) {
        File best = null;
        long bestFree = MIN_FREE_BYTES - 1;
        for (File root : getRemovableRoots(context)) {
            long free = root.getUsableSpace();
            if (free > bestFree) {
                best = root;
                bestFree = free;
            }
        }
        if (best == null) {
            return null;
        }
        File directory = new File(best, name);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "Cannot create a folder on the drive");
            return null;
        }
        return directory;
    }

    /** Whether a removable drive with enough free space is plugged in. */
    public static boolean isAvailable(Context context) {
        for (File root : getRemovableRoots(context)) {
            if (root.getUsableSpace() >= MIN_FREE_BYTES) {
                return true;
            }
        }
        return false;
    }

    /** The app's folders on the mounted removable drives. */
    private static java.util.List<File> getRemovableRoots(Context context) {
        java.util.List<File> roots = new java.util.ArrayList<>();
        File[] directories = context.getExternalFilesDirs(null);
        if (directories == null) {
            return roots;
        }
        for (File directory : directories) {
            // Unavailable drives are null; the primary storage is the TV's own (emulated).
            if (directory == null) {
                continue;
            }
            try {
                if (Environment.isExternalStorageRemovable(directory)
                        && Environment.MEDIA_MOUNTED.equals(
                                Environment.getExternalStorageState(directory))) {
                    roots.add(directory);
                }
            } catch (IllegalArgumentException e) {
                // Not a storage path (should not happen).
            }
        }
        return roots;
    }
}
