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

import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * Classic auto framerate: asks for a display mode with the content refresh rate through the
 * window of the activity showing the video ({@code preferredDisplayModeId}).
 *
 * <p>Needed because some devices ignore {@code Surface#setFrameRate} (SurfaceFlinger content
 * detection disabled, e.g. Amlogic boxes) but honor the window's preferred mode. The TV input
 * runs in the same process as {@code MainActivity}, which registers its window here.
 *
 * <p>Main thread only.
 */
public final class DisplayModeRequester {
    private static final String TAG = "DisplayModeRequester";
    /** Relative tolerance for an exact refresh rate match (29.97 must not match 30). */
    private static final float EXACT_TOLERANCE = 0.0005f;

    private static Window sWindow;
    private static float sFrameRate;

    /** Called by the activity that shows the video when it starts ({@code null} when it stops). */
    public static void setWindow(Window window) {
        if (sWindow != null && sWindow != window) {
            applyTo(sWindow, 0);
        }
        sWindow = window;
        applyTo(sWindow, sFrameRate);
    }

    /**
     * Called by an activity that stops: forgets its window, unless another activity that shows
     * video (e.g. the Video on Demand player, started over it) registered its own since.
     */
    public static void clearWindow(Window window) {
        if (sWindow == window) {
            setWindow(null);
        }
    }

    /**
     * Requests a mode for {@code frameRate}, or the default mode when it is 0.
     *
     * @return the requested display mode, or 0 if none (no window, or no matching mode).
     */
    static int requestFrameRate(float frameRate) {
        sFrameRate = frameRate;
        return applyTo(sWindow, frameRate);
    }

    /** Whether a window is registered to ask for display modes. */
    static boolean hasWindow() {
        return sWindow != null;
    }

    /** Whether the window is visible, so the display can follow its preferred mode. */
    static boolean isWindowShown() {
        return sWindow != null && sWindow.getDecorView().isShown();
    }

    /** Whether the display already uses the mode requested through the window. */
    static boolean isRequestedModeActive() {
        if (sWindow == null) {
            return false;
        }
        int modeId = sWindow.getAttributes().preferredDisplayModeId;
        return modeId != 0 && isCurrentMode(modeId);
    }

    /** Whether the display already uses {@code modeId} (true when there is no window). */
    static boolean isCurrentMode(int modeId) {
        return sWindow == null
                || sWindow.getWindowManager().getDefaultDisplay().getMode().getModeId() == modeId;
    }

    private static int applyTo(Window window, float frameRate) {
        if (window == null) {
            return 0;
        }
        int modeId = frameRate > 0 ? findModeId(window, frameRate) : 0;
        WindowManager.LayoutParams params = window.getAttributes();
        if (frameRate > 0 && modeId == 0) {
            // No mode for this rate: keep the current one rather than going back to the default
            // and switching twice.
            return params.preferredDisplayModeId;
        }
        if (params.preferredDisplayModeId != modeId) {
            params.preferredDisplayModeId = modeId;
            window.setAttributes(params);
            Log.i(TAG, "Preferred display mode " + modeId + " for " + frameRate + " fps");
        }
        return modeId;
    }

    /**
     * Returns a mode with the current resolution whose refresh rate equals the frame rate, or else
     * an exact multiple of it (e.g. 50 Hz for 25 fps); 0 when there is none.
     */
    private static int findModeId(Window window, float frameRate) {
        Display display = window.getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        for (int multiple = 1; multiple <= 4; multiple++) {
            float wanted = frameRate * multiple;
            for (Display.Mode mode : modes) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()
                        && Math.abs(mode.getRefreshRate() - wanted) <= wanted * EXACT_TOLERANCE) {
                    return mode.getModeId();
                }
            }
        }
        return 0;
    }

    private DisplayModeRequester() {}
}
