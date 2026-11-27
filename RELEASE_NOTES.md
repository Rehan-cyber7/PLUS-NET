# PlusNet v0.5.0

Late-joining networks, torrents, and settings.

## What's changed

- **Fixed:** when Wi-Fi (or mobile data) connected in the middle of a download that started without it, it was never used. Such a network was wrongly marked as unable to do byte-range downloads and left out for good. It now joins as soon as it connects, and each piece it fetches is checked to be the same size as the file.
- **New: torrents and magnet links (Android).** Paste a magnet link or a link to a `.torrent` file in New download. Torrents use the phone's main connection (Wi-Fi when connected); they cannot be combined across Wi-Fi and mobile data. Files are saved to your download location when finished, and sharing stops then so it does not keep using your data.
- **New: Settings** (sliders icon, top right): Appearance (System, Light or Dark) and Download location (the Downloads folder by default, or any folder you pick). Network names and speed limits are in the same sheet. A new location applies to new downloads.
- "Open" now says why it could not open something, for example which folder a multi-file torrent is in.

## Android build

- App version: `0.5.0`
- Android versionCode: `9`
- Application ID: `app.plusnet.mobile`
- Artifact: `PlusNet-0.5.0-debug.apk`

## Torrent notes

- The torrent engine (libtorrent4j) is included for 64-bit and 32-bit ARM phones. The APK is larger than before.
- A magnet link has to be found on the network first; this can take up to 2 minutes, and fails if no one is sharing it.
- Torrents are saved in the app's own storage while downloading and moved to the download location when done, so a large torrent needs free space for itself plus, briefly, for the file being moved.
- Torrents are not resumed after the app is closed.

## Upgrade note

The application ID is unchanged, so this installs as an update only if both APKs were signed with the same key. Debug APKs built on GitHub Actions get a new signing key on each build; if Android reports a conflict, uninstall the old version first.

## Network compatibility

Splitting a normal download requires a server that supports HTTP byte ranges and accepts requests over each selected network. Server, CDN and carrier restrictions cannot be bypassed by the app.

This is a debug/testing APK, not a production-signed Google Play release.
