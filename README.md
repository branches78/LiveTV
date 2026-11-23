# LiveTV

LiveTV is an Android TV app for watching live TV, including IPTV lists, with movies and series,
time shifting, recording and a multiview. It is based on the Android Open Source Project's
[Live TV](https://android.googlesource.com/platform/packages/apps/TV) reference app and installs
as a regular app (`com.livetv.tv`), not as a system app.

This is an independent project, not affiliated with or endorsed by Google. LiveTV does not include
any channels: you add your own sources.

## What LiveTV adds

### IPTV lists

- Add several lists, from a URL, an M3U file, or a username and password (Xtream accounts).
- Channel logos, groups and numbers come from the list; channels added to a list later show up
  when it is updated.
- Movies and series that a list mixes in with its channels are kept out of the channel list.
- The customize channel list groups the channels by list.
- Sources of other apps whose channels this app cannot read are listed apart, as incompatible.

### Program guide

- XMLTV guides per list (`.xml` or `.xml.gz`), detected automatically from the list or the
  account, or added by hand, including public guides.
- Channels are matched to the guide automatically; any channel can be assigned by hand to a
  channel of any guide.
- The number keys of the remote go to a channel in the guide.
- The channel banner can expand a program's full description.

### Channels

- Favorites, and manual reordering of the "All" channel list.
- Search channels by text or voice.

### Multiview

- Watch 2, 4 or 6 IPTV channels at once; the selected one has the sound.
- Pick channels with the side panel or the number keys, open one in full screen and come back.
- Optionally opens at start (only when it has channels). The channels and layout are kept.

### Movies and series

- Browse the movies and series of Xtream accounts by category, with posters, and series by
  season.
- "Continue watching", resume where a title was left, and the audio and subtitles chosen per
  title are remembered.
- Player with a ten-second jump per press of rewind or fast forward, and faster rewinding and
  fast forwarding while the key is held.

### Time shifting and recording (with a removable drive)

- With a USB drive, hard disk or memory card plugged in, the IPTV channel playing is kept for the
  last hour: pause, rewind and fast forward live TV.
- Record IPTV channels to the drive, now or scheduled from the guide, and play the recordings with
  the same controls. The number of recordings at once follows the connection limit of the account.

### Playback

- Match frame rate: switches the display to the frame rate of the content (automatic, seamless
  switches only, or off).
- MP2 audio decoder in the app, for the TVs that play these channels muted.

### Backup

- Export the IPTV setup to a file, encrypted with a PIN (AES-256-GCM): lists with their
  credentials, channels (hidden ones included), favorites, channel order, guide settings and the
  multiview. Import it on another TV, or on this one after its data was cleared.

### Privacy and security

- List passwords and stream URLs are never written to the system log.
- An address with a password asked over HTTPS is never sent again over HTTP after a redirect.
- No Android cloud backup, which would upload the passwords of the lists.

### Other changes

- Translated into the languages of the original app.
- The built-in tuner (USB and network tuners of the original app) is removed; channel sources of
  other apps installed on the TV still work.

## From the original Live TV app

The features of the AOSP app remain: the channel banner, the menu with its channels row and play
controls, the program guide, recordings and their scheduling, parental controls, closed captions,
audio track selection, picture-in-picture and the channel sources setup.

## Requirements

- A device running Android TV 6.0 (API 23) or later.
- Time shifting and recording need a removable drive with at least 512 MB free; recording needs
  Android 7.0 (API 24) or later.
- To build: JDK 21 and the Android SDK (API 36), with `sdk.dir` set in `local.properties`.

## Build

```bash
./gradlew assembleDebug assembleRelease
```

The APKs are also copied to `Releases`, next to the project folder: the debug one to
`Releases/APK-Debug-Releases/LiveTV-debug-<version>.apk`, the release one to
`Releases/APK-Releases/LiveTV-<version>.apk`. Install them with:

```bash
adb install -r <path-to-apk>
```

The debug build installs next to the release one, as "LiveTV Debug" (`com.livetv.tv.debug`).

The release is signed with the key given in `keystore.properties` (not in git), with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file the release is
signed with the debug key and must not be distributed.

## License

Licensed under the [Apache License 2.0](LICENSE). This project modifies the original AOSP code.
It uses JLayer by JavaZOOM (LGPL) to decode MP2 audio and AndroidX Media3 to play; third-party
licenses are listed in `res/raw/third_party_licenses`.
