/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.tv.common.compat;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.TvContentRating;
import android.media.tv.TvContentRatingSystemInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Access to the {@code @SystemApi} members used by Live TV, so it also compiles against the public
 * SDK (Gradle build).
 *
 * <p>Constants are copied from the framework. Methods are called through reflection; that only
 * succeeds when the app is allowed to use them (system/privileged app or hidden API checks off).
 * Otherwise the call is logged and ignored, or the given default is returned.
 */
public final class SystemApiCompat {
    private static final String TAG = "SystemApiCompat";

    // android.media.tv.TvContract
    public static final String METHOD_GET_COLUMNS = "get_columns";
    public static final String METHOD_ADD_COLUMN = "add_column";
    public static final String EXTRA_EXISTING_COLUMN_NAMES =
            "android.media.tv.extra.EXISTING_COLUMN_NAMES";
    public static final String EXTRA_COLUMN_NAME = "android.media.tv.extra.COLUMN_NAME";
    public static final String EXTRA_DATA_TYPE = "android.media.tv.extra.DATA_TYPE";
    public static final String EXTRA_DEFAULT_VALUE = "android.media.tv.extra.DEFAULT_VALUE";

    /** {@code android.media.tv.TvContract.WatchedPrograms}. */
    public static final class WatchedPrograms implements BaseColumns {
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + TvContract.AUTHORITY + "/watched_program");
        public static final String COLUMN_WATCH_START_TIME_UTC_MILLIS =
                "watch_start_time_utc_millis";
        public static final String COLUMN_WATCH_END_TIME_UTC_MILLIS = "watch_end_time_utc_millis";
        public static final String COLUMN_CHANNEL_ID = "channel_id";
        public static final String COLUMN_TITLE = "title";
        public static final String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";
        public static final String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";

        private WatchedPrograms() {}
    }

    /** {@code TvInputManager#getTvContentRatingSystemList()}. */
    public static List<TvContentRatingSystemInfo> getTvContentRatingSystemList(
            TvInputManager manager) {
        return invoke(
                TvInputManager.class,
                manager,
                "getTvContentRatingSystemList",
                Collections.<TvContentRatingSystemInfo>emptyList(),
                new Class<?>[0]);
    }

    /** {@code TvInputInfo#getHdmiDeviceInfo()}. */
    public static HdmiDeviceInfo getHdmiDeviceInfo(TvInputInfo info) {
        return invoke(TvInputInfo.class, info, "getHdmiDeviceInfo", null, new Class<?>[0]);
    }

    /** {@code TvInputInfo#isConnectedToHdmiSwitch()}. */
    public static boolean isConnectedToHdmiSwitch(TvInputInfo info) {
        return invoke(TvInputInfo.class, info, "isConnectedToHdmiSwitch", false, new Class<?>[0]);
    }

    /** {@code TvInputManager#setParentalControlsEnabled(boolean)}. */
    public static void setParentalControlsEnabled(TvInputManager manager, boolean enabled) {
        invoke(
                TvInputManager.class,
                manager,
                "setParentalControlsEnabled",
                null,
                new Class<?>[] {boolean.class},
                enabled);
    }

    /** {@code TvInputManager#addBlockedRating(TvContentRating)}. */
    public static void addBlockedRating(TvInputManager manager, TvContentRating rating) {
        invoke(
                TvInputManager.class,
                manager,
                "addBlockedRating",
                null,
                new Class<?>[] {TvContentRating.class},
                rating);
    }

    /** {@code TvInputManager#removeBlockedRating(TvContentRating)}. */
    public static void removeBlockedRating(TvInputManager manager, TvContentRating rating) {
        invoke(
                TvInputManager.class,
                manager,
                "removeBlockedRating",
                null,
                new Class<?>[] {TvContentRating.class},
                rating);
    }

    /** {@code TvView#setMain()}. */
    public static void setMain(TvView tvView) {
        invoke(TvView.class, tvView, "setMain", null, new Class<?>[0]);
    }

    /** {@code TvView#unblockContent(TvContentRating)}. */
    public static void unblockContent(TvView tvView, TvContentRating rating) {
        invoke(
                TvView.class,
                tvView,
                "unblockContent",
                null,
                new Class<?>[] {TvContentRating.class},
                rating);
    }

    /** {@code TvInputService.Session#notifySessionEvent(String, Bundle)}. */
    public static void notifySessionEvent(
            TvInputService.Session session, String eventType, Bundle eventArgs) {
        invoke(
                TvInputService.Session.class,
                session,
                "notifySessionEvent",
                null,
                new Class<?>[] {String.class, Bundle.class},
                eventType,
                eventArgs);
    }

    /** {@code TvInputService.RecordingSession#notifySessionEvent(String, Bundle)}. */
    public static void notifySessionEvent(
            TvInputService.RecordingSession session, String eventType, Bundle eventArgs) {
        invoke(
                TvInputService.RecordingSession.class,
                session,
                "notifySessionEvent",
                null,
                new Class<?>[] {String.class, Bundle.class},
                eventType,
                eventArgs);
    }

    /**
     * Calls {@code clazz#name(parameterTypes)} on {@code target} ({@code null} for static methods).
     *
     * @return the method's result, or {@code defaultValue} if it is not accessible or throws.
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(
            Class<?> clazz,
            Object target,
            String name,
            T defaultValue,
            Class<?>[] parameterTypes,
            Object... args) {
        try {
            Method method = clazz.getMethod(name, parameterTypes);
            return (T) method.invoke(target, args);
        } catch (NoSuchMethodException | IllegalAccessException | SecurityException e) {
            Log.w(TAG, "System API " + clazz.getSimpleName() + "#" + name + " unavailable", e);
        } catch (InvocationTargetException e) {
            Log.w(TAG, "System API " + clazz.getSimpleName() + "#" + name + " failed", e.getCause());
        }
        return defaultValue;
    }

    private SystemApiCompat() {}
}
