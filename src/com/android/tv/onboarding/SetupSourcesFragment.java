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

package com.android.tv.onboarding;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager.TvInputCallback;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.GuidanceStylist.Guidance;
import androidx.leanback.widget.GuidedAction;
import androidx.leanback.widget.GuidedActionsStylist;
import androidx.leanback.widget.VerticalGridView;

import com.android.tv.R;
import com.android.tv.TvSingletons;
import com.android.tv.common.ui.setup.SetupGuidedStepFragment;
import com.android.tv.common.ui.setup.SetupMultiPaneFragment;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.data.TvInputNewComparator;
import com.android.tv.ui.GuidedActionsStylistWithDivider;
import com.android.tv.util.SetupUtils;
import com.android.tv.util.TvInputManagerHelper;

import com.livetv.tv.iptv.IptvBackupActivity;
import com.livetv.tv.iptv.IptvChannels;

import dagger.android.AndroidInjection;
import dagger.android.ContributesAndroidInjector;

import com.android.tv.common.flags.UiFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** A fragment for channel source info/setup. */
public class SetupSourcesFragment extends SetupMultiPaneFragment {
    /** The action category for the actions which is fired from this fragment. */
    public static final String ACTION_CATEGORY = "com.android.tv.onboarding.SetupSourcesFragment";
    /** An action to open the merchant collection. */
    public static final int ACTION_ONLINE_STORE = 1;
    /**
     * An action to show the setup activity of TV input.
     *
     * <p>This action is not added to the action list. This is sent outside of the fragment. Use
     * {@link #ACTION_PARAM_KEY_INPUT_ID} to get the input ID from the parameter.
     */
    public static final int ACTION_SETUP_INPUT = 2;

    /**
     * The key for the action parameter which contains the TV input ID. It's used for the action
     * {@link #ACTION_SETUP_INPUT}.
     */
    public static final String ACTION_PARAM_KEY_INPUT_ID = "input_id";

