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

package com.livetv.tv.vod;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import com.livetv.tv.iptv.IptvChannels;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The movies and series of the IPTV lists imported with a username and a password, read from the
 * Xtream API of their providers ({@code player_api.php}).
 */
public final class VodLibrary {
    private static final String TAG = "VodLibrary";

    /** Movies. */
    public static final int KIND_MOVIES = 0;
    /** Series; their episodes are read with {@link #loadEpisodes}. */
    public static final int KIND_SERIES = 1;

    /** Key of the category that holds every title. */
    public static final String CATEGORY_ALL = "";
    /** Key of the category with the titles left before their end. */
    public static final String CATEGORY_CONTINUE = "continue";

    private static final int TIMEOUT_MS = 20000;
    /** Largest answer accepted, so a huge catalog cannot run the app out of memory. */
    private static final int MAX_ANSWER_BYTES = 64 * 1024 * 1024;
    /** How long a catalog read is reused before it is read again. */
    private static final long CACHE_MS = 15 * 60 * 1000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** A category of the side panel. */
    public static final class Category {
        /** {@link #CATEGORY_ALL}, or the list and the category of the provider. */
        public final String key;
        public final String name;

        Category(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /** A movie, a series or an episode. */
    public static final class Title {
        /**
         * Identifies a movie or an episode for {@link VodProgress}: the list, the kind and the ID
         * of the provider. Empty for a series.
         */
        public final String key;
        public final String name;
        /** Address of the poster, or of the picture of an episode; empty if none. */
        public final String imageUrl;
        /** What a movie or an episode plays; empty for a series. */
        public final String streamUrl;
        /** The {@link Category#key} of a movie or a series. */
        final String categoryKey;
        /** The series of the provider; empty for a movie or an episode. */
        final String seriesId;
        /** The list the title comes from. */
        final Source source;
        /** The season of an episode, or 0. */
        public final int season;

        Title(
                String key,
                String name,
                String imageUrl,
                String streamUrl,
                String categoryKey,
                String seriesId,
                Source source,
                int season) {
            this.key = key;
            this.name = name;
            this.imageUrl = imageUrl;
            this.streamUrl = streamUrl;
            this.categoryKey = categoryKey;
            this.seriesId = seriesId;
            this.source = source;
            this.season = season;
        }

        public boolean isSeries() {
            return !seriesId.isEmpty();
        }

        /** A title of "Continue watching". */
        static Title fromProgress(VodProgress.Entry entry) {
            return new Title(
                    entry.key,
                    entry.name,
                    entry.imageUrl,
                    entry.streamUrl,
                    CATEGORY_CONTINUE,
                    "",
                    null,
                    0);
        }
    }

    /** The titles of "Continue watching" of {@code kind}, the most recent first. */
    public static List<Title> getContinueTitles(Context context, int kind) {
        List<Title> titles = new ArrayList<>();
        for (VodProgress.Entry entry : VodProgress.getEntries(context, kind)) {
            titles.add(Title.fromProgress(entry));
        }
        return titles;
    }

    /**
     * Category names written in capitals by the provider ("CIENCIA FICCION") become "Ciencia
     * ficcion", like the genres of the guide. Names with lowercase letters are kept.
     */
    static String fixCapitals(String name) {
        Locale locale = Locale.getDefault();
        String lower = name.toLowerCase(locale);
        if (!name.equals(name.toUpperCase(locale)) || name.equals(lower)) {
            return name;
        }
        int first = lower.offsetByCodePoints(0, 1);
        return lower.substring(0, first).toUpperCase(locale) + lower.substring(first);
    }

    /** The movies or the series of every list. */
    public static final class Catalog {
        /** "All" first, then the categories that have titles, in the order of the providers. */
        public final List<Category> categories;
        public final List<Title> titles;
        /** Lists that could not be read. */
        public final int failedSources;
        final long readAtMs;
        /** The lists read ({@link #sourcesKey}): the catalog is read again when they change. */
        String sourcesKey = "";

        Catalog(List<Category> categories, List<Title> titles, int failedSources) {
            this.categories = categories;
            this.titles = titles;
            this.failedSources = failedSources;
            readAtMs = SystemClock.elapsedRealtime();
        }

        /** Returns the titles of the category {@code key}. */
        public List<Title> getTitles(String key) {
            if (CATEGORY_ALL.equals(key)) {
                return titles;
            }
            List<Title> result = new ArrayList<>();
            for (Title title : titles) {
                if (title.categoryKey.equals(key)) {
                    result.add(title);
                }
            }
            return result;
        }
    }

    /** The episodes of a series, by season. */
    public static final class Episodes {
        /** Season numbers, in order. */
        public final List<Integer> seasons;
        public final List<Title> episodes;

        Episodes(List<Integer> seasons, List<Title> episodes) {
            this.seasons = seasons;
            this.episodes = episodes;
        }

        public List<Title> getEpisodes(int season) {
            List<Title> result = new ArrayList<>();
            for (Title episode : episodes) {
                if (episode.season == season) {
                    result.add(episode);
                }
            }
            return result;
        }
    }

    /** Receives a result on the main thread; {@code result} is null if nothing could be read. */
    public interface Callback<T> {
        void onLoaded(@Nullable T result);
    }

    /** The Xtream API of one list. */
    static final class Source {
        final long playlistId;
        final String apiUrl;
        /** The server, such as {@code http://example.com:8080}. */
        final String base;
        final String username;
        final String password;

        Source(long playlistId, String apiUrl) {
            this.playlistId = playlistId;
            this.apiUrl = apiUrl;
            Uri uri = Uri.parse(apiUrl);
            // An address without "?" (from an edited backup) would throw here, on the main thread;
            // its reads just fail.
            int query = apiUrl.indexOf('?');
            String url = query >= 0 ? apiUrl.substring(0, query) : apiUrl;
            int slash = url.lastIndexOf('/');
            base = slash >= 0 ? url.substring(0, slash) : url;
            username = TextUtils.isEmpty(uri.getQueryParameter("username"))
                    ? "" : uri.getQueryParameter("username");
            password = TextUtils.isEmpty(uri.getQueryParameter("password"))
                    ? "" : uri.getQueryParameter("password");
        }

        /** The address of a movie ({@code type} "movie") or an episode ("series"). */
        String streamUrl(String type, String id, String extension) {
            return base
                    + "/"
                    + type
                    + "/"
                    + Uri.encode(username)
                    + "/"
                    + Uri.encode(password)
                    + "/"
                    + id
                    + "."
                    + (TextUtils.isEmpty(extension) ? "mp4" : extension);
        }
    }

    private static final Catalog[] sCatalogs = new Catalog[2];

    private VodLibrary() {}

    /** Whether some list has movies and series. */
    public static boolean hasSources(Context context) {
        return !IptvChannels.getVodSources(context).isEmpty();
    }

    /**
     * Reads the movies or the series. A recent read is reused, unless the lists changed since
     * (a list imported, updated or deleted).
     */
    public static void loadCatalog(Context context, int kind, Callback<Catalog> callback) {
        // The application: the screen that asked may close during the read.
        Context appContext = context.getApplicationContext();
        List<Source> sources = new ArrayList<>();
        for (IptvChannels.VodSource source : IptvChannels.getVodSources(appContext)) {
            sources.add(new Source(source.playlistId, source.apiUrl));
        }
        String sourcesKey = sourcesKey(sources);
        Catalog cached = sCatalogs[kind];
        if (cached != null
                && cached.sourcesKey.equals(sourcesKey)
                && SystemClock.elapsedRealtime() - cached.readAtMs < CACHE_MS) {
            callback.onLoaded(cached);
            return;
        }
        EXECUTOR.execute(
                () -> {
                    Catalog catalog = readCatalog(appContext, kind, sources);
                    if (catalog != null) {
                        catalog.sourcesKey = sourcesKey;
                    }
                    MAIN_HANDLER.post(
                            () -> {
                                if (catalog != null) {
                                    sCatalogs[kind] = catalog;
                                }
                                callback.onLoaded(catalog);
                            });
                });
    }

    /** Identifies the lists and their accounts (a new password changes the addresses). */
    private static String sourcesKey(List<Source> sources) {
        StringBuilder key = new StringBuilder();
        for (Source source : sources) {
            key.append(source.playlistId).append('\n').append(source.apiUrl).append('\n');
        }
        return key.toString();
    }

    /** Reads the seasons and episodes of {@code series}. */
    public static void loadEpisodes(Title series, Callback<Episodes> callback) {
        EXECUTOR.execute(
                () -> {
                    Episodes episodes = null;
                    try {
                        episodes = readEpisodes(series);
                    } catch (IOException | JSONException e) {
                        Log.w(TAG, "Cannot read the episodes: " + e.getClass().getSimpleName());
                    }
                    Episodes result = episodes;
                    MAIN_HANDLER.post(() -> callback.onLoaded(result));
                });
    }

    @Nullable
    private static Catalog readCatalog(Context context, int kind, List<Source> sources) {
        boolean movies = kind == KIND_MOVIES;
        List<Category> categories = new ArrayList<>();
        categories.add(new Category(CATEGORY_ALL, context.getString(
                movies ? com.android.tv.R.string.livetv_vod_all_movies
                        : com.android.tv.R.string.livetv_vod_all_series)));
        List<Title> titles = new ArrayList<>();
        int failed = 0;
        for (Source source : sources) {
            try {
                // The category names, in the order of the provider.
                Map<String, String> names = new LinkedHashMap<>();
                for (Map<String, String> category :
                        readItems(
                                source,
                                movies ? "get_vod_categories" : "get_series_categories",
                                "category_id",
                                "category_name")) {
                    names.put(
                            value(category, "category_id"),
                            fixCapitals(value(category, "category_name").trim()));
                }
                List<Map<String, String>> items =
                        movies
                                ? readItems(
                                        source,
                                        "get_vod_streams",
                                        "category_id",
                                        "name",
                                        "stream_id",
                                        "stream_icon",
                                        "container_extension")
                                : readItems(
                                        source,
                                        "get_series",
                                        "category_id",
                                        "name",
                                        "cover",
                                        "series_id");
                Map<String, Boolean> used = new LinkedHashMap<>();
                for (Map<String, String> item : items) {
                    String categoryId = value(item, "category_id");
                    String key = source.playlistId + ":" + categoryId;
                    used.put(categoryId, true);
                    String name = value(item, "name").trim();
                    if (movies) {
                        titles.add(
                                new Title(
                                        source.playlistId + "/movie/" + value(item, "stream_id"),
                                        name,
                                        value(item, "stream_icon"),
                                        source.streamUrl(
                                                "movie",
                                                value(item, "stream_id"),
                                                value(item, "container_extension")),
                                        key,
                                        "",
                                        source,
                                        0));
                    } else {
                        titles.add(
                                new Title(
                                        "",
                                        name,
                                        value(item, "cover"),
                                        "",
                                        key,
                                        value(item, "series_id"),
                                        source,
                                        0));
                    }
                }
                for (Map.Entry<String, String> entry : names.entrySet()) {
                    // A category named like the section ("PELICULAS" among the movies) repeats
                    // "All movies": hidden, its titles are still in "All movies".
                    if (used.containsKey(entry.getKey())
                            && !isSectionName(context, entry.getValue())) {
                        categories.add(
                                new Category(
                                        source.playlistId + ":" + entry.getKey(),
                                        entry.getValue()));
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Cannot read a list: " + e.getClass().getSimpleName());
                failed++;
            }
        }
        if (failed > 0 && failed == sources.size()) {
            return null;
        }
        return new Catalog(categories, titles, failed);
    }

    /** Words a provider uses for a whole section, in the languages of LiveTV and in English. */
    private static final String[] SECTION_NAMES = {
        "peliculas", "pelicula", "movies", "movie", "films", "film", "series", "serie", "tv series",
        "vod", "all", "todas", "todos", "todas las peliculas", "todas las series"
    };

    /** Whether {@code name} only names the movies or the series as a whole. */
    private static boolean isSectionName(Context context, String name) {
        String normalized = normalize(name);
        for (String section : SECTION_NAMES) {
            if (normalized.equals(section)) {
                return true;
            }
        }
        for (int resId :
                new int[] {
                    com.android.tv.R.string.livetv_vod_movies,
                    com.android.tv.R.string.livetv_vod_series,
                    com.android.tv.R.string.livetv_vod_all_movies,
                    com.android.tv.R.string.livetv_vod_all_series
                }) {
            if (normalized.equals(normalize(context.getString(resId)))) {
                return true;
            }
        }
        return false;
    }

    /** Lowercase, without accents nor extra spaces, to compare names. */
    static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Episodes readEpisodes(Title series) throws IOException, JSONException {
        Source source = series.source;
        JSONObject info =
                new JSONObject(read(source.apiUrl + "&action=get_series_info&series_id="
                        + Uri.encode(series.seriesId)));
        List<Integer> seasons = new ArrayList<>();
        List<Title> episodes = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        Object episodesObject = info.opt("episodes");
        List<JSONArray> seasonArrays = new ArrayList<>();
        if (episodesObject instanceof JSONObject) {
            JSONObject bySeason = (JSONObject) episodesObject;
            for (Iterator<String> keys = bySeason.keys(); keys.hasNext(); ) {
                JSONArray array = bySeason.optJSONArray(keys.next());
                if (array != null) {
                    seasonArrays.add(array);
                }
            }
        } else if (episodesObject instanceof JSONArray) {
            JSONArray array = (JSONArray) episodesObject;
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONArray(i) != null) {
                    seasonArrays.add(array.optJSONArray(i));
                }
            }
        }
        for (JSONArray array : seasonArrays) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject episode = array.optJSONObject(i);
                if (episode == null) {
                    continue;
                }
                int season = episode.optInt("season", 1);
                JSONObject episodeInfo = episode.optJSONObject("info");
                String image = episodeInfo != null ? episodeInfo.optString("movie_image") : "";
                if (TextUtils.isEmpty(image)) {
                    image = series.imageUrl;
                }
                episodes.add(
                        new Title(
                                source.playlistId + "/episode/" + episode.optString("id"),
                                episode.optString("title").trim(),
                                image,
                                source.streamUrl(
                                        "series",
                                        episode.optString("id"),
                                        episode.optString("container_extension")),
                                series.categoryKey,
                                "",
                                source,
                                season));
                numbers.add(episode.optInt("episode_num", i + 1));
                if (!seasons.contains(season)) {
                    seasons.add(season);
                }
            }
        }
        Collections.sort(seasons);
        // Episodes in the order of their season, then of their number.
        List<Integer> order = new ArrayList<>(episodes.size());
        for (int i = 0; i < episodes.size(); i++) {
            order.add(i);
        }
        Collections.sort(
                order,
                (a, b) -> {
                    int bySeason = Integer.compare(episodes.get(a).season, episodes.get(b).season);
                    return bySeason != 0
                            ? bySeason
                            : Integer.compare(numbers.get(a), numbers.get(b));
                });
        List<Title> sorted = new ArrayList<>(episodes.size());
        for (int index : order) {
            sorted.add(episodes.get(index));
        }
        return new Episodes(seasons, sorted);
    }

