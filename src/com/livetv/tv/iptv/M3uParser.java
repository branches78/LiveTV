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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses extended M3U playlists ({@code #EXTM3U} / {@code #EXTINF}). Only web addresses (http and
 * https) are kept for streams and logos: a list must not make the player read files of the device.
 * Movies and episodes of series are left out: only live channels are imported.
 */
final class M3uParser {
    private static final String EXTINF = "#EXTINF:";
    private static final String EXTM3U = "#EXTM3U";
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    /** What is kept of a line, so that a list without line breaks cannot fill the memory. */
    private static final int MAX_LINE_CHARS = 64 * 1024;

    /** One channel of the playlist. */
    static final class Entry {
        final String name;
        final String url;
        final String logoUrl;
        final String group;
        /** The channel's id in the XMLTV guide (tvg-id), or null. */
        final String guideId;

        Entry(String name, String url, String logoUrl, String group, String guideId) {
            this.name = name;
            this.url = url;
            this.logoUrl = logoUrl;
            this.group = group;
            this.guideId = guideId;
        }
    }

    /** The channels of a playlist and the XMLTV guide it names. */
    static final class Result {
        /** The first channels of the list, as many as were asked for. */
        final List<Entry> entries;
        /** How many channels the whole list has. */
        final int total;
        /** How many movies and episodes the list has: they are not channels and are left out. */
        final int videos;
        /** The guide addresses of the header (url-tvg, x-tvg-url), separated by spaces. */
        final String guideUrls;

        Result(List<Entry> entries, int total, int videos, String guideUrls) {
            this.entries = entries;
            this.total = total;
            this.videos = videos;
            this.guideUrls = guideUrls;
        }
    }

    /**
     * @param maxEntries how many channels to keep. The rest are only counted: a list can be much
     *     larger than the memory of the app.
     */
    static Result parse(Reader input, int maxEntries) throws IOException {
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        int videos = 0;
        StringBuilder guideUrls = new StringBuilder();
        BufferedReader reader = new BufferedReader(input);
        StringBuilder lineBuffer = new StringBuilder();
        String name = null;
        String logo = null;
        String group = null;
        String guideId = null;
        String line;
        while ((line = readLine(reader, lineBuffer)) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(EXTM3U)) {
                Matcher matcher = ATTRIBUTE.matcher(line);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if ("url-tvg".equals(key) || "x-tvg-url".equals(key)) {
                        // Several guides can be listed, separated by commas.
                        for (String url : matcher.group(2).split("[,\\s]+")) {
                            if (isWebUrl(url) && guideUrls.indexOf(url) < 0) {
                                guideUrls.append(guideUrls.length() > 0 ? " " : "").append(url);
                            }
                        }
                    }
                }
            } else if (line.startsWith(EXTINF)) {
                String header = line.substring(EXTINF.length());
                int comma = findNameSeparator(header);
                name = comma >= 0 ? header.substring(comma + 1).trim() : "";
                String attributes = comma >= 0 ? header.substring(0, comma) : header;
                logo = null;
                group = null;
                guideId = null;
                Matcher matcher = ATTRIBUTE.matcher(attributes);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if ("tvg-logo".equals(key)) {
                        logo = isWebUrl(matcher.group(2)) ? matcher.group(2) : null;
                    } else if ("tvg-id".equals(key)) {
                        guideId = emptyToNull(matcher.group(2));
                    } else if ("group-title".equals(key)) {
                        group = emptyToNull(matcher.group(2));
                    } else if ("tvg-name".equals(key) && name.isEmpty()) {
                        name = matcher.group(2);
                    }
                }
            } else if (!line.startsWith("#")) {
                if (name != null && isWebUrl(line)) {
                    if (isVideoFile(line)) {
                        videos++;
                        name = null;
                        continue;
                    }
                    total++;
                    if (entries.size() < maxEntries) {
                        entries.add(
                                new Entry(
                                        name.isEmpty() ? line : name, line, logo, group, guideId));
                    }
                }
                name = null;
            }
        }
        return new Result(entries, total, videos, guideUrls.toString());
    }

    /** Returns the next line, cut to {@link #MAX_LINE_CHARS}, or null at the end of the list. */
    private static String readLine(Reader reader, StringBuilder buffer) throws IOException {
        buffer.setLength(0);
        int c = reader.read();
        if (c == -1) {
            return null;
        }
        while (c != -1 && c != '\n' && c != '\r') {
            if (buffer.length() < MAX_LINE_CHARS) {
                buffer.append((char) c);
            }
            c = reader.read();
        }
        return buffer.toString();
    }

    /** Returns the index of the first comma outside quotes, which precedes the channel name. */
    private static int findNameSeparator(String header) {
        boolean quoted = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static final Pattern VIDEO_FILE =
            Pattern.compile(
                    "\\.(mp4|mkv|avi|mov|wmv|flv|webm|mpg|mpeg|m4v|divx|vob|3gp|rmvb)$");

    /**
     * Whether an address is a movie or an episode of a series (video on demand) instead of a live
     * channel: a video file, or the "/movie/" and "/series/" paths of the Xtream servers. Some
     * servers hide the address and put the extension after it ("/play/TOKEN#.mkv").
     */
    static boolean isVideoFile(String url) {
        String lowerCase = url.toLowerCase(Locale.ROOT);
        int hash = lowerCase.indexOf('#');
        String fragment = hash >= 0 ? lowerCase.substring(hash + 1) : "";
        String path = hash >= 0 ? lowerCase.substring(0, hash) : lowerCase;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path.contains("/movie/")
                || path.contains("/series/")
                || VIDEO_FILE.matcher(path).find()
                || VIDEO_FILE.matcher(fragment).find();
    }

    private static boolean isWebUrl(String url) {
        String lowerCase = url.toLowerCase(Locale.ROOT);
        return lowerCase.startsWith("http://") || lowerCase.startsWith("https://");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private M3uParser() {}
}
