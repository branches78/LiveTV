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

package com.android.tv.menu;

import android.content.Context;
import android.media.tv.TvTrackInfo;
import android.text.TextUtils;

import android.support.annotation.Nullable;

import com.android.tv.TvOptionsManager;
import com.android.tv.common.BuildConfig;
import com.android.tv.common.customization.CustomAction;
import com.android.tv.data.DisplayMode;
import com.android.tv.features.TvFeatures;
import com.android.tv.ui.TvViewUiManager;
import com.android.tv.ui.sidepanel.ClosedCaptionFragment;
import com.android.tv.ui.sidepanel.DeveloperOptionFragment;
import com.android.tv.ui.sidepanel.DisplayModeFragment;
import com.android.tv.ui.sidepanel.MultiAudioFragment;

import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;

import com.android.tv.common.flags.LegacyFlags;
import com.android.tv.common.flags.UiFlags;

import java.util.ArrayList;
import java.util.List;

/*
 * An adapter of options.
 */
@AutoFactory(implementing = TvOptionsRowAdapter.Factory.class)
public class TvOptionsRowAdapter extends CustomizableOptionsRowAdapter {
    private final LegacyFlags mLegacyFlags;
    private final UiFlags mUiFlags;
    /** Every action, including the ones hidden by {@link #getVisibleActions}. */
    private List<MenuAction> mAllActions;

    /** Factory for a {@link TvOptionsRowAdapter}. */
    public interface Factory {
        /** Creates a {@link TvOptionsRowAdapter} */
        TvOptionsRowAdapter create(Context context, @Nullable List<CustomAction> customActions);
    }

    public TvOptionsRowAdapter(
            Context context,
            @Nullable List<CustomAction> customActions,
            @Provided LegacyFlags mLegacyFlags,
            @Provided UiFlags uiFlags) {
        super(context, customActions);
        this.mLegacyFlags = mLegacyFlags;
        mUiFlags = uiFlags;
    }

    @Override
    protected List<MenuAction> createBaseActions() {
        List<MenuAction> actionList = new ArrayList<>();
        actionList.add(MenuAction.SELECT_CLOSED_CAPTION_ACTION);
        actionList.add(MenuAction.SELECT_DISPLAY_MODE_ACTION);
        if (TvFeatures.PICTURE_IN_PICTURE.isEnabled(getMainActivity())) {
            actionList.add(MenuAction.SYSTEMWIDE_PIP_ACTION);
        }
        actionList.add(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION);
        if (!TextUtils.isEmpty(mUiFlags.moreChannelsUrl())) {
            actionList.add(MenuAction.MORE_CHANNELS_ACTION);
        }
        if (BuildConfig.ENG || mLegacyFlags.enableDeveloperFeatures()) {
            actionList.add(MenuAction.DEV_ACTION);
        }
        actionList.add(MenuAction.SETTINGS_ACTION);

        updateClosedCaptionAction();
        updatePipAction();
        updateMultiAudioAction();
        updateDisplayModeAction();
        return actionList;
    }

    @Override
    protected List<MenuAction> createActions() {
        mAllActions = super.createActions();
        return getVisibleActions();
    }

    @Override
    protected void updateActions() {
        boolean closedCaptionChanged = updateClosedCaptionAction();
        boolean pipChanged = updatePipAction();
        boolean multiAudioChanged = updateMultiAudioAction();
        boolean displayModeChanged = updateDisplayModeAction();
        List<MenuAction> visibleActions = getVisibleActions();
        if (!visibleActions.equals(getItemList())) {
            setActions(visibleActions);
            return;
        }
        if (closedCaptionChanged) {
            notifyActionChanged(MenuAction.SELECT_CLOSED_CAPTION_ACTION);
        }
        if (pipChanged) {
            notifyActionChanged(MenuAction.SYSTEMWIDE_PIP_ACTION);
        }
        if (multiAudioChanged) {
            notifyActionChanged(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION);
        }
        if (displayModeChanged) {
            notifyActionChanged(MenuAction.SELECT_DISPLAY_MODE_ACTION);
        }
    }

