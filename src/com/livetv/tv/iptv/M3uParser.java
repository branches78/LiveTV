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
 */
final class M3uParser {
    private static final String EXTINF = "#EXTINF:";
    private static final String EXTM3U = "#EXTM3U";
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

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
        final List<Entry> entries;
        /** The guide addresses of the header (url-tvg, x-tvg-url), separated by spaces. */
        final String guideUrls;

        Result(List<Entry> entries, String guideUrls) {
            this.entries = entries;
            this.guideUrls = guideUrls;
        }
    }

    static Result parse(Reader input) throws IOException {
        List<Entry> entries = new ArrayList<>();
        StringBuilder guideUrls = new StringBuilder();
        BufferedReader reader = new BufferedReader(input);
        String name = null;
        String logo = null;
        String group = null;
        String guideId = null;
        String line;
        while ((line = reader.readLine()) != null) {
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
                    entries.add(
                            new Entry(name.isEmpty() ? line : name, line, logo, group, guideId));
                }
                name = null;
            }
        }
        return new Result(entries, guideUrls.toString());
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

    private static boolean isWebUrl(String url) {
        String lowerCase = url.toLowerCase(Locale.ROOT);
        return lowerCase.startsWith("http://") || lowerCase.startsWith("https://");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private M3uParser() {}
}
