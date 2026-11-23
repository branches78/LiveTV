/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.ApplicationErrorReport;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;
import com.android.tv.MainActivity;
import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.TvSingletons;
import com.android.tv.common.util.PermissionUtils;
import com.android.tv.dialog.PinDialogFragment;
import com.android.tv.features.TvFeatures;
import com.android.tv.license.LicenseSideFragment;
import com.android.tv.license.Licenses;
import com.android.tv.util.TvSettings;
import com.livetv.tv.framerate.FrameRateSettings;
import com.livetv.tv.iptv.IptvBackupActivity;
import com.livetv.tv.iptv.IptvChannels;
import com.livetv.tv.multiview.MultiviewActivity;
import java.util.ArrayList;
import java.util.List;

/** Shows TV app settings. */
public class SettingsFragment extends SideFragment {
    private static final String TRACKER_LABEL = "settings";

    @Override
    protected String getTitle() {
        return getResources().getString(R.string.side_panel_title_settings);
    }

    @Override
    public String getTrackerLabel() {
        return TRACKER_LABEL;
    }

    @Override
    protected List<Item> getItemList() {
        List<Item> items = new ArrayList<>();
        final MainActivity activity = getMainActivity();
        TvSingletons singletons = TvSingletons.getSingletons(getContext());
        boolean hasNewInput =
                singletons.getSetupUtils().hasNewInput(activity.getTvInputManagerHelper());
        // LiveTV order: sources, customize channel list, match framerate, start in multiview,
        // interactive apps, export the IPTV setup,
        // licenses, feedback, version. Parental controls only show up for a system install.
        // Same list as the channel sources screen.
        int sourceCount = activity.getTvInputManagerHelper().getTvInputInfos(true, true).size();
        String sourcesDescription =
                getResources()
                        .getQuantityString(R.plurals.livetv_source_count, sourceCount, sourceCount);
        if (hasNewInput) {
            sourcesDescription +=
                    " · " + getString(R.string.settings_channel_source_item_setup_new_inputs);
        }
        items.add(
                new ActionItem(
                        getString(R.string.settings_channel_source_item_setup),
                        sourcesDescription) {
                    @Override
                    protected void onSelected() {
                        closeFragment();
                        activity.getOverlayManager().showSetupFragmentFromSettings();
                    }
                });
        Item customizeChannelListItem =
                new SubMenuItem(
                        getString(R.string.settings_channel_source_item_customize_channels),
                        getString(
                                R.string
                                        .settings_channel_source_item_customize_channels_description),
                        getMainActivity().getOverlayManager().getSideFragmentManager()) {
                    @Override
                    protected SideFragment getFragment() {
                        return new CustomizeChannelListFragment();
                    }

                    @Override
                    protected void onBind(View view) {
                        super.onBind(view);
                        setEnabled(false);
                    }

                    @Override
                    protected void onUpdate() {
                        super.onUpdate();
                        setEnabled(getChannelDataManager().getChannelCount() != 0);
                    }
                };
        customizeChannelListItem.setEnabled(false);
        items.add(customizeChannelListItem);
        if (FrameRateSettings.isSupported()) {
            items.add(
                    new SubMenuItem(
                            getString(R.string.livetv_match_frame_rate),
                            getString(
                                    FrameRateSettings.getModeLabel(
                                            FrameRateSettings.getMode(getContext()))),
                            getMainActivity().getOverlayManager().getSideFragmentManager()) {
                        @Override
                        protected SideFragment getFragment() {
                            return new MatchFrameRateFragment();
                        }
                    });
        }
        // LiveTV: open the app in the multiview.
        items.add(
                new SwitchItem(
                        getString(R.string.livetv_multiview_open_at_start),
                        getString(R.string.livetv_multiview_open_at_start),
                        getString(R.string.livetv_multiview_open_at_start_description)) {
                    @Override
                    protected void onUpdate() {
                        super.onUpdate();
                        setChecked(MultiviewActivity.isOpenAtStart(getContext()));
                    }

                    @Override
                    protected void onSelected() {
                        super.onSelected();
                        MultiviewActivity.setOpenAtStart(getContext(), isChecked());
                    }
                });
        if (PermissionUtils.hasModifyParentalControls(getMainActivity())) {
            items.add(
                    new ActionItem(
                            getString(R.string.settings_parental_controls),
                            getString(
                                    activity.getParentalControlSettings()
                                                    .isParentalControlsEnabled()
                                            ? R.string.option_toggle_parental_controls_on
                                            : R.string.option_toggle_parental_controls_off)) {
                        @Override
                        protected void onSelected() {
                            getMainActivity()
                                    .getOverlayManager()
                                    .getSideFragmentManager()
                                    .hideSidePanel(true);
                            PinDialogFragment fragment =
                                    PinDialogFragment.create(
                                            PinDialogFragment.PIN_DIALOG_TYPE_ENTER_PIN);
                            getMainActivity()
                                    .getOverlayManager()
                                    .showDialogFragment(
                                            PinDialogFragment.DIALOG_TAG, fragment, true);
                        }
                    });
        } else {
            // Note: parental control is turned off, when MODIFY_PARENTAL_CONTROLS is not granted.
            // But, we may be able to turn on channel lock feature regardless of the permission.
            // It's TBD.
        }
        //Interactive Application Settings
        if (TvFeatures.HAS_TIAF.isEnabled(getContext()))
        {
            items.add(
                    new ActionItem(
                            getString(R.string.interactive_app_settings),
                            getString(
                                    TvSettings.isTvIAppOn(getContext())
                                            ? R.string.tv_iapp_on
                                            : R.string.tv_iapp_off)) {
                        @Override
                        protected void onSelected() {
                            getMainActivity()
                                    .getOverlayManager()
                                    .getSideFragmentManager()
                                    .show(new InteractiveAppSettingsFragment(), false);
                        }
                    });
        }

        // LiveTV: a backup of the IPTV setup, below the interactive apps.
        if (IptvChannels.hasPlaylists(getContext())) {
            items.add(
                    new ActionItem(
                            getString(R.string.livetv_backup_settings_title),
                            getString(R.string.livetv_backup_settings_description)) {
                        @Override
                        protected void onSelected() {
                            closeFragment();
                            activity.startActivity(
                                    IptvBackupActivity.buildExportIntent(activity));
                        }
                    });
        }

        if (Licenses.hasLicenses(getContext())) {
            items.add(
                    new SubMenuItem(
                            getString(R.string.settings_menu_licenses),
                            getMainActivity().getOverlayManager().getSideFragmentManager()) {
                        @Override
                        protected SideFragment getFragment() {
                            return new LicenseSideFragment();
                        }
                    });
        }

        items.add(
                new ActionItem(getString(R.string.settings_send_feedback)) {
                    @Override
                    protected void onSelected() {
                        Intent intent = new Intent(Intent.ACTION_APP_ERROR);
                        ApplicationErrorReport report = new ApplicationErrorReport();
                        report.packageName = report.processName = getContext().getPackageName();
                        report.time = System.currentTimeMillis();
                        report.type = ApplicationErrorReport.TYPE_NONE;
                        intent.putExtra(Intent.EXTRA_BUG_REPORT, report);
                        startActivityForResult(intent, 0);
                    }
                });
        // Show version.
        SimpleActionItem version =
                new SimpleActionItem(
                        getString(R.string.settings_menu_version),
                        ((TvApplication) activity.getApplicationContext()).getVersionName());
        version.setClickable(false);
        items.add(version);
        return items;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getChannelDataManager().areAllChannelsHidden()) {
            Toast.makeText(getActivity(), R.string.msg_all_channels_hidden, Toast.LENGTH_SHORT)
                    .show();
        }
    }
}
