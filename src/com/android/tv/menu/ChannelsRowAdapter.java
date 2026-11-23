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
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import com.android.tv.ChannelChanger;
import com.android.tv.R;
import com.android.tv.TvSingletons;
import com.android.tv.analytics.Tracker;
import com.android.tv.common.feature.CommonFeatures;
import com.android.tv.data.ChannelImpl;
import com.android.tv.data.api.Channel;
import com.android.tv.dvr.DvrDataManager;
import com.android.tv.recommendation.Recommender;
import com.android.tv.ui.TvOverlayManager;
import com.android.tv.util.TvInputManagerHelper;
import com.livetv.tv.channels.Favorites;
import com.livetv.tv.iptv.IptvChannels;
import com.livetv.tv.multiview.MultiviewActivity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** An adapter of the Channels row. */
public class ChannelsRowAdapter extends ItemListRowView.ItemListAdapter<ChannelsRowItem>
        implements AccessibilityStateChangeListener {

    private final Context mContext;
    private final Tracker mTracker;
    private final Recommender mRecommender;
    private final DvrDataManager mDvrDataManager;
    private final int mMaxCount;
    private final int mMinCount;
    private final ChannelChanger mChannelChanger;
    private final AccessibilityManager mAccessibilityManager;

    private boolean mShowChannelUpDown;

    public ChannelsRowAdapter(
            Context context, Recommender recommender, int minCount, int maxCount) {
        super(context);
        mContext = context;
        TvSingletons tvSingletons = TvSingletons.getSingletons(context);
        mTracker = tvSingletons.getTracker();
        if (CommonFeatures.DVR.isEnabled(context)) {
            mDvrDataManager = tvSingletons.getDvrDataManager();
        } else {
            mDvrDataManager = null;
        }
        mRecommender = recommender;
        mMinCount = minCount;
        mMaxCount = maxCount;
        setHasStableIds(true);
        mChannelChanger = (ChannelChanger) (context);
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mShowChannelUpDown = mAccessibilityManager.isEnabled();
        mAccessibilityManager.addAccessibilityStateChangeListener(this);
    }

    @Override
    public int getItemViewType(int position) {
        return getItemList().get(position).getLayoutId();
    }

    @Override
    protected int getLayoutResId(int viewType) {
        return viewType;
    }

    @Override
    public long getItemId(int position) {
        return getItemList().get(position).getItemId();
    }

    @Override
    public void onBindViewHolder(MyViewHolder viewHolder, int position) {
        int viewType = getItemViewType(position);
        if (viewType == R.layout.menu_card_guide) {
            viewHolder.itemView.setOnClickListener(this::onGuideClicked);
        } else if (viewType == R.layout.menu_card_favorite) {
            viewHolder.itemView.setOnClickListener(this::onFavoriteClicked);
            bindFavoriteCard(viewHolder.itemView);
        } else if (viewType == R.layout.menu_card_search) {
            viewHolder.itemView.setOnClickListener(this::onSearchClicked);
            ((SimpleCardView) viewHolder.itemView).setText(R.string.livetv_search_channel);
        } else if (viewType == R.layout.menu_card_multiview) {
            viewHolder.itemView.setOnClickListener(this::onMultiviewClicked);
            ((SimpleCardView) viewHolder.itemView).setText(R.string.livetv_multiview);
        } else if (viewType == R.layout.menu_card_up) {
            viewHolder.itemView.setOnClickListener(this::onChannelUpClicked);
        } else if (viewType == R.layout.menu_card_down) {
            viewHolder.itemView.setOnClickListener(this::onChannelDownClicked);
        } else if (viewType == R.layout.menu_card_setup) {
            viewHolder.itemView.setOnClickListener(this::onSetupClicked);
        } else if (viewType == R.layout.menu_card_app_link) {
            viewHolder.itemView.setOnClickListener(this::onAppLinkClicked);
        } else if (viewType == R.layout.menu_card_dvr) {
            viewHolder.itemView.setOnClickListener(this::onDvrClicked);
            SimpleCardView view = (SimpleCardView) viewHolder.itemView;
            view.setText(R.string.channels_item_dvr);
        } else {
            viewHolder.itemView.setTag(getItemList().get(position).getChannel());
            viewHolder.itemView.setOnClickListener(this::onChannelClicked);
        }
        super.onBindViewHolder(viewHolder, position);
    }

    @Override
    public void update() {
        if (getItemCount() == 0) {
            createItems();
        } else {
            updateItems();
        }
    }

    private void onGuideClicked(View unused) {
        mTracker.sendMenuClicked(R.string.channels_item_program_guide);
        getMainActivity().getOverlayManager().showProgramGuide();
    }

    /** LiveTV: adds the current channel to the favorites, or removes it. */
    private void onFavoriteClicked(View view) {
        Channel channel = getMainActivity().getCurrentChannel();
        if (channel == null) {
            return;
        }
        Favorites.setFavorite(
                mContext, channel.getId(), !Favorites.isFavorite(mContext, channel.getId()));
        bindFavoriteCard(view);
    }

    /** LiveTV: opens the channel search. */
    private void onSearchClicked(View unused) {
        getMainActivity()
                .getOverlayManager()
                .hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_DEFAULT);
        getMainActivity().showProgramGuideSearchFragment();
    }

    /** LiveTV: opens the multiview, which plays several IPTV channels at once. */
    private void onMultiviewClicked(View unused) {
        getMainActivity()
                .getOverlayManager()
                .hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_DEFAULT);
        getMainActivity().startActivitySafe(new Intent(mContext, MultiviewActivity.class));
    }

    /** LiveTV: the multiview plays only the channels of the IPTV lists. */
    private boolean needToShowMultiviewItem() {
        String inputId = IptvChannels.getInputId(mContext);
        for (Channel channel :
                TvSingletons.getSingletons(mContext)
                        .getChannelDataManager()
                        .getBrowsableChannelList()) {
            if (inputId.equals(channel.getInputId())) {
                return true;
            }
        }
        return false;
    }

    /** LiveTV: shows "Add to favorites" or "Remove from favorites" for the current channel. */
    private void bindFavoriteCard(View view) {
        Channel channel = getMainActivity().getCurrentChannel();
        boolean favorite = channel != null && Favorites.isFavorite(mContext, channel.getId());
        ((SimpleCardView) view)
                .setText(
                        favorite
                                ? R.string.livetv_favorites_remove
                                : R.string.livetv_favorites_add);
        ((ImageView) view.findViewById(R.id.livetv_favorite_icon))
                .setImageResource(
                        favorite
                                ? R.drawable.ic_livetv_favorite_remove
                                : R.drawable.ic_livetv_favorite_add);
    }

    private void onChannelDownClicked(View unused) {
        mChannelChanger.channelDown();
    }

    private void onChannelUpClicked(View unused) {
        mChannelChanger.channelUp();
    }

    private void onSetupClicked(View unused) {
        mTracker.sendMenuClicked(R.string.channels_item_setup);
        getMainActivity().getOverlayManager().showSetupFragment();
    }

    private void onDvrClicked(View unused) {
        mTracker.sendMenuClicked(R.string.channels_item_dvr);
        getMainActivity().getOverlayManager().showDvrManager();
    }

    private void onAppLinkClicked(View view) {
        mTracker.sendMenuClicked(R.string.channels_item_app_link);
        Intent intent = ((AppLinkCardView) view).getIntent();
        if (intent != null) {
            getMainActivity().startActivitySafe(intent);
        }
    }

    private void onChannelClicked(View view) {
        // Always send the label "Channels" because the channel ID or name or number might be
        // sensitive.
        mTracker.sendMenuClicked(R.string.menu_title_channels);
        getMainActivity().tuneToChannel((Channel) view.getTag());
        getMainActivity().hideOverlaysForTune();
    }

    private void createItems() {
        List<ChannelsRowItem> items = new ArrayList<>();
        items.add(ChannelsRowItem.GUIDE_ITEM);
        if (needToShowFavoriteItem()) {
            items.add(ChannelsRowItem.FAVORITE_ITEM);
        }
        items.add(ChannelsRowItem.SEARCH_ITEM);
        if (needToShowMultiviewItem()) {
            items.add(ChannelsRowItem.MULTIVIEW_ITEM);
        }
        if (mShowChannelUpDown) {
            items.add(ChannelsRowItem.UP_ITEM);
            items.add(ChannelsRowItem.DOWN_ITEM);
        }

        if (needToShowSetupItem()) {
            items.add(ChannelsRowItem.SETUP_ITEM);
        }
        if (needToShowDvrItem()) {
            items.add(ChannelsRowItem.DVR_ITEM);
        }
        if (needToShowAppLinkItem()) {
            ChannelsRowItem.APP_LINK_ITEM.setChannel(
                    new ChannelImpl.Builder(getMainActivity().getCurrentChannel()).build());
            items.add(ChannelsRowItem.APP_LINK_ITEM);
        }
        for (Channel channel : getRecentChannels()) {
            items.add(new ChannelsRowItem(channel, R.layout.menu_card_channel));
        }
        setItemList(items);
    }

    private void updateItems() {
        List<ChannelsRowItem> items = getItemList();
        // The current index of the item list to iterate. It starts from 1 because the first item
        // (GUIDE) is always visible and not updated.
        int currentIndex = 1;
        if (updateItem(needToShowFavoriteItem(), ChannelsRowItem.FAVORITE_ITEM, currentIndex)) {
            // The channel may have changed since the menu was last shown.
            notifyItemChanged(currentIndex);
            ++currentIndex;
        }
        if (updateItem(true, ChannelsRowItem.SEARCH_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(needToShowMultiviewItem(), ChannelsRowItem.MULTIVIEW_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(mShowChannelUpDown, ChannelsRowItem.UP_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(mShowChannelUpDown, ChannelsRowItem.DOWN_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(needToShowSetupItem(), ChannelsRowItem.SETUP_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(needToShowDvrItem(), ChannelsRowItem.DVR_ITEM, currentIndex)) {
            ++currentIndex;
        }
        if (updateItem(needToShowAppLinkItem(), ChannelsRowItem.APP_LINK_ITEM, currentIndex)) {
            if (!getMainActivity()
                    .getCurrentChannel()
                    .hasSameReadOnlyInfo(ChannelsRowItem.APP_LINK_ITEM.getChannel())) {
                ChannelsRowItem.APP_LINK_ITEM.setChannel(
                        new ChannelImpl.Builder(getMainActivity().getCurrentChannel()).build());
                notifyItemChanged(currentIndex);
            }
            ++currentIndex;
        }
        int numOldChannels = items.size() - currentIndex;
        if (numOldChannels > 0) {
            while (items.size() > currentIndex) {
                items.remove(items.size() - 1);
            }
            notifyItemRangeRemoved(currentIndex, numOldChannels);
        }
        for (Channel channel : getRecentChannels()) {
            items.add(new ChannelsRowItem(channel, R.layout.menu_card_channel));
        }
        int numNewChannels = items.size() - currentIndex;
        if (numNewChannels > 0) {
            notifyItemRangeInserted(currentIndex, numNewChannels);
        }
    }

    /** Returns {@code true} if the item should be shown. */
    private boolean updateItem(boolean needToShow, ChannelsRowItem item, int index) {
        List<ChannelsRowItem> items = getItemList();
        boolean isItemInList = index < items.size() && item.equals(items.get(index));
        if (needToShow && !isItemInList) {
            items.add(index, item);
            notifyItemInserted(index);
        } else if (!needToShow && isItemInList) {
            items.remove(index);
            notifyItemRemoved(index);
        }
        return needToShow;
    }

    /** LiveTV: the favorites item is for a channel being watched, not for a passthrough input. */
    private boolean needToShowFavoriteItem() {
        Channel channel = getMainActivity().getCurrentChannel();
        return channel != null && !channel.isPassthrough();
    }

    private boolean needToShowSetupItem() {
        TvSingletons singletons = TvSingletons.getSingletons(mContext);
        TvInputManagerHelper inputManager = singletons.getTvInputManagerHelper();
        return singletons.getSetupUtils().hasNewInput(inputManager);
    }

    private boolean needToShowDvrItem() {
        TvInputManagerHelper inputManager =
                TvSingletons.getSingletons(mContext).getTvInputManagerHelper();
        if (mDvrDataManager != null) {
            for (TvInputInfo info : inputManager.getTvInputInfos(true, true)) {
                if (info.canRecord()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean needToShowAppLinkItem() {
        TvInputManagerHelper inputManager =
                TvSingletons.getSingletons(mContext).getTvInputManagerHelper();
        Channel currentChannel = getMainActivity().getCurrentChannel();
        return currentChannel != null
                && currentChannel.getAppLinkType(mContext) != Channel.APP_LINK_TYPE_NONE
                // Sometimes applicationInfo can be null. b/28932537
                && inputManager.getTvInputAppInfo(currentChannel.getInputId()) != null;
    }

    private List<Channel> getRecentChannels() {
        List<Channel> channelList = new ArrayList<>();
        long currentChannelId = getMainActivity().getCurrentChannelId();
        ArrayDeque<Long> recentChannels = getMainActivity().getRecentChannels();
        // Add the last watched channel as the first one.
        for (long channelId : recentChannels) {
            if (addChannelToList(
                    channelList, mRecommender.getChannel(channelId), currentChannelId)) {
                break;
            }
        }
        // Add the recommended channels.
        for (Channel channel : mRecommender.recommendChannels(mMaxCount)) {
            if (channelList.size() >= mMaxCount) {
                break;
            }
            addChannelToList(channelList, channel, currentChannelId);
        }
        // If the number of recommended channels is not enough, add more from the recent channel
        // list.
        for (long channelId : recentChannels) {
            if (channelList.size() >= mMinCount) {
                break;
            }
            addChannelToList(channelList, mRecommender.getChannel(channelId), currentChannelId);
        }
        return channelList;
    }

    private static boolean addChannelToList(
            List<Channel> channelList, Channel channel, long currentChannelId) {
        if (channel == null
                || channel.getId() == currentChannelId
                || channelList.contains(channel)
                || !channel.isBrowsable()) {
            return false;
        }
        channelList.add(channel);
        return true;
    }

    @Override
    public void onAccessibilityStateChanged(boolean enabled) {
        mShowChannelUpDown = enabled;
        update();
    }

    @Override
    public void release() {
        mAccessibilityManager.removeAccessibilityStateChangeListener(this);
        super.release();
    }
}
