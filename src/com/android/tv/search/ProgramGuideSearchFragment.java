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

package com.android.tv.search;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.annotation.Nullable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.android.tv.MainActivity;
import com.android.tv.R;
import com.android.tv.data.api.Channel;
import com.android.tv.util.Utils;
import com.android.tv.util.images.ImageLoader.ImageLoaderCallback;
import com.livetv.tv.iptv.IptvChannels;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LiveTV: searches the shown channels by name or number. It starts listening when it opens: "canal
 * 2" (or "2") tunes to channel 2, and a name shows the matching channels, or tunes when only one
 * matches. The text field next to the microphone searches by typing instead; the results change
 * with every letter. OK on a result tunes to it and closes the search.
 *
 * <p>The speech recognizer runs inside this screen: opening the system's voice screen would pause
 * the TV, which closes the search.
 */
public class ProgramGuideSearchFragment extends Fragment {
    private static final int MAX_RESULTS = 200;
    private static final int SHOW_KEYBOARD_DELAY_MS = 200;
    /** Waits for the next letter before searching, so typing stays smooth. */
    private static final int SEARCH_DELAY_MS = 150;
    /** Asked by {@link #startVoiceSearch}; the answer reopens the search. */
    public static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 2;
    private static final String SHARED_PREF_SEARCH = "livetv_search";
    /** The microphone stays open at least this long. */
    private static final long VOICE_MINIMUM_LENGTH_MS = 5000;
    /** Silence that ends the sentence. */
    private static final long VOICE_SILENCE_MS = 3500;
    /**
     * Recognizers may ignore the two values above and give up early: until this long has passed
     * without hearing a word, listening starts again.
     */
    private static final long VOICE_WAIT_FOR_SPEECH_MS = 12000;
    /** Voice levels reported by the recognizer, in dB, for the smallest and largest circle. */
    private static final float VOICE_QUIET_DB = 0f;
    private static final float VOICE_LOUD_DB = 10f;
    private static final long VOICE_PULSE_PERIOD_MS = 1400;
    /** Set when the user refused the microphone: the search starts with the keyboard then. */
    private static final String PREF_KEY_VOICE_DENIED = "voice_denied";
    /** Words said before a channel number or name, such as "pon el canal". */
    private static final String[] VOICE_PREFIX_WORDS = {
        "pon", "poner", "ir", "ve", "al", "a", "el", "la", "canal", "channel", "numero", "number"
    };

