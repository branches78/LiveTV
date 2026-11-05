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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.android.tv.R;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Setup screen of {@link IptvTvInputService}: imports M3U lists from a URL or from a file picked
 * with a file manager, and shows the imported lists so each one can be deleted. The screen stays
 * open after an import; Back closes it.
 */
public class IptvSetupActivity extends Activity {
    private static final String TAG = "IptvSetupActivity";
    /** Public list of free-to-air Spanish language channels, see https://github.com/iptv-org/iptv. */
    static final String DEFAULT_PLAYLIST_URL =
            "https://iptv-org.github.io/iptv/languages/spa.m3u";
    private static final int REQUEST_CODE_PICK_FILE = 1;
    /**
     * Apps that answer file requests but cannot browse files on Android TV: the photo picker and
     * the "not supported" stubs of the TV framework.
     */
    private static final String[] IGNORED_FILE_PICKER_PACKAGES = {
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.tv.frameworkpackagestubs",
    };

    private EditText mUrlView;
    private Button mImportButton;
    private Button mOpenFileButton;
    private Button mLoginButton;
    private Button mDefaultButton;
    private ProgressBar mProgressView;
    private TextView mStatusView;
    private ViewGroup mListsView;
    private final List<IptvChannels.Playlist> mPlaylists = new ArrayList<>();
    /** Lists whose guide is being read. */
    private final Set<Long> mGuideUpdating = new HashSet<>();
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.livetv_iptv_setup);
        // The URL gets the focus, but the keyboard only opens when the user clicks it.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        mUrlView = findViewById(R.id.livetv_iptv_url);
        mImportButton = findViewById(R.id.livetv_iptv_import);
        mOpenFileButton = findViewById(R.id.livetv_iptv_open_file);
        mLoginButton = findViewById(R.id.livetv_iptv_login);
        mDefaultButton = findViewById(R.id.livetv_iptv_use_default);
        mProgressView = findViewById(R.id.livetv_iptv_progress);
        mStatusView = findViewById(R.id.livetv_iptv_status);
        mListsView = findViewById(R.id.livetv_iptv_lists);

        mUrlView.addTextChangedListener(new AfterTextChangedWatcher(this::updateImportButton));
        mImportButton.setOnClickListener(v -> startImport(mUrlView.getText().toString().trim()));
        mOpenFileButton.setOnClickListener(v -> pickFile());
        mLoginButton.setOnClickListener(v -> showLoginDialog());
        mDefaultButton.setOnClickListener(v -> startImport(DEFAULT_PLAYLIST_URL));
        updateImportButton();
        mUrlView.requestFocus();
        loadPlaylists(-1);
    }

    /** "Import channels" can only be pressed with a valid URL. */
    private void updateImportButton() {
        mImportButton.setEnabled(!mBusy && isValidUrl(mUrlView.getText().toString().trim()));
    }

    private static boolean isValidUrl(String url) {
        if (url.isEmpty() || url.contains(" ")) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && !TextUtils.isEmpty(uri.getHost());
    }

    /**
     * Asks for the server, username and password of a provider that needs them (the Xtream Codes
     * API that most IPTV providers use).
     */
    private void showLoginDialog() {
        View view = getLayoutInflater().inflate(R.layout.livetv_iptv_login_dialog, null);
        EditText serverView = view.findViewById(R.id.livetv_iptv_login_server);
        EditText usernameView = view.findViewById(R.id.livetv_iptv_login_username);
        EditText passwordView = view.findViewById(R.id.livetv_iptv_login_password);
        CheckBox showPasswordView = view.findViewById(R.id.livetv_iptv_login_show_password);
        showPasswordView.setOnCheckedChangeListener(
                (button, checked) -> {
                    passwordView.setInputType(
                            InputType.TYPE_CLASS_TEXT
                                    | (checked
                                            ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                            : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    passwordView.setSelection(passwordView.length());
                });
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(R.string.livetv_iptv_login_title)
                        .setView(view)
                        .setPositiveButton(
                                R.string.livetv_iptv_import,
                                (d, which) ->
                                        startLoginImport(
                                                serverView.getText().toString().trim(),
                                                usernameView.getText().toString().trim(),
                                                passwordView.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        dialog.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        // Like "Import channels" on this screen, the dialog's one needs every field.
        Button importButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable updateImportButton =
                () ->
                        importButton.setEnabled(
                                isValidServer(serverView.getText().toString().trim())
                                        && !TextUtils.isEmpty(
                                                usernameView.getText().toString().trim())
                                        && passwordView.length() > 0);
        AfterTextChangedWatcher watcher = new AfterTextChangedWatcher(updateImportButton);
        serverView.addTextChangedListener(watcher);
        usernameView.addTextChangedListener(watcher);
        passwordView.addTextChangedListener(watcher);
        updateImportButton.run();
        serverView.requestFocus();
    }

    /** A server may be given without "http://". */
    private static boolean isValidServer(String server) {
        return isValidUrl(server.contains("://") ? server : "http://" + server);
    }

    private void startLoginImport(String server, String username, String password) {
        mStatusView.setText(R.string.livetv_iptv_importing);
        runImport(
                () -> IptvChannels.importPlaylistWithLogin(this, server, username, password),
                true);
    }

    /**
     * Reads the imported lists in the background and shows them.
     *
     * @param focusIndex the list whose delete button gets the focus (the last one if there are
     *     fewer), or -1 to keep the focus.
     */
    private void loadPlaylists(int focusIndex) {
        new Thread(
                        () -> {
                            List<IptvChannels.Playlist> playlists;
                            try {
                                playlists = IptvChannels.getPlaylists(this);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "Cannot read the lists", e);
                                playlists = new ArrayList<>();
                            }
                            final List<IptvChannels.Playlist> result = playlists;
                            runOnUiThread(() -> showPlaylists(result, focusIndex));
                        },
                        TAG)
                .start();
    }

    private void showPlaylists(List<IptvChannels.Playlist> playlists, int focusIndex) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mPlaylists.clear();
        mPlaylists.addAll(playlists);
        mListsView.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < playlists.size(); i++) {
            IptvChannels.Playlist playlist = playlists.get(i);
            View item = inflater.inflate(R.layout.livetv_iptv_list_item, mListsView, false);
            String title = getPlaylistTitle(i);
            ((TextView) item.findViewById(R.id.livetv_iptv_list_title)).setText(title);
            String description =
                    getResources()
                            .getQuantityString(
                                    R.plurals.livetv_iptv_list_channels,
                                    playlist.channelCount,
                                    playlist.channelCount);
            description += "  \u00B7  " + getGuideStatus(playlist);
            if (!TextUtils.isEmpty(playlist.label)) {
                // A lock marks the lists imported with a username and a password.
                description +=
                        "  \u00B7  "
                                + (playlist.hasLogin() ? "\uD83D\uDD12 " : "")
                                + playlist.label;
            }
            ((TextView) item.findViewById(R.id.livetv_iptv_list_description))
                    .setText(description);
            Button guideButton = item.findViewById(R.id.livetv_iptv_list_guide);
            guideButton.setEnabled(!mBusy);
            guideButton.setOnClickListener(v -> showGuideDialog(playlist, title));
            Button deleteButton = item.findViewById(R.id.livetv_iptv_list_delete);
            deleteButton.setEnabled(!mBusy);
            deleteButton.setOnClickListener(v -> confirmDeletePlaylist(playlist, title));
            mListsView.addView(item);
        }
        if (focusIndex >= 0) {
            if (mPlaylists.isEmpty()) {
                mUrlView.requestFocus();
            } else {
                int index = Math.min(focusIndex, mPlaylists.size() - 1);
                mListsView
                        .getChildAt(index)
                        .findViewById(R.id.livetv_iptv_list_delete)
                        .requestFocus();
            }
        }
    }

    private String getGuideStatus(IptvChannels.Playlist playlist) {
        if (playlist.guideUrls.trim().isEmpty()) {
            return getString(R.string.livetv_iptv_guide_none);
        } else if (mGuideUpdating.contains(playlist.id)) {
            return getString(R.string.livetv_iptv_guide_updating);
        } else if (playlist.guideChannelCount == IptvChannels.Playlist.GUIDE_FAILED) {
            return getString(R.string.livetv_iptv_guide_failed);
        } else if (playlist.guideChannelCount == IptvChannels.Playlist.GUIDE_NOT_READ) {
            return getString(R.string.livetv_iptv_guide_pending);
        }
        return getResources()
                .getQuantityString(
                        R.plurals.livetv_iptv_guide_channels,
                        playlist.guideChannelCount,
                        playlist.guideChannelCount);
    }

    /**
     * Asks for the XMLTV guide of a list. The password of a list with a username is shown masked;
     * leaving the text as it is keeps the guide.
     */
    private void showGuideDialog(IptvChannels.Playlist playlist, String title) {
        View view = getLayoutInflater().inflate(R.layout.livetv_iptv_guide_dialog, null);
        EditText urlsView = view.findViewById(R.id.livetv_iptv_guide_urls);
        String shownUrls = maskPasswords(playlist.guideUrls);
        urlsView.setText(shownUrls);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.livetv_iptv_guide_title, title))
                        .setView(view)
                        .setPositiveButton(
                                R.string.livetv_iptv_guide_save,
                                (d, which) -> {
                                    String typed = normalizeUrls(urlsView.getText().toString());
                                    if (!typed.equals(normalizeUrls(shownUrls))) {
                                        saveGuideUrls(
                                                playlist.id, unmaskPasswords(typed, playlist));
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        // Which guide channel gives its programs to each channel.
                        .setNeutralButton(
                                R.string.livetv_assign_button,
                                (d, which) ->
                                        IptvGuideAssignActivity.start(this, playlist.id, title))
                        .create();
        dialog.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        // Every address must be a web address (or none, to remove the guide).
        Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable updateSaveButton =
                () -> {
                    boolean valid = true;
                    String urls = normalizeUrls(urlsView.getText().toString());
                    for (String url : urls.split(" ")) {
                        valid &= url.isEmpty() || isValidUrl(url);
                    }
                    saveButton.setEnabled(valid);
                };
        urlsView.addTextChangedListener(new AfterTextChangedWatcher(updateSaveButton));
        updateSaveButton.run();
    }

    private static String normalizeUrls(String urls) {
        return urls.trim().replaceAll("[\\s,]+", " ");
    }

    /** Hides the value of "password=" in the addresses. */
    private static String maskPasswords(String urls) {
        return urls.replaceAll("(?i)(password=)[^&\\s]*", "$1\u2022\u2022\u2022\u2022");
    }

    /** Puts back the hidden passwords of the addresses the user kept. */
    private static String unmaskPasswords(String urls, IptvChannels.Playlist playlist) {
        StringBuilder result = new StringBuilder();
        for (String url : urls.split(" ")) {
            String original = url;
            for (String saved : playlist.guideUrls.trim().split("\\s+")) {
                if (!saved.isEmpty() && maskPasswords(saved).equals(url)) {
                    original = saved;
                    break;
                }
            }
            result.append(result.length() > 0 ? " " : "").append(original);
        }
        return result.toString();
    }

    private void saveGuideUrls(long playlistId, String urls) {
        mGuideUpdating.add(playlistId);
        new Thread(
                        () -> {
                            IptvChannels.updatePlaylist(
                                    this, playlistId, p -> p.withGuideUrls(urls));
                            runOnUiThread(
                                    () -> {
                                        loadPlaylists(-1);
                                        updateGuide(playlistId);
                                    });
                        },
                        TAG)
                .start();
    }

    /** Reads the guide of a list in the background, showing it as updating meanwhile. */
    private void updateGuide(long playlistId) {
        mGuideUpdating.add(playlistId);
        IptvGuide.update(
                this,
                playlistId,
                () -> {
                    mGuideUpdating.remove(playlistId);
                    if (!isFinishing() && !isDestroyed()) {
                        loadPlaylists(-1);
                    }
                });
    }

    private String getPlaylistTitle(int index) {
        return getString(R.string.livetv_iptv_list_title, index + 1);
    }

    /** Asks before deleting a list, since it cannot be undone. */
    private void confirmDeletePlaylist(IptvChannels.Playlist playlist, String title) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.livetv_iptv_list_delete_title)
                .setMessage(
                        getResources()
                                .getQuantityString(
                                        R.plurals.livetv_iptv_list_delete_confirm,
                                        playlist.channelCount,
                                        title,
                                        playlist.channelCount))
                .setPositiveButton(
                        R.string.livetv_iptv_list_delete,
                        (dialog, which) -> deletePlaylist(playlist))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deletePlaylist(IptvChannels.Playlist playlist) {
        int index = mPlaylists.indexOf(playlist);
        setBusy(true);
        new Thread(
                        () -> {
                            int deleted;
                            try {
                                deleted = IptvChannels.deletePlaylist(this, playlist.id);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "Deleting the list failed", e);
                                deleted = -1;
                            }
                            final int result = deleted;
                            runOnUiThread(() -> onPlaylistDeleted(result, index));
                        },
                        TAG)
                .start();
    }

    /** @param deleted number of deleted channels, or -1 if it failed. */
    private void onPlaylistDeleted(int deleted, int index) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        mStatusView.setText(
                deleted < 0
                        ? getString(R.string.livetv_iptv_delete_failed)
                        : getResources()
                                .getQuantityString(
                                        R.plurals.livetv_iptv_deleted, deleted, deleted));
        setResult(RESULT_OK);
        loadPlaylists(Math.max(index, 0));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri fileUri = data.getData();
            mStatusView.setText(R.string.livetv_iptv_reading_file);
            runImport(() -> IptvChannels.importPlaylistFile(this, fileUri), false);
        }
    }

    /**
     * Opens a file manager that can browse the internal storage.
     *
     * <p>The system chooser is not used: on Android TV it hands GET_CONTENT to the photo picker,
     * which bounces it back in an endless loop, and the TV framework stubs only show "not
     * supported". Real file managers are listed instead and started explicitly.
     */
    private void pickFile() {
        List<Intent> fileManagers = findFileManagers();
        if (fileManagers.isEmpty()) {
            mStatusView.setText(R.string.livetv_iptv_no_file_manager);
        } else if (fileManagers.size() == 1) {
            startFileManager(fileManagers.get(0));
        } else {
            PackageManager pm = getPackageManager();
            CharSequence[] labels = new CharSequence[fileManagers.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = getAppLabel(pm, fileManagers.get(i).getComponent().getPackageName());
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.livetv_iptv_open_file)
                    .setItems(labels, (dialog, which) -> startFileManager(fileManagers.get(which)))
                    .show();
        }
    }

    /** Returns one explicit intent per file manager app, preferring GET_CONTENT. */
    private List<Intent> findFileManagers() {
        PackageManager pm = getPackageManager();
        Map<String, Intent> byPackage = new LinkedHashMap<>();
        for (String action : new String[] {Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT}) {
            Intent query =
                    new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String packageName = info.activityInfo.packageName;
                if (isIgnoredFilePicker(packageName) || byPackage.containsKey(packageName)) {
                    continue;
                }
                byPackage.put(
                        packageName,
                        new Intent(query)
                                .setComponent(
                                        new ComponentName(packageName, info.activityInfo.name)));
            }
        }
        return new ArrayList<>(byPackage.values());
    }

    private static boolean isIgnoredFilePicker(String packageName) {
        for (String ignored : IGNORED_FILE_PICKER_PACKAGES) {
            if (ignored.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void startFileManager(Intent intent) {
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start file manager " + intent.getComponent(), e);
            mStatusView.setText(R.string.livetv_iptv_no_file_manager);
        }
    }

    private static CharSequence getAppLabel(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void startImport(String url) {
        mStatusView.setText(R.string.livetv_iptv_importing);
        runImport(() -> IptvChannels.importPlaylist(this, url), false);
    }

    /**
     * Runs {@code task} in the background.
     *
     * @param withLogin whether the list was asked with a username and password.
     */
    private void runImport(Callable<IptvChannels.ImportResult> task, boolean withLogin) {
        setBusy(true);
        new Thread(
                        () -> {
                            try {
                                IptvChannels.ImportResult result = task.call();
                                runOnUiThread(() -> onImported(result, withLogin));
                            } catch (Exception e) {
                                Log.w(TAG, "Import failed", e);
                                runOnUiThread(() -> onImportFailed(e));
                            }
                        },
                        TAG)
                .start();
    }

    private void onImported(IptvChannels.ImportResult result, boolean withLogin) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        if (result.imported == 0) {
            if (result.total > 0) {
                mStatusView.setText(
                        getString(R.string.livetv_iptv_limit_reached, IptvChannels.MAX_CHANNELS));
            } else {
                // Some providers answer a wrong password with an empty list.
                mStatusView.setText(
                        withLogin ? R.string.livetv_iptv_login_empty : R.string.livetv_iptv_empty);
            }
            loadPlaylists(-1);
            return;
        }
        mStatusView.setText(
                getString(R.string.livetv_iptv_imported, result.imported, result.total));
        // Stay on this screen, ready for another list. "Import channels" turns disabled with the
        // URL gone, so its focus goes to the URL.
        View focus = getCurrentFocus();
        if (focus == null || focus == mImportButton || !focus.isEnabled()) {
            mUrlView.requestFocus();
        }
        mUrlView.setText("");
        setResult(RESULT_OK);
        loadPlaylists(-1);
        // The new channels have no programs yet.
        updateGuide(result.playlistId);
    }

    private void onImportFailed(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        if (e instanceof IptvChannels.HttpStatusException
                && (((IptvChannels.HttpStatusException) e).code == 401
                        || ((IptvChannels.HttpStatusException) e).code == 403)) {
            mStatusView.setText(R.string.livetv_iptv_login_failed);
        } else if (e instanceof IptvChannels.TooLargeException) {
            mStatusView.setText(R.string.livetv_iptv_too_large);
        } else {
            String message = e.getMessage();
            mStatusView.setText(
                    getString(
                            R.string.livetv_iptv_failed,
                            message != null ? message : e.getClass().getSimpleName()));
        }
        View focus = getCurrentFocus();
        if (focus == null || !focus.isEnabled()) {
            mUrlView.requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mProgressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        mUrlView.setEnabled(!busy);
        updateImportButton();
        mOpenFileButton.setEnabled(!busy);
        mLoginButton.setEnabled(!busy);
        mDefaultButton.setEnabled(!busy);
        for (int i = 0; i < mListsView.getChildCount(); i++) {
            mListsView.getChildAt(i).findViewById(R.id.livetv_iptv_list_delete).setEnabled(!busy);
            mListsView.getChildAt(i).findViewById(R.id.livetv_iptv_list_guide).setEnabled(!busy);
        }
    }

    /** Runs an action every time the text changes. */
    private static final class AfterTextChangedWatcher implements TextWatcher {
        private final Runnable mAction;

        AfterTextChangedWatcher(Runnable action) {
            mAction = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            mAction.run();
        }
    }
}
