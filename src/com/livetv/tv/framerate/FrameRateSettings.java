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

package com.livetv.tv.framerate;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import com.android.tv.R;

/**
 * "Match framerate": switches the TV output to the frame rate of the channel being played.
 *
 * <p>Uses {@code Surface#setFrameRate}, so the platform picks how to switch: seamlessly when the
 * TV allows it (HDMI 2.1 Quick Media Switching / VRR), or with an HDMI mode change otherwise
 * (classic auto framerate on HDMI 2.0, the screen may go blank for a moment).
 */
public final class FrameRateSettings {
    /** Do not change the output frame rate. */
    public static final int MODE_OFF = 0;
    /** Seamless switch when possible, otherwise an HDMI mode change. */
    public static final int MODE_AUTO = 1;
    /** Only switch when it is seamless (no blank screen). */
    public static final int MODE_SEAMLESS_ONLY = 2;

    /** The connected display can switch the frame rate without interruption. */
    public static final int SUPPORT_SEAMLESS = 0;
    /** The connected display offers modes with other refresh rates (non-seamless switch). */
    public static final int SUPPORT_MODE_SWITCH = 1;
    /** The connected display only offers the current refresh rate. */
    public static final int SUPPORT_NONE = 2;

    private static final String PREFS = "livetv_playback";
    private static final String KEY_MODE = "match_frame_rate_mode";
    /** Android TV "Match content frame rate" system setting: 0 never, 1 seamless, 2 always. */
    private static final String SYSTEM_SETTING_MATCH_CONTENT_FRAME_RATE =
            "match_content_frame_rate";

    public static final int SYSTEM_PREFERENCE_UNKNOWN = -1;
    public static final int SYSTEM_PREFERENCE_NEVER = 0;

    /**
     * The window's preferred display mode works since Android 6 ({@code Surface#setFrameRate},
     * used for seamless switches, needs Android 11).
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** Off until the user turns it on, since an HDMI 2.0 switch blanks the screen. */
    public static int getMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, MODE_OFF);
    }

    public static void setMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, mode)
                .apply();
    }

    public static int getModeLabel(int mode) {
        switch (mode) {
            case MODE_OFF:
                return R.string.livetv_match_frame_rate_off;
            case MODE_SEAMLESS_ONLY:
                return R.string.livetv_match_frame_rate_seamless;
            case MODE_AUTO:
            default:
                return R.string.livetv_match_frame_rate_auto;
        }
    }

    /** Tells what kind of frame rate switch the connected display allows. */
    public static int getDisplaySupport(Context context) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        Display display =
                displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return SUPPORT_NONE;
        }
        Display.Mode current = display.getMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && current.getAlternativeRefreshRates().length > 0) {
            return SUPPORT_SEAMLESS;
        }
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && Math.abs(mode.getRefreshRate() - current.getRefreshRate()) > 0.5f) {
                return SUPPORT_MODE_SWITCH;
            }
        }
        return SUPPORT_NONE;
    }

    /**
     * Returns the system "Match content frame rate" preference, which can turn the switch off for
     * every app, or {@link #SYSTEM_PREFERENCE_UNKNOWN} if it cannot be read.
     */
    public static int getSystemPreference(Context context) {
        try {
            return Settings.Secure.getInt(
                    context.getContentResolver(),
                    SYSTEM_SETTING_MATCH_CONTENT_FRAME_RATE,
                    SYSTEM_PREFERENCE_UNKNOWN);
        } catch (SecurityException e) {
            return SYSTEM_PREFERENCE_UNKNOWN;
        }
    }

    private FrameRateSettings() {}
}
