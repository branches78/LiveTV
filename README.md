# LiveTV

LiveTV is an Android TV app for watching live TV, including IPTV lists. It is based on the
Android Open Source Project's [Live TV](https://android.googlesource.com/platform/packages/apps/TV)
reference app and installs as a regular app (`com.livetv.tv`), not as a system app.

This is an independent project, not affiliated with or endorsed by Google. LiveTV does not include
any channels: you add your own sources.

## Features

- **IPTV lists:** add several lists from a URL, an M3U file, or a username and password.
- **Program guide:** XMLTV guides per list (`.xml` or `.xml.gz`), detected automatically or set by
  hand, with manual channel assignment.
- **Channel order:** favorites, and manual reordering of the full channel list.
- **Search:** find channels by text or voice.
- **Match frame rate:** adjusts the display to the content.

## Requirements

- A device running Android TV 6.0 (API 23) or later.
- To build: JDK 21 and the Android SDK (API 36), with `sdk.dir` set in `local.properties`.

## Build

```bash
./gradlew assembleDebug
```

The APK is also copied to an `APK Builds` folder next to the project folder. Install it with:

```bash
adb install -r <path-to-apk>
```

The `release` build type is currently signed with the debug key, so it is not ready for
distribution.

## License

Licensed under the [Apache License 2.0](LICENSE). This project modifies the original AOSP code;
third-party licenses are listed in `res/raw/third_party_licenses`.
