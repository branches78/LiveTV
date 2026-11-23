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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.android.tv.R;
import com.android.tv.TvSingletons;
import com.android.tv.data.api.Channel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LiveTV: the screen that exports the IPTV setup to the Downloads folder (from the settings) or
 * imports it from such a file (from the channel sources, while no list is imported), in numbered
 * steps. Import: choose the file, type its PIN, import; then it goes back to the sources. Export:
 * choose a PIN, repeat it, export. See {@link IptvBackup}.
 */
public final class IptvBackupActivity extends Activity {
    private static final String TAG = "IptvBackupActivity";
    private static final String EXTRA_RESTORE = "com.livetv.tv.extra.RESTORE";
    private static final int REQUEST_CODE_PICK_FILE = 1;
    private static final int REQUEST_CODE_STORAGE = 2;

    private static final int STEP_PENDING = 0;
    private static final int STEP_CURRENT = 1;
    private static final int STEP_DONE = 2;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0x66FFFFFF;
    private static final int COLOR_DONE = 0xFF1D9E75;
    private static final int COLOR_ON_CURRENT = 0xFF202124;

    private boolean mRestore;
    private PinEntryView mPinView;
    private TextView mFileView;
    private Button mPickFileButton;
    private Button mActionButton;
    private Button mDoneButton;
    private ProgressBar mProgressView;
    private TextView mStatusView;
    private View mBusyView;
    private TextView mBusyTitleView;
    private TextView mBusyMessageView;
    private TextView mFileBadge;
    private TextView mFileTitle;
    private TextView mPinBadge;
    private TextView mPinTitle;
    private TextView mActionBadge;
    private TextView mActionTitle;
    private Uri mFileUri;
    private boolean mBusy;

    /** The screen that saves the backup, for the settings. */
    public static Intent buildExportIntent(Context context) {
        return new Intent(context, IptvBackupActivity.class);
    }

    /** The screen that imports a backup, for the channel sources. */
    public static Intent buildRestoreIntent(Context context) {
        return new Intent(context, IptvBackupActivity.class).putExtra(EXTRA_RESTORE, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_iptv_backup);
        // The keyboard only opens when the user presses OK on a PIN.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        mRestore = getIntent().getBooleanExtra(EXTRA_RESTORE, false);
        TextView titleView = findViewById(R.id.livetv_backup_title);
        TextView descriptionView = findViewById(R.id.livetv_backup_description);
        mPinView = findViewById(R.id.livetv_backup_pin);
        mFileView = findViewById(R.id.livetv_backup_file);
        mPickFileButton = findViewById(R.id.livetv_backup_pick_file);
        mActionButton = findViewById(R.id.livetv_backup_action);
        mDoneButton = findViewById(R.id.livetv_backup_done);
        mProgressView = findViewById(R.id.livetv_backup_progress);
        mStatusView = findViewById(R.id.livetv_backup_status);
        mBusyView = findViewById(R.id.livetv_backup_busy);
        mBusyTitleView = findViewById(R.id.livetv_backup_busy_title);
        mBusyMessageView = findViewById(R.id.livetv_backup_busy_message);
        mFileBadge = findViewById(R.id.livetv_backup_step_file_badge);
        mFileTitle = findViewById(R.id.livetv_backup_step_file_title);
        mPinBadge = findViewById(R.id.livetv_backup_step_pin_badge);
        mPinTitle = findViewById(R.id.livetv_backup_step_pin_title);
        mActionBadge = findViewById(R.id.livetv_backup_step_action_badge);
        mActionTitle = findViewById(R.id.livetv_backup_step_action_title);

        mPinView.setListener(this::onPinChanged);
        mActionButton.setOnClickListener(v -> onActionClicked());
        mDoneButton.setOnClickListener(v -> finish());

        if (mRestore) {
            setTitle(R.string.livetv_backup_restore_title);
            titleView.setText(R.string.livetv_backup_restore_title);
            descriptionView.setText(R.string.livetv_backup_restore_description);
            mPinTitle.setText(R.string.livetv_backup_step_enter_pin);
            mActionTitle.setText(R.string.livetv_backup_step_restore);
            mActionButton.setText(R.string.livetv_backup_restore);
            mPickFileButton.setOnClickListener(v -> pickFile());
            mPickFileButton.requestFocus();
        } else {
            setTitle(R.string.livetv_backup_export_title);
            titleView.setText(R.string.livetv_backup_export_title);
            descriptionView.setText(R.string.livetv_backup_export_description);
            mPinTitle.setText(R.string.livetv_backup_step_new_pin);
            mActionTitle.setText(R.string.livetv_backup_step_export);
            mActionButton.setText(R.string.livetv_backup_export);
            findViewById(R.id.livetv_backup_step_file).setVisibility(View.GONE);
        }
        updateSteps();
        if (!mRestore) {
            mPinView.requestFocus();
        }
    }