    private MainActivity mMainActivity;
    private EditText mQueryView;
    private ImageView mVoiceView;
    private View mVoicePulseView;
    @Nullable private ValueAnimator mVoicePulseAnimator;
    /** How loud the voice is now, from 0 to 1, and the value shown, which follows it smoothly. */
    private float mVoiceTargetLevel;
    private float mVoiceLevel;
    @Nullable private SpeechRecognizer mSpeechRecognizer;
    private boolean mListening;
    /** What the recognizer understood so far, used if it ends without a final result. */
    @Nullable private String mLastPartialResult;
    private long mVoiceWaitEndMs;
    private final Runnable mListenAgainRunnable =
            () -> {
                if (mSpeechRecognizer != null) {
                    mSpeechRecognizer.cancel();
                    listen();
                }
            };
    private TextView mStatusView;
    private VerticalGridView mResultsView;
    private final ResultsAdapter mAdapter = new ResultsAdapter();
    private final Map<String, String> mInputLabels = new HashMap<>();
    /** LiveTV: the IPTV list of each IPTV channel, read in the background when the search opens. */
    private Map<Long, String> mListNames = new HashMap<>();
    /** The channels to search, with their names prepared once when the search opens. */
    private final List<SearchableChannel> mChannels = new ArrayList<>();
    private final Runnable mSearchRunnable = () -> search(mQueryView.getText().toString());
    private int mLogoWidth;
    private int mLogoHeight;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMainActivity = (MainActivity) getActivity();
        mLogoWidth = getResources().getDimensionPixelSize(R.dimen.livetv_search_logo_width);
        mLogoHeight = getResources().getDimensionPixelSize(R.dimen.livetv_search_logo_height);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.livetv_channel_search, container, false);
        mQueryView = view.findViewById(R.id.livetv_search_query);
        mVoiceView = view.findViewById(R.id.livetv_search_voice);
        mVoicePulseView = view.findViewById(R.id.livetv_search_voice_pulse);
        // Going to the text field to type stops listening, so a late voice result cannot replace
        // what is typed.
        mQueryView.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    if (hasFocus && mListening) {
                        stopVoiceSearch();
                    }
                });
        mVoiceView.setOnClickListener(
                v -> {
                    if (mListening) {
                        stopVoiceSearch();
                    } else {
                        startVoiceSearch();
                    }
                });
        mStatusView = view.findViewById(R.id.livetv_search_status);
        mResultsView = view.findViewById(R.id.livetv_search_results);
        mResultsView.setAdapter(mAdapter);
        // Up from the first result goes back to the text field to keep typing: the grid keeps the
        // focus at its edges otherwise.
        mResultsView.setOnKeyInterceptListener(
                event -> {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                            && mResultsView.getSelectedPosition() == 0) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            mQueryView.requestFocus();
                        }
                        return true;
                    }
                    return false;
                });
        mQueryView.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        mQueryView.removeCallbacks(mSearchRunnable);
                        mQueryView.postDelayed(mSearchRunnable, SEARCH_DELAY_MS);
                    }
                });
        // The keyboard's search key goes to the results.
        mQueryView.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                        hideKeyboard();
                        if (mAdapter.getItemCount() > 0) {
                            mResultsView.requestFocus();
                        }
                        return true;
                    }
                    return false;
                });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // A new search every time.
        mInputLabels.clear();
        new Thread(
                        () -> {
                            Map<Long, String> listNames =
                                    IptvChannels.getChannelListNames(mMainActivity);
                            mMainActivity.runOnUiThread(
                                    () -> {
                                        mListNames = listNames;
                                        mAdapter.notifyDataSetChanged();
                                    });
                        },
                        "LiveTvSearchLists")
                .start();
        mChannels.clear();
        for (Channel channel : mMainActivity.getChannelDataManager().getBrowsableChannelList()) {
            mChannels.add(new SearchableChannel(channel));
        }
        mQueryView.setText("");
        mQueryView.removeCallbacks(mSearchRunnable);
        search("");
        // Voice first; the keyboard when the microphone cannot be used.
        boolean voiceRefused =
                !hasRecordAudioPermission() && getPrefs().getBoolean(PREF_KEY_VOICE_DENIED, false);
        if (isVoiceAvailable() && !voiceRefused) {
            mVoiceView.setVisibility(View.VISIBLE);
            mVoiceView.requestFocus();
            startVoiceSearch();
        } else {
            mVoiceView.setVisibility(isVoiceAvailable() ? View.VISIBLE : View.GONE);
            mQueryView.requestFocus();
            mQueryView.postDelayed(this::showKeyboard, SHOW_KEYBOARD_DELAY_MS);
        }
    }

    @Override
    public void onPause() {
        mQueryView.removeCallbacks(mSearchRunnable);
        hideKeyboard();
        releaseSpeechRecognizer();
        super.onPause();
    }

    /**
     * Called by {@link MainActivity} with the answer to the microphone permission. The permission
     * dialog closed the search, so the search opens again: listening, or with the keyboard.
     */
    public static void onRecordAudioPermissionResult(MainActivity activity, boolean granted) {
        activity.getSharedPreferences(SHARED_PREF_SEARCH, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_VOICE_DENIED, !granted)
                .apply();
        activity.getWindow().getDecorView().post(activity::showProgramGuideSearchFragment);
    }

    private boolean isVoiceAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(mMainActivity);
    }

    private boolean hasRecordAudioPermission() {
        return mMainActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences getPrefs() {
        return mMainActivity.getSharedPreferences(SHARED_PREF_SEARCH, Context.MODE_PRIVATE);
    }

    private void startVoiceSearch() {
        if (!hasRecordAudioPermission()) {
            mMainActivity.requestPermissions(
                    new String[] {Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }
        hideKeyboard();
        if (mSpeechRecognizer == null) {
            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mMainActivity);
            mSpeechRecognizer.setRecognitionListener(mRecognitionListener);
        }
        mVoiceWaitEndMs = SystemClock.elapsedRealtime() + VOICE_WAIT_FOR_SPEECH_MS;
        mLastPartialResult = null;
        setListening(true);
        mStatusView.setText(R.string.livetv_search_listening);
        listen();
    }

    private void listen() {
        Intent intent =
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        // By default it stops at the first short pause, before "canal ... dos"
                        // is said: wait longer for the end of the sentence.
                        .putExtra(
                                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                                VOICE_MINIMUM_LENGTH_MS)
                        .putExtra(
                                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                                VOICE_SILENCE_MS)
                        .putExtra(
                                RecognizerIntent
                                        .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                                VOICE_SILENCE_MS);
        mSpeechRecognizer.startListening(intent);
    }

    /** Listens again if no word has been heard yet and there is still time to wait for one. */
    private boolean listenAgainIfNothingHeard() {
        if (mSpeechRecognizer == null
                || mLastPartialResult != null
                || SystemClock.elapsedRealtime() >= mVoiceWaitEndMs) {
            return false;
        }
        // Not from inside the callback of the recognizer, which has not finished yet.
        mVoiceView.post(mListenAgainRunnable);
        return true;
    }

    private void stopVoiceSearch() {
        mVoiceView.removeCallbacks(mListenAgainRunnable);
        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.cancel();
        }
        setListening(false);
        // The status of what the text field shows.
        search(mQueryView.getText().toString());
    }

    private void releaseSpeechRecognizer() {
        mVoiceView.removeCallbacks(mListenAgainRunnable);
        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.destroy();
            mSpeechRecognizer = null;
        }
        setListening(false);
    }

    private void setListening(boolean listening) {
        mListening = listening;
        mVoiceView.setActivated(listening);
        if (listening) {
            startVoicePulse();
        } else {
            stopVoicePulse();
        }
    }

    /**
     * The circle behind the microphone breathes while listening and grows with the voice. The
     * breathing shows it is listening also with recognizers that do not report the voice level.
     */
    private void startVoicePulse() {
        if (mVoicePulseAnimator != null) {
            return;
        }
        mVoiceTargetLevel = 0;
        mVoiceLevel = 0;
        mVoicePulseView.setVisibility(View.VISIBLE);
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(VOICE_PULSE_PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(
                animation -> {
                    float breath =
                            (1 - (float) Math.cos(2 * Math.PI * animation.getAnimatedFraction()))
                                    / 2;
                    mVoiceTargetLevel *= 0.96f;
                    mVoiceLevel += (mVoiceTargetLevel - mVoiceLevel) * 0.3f;
                    float scale = 1.1f + 0.1f * breath + 0.6f * mVoiceLevel;
                    mVoicePulseView.setScaleX(scale);
                    mVoicePulseView.setScaleY(scale);
                });
        animator.start();
        mVoicePulseAnimator = animator;
    }

    private void stopVoicePulse() {
        if (mVoicePulseAnimator != null) {
            mVoicePulseAnimator.cancel();
            mVoicePulseAnimator = null;
        }
        mVoicePulseView.setVisibility(View.GONE);
    }

    private final RecognitionListener mRecognitionListener =
            new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {}

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {
                    float level = (rmsdB - VOICE_QUIET_DB) / (VOICE_LOUD_DB - VOICE_QUIET_DB);
                    mVoiceTargetLevel =
                            Math.max(mVoiceTargetLevel, Math.max(0f, Math.min(1f, level)));
                }

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    boolean nothingUnderstood =
                            error == SpeechRecognizer.ERROR_NO_MATCH
                                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    if (nothingUnderstood && listenAgainIfNothingHeard()) {
                        return;
                    }
                    setListening(false);
                    // It sometimes ends with "no match" after understanding part of the words.
                    if (nothingUnderstood && mLastPartialResult != null) {
                        onVoiceResult(mLastPartialResult);
                        return;
                    }
                    mStatusView.setText(
                            nothingUnderstood
                                    ? R.string.livetv_search_voice_not_understood
                                    : R.string.livetv_search_voice_unavailable);
                }

                @Override
                public void onResults(Bundle results) {
                    List<String> texts =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts == null || texts.isEmpty() || texts.get(0).trim().isEmpty()) {
                        if (listenAgainIfNothingHeard()) {
                            return;
                        }
                        setListening(false);
                        mStatusView.setText(R.string.livetv_search_voice_not_understood);
                        return;
                    }
                    setListening(false);
                    onVoiceResult(texts.get(0));
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    List<String> texts =
                            partialResults.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts != null && !texts.isEmpty() && !texts.get(0).trim().isEmpty()) {
                        mLastPartialResult = texts.get(0);
                        // New words were heard, also if the recognizer reports no voice level.
                        mVoiceTargetLevel = 1f;
                        // The text field shows the words as they are said; its results follow.
                        mQueryView.setText(texts.get(0));
                        mQueryView.setSelection(mQueryView.length());
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            };

    /**
     * "canal 2", "canal dos" or "2" tunes to that channel. A name shows the matching channels, or
     * tunes when only one matches.
     */
    private void onVoiceResult(String text) {
        String query = stripVoicePrefix(text);
        Integer number = parseNumber(query);
        if (number != null) {
            String displayNumber = String.valueOf(number);
            for (SearchableChannel searchable : mChannels) {
                if (displayNumber.equals(searchable.channel.getDisplayNumber())) {
                    onResultClicked(searchable.channel);
                    return;
                }
            }
            showQuery(displayNumber);
            mStatusView.setText(getString(R.string.livetv_search_channel_not_found, displayNumber));
            return;
        }
        List<Result> results = showQuery(query);
        if (results.size() == 1) {
            onResultClicked(results.get(0).channel);
        } else if (!results.isEmpty()) {
            mResultsView.setSelectedPosition(0);
            mResultsView.requestFocus();
        }
    }

    /** Shows {@code query} in the text field and its results right away. */
    private List<Result> showQuery(String query) {
        mQueryView.setText(query);
        mQueryView.setSelection(query.length());
        mQueryView.removeCallbacks(mSearchRunnable);
        return search(query);
    }

    /** Removes words like "pon el canal" said before the channel. */
    private static String stripVoicePrefix(String text) {
        String[] words = text.trim().split("\\s+");
        int first = 0;
        while (first < words.length - 1 && isVoicePrefixWord(normalize(words[first]))) {
            first++;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = first; i < words.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(words[i]);
        }
        return builder.toString();
    }

    private static boolean isVoicePrefixWord(String word) {
        for (String prefixWord : VOICE_PREFIX_WORDS) {
            if (prefixWord.equals(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the number in {@code text}, written with digits ("23") or with Spanish words
     * ("veintitrés", "ciento dos"), or {@code null} if the text is not only a number.
     */
    @Nullable
    private static Integer parseNumber(String text) {
        // The recognizer may add punctuation, as in "2.".
        String normalized = normalize(text).replaceAll("[.,;:!?¿¡]", "").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.matches("\\d{1,5}")) {
            return Integer.parseInt(normalized);
        }
        int total = 0;
        int current = 0;
        boolean found = false;
        for (String word : normalized.split("[\\s-]+")) {
            if (word.equals("y")) {
                continue;
            }
            if (word.equals("mil")) {
                total += (current == 0 ? 1 : current) * 1000;
                current = 0;
                found = true;
                continue;
            }
            Integer value = SPANISH_NUMBERS.get(word);
            if (value == null) {
                return null;
            }
            current += value;
            found = true;
        }
        return found ? total + current : null;
    }

    private static final Map<String, Integer> SPANISH_NUMBERS = new HashMap<>();

    static {
        String[] units = {
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
            "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres",
            "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve"
        };
        for (int i = 0; i < units.length; i++) {
            SPANISH_NUMBERS.put(units[i], i);
        }
        SPANISH_NUMBERS.put("un", 1);
        SPANISH_NUMBERS.put("una", 1);
        SPANISH_NUMBERS.put("veintiun", 21);
        String[] tens = {"treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta",
            "noventa"};
        for (int i = 0; i < tens.length; i++) {
            SPANISH_NUMBERS.put(tens[i], (i + 3) * 10);
        }
        SPANISH_NUMBERS.put("cien", 100);
        SPANISH_NUMBERS.put("ciento", 100);
        String[] hundreds = {"doscientos", "trescientos", "cuatrocientos", "quinientos",
            "seiscientos", "setecientos", "ochocientos", "novecientos"};
        for (int i = 0; i < hundreds.length; i++) {
            SPANISH_NUMBERS.put(hundreds[i], (i + 2) * 100);
        }
    }

    private void showKeyboard() {
        if (isResumed() && mQueryView.hasFocus()) {
            getInputMethodManager().showSoftInput(mQueryView, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        getInputMethodManager().hideSoftInputFromWindow(mQueryView.getWindowToken(), 0);
    }

    private InputMethodManager getInputMethodManager() {
        return mMainActivity.getSystemService(InputMethodManager.class);
    }

    private List<Result> search(String query) {
        String normalizedQuery = normalize(query).trim();
        List<Result> results = new ArrayList<>();
        if (!normalizedQuery.isEmpty()) {
            // Exact numbers first, then names starting with the text, then the other matches.
            List<Result> numberMatches = new ArrayList<>();
            List<Result> prefixMatches = new ArrayList<>();
            List<Result> otherMatches = new ArrayList<>();
            for (SearchableChannel searchable : mChannels) {
                Channel channel = searchable.channel;
                NormalizedText normalizedName = searchable.name;
                int index = normalizedName.text.indexOf(normalizedQuery);
                if (normalizedQuery.equals(channel.getDisplayNumber())) {
                    numberMatches.add(new Result(channel, normalizedName, index, normalizedQuery));
                } else if (index == 0) {
                    prefixMatches.add(new Result(channel, normalizedName, index, normalizedQuery));
                } else if (index > 0) {
                    otherMatches.add(new Result(channel, normalizedName, index, normalizedQuery));
                }
            }
            results.addAll(numberMatches);
            results.addAll(prefixMatches);
            results.addAll(otherMatches);
            if (results.size() > MAX_RESULTS) {
                results = new ArrayList<>(results.subList(0, MAX_RESULTS));
            }
        }
        mAdapter.setResults(results);
        if (mListening) {
            mStatusView.setText(R.string.livetv_search_listening);
        } else if (normalizedQuery.isEmpty()) {
            mStatusView.setText(R.string.livetv_search_empty_query);
        } else if (results.isEmpty()) {
            mStatusView.setText(R.string.livetv_search_no_results);
        } else {
            mStatusView.setText(
                    getResources()
                            .getQuantityString(
                                    R.plurals.livetv_search_results,
                                    results.size(),
                                    results.size()));
        }
        return results;
    }

    private void onResultClicked(Channel channel) {
        hideKeyboard();
        mMainActivity.getFragmentManager().popBackStack();
        mMainActivity.tuneToChannel(channel);
        mMainActivity.hideOverlaysForTune();
    }

    private String getInputLabel(String inputId) {
        String label = mInputLabels.get(inputId);
        if (label == null) {
            TvInputInfo info = mMainActivity.getTvInputManagerHelper().getTvInputInfo(inputId);
            label = info == null ? "" : Utils.loadLabel(mMainActivity, info);
            mInputLabels.put(inputId, label);
        }
        return label;
    }

    /**
     * The broadcast system of a tuner channel ("DVB-T2", "ATSC-T", "ISDB-TB", "PAL"…), or null
     * for channels without one, such as IPTV or HDMI.
     */
    @Nullable
    private static String getBroadcastSystem(Channel channel) {
        String type = channel.getType();
        if (type == null
                || !type.startsWith("TYPE_")
                || TvContract.Channels.TYPE_OTHER.equals(type)
                || TvContract.Channels.TYPE_PREVIEW.equals(type)) {
            return null;
        }
        // TvContract types are "TYPE_DVB_T2", "TYPE_ATSC3_T"…
        return type.substring("TYPE_".length()).replace('_', '-');
    }

    /** Lower case and without accents, so "noticias" finds "Notícias". */
    private static String normalize(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            appendNormalized(builder, text.charAt(i));
        }
        return builder.toString();
    }

    /** Appends {@code c} in lower case and without accents; most chars need no Normalizer. */
    private static void appendNormalized(StringBuilder builder, char c) {
        if (c < 0x80) {
            builder.append(Character.toLowerCase(c));
            return;
        }
        String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        for (int i = 0; i < decomposed.length(); i++) {
            char d = decomposed.charAt(i);
            if (Character.getType(d) != Character.NON_SPACING_MARK) {
                builder.append(String.valueOf(d).toLowerCase(Locale.ROOT));
            }
        }
    }

    /** A channel with its name prepared for the search. */
    private static final class SearchableChannel {
        final Channel channel;
        final NormalizedText name;

        SearchableChannel(Channel channel) {
            this.channel = channel;
            name =
                    new NormalizedText(
                            channel.getDisplayName() == null ? "" : channel.getDisplayName());
        }
    }

    /** A text without accents and in lower case, with the position of each char in the original. */
    private static final class NormalizedText {
        final String text;
        /** For each char of {@link #text}, its index in the original text. */
        final int[] originalIndexes;

        NormalizedText(String original) {
            StringBuilder builder = new StringBuilder();
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < original.length(); i++) {
                int before = builder.length();
                appendNormalized(builder, original.charAt(i));
                for (int j = before; j < builder.length(); j++) {
                    indexes.add(i);
                }
            }
            text = builder.toString();
            originalIndexes = new int[indexes.size()];
            for (int i = 0; i < originalIndexes.length; i++) {
                originalIndexes[i] = indexes.get(i);
            }
        }
    }

    private static final class Result {
        final Channel channel;
        /** The name with the matching text in bold. */
        final CharSequence name;

        Result(Channel channel, NormalizedText normalizedName, int index, String query) {
            this.channel = channel;
            String original = channel.getDisplayName() == null ? "" : channel.getDisplayName();
            if (index < 0) {
                name = original;
                return;
            }
            int start = normalizedName.originalIndexes[index];
            int end = normalizedName.originalIndexes[index + query.length() - 1] + 1;
            SpannableString span = new SpannableString(original);
            span.setSpan(
                    new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            name = span;
        }
    }

    private final class ResultsAdapter extends RecyclerView.Adapter<ResultViewHolder> {
        private List<Result> mResults = new ArrayList<>();

        void setResults(List<Result> results) {
            mResults = results;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mResults.size();
        }

        @Override
        public ResultViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ResultViewHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.livetv_channel_search_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ResultViewHolder holder, int position) {
            holder.bind(mResults.get(position));
        }
    }

    private final class ResultViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mLogoView;
        private final TextView mNumberView;
        private final TextView mNameView;
        private final TextView mSourceView;
        @Nullable private Channel mChannel;

        ResultViewHolder(View itemView) {
            super(itemView);
            mLogoView = itemView.findViewById(R.id.livetv_search_item_logo);
            mNumberView = itemView.findViewById(R.id.livetv_search_item_number);
            mNameView = itemView.findViewById(R.id.livetv_search_item_name);
            mSourceView = itemView.findViewById(R.id.livetv_search_item_source);
            itemView.setOnClickListener(
                    v -> {
                        if (mChannel != null) {
                            onResultClicked(mChannel);
                        }
                    });
        }

        void bind(Result result) {
            Channel channel = result.channel;
            mChannel = channel;
            mNumberView.setText(channel.getDisplayNumber());
            mNameView.setText(result.name);
            // The source and where in it the channel comes from: "IPTV lists · Colombia
            // IPTV.m3u" or, for a USB or network tuner, "TV tuner · DVB-T2".
            String subSource = mListNames.get(channel.getId());
            if (subSource == null) {
                subSource = getBroadcastSystem(channel);
            }
            String source = getInputLabel(channel.getInputId());
            mSourceView.setText(
                    subSource == null ? source : source + "  \u00B7  " + subSource);
            mLogoView.setImageDrawable(null);
            channel.loadBitmap(
                    mMainActivity,
                    Channel.LOAD_IMAGE_TYPE_CHANNEL_LOGO,
                    mLogoWidth,
                    mLogoHeight,
                    new ImageLoaderCallback<ResultViewHolder>(this) {
                        @Override
                        public void onBitmapLoaded(
                                ResultViewHolder holder, @Nullable Bitmap logo) {
                            // The view may show another channel by now.
                            if (channel.equals(holder.mChannel)) {
                                holder.mLogoView.setImageBitmap(logo);
                            }
                        }
                    });
        }
    }
}
