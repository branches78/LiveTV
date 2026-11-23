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

package com.livetv.tv.multiview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays out the visible tiles of the multiview in a grid: 2 side by side, 4 in two rows of two, 6
 * in two rows of three. Every tile is 16:9, as large as fits, and the grid is centered.
 */
public final class MultiviewGrid extends ViewGroup {
    private static final float ASPECT_RATIO = 16f / 9f;

    private final int mGap;

    public MultiviewGrid(Context context) {
        this(context, null);
    }

    public MultiviewGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGap = Math.round(16 * getResources().getDisplayMetrics().density);
    }

    /** Columns for that many tiles. */
    static int getColumns(int count) {
        return count <= 4 ? 2 : 3;
    }

    /** Rows for that many tiles. */
    static int getRows(int count) {
        return count <= 2 ? 1 : 2;
    }

    private int getVisibleCount() {
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                count++;
            }
        }
        return count;
    }

    /** {width, height} of each tile within the given size. */
    private int[] getTileSize(int width, int height, int count) {
        int columns = getColumns(count);
        int rows = getRows(count);
        int tileWidth = (width - mGap * (columns - 1)) / columns;
        int tileHeight = (height - mGap * (rows - 1)) / rows;
        if (tileWidth > tileHeight * ASPECT_RATIO) {
            tileWidth = Math.round(tileHeight * ASPECT_RATIO);
        } else {
            tileHeight = Math.round(tileWidth / ASPECT_RATIO);
        }
        return new int[] {Math.max(0, tileWidth), Math.max(0, tileHeight)};
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int[] tile = getTileSize(width, height, Math.max(1, getVisibleCount()));
        int childWidth = MeasureSpec.makeMeasureSpec(tile[0], MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(tile[1], MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(childWidth, childHeight);
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int count = getVisibleCount();
        if (count == 0) {
            return;
        }
        int columns = getColumns(count);
        int rows = getRows(count);
        int[] tile = getTileSize(right - left, bottom - top, count);
        int gridWidth = tile[0] * columns + mGap * (columns - 1);
        int gridHeight = tile[1] * rows + mGap * (rows - 1);
        int startX = (right - left - gridWidth) / 2;
        int startY = (bottom - top - gridHeight) / 2;
        int index = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int x = startX + (index % columns) * (tile[0] + mGap);
            int y = startY + (index / columns) * (tile[1] + mGap);
            child.layout(x, y, x + tile[0], y + tile[1]);
            index++;
        }
    }
}
