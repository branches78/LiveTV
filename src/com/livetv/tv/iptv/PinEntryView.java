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

package com.livetv.tv.iptv;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * LiveTV: a PIN of {@link IptvBackup#MIN_PIN_LENGTH} to {@link IptvBackup#MAX_PIN_LENGTH} digits,
 * one box per digit. The number keys of the remote type it directly; OK opens the numeric
 * keyboard, for remotes without them. The keyboard's delete key (or the remote's) erases the last
 * digit.
 */
public final class PinEntryView extends LinearLayout {
    /** Told every time the PIN changes. */
    public interface Listener {
        void onPinChanged(String pin);
    }

    private static final int COLOR_IDLE = 0x66FFFFFF;
    private static final int COLOR_FOCUSED = 0xFFFFFFFF;
    private static final int COLOR_FILL = 0x1AFFFFFF;

    private final StringBuilder mPin = new StringBuilder();
    private Listener mListener;

    public PinEntryView(Context context) {
        this(context, null);
    }

    public PinEntryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setOnClickListener(v -> showKeyboard());
        refresh();
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public String getPin() {
        return mPin.toString();
    }

    public void clear() {
        if (mPin.length() > 0) {
            mPin.setLength(0);
            onChanged();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        // Only the PIN being typed closes the keyboard: it belongs to the whole window.
        boolean typing = hasFocus();
        super.setEnabled(enabled);
        setFocusable(enabled);
        if (!enabled && typing) {
            hideKeyboard();
        }
        refresh();
    }

    private void append(char digit) {
        if (mPin.length() < IptvBackup.MAX_PIN_LENGTH) {
            mPin.append(digit);
            onChanged();
        }
    }

    private void deleteLast() {
        if (mPin.length() > 0) {
            mPin.setLength(mPin.length() - 1);
            onChanged();
        }
    }

    private void onChanged() {
        refresh();
        if (mListener != null) {
            mListener.onPinChanged(getPin());
        }
    }

    /** One box per digit typed and one for the next, at least the shortest PIN. */
    private void refresh() {
        int boxes =
                Math.max(
                        IptvBackup.MIN_PIN_LENGTH,
                        Math.min(IptvBackup.MAX_PIN_LENGTH, mPin.length() + 1));
        while (getChildCount() < boxes) {
            addView(createBox());
        }
        while (getChildCount() > boxes) {
            removeViewAt(getChildCount() - 1);
        }
        boolean active = hasFocus() && isEnabled();
        for (int i = 0; i < boxes; i++) {
            TextView box = (TextView) getChildAt(i);
            box.setText(i < mPin.length() ? "•" : "");
            GradientDrawable background = (GradientDrawable) box.getBackground();
            boolean highlighted = active && i <= mPin.length();
            background.setStroke(dp(highlighted ? 2 : 1), highlighted ? COLOR_FOCUSED : COLOR_IDLE);
        }
        setAlpha(isEnabled() ? 1f : 0.4f);
    }

    private TextView createBox() {
        TextView box = new TextView(getContext());
        LayoutParams params = new LayoutParams(dp(44), dp(52));
        params.setMarginEnd(dp(10));
        box.setLayoutParams(params);
        box.setGravity(Gravity.CENTER);
        box.setTextColor(COLOR_FOCUSED);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_FILL);
        background.setCornerRadius(dp(6));
        box.setBackground(background);
        return box;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            hideKeyboard();
        }
        refresh();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled()) {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                append((char) ('0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                deleteLast();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showKeyboard() {
        requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return isEnabled();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                typeDigits(text);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Numeric keyboards commit at once; a composing one is taken as typed.
                typeDigits(text);
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    deleteLast();
                }
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                // "Done": on to the next step.
                hideKeyboard();
                View next = focusSearch(FOCUS_DOWN);
                if (next != null) {
                    next.requestFocus();
                }
                return true;
            }
        };
    }

    private void typeDigits(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                append(c);
            }
        }
    }
}