    private void onPinChanged(String pin) {
        updateSteps();
        // The longest PIN: on to the next step.
        if (pin.length() == IptvBackup.MAX_PIN_LENGTH && mActionButton.isEnabled()) {
            mActionButton.requestFocus();
        }
    }

    private boolean isPinDone() {
        return IptvBackup.isValidPin(mPinView.getPin());
    }

    /** Numbers the steps, marks the done ones and enables only what can be used. */
    private void updateSteps() {
        boolean ready;
        if (mRestore) {
            boolean fileDone = mFileUri != null;
            boolean pinDone = fileDone && isPinDone();
            ready = pinDone;
            setStep(mFileBadge, mFileTitle, 1, fileDone ? STEP_DONE : STEP_CURRENT);
            setStep(mPinBadge, mPinTitle, 2, pinDone ? STEP_DONE : fileDone ? STEP_CURRENT
                    : STEP_PENDING);
            setStep(mActionBadge, mActionTitle, 3, pinDone ? STEP_CURRENT : STEP_PENDING);
            mPickFileButton.setEnabled(!mBusy);
            mPinView.setEnabled(fileDone && !mBusy);
        } else {
            boolean pinDone = isPinDone();
            ready = pinDone;
            setStep(mPinBadge, mPinTitle, 1, pinDone ? STEP_DONE : STEP_CURRENT);
            setStep(mActionBadge, mActionTitle, 2, pinDone ? STEP_CURRENT : STEP_PENDING);
            mPinView.setEnabled(!mBusy);
        }
        mActionButton.setEnabled(ready && !mBusy);
    }