    /**
     * Reads the array an action of the API answers, keeping only the {@code keys} of each item.
     *
     * <p>LiveTV: read as it arrives. A big catalog (tens of thousands of titles, tens of MB) was
     * kept whole in memory twice, as bytes and as text, before being parsed: enough to run the
     * app out of memory.
     */
    private static List<Map<String, String>> readItems(Source source, String action, String... keys)
            throws IOException {
        List<Map<String, String>> items = new ArrayList<>();
        HttpURLConnection connection =
                IptvChannels.open(source.apiUrl + "&action=" + action, TIMEOUT_MS);
        try (JsonReader reader =
                new JsonReader(
                        new InputStreamReader(
                                new IptvChannels.LimitedInputStream(
                                        connection.getInputStream(), MAX_ANSWER_BYTES),
                                StandardCharsets.UTF_8))) {
            reader.setLenient(true);
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                // Some servers answer an empty object instead of an empty array.
                return items;
            }
            reader.beginArray();
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue();
                    continue;
                }
                Map<String, String> item = new HashMap<>(keys.length * 2);
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    JsonToken token = reader.peek();
                    if (isOneOf(name, keys)
                            && (token == JsonToken.STRING || token == JsonToken.NUMBER)) {
                        item.put(name, reader.nextString());
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                items.add(item);
            }
            reader.endArray();
        } catch (IllegalStateException | NumberFormatException e) {
            // Not the expected JSON.
            throw new IOException("Unexpected answer", e);
        } finally {
            connection.disconnect();
        }
        return items;
    }

    private static boolean isOneOf(String name, String[] keys) {
        for (String key : keys) {
            if (key.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The value of {@code key}, or an empty text. */
    private static String value(Map<String, String> item, String key) {
        String value = item.get(key);
        return value != null ? value : "";
    }

    private static String read(String url) throws IOException {
        HttpURLConnection connection = IptvChannels.open(url, TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_ANSWER_BYTES) {
                    throw new IOException("Answer too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