    private static final String SETUP_TRACKER_LABEL = "Setup fragment";

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        TvSingletons.getSingletons(getActivity()).getTracker().sendScreenView(SETUP_TRACKER_LABEL);
        return view;
    }

    @Override
    protected void onEnterTransitionEnd() {
        SetupGuidedStepFragment f = getContentFragment();
        if (f instanceof ContentFragment) {
            // If the enter transition is canceled quickly, the child fragment can be null because
            // the fragment is added asynchronously.
            ((ContentFragment) f).executePendingAction();
        }
    }

    @Override
    protected SetupGuidedStepFragment onCreateContentFragment() {
        SetupGuidedStepFragment f = new ContentFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean(SetupGuidedStepFragment.KEY_THREE_PANE, true);
        f.setArguments(arguments);
        return f;
    }

    @Override
    protected String getActionCategory() {
        return ACTION_CATEGORY;
    }

    public static class ContentFragment extends SetupGuidedStepFragment {
        // ACTION_ONLINE_STORE is defined in the outer class.
        /** An incompatible app's source: shown disabled, does nothing. */
        private static final int ACTION_INCOMPATIBLE_INPUT = 2;
        private static final int ACTION_HEADER = 3;
        private static final int ACTION_INPUT_START = 4;
        /** LiveTV: restores a backup of the IPTV setup, while no list is imported. */
        private static final int ACTION_RESTORE_IPTV_BACKUP = -10;

        private static final int PENDING_ACTION_NONE = 0;
        private static final int PENDING_ACTION_INPUT_CHANGED = 1;
        private static final int PENDING_ACTION_CHANNEL_CHANGED = 2;

        @Inject TvInputManagerHelper mInputManager;
        @Inject ChannelDataManager mChannelDataManager;
        @Inject SetupUtils mSetupUtils;
        @Inject UiFlags mUiFlags;
        private List<TvInputInfo> mInputs;
        private List<TvInputInfo> mIncompatibleInputs = new ArrayList<>();
        private int mKnownInputStartIndex;

        private SetupSourcesFragment mParentFragment;

        private String mNewlyAddedInputId;

        private int mPendingAction = PENDING_ACTION_NONE;
        /** LiveTV: the restore screen was opened from here. */
        private boolean mRestoreStarted;
        /** LiveTV: the next update of the list puts the focus on the IPTV source. */
        private boolean mFocusIptvInput;

        private final TvInputCallback mInputCallback =
                new TvInputCallback() {
                    @Override
                    public void onInputAdded(String inputId) {
                        handleInputChanged();
                    }

                    @Override
                    public void onInputRemoved(String inputId) {
                        handleInputChanged();
                    }

                    @Override
                    public void onInputUpdated(String inputId) {
                        handleInputChanged();
                    }

                    @Override
                    public void onTvInputInfoUpdated(TvInputInfo inputInfo) {
                        handleInputChanged();
                    }

                    private void handleInputChanged() {
                        // The actions created while enter transition is running will not be
                        // included in the
                        // fragment transition.
                        if (mParentFragment.isEnterTransitionRunning()) {
                            mPendingAction = PENDING_ACTION_INPUT_CHANGED;
                            return;
                        }
                        buildInputs();
                        updateActions();
                    }
                };

        private final ChannelDataManager.Listener mChannelDataManagerListener =
                new ChannelDataManager.Listener() {
                    @Override
                    public void onLoadFinished() {
                        handleChannelChanged();
                    }

                    @Override
                    public void onChannelListUpdated() {
                        handleChannelChanged();
                    }

                    @Override
                    public void onChannelBrowsableChanged() {
                        handleChannelChanged();
                    }

                    private void handleChannelChanged() {
                        // The actions created while enter transition is running will not be
                        // included in the
                        // fragment transition.
                        if (mParentFragment.isEnterTransitionRunning()) {
                            if (mPendingAction != PENDING_ACTION_INPUT_CHANGED) {
                                mPendingAction = PENDING_ACTION_CHANNEL_CHANGED;
                            }
                            return;
                        }
                        updateActions();
                    }
                };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mParentFragment = (SetupSourcesFragment) getParentFragment();
        }

        @Override
        public void onAttach(Activity activity) {
            AndroidInjection.inject(this);
            super.onAttach(activity);
            buildInputs();
            mInputManager.addCallback(mInputCallback);
            mChannelDataManager.addListener(mChannelDataManagerListener);
            mParentFragment = (SetupSourcesFragment) getParentFragment();
        }

        @Override
        public void onDetach() {
            mChannelDataManager.removeListener(mChannelDataManagerListener);
            mInputManager.removeCallback(mInputCallback);
            super.onDetach();
        }

        @NonNull
        @Override
        public Guidance onCreateGuidance(Bundle savedInstanceState) {
            String title = getString(R.string.setup_sources_text);
            String description = getString(R.string.setup_sources_description2);
            return new Guidance(title, description, null, null);
        }

        @Override
        public GuidedActionsStylist onCreateActionsStylist() {
            return new SetupSourceGuidedActionsStylist();
        }

        @Override
        public void onCreateActions(
                @NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
            createActionsInternal(actions);
        }

        private void buildInputs() {
            List<TvInputInfo> oldInputs = mInputs;
            mInputs = mInputManager.getTvInputInfos(true, true);
            mIncompatibleInputs = mInputManager.getUnreadableAppTunerInputs();
            // Get newly installed input ID.
            if (oldInputs != null) {
                List<TvInputInfo> newList = new ArrayList<>(mInputs);
                for (TvInputInfo input : oldInputs) {
                    newList.remove(input);
                }
                if (newList.size() > 0 && mSetupUtils.isNewInput(newList.get(0).getId())) {
                    mNewlyAddedInputId = newList.get(0).getId();
                } else {
                    mNewlyAddedInputId = null;
                }
            }
            Collections.sort(mInputs, new TvInputNewComparator(mSetupUtils, mInputManager));
            mKnownInputStartIndex = 0;
            for (TvInputInfo input : mInputs) {
                if (mSetupUtils.isNewInput(input.getId())) {
                    mSetupUtils.markAsKnownInput(input.getId());
                    ++mKnownInputStartIndex;
                }
            }
        }

        private void updateActions() {
            List<GuidedAction> actions = new ArrayList<>();
            createActionsInternal(actions);
            // LiveTV: back from restoring a backup, the IPTV source gets the focus (the restore
            // item, which had it, is gone).
            int focusPosition = -1;
            if (mFocusIptvInput) {
                mFocusIptvInput = false;
                String iptvInputId = IptvChannels.getInputId(getActivity());
                for (int i = 0; i < actions.size(); i++) {
                    int index = (int) actions.get(i).getId() - ACTION_INPUT_START;
                    if (index >= 0
                            && index < mInputs.size()
                            && mInputs.get(index).getId().equals(iptvInputId)) {
                        focusPosition = i;
                        break;
                    }
                }
            }
            setActions(actions);
            VerticalGridView gridView = getGuidedActionsStylist().getActionsGridView();
            if (gridView == null) {
                return;
            }
            if (focusPosition >= 0) {
                gridView.setSelectedPosition(focusPosition);
            }
            // LiveTV: the focused item may have been removed; without focus the remote did
            // nothing on this screen.
            View view = getView();
            if (view != null && !view.hasFocus()) {
                gridView.requestFocus();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (mRestoreStarted) {
                // Back from restoring a backup: show the imported source.
                mRestoreStarted = false;
                mFocusIptvInput = true;
                if (mParentFragment.isEnterTransitionRunning()) {
                    mPendingAction = PENDING_ACTION_CHANNEL_CHANGED;
                } else {
                    updateActions();
                }
            }
        }

        private GuidedAction createHeader(int titleRes) {
            return new GuidedAction.Builder(getActivity())
                    .id(ACTION_HEADER)
                    .title(null)
                    .description(getString(titleRes))
                    .focusable(false)
                    .infoOnly(true)
                    .build();
        }

        /**
         * LiveTV sections: "Import backup file" (while no IPTV list is imported), "Sources" (every
         * source, the new ones first) and "Incompatible sources".
         */
        private void createActionsInternal(List<GuidedAction> actions) {
            int newPosition = -1;
            if (!IptvChannels.hasPlaylists(getActivity())) {
                actions.add(createHeader(R.string.livetv_setup_category_backup));
                actions.add(
                        new GuidedAction.Builder(getActivity())
                                .id(ACTION_RESTORE_IPTV_BACKUP)
                                .title(getString(R.string.livetv_backup_sources_title))
                                .description(getString(R.string.livetv_backup_sources_description))
                                .build());
            }
            if (!mInputs.isEmpty()) {
                actions.add(createHeader(R.string.setup_category_done));
            }
            for (int i = 0; i < mInputs.size(); ++i) {
                TvInputInfo input = mInputs.get(i);
                String inputId = input.getId();
                String description;
                int channelCount = mChannelDataManager.getBrowsableChannelCountForInput(inputId);
                if (mSetupUtils.isSetupDone(inputId) || channelCount > 0) {
                    if (channelCount == 0) {
                        description = getString(R.string.setup_input_no_channels);
                    } else {
                        description =
                                getResources()
                                        .getQuantityString(
                                                R.plurals.setup_input_channels,
                                                channelCount,
                                                channelCount);
                    }
                } else if (i >= mKnownInputStartIndex) {
                    description = getString(R.string.setup_input_setup_now);
                } else {
                    description = getString(R.string.setup_input_new);
                }
                if (input.getId().equals(mNewlyAddedInputId)) {
                    newPosition = actions.size();
                }
                actions.add(
                        new GuidedAction.Builder(getActivity())
                                .id(ACTION_INPUT_START + i)
                                .title(input.loadLabel(getActivity()).toString())
                                .description(description)
                                .build());
            }
            if (!mIncompatibleInputs.isEmpty()) {
                // LiveTV: sources of other apps whose channels it cannot read (it is not a system
                // app), listed apart and disabled so the user knows why they do not work.
                actions.add(
                        new GuidedAction.Builder(getActivity())
                                .id(ACTION_HEADER)
                                .title(null)
                                .description(getString(R.string.livetv_setup_category_incompatible))
                                .focusable(false)
                                .infoOnly(true)
                                .build());
                for (TvInputInfo input : mIncompatibleInputs) {
                    actions.add(
                            new GuidedAction.Builder(getActivity())
                                    .id(ACTION_INCOMPATIBLE_INPUT)
                                    .title(input.loadLabel(getActivity()).toString())
                                    .description(getString(R.string.livetv_setup_input_incompatible))
                                    .enabled(false)
                                    .focusable(false)
                                    .infoOnly(true)
                                    .build());
                }
            }
            if (!TextUtils.isEmpty(mUiFlags.moreChannelsUrl())) {
                // online store action, after a divider
                actions.add(GuidedActionsStylistWithDivider.createDividerAction(getContext()));
                actions.add(
                        new GuidedAction.Builder(getActivity())
                                .id(ACTION_ONLINE_STORE)
                                .title(getString(R.string.setup_store_action_title))
                                .description(getString(R.string.setup_store_action_description))
                                .icon(R.drawable.ic_app_store)
                                .build());
            }
            if (newPosition != -1) {
                VerticalGridView gridView = getGuidedActionsStylist().getActionsGridView();
                gridView.setSelectedPosition(newPosition);
            }
        }

        @Override
        protected String getActionCategory() {
            return ACTION_CATEGORY;
        }

        @Override
        public void onGuidedActionClicked(GuidedAction action) {
            if (action.getId() == ACTION_ONLINE_STORE) {
                mParentFragment.onActionClick(ACTION_CATEGORY, (int) action.getId());
                return;
            }
            if (action.getId() == ACTION_RESTORE_IPTV_BACKUP) {
                mRestoreStarted = true;
                startActivity(IptvBackupActivity.buildRestoreIntent(getActivity()));
                return;
            }
            int index = (int) action.getId() - ACTION_INPUT_START;
            if (index >= 0) {
                TvInputInfo input = mInputs.get(index);
                Bundle params = new Bundle();
                params.putString(ACTION_PARAM_KEY_INPUT_ID, input.getId());
                mParentFragment.onActionClick(ACTION_CATEGORY, ACTION_SETUP_INPUT, params);
            }
        }

        void executePendingAction() {
            switch (mPendingAction) {
                case PENDING_ACTION_INPUT_CHANGED:
                    buildInputs();
                    // Fall through
                case PENDING_ACTION_CHANNEL_CHANGED:
                    updateActions();
                    break;
                default: // fall out
            }
            mPendingAction = PENDING_ACTION_NONE;
        }

        private class SetupSourceGuidedActionsStylist extends GuidedActionsStylistWithDivider {
            private static final float ALPHA_CATEGORY = 1.0f;
            private static final float ALPHA_INPUT_DESCRIPTION = 0.5f;
            @Override
            public void onBindViewHolder(ViewHolder vh, GuidedAction action) {
                super.onBindViewHolder(vh, action);
                // Incompatible sources are disabled, which leanback greys out; keep their name
                // readable in white (they still cannot be focused or selected).
                vh.itemView.setAlpha(1f);
                TextView titleView = vh.getTitleView();
                if (titleView != null && action.getId() == ACTION_INCOMPATIBLE_INPUT) {
                    titleView.setAlpha(1f);
                    titleView.setTextColor(Color.WHITE);
                }
                TextView descriptionView = vh.getDescriptionView();
                if (descriptionView != null) {
                    if (action.getId() == ACTION_HEADER) {
                        descriptionView.setAlpha(ALPHA_CATEGORY);
                        descriptionView.setTextColor(
                                getResources().getColor(R.color.setup_category, null));
                        descriptionView.setTypeface(
                                Typeface.create(getString(R.string.condensed_font), 0));
                    } else {
                        descriptionView.setAlpha(ALPHA_INPUT_DESCRIPTION);
                        descriptionView.setTextColor(
                                getResources()
                                        .getColor(R.color.common_setup_input_description, null));
                        descriptionView.setTypeface(Typeface.create(getString(R.string.font), 0));
                    }
                }
                setAccessibilityDelegate(vh, action);
            }
        }
        /**
         * Exports {@link ContentFragment} for Dagger codegen to create the appropriate injector.
         */
        @dagger.Module
        public abstract static class Module {
            @ContributesAndroidInjector
            abstract ContentFragment contributesContentFragment();
        }
    }
}