    /** Hides the subtitle, display mode and multi-audio actions while there is nothing to choose. */
    private List<MenuAction> getVisibleActions() {
        List<MenuAction> visibleActions = new ArrayList<>(mAllActions.size());
        for (MenuAction action : mAllActions) {
            if ((action == MenuAction.SELECT_CLOSED_CAPTION_ACTION
                            || action == MenuAction.SELECT_DISPLAY_MODE_ACTION
                            || action == MenuAction.SELECT_AUDIO_LANGUAGE_ACTION)
                    && !action.isEnabled()) {
                continue;
            }
            visibleActions.add(action);
        }
        return visibleActions;
    }

    private void notifyActionChanged(MenuAction action) {
        if (containsItem(action)) {
            notifyItemChanged(getItemPosition(action));
        }
    }

    private boolean updateClosedCaptionAction() {
        List<TvTrackInfo> subtitleTracks = getMainActivity().getTracks(TvTrackInfo.TYPE_SUBTITLE);
        boolean enabled = subtitleTracks != null && !subtitleTracks.isEmpty();
        // Use "|" operator for non-short-circuit evaluation.
        return MenuAction.setEnabled(MenuAction.SELECT_CLOSED_CAPTION_ACTION, enabled)
                | updateActionDescription(MenuAction.SELECT_CLOSED_CAPTION_ACTION);
    }

    private boolean updatePipAction() {
        if (containsItem(MenuAction.SYSTEMWIDE_PIP_ACTION)) {
            return MenuAction.setEnabled(
                    MenuAction.SYSTEMWIDE_PIP_ACTION,
                    !getMainActivity().isScreenBlockedByResourceConflictOrParentalControl());
        }
        return false;
    }

    boolean updateMultiAudioAction() {
        List<TvTrackInfo> audioTracks = getMainActivity().getTracks(TvTrackInfo.TYPE_AUDIO);
        boolean enabled = audioTracks != null && audioTracks.size() > 1;
        // Use "|" operator for non-short-circuit evaluation.
        return MenuAction.setEnabled(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION, enabled)
                | updateActionDescription(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION);
    }

    private boolean updateDisplayModeAction() {
        TvViewUiManager uiManager = getMainActivity().getTvViewUiManager();
        boolean enabled =
                uiManager.isDisplayModeAvailable(DisplayMode.MODE_FULL)
                        || uiManager.isDisplayModeAvailable(DisplayMode.MODE_ZOOM);
        // Use "|" operator for non-short-circuit evaluation.
        return MenuAction.setEnabled(MenuAction.SELECT_DISPLAY_MODE_ACTION, enabled)
                | updateActionDescription(MenuAction.SELECT_DISPLAY_MODE_ACTION);
    }

    private boolean updateActionDescription(MenuAction action) {
        return MenuAction.setActionDescription(
                action, getMainActivity().getTvOptionsManager().getOptionString(action.getType()));
    }

    @Override
    protected void executeBaseAction(int type) {
        switch (type) {
            case TvOptionsManager.OPTION_CLOSED_CAPTIONS:
                getMainActivity()
                        .getOverlayManager()
                        .getSideFragmentManager()
                        .show(new ClosedCaptionFragment());
                break;
            case TvOptionsManager.OPTION_DISPLAY_MODE:
                getMainActivity()
                        .getOverlayManager()
                        .getSideFragmentManager()
                        .show(new DisplayModeFragment());
                break;
            case TvOptionsManager.OPTION_SYSTEMWIDE_PIP:
                getMainActivity().enterPictureInPictureMode();
                break;
            case TvOptionsManager.OPTION_MULTI_AUDIO:
                getMainActivity()
                        .getOverlayManager()
                        .getSideFragmentManager()
                        .show(new MultiAudioFragment());
                break;
            case TvOptionsManager.OPTION_MORE_CHANNELS:
                getMainActivity().showMerchantCollection();
                break;
            case TvOptionsManager.OPTION_DEVELOPER:
                getMainActivity()
                        .getOverlayManager()
                        .getSideFragmentManager()
                        .show(new DeveloperOptionFragment());
                break;
            case TvOptionsManager.OPTION_SETTINGS:
                getMainActivity().showSettingsFragment();
                break;
        }
    }
}
