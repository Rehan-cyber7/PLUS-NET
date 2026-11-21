# PlusNet v0.4.3

Wi-Fi and mobile data always download together.

## What's changed

- **Removed** the optional "Wi-Fi first" mode from 0.4.1. There is no setting that limits a download to Wi-Fi any more.
- **Fixed:** mobile data is no longer left out when it is not yet connected at the moment you tap Start. Every network you have not switched off is part of the download, and one that connects a moment later joins automatically.
- The new-download sheet always lists Wi-Fi and Mobile data, and says "Not connected yet" when one is not ready.
- The app version is shown at the bottom of Networks (sliders icon, top right), so you can check which build is installed.
- The 0.4.2 scheduling fix is included: the faster network does more and neither sits idle while there is work left.

## Android build

- App version: `0.4.3`
- Android versionCode: `8`
- Application ID: `app.plusnet.mobile`
- Artifact: `PlusNet-0.4.3-debug.apk`

## Upgrade note

The application ID is unchanged, so this installs as an update only if both APKs were signed with the same key. Debug APKs built on GitHub Actions get a new signing key on each build; if Android reports a conflict, uninstall the old version first.

## Network compatibility

Splitting requires a server that supports HTTP byte ranges and accepts requests over each selected network. Server, CDN and carrier restrictions cannot be bypassed by the app.

This is a debug/testing APK, not a production-signed Google Play release.