    private void setStep(TextView badge, TextView title, int number, int state) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int stroke = Math.round(getResources().getDisplayMetrics().density);
        switch (state) {
            case STEP_DONE:
                circle.setColor(COLOR_DONE);
                badge.setText("✓");
                badge.setTextColor(COLOR_WHITE);
                break;
            case STEP_CURRENT:
                circle.setColor(COLOR_WHITE);
                badge.setText(String.valueOf(number));
                badge.setTextColor(COLOR_ON_CURRENT);
                break;
            default:
                circle.setStroke(stroke, COLOR_DIM);
                badge.setText(String.valueOf(number));
                badge.setTextColor(COLOR_DIM);
                break;
        }
        badge.setBackground(circle);
        title.setTextColor(state == STEP_PENDING ? COLOR_DIM : COLOR_WHITE);
    }

    private void onActionClicked() {
        if (mRestore) {
            startRestore();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            // Before Android 10, writing in Downloads needs the storage permission.
            requestPermissions(
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_STORAGE);
        } else {
            startExport();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_STORAGE) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startExport();
        } else {
            mStatusView.setText(R.string.livetv_backup_no_storage_permission);
        }
    }

    private void startExport() {
        String pin = mPinView.getPin();
        // The hidden channels, as the TV shows them.
        Set<Long> hidden = new HashSet<>();
        String inputId = IptvChannels.getInputId(this);
        for (Channel channel :
                TvSingletons.getSingletons(this).getChannelDataManager().getChannelList()) {
            if (inputId.equals(channel.getInputId()) && !channel.isBrowsable()) {
                hidden.add(channel.getId());
            }
        }
        mDoneButton.setVisibility(View.GONE);
        setBusy(true);
        mStatusView.setText(R.string.livetv_backup_exporting);
        Context appContext = getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                IptvBackup.Result result =
                                        IptvBackup.export(appContext, pin, hidden);
                                runOnUiThread(() -> onExported(result));
                            } catch (Exception e) {
                                // Not logged with its message: it could hold a URL.
                                Log.w(TAG, "Export failed: " + e.getClass().getSimpleName());
                                runOnUiThread(() -> onFailed(e));
                            }
                        },
                        TAG)
                .start();
    }

    private void onExported(IptvBackup.Result result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        mStatusView.setText(
                getString(
                        R.string.livetv_backup_exported,
                        result.fileName,
                        getResources()
                                .getQuantityString(
                                        R.plurals.livetv_backup_lists, result.lists, result.lists),
                        getResources()
                                .getQuantityString(
                                        R.plurals.livetv_backup_channels,
                                        result.channels,
                                        result.channels)));
        // Saved: the user only has to leave.
        mDoneButton.setVisibility(View.VISIBLE);
        mDoneButton.requestFocus();
    }

    private void startRestore() {
        String pin = mPinView.getPin();
        Uri fileUri = mFileUri;
        setBusy(true);
        mStatusView.setText(R.string.livetv_backup_restoring);
        Context appContext = getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                IptvBackup.Result result =
                                        IptvBackup.restore(appContext, fileUri, pin);
                                runOnUiThread(() -> onRestored(result));
                            } catch (Exception e) {
                                Log.w(TAG, "Restore failed: " + e.getClass().getSimpleName());
                                runOnUiThread(() -> onFailed(e));
                            }
                        },
                        TAG)
                .start();
    }

    /** Back to the channel sources, which show what was imported. */
    private void onRestored(IptvBackup.Result result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Toast.makeText(
                        this,
                        getString(
                                R.string.livetv_backup_restored,
                                getResources()
                                        .getQuantityString(
                                                R.plurals.livetv_backup_lists,
                                                result.lists,
                                                result.lists),
                                getResources()
                                        .getQuantityString(
                                                R.plurals.livetv_backup_channels,
                                                result.channels,
                                                result.channels)),
                        Toast.LENGTH_LONG)
                .show();
        setResult(RESULT_OK);
        finish();
    }

    private void onFailed(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        if (e instanceof IptvBackup.WrongPinException) {
            mStatusView.setText(R.string.livetv_backup_wrong_pin);
            mPinView.clear();
            mPinView.requestFocus();
        } else if (e instanceof IptvBackup.NotBackupException
                || e instanceof org.json.JSONException) {
            mStatusView.setText(R.string.livetv_backup_not_backup);
        } else if (e instanceof IptvBackup.AlreadySetUpException) {
            mStatusView.setText(R.string.livetv_backup_already_set_up);
        } else {
            mStatusView.setText(
                    getString(R.string.livetv_backup_failed, e.getClass().getSimpleName()));
        }
        // The busy screen had the focus.
        if (getCurrentFocus() == null || !getCurrentFocus().isShown()) {
            (mRestore ? mPickFileButton : mPinView).requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mProgressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        // A screen over the steps, so the user sees that the import or export is working.
        if (busy) {
            mBusyTitleView.setText(
                    mRestore
                            ? R.string.livetv_backup_restoring_title
                            : R.string.livetv_backup_exporting_title);
            if (mRestore) {
                mBusyMessageView.setText(R.string.livetv_backup_restoring_message);
            }
            mBusyMessageView.setVisibility(mRestore ? View.VISIBLE : View.GONE);
            mBusyView.setVisibility(View.VISIBLE);
            mBusyView.requestFocus();
        } else {
            mBusyView.setVisibility(View.GONE);
        }
        updateSteps();
    }

    @Override
    public void onBackPressed() {
        // Leaving in the middle would hide the result of the import or export.
        if (mBusy) {
            return;
        }
        super.onBackPressed();
    }

    /** Opens a file manager, as the IPTV setup does for M3U files. */
    private void pickFile() {
        List<Intent> fileManagers = IptvSetupActivity.findFileManagers(getPackageManager());
        if (fileManagers.isEmpty()) {
            mStatusView.setText(R.string.livetv_iptv_no_file_manager);
        } else if (fileManagers.size() == 1) {
            startFileManager(fileManagers.get(0));
        } else {
            PackageManager pm = getPackageManager();
            CharSequence[] labels = new CharSequence[fileManagers.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] =
                        IptvSetupActivity.getAppLabel(
                                pm, fileManagers.get(i).getComponent().getPackageName());
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.livetv_backup_pick_file)
                    .setItems(labels, (dialog, which) -> startFileManager(fileManagers.get(which)))
                    .show();
        }
    }

    private void startFileManager(Intent intent) {
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start file manager " + intent.getComponent(), e);
            mStatusView.setText(R.string.livetv_iptv_no_file_manager);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_PICK_FILE
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        mFileUri = data.getData();
        mFileView.setText(getFileName(mFileUri));
        mStatusView.setText(null);
        updateSteps();
        // On to the PIN.
        mPinView.requestFocus();
    }

    private String getFileName(Uri fileUri) {
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                fileUri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the name of the file", e);
        }
        String name = fileUri.getLastPathSegment();
        return name != null ? name : "";
    }
}
