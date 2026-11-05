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

import android.media.tv.TvContract.Channels;
import android.os.Bundle;
import androidx.leanback.widget.VerticalGridView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.android.tv.R;
import com.android.tv.data.ChannelImpl;
import com.android.tv.data.ChannelNumber;
import com.android.tv.data.api.Channel;
import com.android.tv.ui.OnRepeatedKeyInterceptListener;
import com.android.tv.util.TvInputManagerHelper;
import com.android.tv.util.Utils;
import com.livetv.tv.channels.ChannelOrder;
import com.livetv.tv.channels.Favorites;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CustomizeChannelListFragment extends SideFragment {
    private static final int GROUP_BY_SOURCE = ChannelOrder.GROUP_BY_SOURCE;
    private static final int GROUP_BY_HD_SD = ChannelOrder.GROUP_BY_HD_SD;
    private static final int GROUP_BY_ALL = ChannelOrder.GROUP_BY_ALL;
    private static final int GROUP_BY_FAVORITES = ChannelOrder.GROUP_BY_FAVORITES;
    private static final String TRACKER_LABEL = "customize channel list";

    private static final String GROUP_KEY_HD = "hd";
    private static final String GROUP_KEY_SD = "sd";
    private static final String GROUP_KEY_ALL = "all";

    private final List<Channel> mChannels = new ArrayList<>();
    private long mLastFocusedChannelId = Channel.INVALID_ID;

    private static Integer sGroupingType;
    private TvInputManagerHelper mInputManager;
    private ChannelImpl.DefaultComparator mChannelComparator;
    private boolean mGroupByFragmentRunning;

    private final List<Item> mItems = new ArrayList<>();
    private final List<ChannelGroup> mGroups = new ArrayList<>();
    /** Keys of the groups the user expanded since the list was last built. */
    private final Set<String> mExpandedGroups = new HashSet<>();
    private GroupBySubMenu mGroupBySubMenu;
    /** LiveTV: the channel being moved in the "All" list, or null. */
    private ChannelItem mMovingItem;
    /** LiveTV: whether the key that drops the moved channel went down while moving. */
    private boolean mDropKeyDown;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInputManager = getMainActivity().getTvInputManagerHelper();
        mChannelComparator = new ChannelImpl.DefaultComparator(getActivity(), mInputManager);
        if (sGroupingType == null) {
            sGroupingType = ChannelOrder.getGroupingType(getContext());
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        VerticalGridView listView = (VerticalGridView) view.findViewById(R.id.side_panel_list);
        listView.setOnKeyInterceptListener(
                new OnRepeatedKeyInterceptListener(listView) {
                    @Override
                    public boolean onInterceptKeyEvent(KeyEvent event) {
                        if (mMovingItem != null) {
                            return handleMoveKey(event);
                        }
                        // In order to send tune operation once for continuous channel up/down
                        // events, we only call the moveToChannel method on ACTION_UP event of
                        // channel switch keys.
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            switch (event.getKeyCode()) {
                                case KeyEvent.KEYCODE_DPAD_UP:
                                case KeyEvent.KEYCODE_DPAD_DOWN:
                                    // Group headers keep the last focused channel, which is
                                    // already playing, so they do not re-tune.
                                    if (mLastFocusedChannelId != Channel.INVALID_ID
                                            && mLastFocusedChannelId
                                                    != getMainActivity().getCurrentChannelId()) {
                                        getMainActivity()
                                                .tuneToChannel(
                                                        getChannelDataManager()
                                                                .getChannel(mLastFocusedChannelId));
                                    }
                                    break;
                            }
                        }
                        return super.onInterceptKeyEvent(event);
                    }
                });

        if (!mGroupByFragmentRunning) {
            mLastFocusedChannelId = getMainActivity().getCurrentChannelId();
            getMainActivity().startShrunkenTvView(false, true);
            // Every source starts collapsed; focus the first one so the list starts at the top
            // ("All" has no header, so it starts at the first item).
            int firstHeader = mGroups.isEmpty() ? -1 : mItems.indexOf(mGroups.get(0).mHeaderItem);
            setSelectedPosition(Math.max(firstHeader, 0));
        }
        mGroupByFragmentRunning = false;
        return view;
    }

    @Override
    public void onDestroyView() {
        if (mMovingItem != null) {
            dropMovingChannel();
        }
        getChannelDataManager().applyUpdatedValuesToDb();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getMainActivity().endShrunkenTvView();
    }

    @Override
    protected String getTitle() {
        return getString(R.string.side_panel_title_edit_channels_for_an_input);
    }

    @Override
    public String getTrackerLabel() {
        return TRACKER_LABEL;
    }

    @Override
    protected List<Item> getItemList() {
        mChannels.clear();
        mChannels.addAll(getChannelDataManager().getChannelList());
        mGroups.clear();
        // The list is rebuilt when it opens and after "group by" changes: start collapsed.
        mExpandedGroups.clear();
        if (sGroupingType == GROUP_BY_SOURCE) {
            mGroupBySubMenu = new GroupBySubMenu(getString(R.string.edit_channels_group_by_sources));
            addGroupsBySource(mGroups);
        } else if (sGroupingType == GROUP_BY_ALL) {
            mGroupBySubMenu = new GroupBySubMenu(getString(R.string.livetv_group_by_all));
            addAllChannelsGroup(mGroups, false);
            // The single list is always shown open.
            mExpandedGroups.add(GROUP_KEY_ALL);
        } else if (sGroupingType == GROUP_BY_FAVORITES) {
            mGroupBySubMenu = new GroupBySubMenu(getString(R.string.livetv_favorites));
            addAllChannelsGroup(mGroups, true);
            mExpandedGroups.add(GROUP_KEY_ALL);
        } else {
            // GROUP_BY_HD_SD
            mGroupBySubMenu = new GroupBySubMenu(getString(R.string.edit_channels_group_by_hd_sd));
            addGroupsByHdSd(mGroups);
        }
        return buildVisibleItems();
    }

    /** Builds the shown list: every group header, plus the channels of the expanded groups. */
    private List<Item> buildVisibleItems() {
        mItems.clear();
        mItems.add(mGroupBySubMenu);
        for (ChannelGroup group : mGroups) {
            if (!GROUP_KEY_ALL.equals(group.mKey)) {
                mItems.add(group.mHeaderItem);
            }
            if (mExpandedGroups.contains(group.mKey)) {
                if (group.mSelectGroupItem != null) {
                    mItems.add(group.mSelectGroupItem);
                }
                mItems.addAll(group.mChannelItems);
            }
        }
        return mItems;
    }

    private void toggleGroup(ChannelGroup group) {
        if (!mExpandedGroups.remove(group.mKey)) {
            mExpandedGroups.add(group.mKey);
        }
        setItems(new ArrayList<>(buildVisibleItems()));
        setSelectedPosition(mItems.indexOf(group.mHeaderItem));
    }

    private void addChannelToGroup(ChannelGroup group, Channel channel) {
        ChannelItem channelItem = new ChannelItem(channel, group.mSelectGroupItem);
        group.mChannelItems.add(channelItem);
        group.mSelectGroupItem.addChannelItem(channelItem);
    }

    /** A group with only one channel does not need the "select all" item. */
    private static void cleanUpOneChannelGroupItem(List<ChannelGroup> groups) {
        for (ChannelGroup group : groups) {
            if (group.mChannelItems.size() == 1) {
                group.mChannelItems.get(0).mSelectGroupItem = null;
                group.mSelectGroupItem = null;
            }
        }
    }

    private void addGroupsBySource(List<ChannelGroup> groups) {
        ArrayList<Channel> channels = new ArrayList<>(mChannels);
        Collections.sort(channels, mChannelComparator);

        ChannelGroup group = null;
        for (Channel channel : channels) {
            if (group == null || !channel.getInputId().equals(group.mKey)) {
                String inputId = channel.getInputId();
                String inputLabel =
                        Utils.loadLabel(getActivity(), mInputManager.getTvInputInfo(inputId));
                group = new ChannelGroup(inputId, inputLabel);
                groups.add(group);
            }
            addChannelToGroup(group, channel);
        }
        cleanUpOneChannelGroupItem(groups);
    }

    /**
     * Every channel in a single list without header, or only the favorites ("Favorites" shows
     * them in the order of "All" too).
     */
    private void addAllChannelsGroup(List<ChannelGroup> groups, boolean onlyFavorites) {
        Set<String> favoriteIds = onlyFavorites ? Favorites.getIds(getContext()) : null;
        // The channel data manager already keeps the order of the "All" list.
        ChannelGroup group = new ChannelGroup(GROUP_KEY_ALL, getString(R.string.livetv_group_by_all));
        for (Channel channel : mChannels) {
            if (favoriteIds == null || favoriteIds.contains(String.valueOf(channel.getId()))) {
                addChannelToGroup(group, channel);
            }
        }
        if (group.mChannelItems.isEmpty()) {
            return;
        }
        groups.add(group);
        cleanUpOneChannelGroupItem(groups);
    }

    /** LiveTV: starts moving a channel of the "All" list with up/down. */
    private void startMovingChannel(ChannelItem item) {
        mMovingItem = item;
        mDropKeyDown = false;
        item.notifyUpdated();
    }

    /**
     * LiveTV: while a channel is being moved, up/down move it and OK or Back drop it. Returns
     * whether the key was used.
     */
    private boolean handleMoveKey(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    moveChannel(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    mDropKeyDown = true;
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP && mDropKeyDown) {
                    dropMovingChannel();
                    return true;
                }
                // The release of the long press that started the move goes to the item, which
                // then skips its click.
                return event.getKeyCode() == KeyEvent.KEYCODE_BACK
                        || event.getAction() == KeyEvent.ACTION_DOWN;
            default:
                return false;
        }
    }

    private void moveChannel(int offset) {
        List<ChannelItem> channelItems = mGroups.get(0).mChannelItems;
        int from = channelItems.indexOf(mMovingItem);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= channelItems.size()) {
            return;
        }
        Collections.swap(channelItems, from, to);
        getChannelDataManager().setAllChannelsOrder(getChannelIds(channelItems), false);
        setItems(new ArrayList<>(buildVisibleItems()));
        setSelectedPosition(mItems.indexOf(mMovingItem));
    }

    private void dropMovingChannel() {
        ChannelItem item = mMovingItem;
        mMovingItem = null;
        mDropKeyDown = false;
        getChannelDataManager()
                .setAllChannelsOrder(getChannelIds(mGroups.get(0).mChannelItems), true);
        item.notifyUpdated();
    }

    private static List<Long> getChannelIds(List<ChannelItem> channelItems) {
        List<Long> ids = new ArrayList<>(channelItems.size());
        for (ChannelItem channelItem : channelItems) {
            ids.add(channelItem.getChannel().getId());
        }
        return ids;
    }

    private void addGroupsByHdSd(List<ChannelGroup> groups) {
        ArrayList<Channel> channels = new ArrayList<>(mChannels);
        Collections.sort(
                channels,
                (Channel lhs, Channel rhs) -> {
                    boolean lhsHd = isHdChannel(lhs);
                    boolean rhsHd = isHdChannel(rhs);
                    if (lhsHd == rhsHd) {
                        return ChannelNumber.compare(
                                lhs.getDisplayNumber(), rhs.getDisplayNumber());
                    } else {
                        return lhsHd ? -1 : 1;
                    }
                });

        Boolean isHdGroup = null;
        ChannelGroup group = null;
        for (Channel channel : channels) {
            boolean isHd = isHdChannel(channel);
            if (isHdGroup == null || isHd != isHdGroup) {
                isHdGroup = isHd;
                group =
                        new ChannelGroup(
                                isHd ? GROUP_KEY_HD : GROUP_KEY_SD,
                                isHd
                                        ? getString(R.string.edit_channels_group_divider_for_hd)
                                        : getString(R.string.edit_channels_group_divider_for_sd));
                groups.add(group);
            }
            addChannelToGroup(group, channel);
        }
        cleanUpOneChannelGroupItem(groups);
    }

    private static boolean isHdChannel(Channel channel) {
        String videoFormat = channel.getVideoFormat();
        return videoFormat != null
                && (Channels.VIDEO_FORMAT_720P.equals(videoFormat)
                        || Channels.VIDEO_FORMAT_1080I.equals(videoFormat)
                        || Channels.VIDEO_FORMAT_1080P.equals(videoFormat)
                        || Channels.VIDEO_FORMAT_2160P.equals(videoFormat)
                        || Channels.VIDEO_FORMAT_4320P.equals(videoFormat));
    }

    /** Channels of one source (or HD/SD), shown under a header that expands and collapses. */
    private class ChannelGroup {
        private final String mKey;
        private final GroupHeaderItem mHeaderItem;
        private SelectGroupItem mSelectGroupItem = new SelectGroupItem();
        private final List<ChannelItem> mChannelItems = new ArrayList<>();

        ChannelGroup(String key, String label) {
            mKey = key;
            mHeaderItem = new GroupHeaderItem(this, label);
        }
    }

    private class GroupHeaderItem extends ActionItem {
        private final ChannelGroup mGroup;
        private final String mLabel;
        private TextView mTitleView;
        private TextView mDescriptionView;

        GroupHeaderItem(ChannelGroup group, String label) {
            super(label);
            mGroup = group;
            mLabel = label;
        }

        @Override
        protected void onBind(View view) {
            super.onBind(view);
            mTitleView = (TextView) view.findViewById(R.id.title);
            mDescriptionView = (TextView) view.findViewById(R.id.description);
        }

        @Override
        protected void onUpdate() {
            super.onUpdate();
            boolean expanded = mExpandedGroups.contains(mGroup.mKey);
            mTitleView.setText((expanded ? "\u25BE  " : "\u25B8  ") + mLabel);
            int count = mGroup.mChannelItems.size();
            mDescriptionView.setVisibility(View.VISIBLE);
            mDescriptionView.setText(
                    getResources()
                            .getQuantityString(R.plurals.livetv_channel_group_count, count, count));
        }

        @Override
        protected void onSelected() {
            toggleGroup(mGroup);
        }
    }

    private class SelectGroupItem extends ActionItem {
        private final List<ChannelItem> mChannelItemsInGroup = new ArrayList<>();
        private TextView mTextView;
        private boolean mAllChecked;

        public SelectGroupItem() {
            super(null);
        }

        private void addChannelItem(ChannelItem channelItem) {
            mChannelItemsInGroup.add(channelItem);
        }

        @Override
        protected void onBind(View view) {
            super.onBind(view);
            mTextView = (TextView) view.findViewById(R.id.title);
        }

        @Override
        protected void onUpdate() {
            super.onUpdate();
            mAllChecked = true;
            for (ChannelItem channelItem : mChannelItemsInGroup) {
                if (!channelItem.getChannel().isBrowsable()) {
                    mAllChecked = false;
                    break;
                }
            }
            mTextView.setText(
                    getString(
                            mAllChecked
                                    ? R.string.edit_channels_item_deselect_group
                                    : R.string.edit_channels_item_select_group));
        }

        @Override
        protected void onSelected() {
            for (ChannelItem channelItem : mChannelItemsInGroup) {
                Channel channel = channelItem.getChannel();
                if (channel.isBrowsable() == mAllChecked) {
                    getChannelDataManager().updateBrowsable(channel.getId(), !mAllChecked, true);
                    channelItem.notifyUpdated();
                }
            }
            getChannelDataManager().notifyChannelBrowsableChanged();
            mAllChecked = !mAllChecked;
            mTextView.setText(
                    getString(
                            mAllChecked
                                    ? R.string.edit_channels_item_deselect_group
                                    : R.string.edit_channels_item_select_group));
        }
    }

    private class ChannelItem extends ChannelCheckItem {
        private SelectGroupItem mSelectGroupItem;
        private View mView;

        public ChannelItem(Channel channel, SelectGroupItem selectGroupItem) {
            super(channel, getChannelDataManager(), getProgramDataManager());
            mSelectGroupItem = selectGroupItem;
        }

        @Override
        protected void onBind(View view) {
            super.onBind(view);
            mView = view;
            // LiveTV: holding OK on a channel of the "All" list starts moving it.
            if (sGroupingType == GROUP_BY_ALL) {
                view.setOnLongClickListener(
                        v -> {
                            if (mMovingItem == null) {
                                startMovingChannel(this);
                            }
                            return true;
                        });
            }
        }

        @Override
        protected void onUnbind() {
            // Item views are shared with other side panels.
            mView.setOnLongClickListener(null);
            mView.setLongClickable(false);
            mView = null;
            super.onUnbind();
        }

        @Override
        protected void onUpdate() {
            super.onUpdate();
            setChecked(getChannel().isBrowsable());
            if (mMovingItem == this) {
                ((TextView) mView.findViewById(R.id.channel_name))
                        .setText("▲▼  " + getChannel().getDisplayName());
                ((TextView) mView.findViewById(R.id.program_title))
                        .setText(R.string.livetv_channel_move_hint);
            }
        }

        @Override
        protected void onSelected() {
            super.onSelected();
            getChannelDataManager().updateBrowsable(getChannel().getId(), isChecked());
            if (mSelectGroupItem != null) {
                mSelectGroupItem.notifyUpdated();
            }
        }

        @Override
        protected void onFocused() {
            super.onFocused();
            mLastFocusedChannelId = getChannel().getId();
        }
    }

    public static class GroupByFragment extends SideFragment {
        @Override
        protected String getTitle() {
            return getString(R.string.side_panel_title_group_by);
        }

        @Override
        public String getTrackerLabel() {
            return GroupBySubMenu.TRACKER_LABEL;
        }

        @Override
        protected List<Item> getItemList() {
            List<Item> items = new ArrayList<>();
            items.add(createGroupingItem(R.string.livetv_group_by_all, GROUP_BY_ALL));
            items.add(createGroupingItem(R.string.livetv_favorites, GROUP_BY_FAVORITES));
            items.add(createGroupingItem(R.string.edit_channels_group_by_sources, GROUP_BY_SOURCE));
            items.add(createGroupingItem(R.string.edit_channels_group_by_hd_sd, GROUP_BY_HD_SD));
            return items;
        }

        private RadioButtonItem createGroupingItem(int titleResId, int groupingType) {
            RadioButtonItem item =
                    new RadioButtonItem(getString(titleResId)) {
                        @Override
                        protected void onSelected() {
                            super.onSelected();
                            setGroupingType(groupingType);
                            closeFragment();
                        }
                    };
            item.setChecked(sGroupingType == groupingType);
            return item;
        }

        private void setGroupingType(int groupingType) {
            sGroupingType = groupingType;
            ChannelOrder.setGroupingType(getContext(), groupingType);
            // LiveTV: the chosen list decides the order of the guide and the remote.
            getChannelDataManager().applyChannelOrder();
        }
    }

    private class GroupBySubMenu extends SubMenuItem {
        private static final String TRACKER_LABEL = "Group by";

        public GroupBySubMenu(String description) {
            super(
                    getString(R.string.edit_channels_item_group_by),
                    description,
                    getMainActivity().getOverlayManager().getSideFragmentManager());
        }

        @Override
        protected SideFragment getFragment() {
            return new GroupByFragment();
        }

        @Override
        protected void onSelected() {
            mGroupByFragmentRunning = true;
            super.onSelected();
        }
    }
}
