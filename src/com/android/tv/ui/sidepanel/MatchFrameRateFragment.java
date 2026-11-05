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

package com.android.tv.ui.sidepanel;

import com.android.tv.R;
import com.livetv.tv.framerate.FrameRateMatcher;
import com.livetv.tv.framerate.FrameRateSettings;
import java.util.ArrayList;
import java.util.List;

/** Lets the user choose how LiveTV matches the TV output to the channel's frame rate. */
public class MatchFrameRateFragment extends SideFragment {
    private static final String TRACKER_LABEL = "match frame rate";

    @Override
    protected String getTitle() {
        return getString(R.string.livetv_match_frame_rate);
    }

    @Override
    public String getTrackerLabel() {
        return TRACKER_LABEL;
    }

    @Override
    protected List<Item> getItemList() {
        List<Item> items = new ArrayList<>();
        items.add(
                new ModeItem(
                        FrameRateSettings.MODE_OFF,
                        R.string.livetv_match_frame_rate_off_description));
        items.add(
                new ModeItem(
                        FrameRateSettings.MODE_AUTO,
                        R.string.livetv_match_frame_rate_auto_description));
        items.add(
                new ModeItem(
                        FrameRateSettings.MODE_SEAMLESS_ONLY,
                        R.string.livetv_match_frame_rate_seamless_description));

        items.add(new DividerItem());
        SimpleActionItem displayInfo =
                new SimpleActionItem(
                        getString(R.string.livetv_match_frame_rate_display),
                        getString(getDisplaySupportDescription()));
        displayInfo.setClickable(false);
        items.add(displayInfo);
        if (FrameRateSettings.getSystemPreference(getContext())
                == FrameRateSettings.SYSTEM_PREFERENCE_NEVER) {
            SimpleActionItem systemInfo =
                    new SimpleActionItem(
                            getString(R.string.livetv_match_frame_rate_system_off),
                            getString(R.string.livetv_match_frame_rate_system_off_description));
            systemInfo.setClickable(false);
            items.add(systemInfo);
        }
        return items;
    }

    private int getDisplaySupportDescription() {
        switch (FrameRateSettings.getDisplaySupport(getContext())) {
            case FrameRateSettings.SUPPORT_SEAMLESS:
                return R.string.livetv_match_frame_rate_display_seamless;
            case FrameRateSettings.SUPPORT_MODE_SWITCH:
                return R.string.livetv_match_frame_rate_display_mode_switch;
            case FrameRateSettings.SUPPORT_NONE:
            default:
                return R.string.livetv_match_frame_rate_display_none;
        }
    }

    private class ModeItem extends RadioButtonItem {
        private final int mMode;

        private ModeItem(int mode, int descriptionResId) {
            super(getString(FrameRateSettings.getModeLabel(mode)), getString(descriptionResId));
            mMode = mode;
        }

        @Override
        protected void onUpdate() {
            super.onUpdate();
            setChecked(FrameRateSettings.getMode(getContext()) == mMode);
        }

        @Override
        protected void onSelected() {
            super.onSelected();
            FrameRateSettings.setMode(getContext(), mMode);
            // Applies to the channel being watched, not only from the next tune.
            FrameRateMatcher.onModeChanged();
            notifyItemsChanged();
        }
    }
}
