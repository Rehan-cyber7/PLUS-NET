# PlusNet v0.4.2

Wi-Fi and mobile data now download together at full speed.

## What's changed

- **Fixed:** a download is no longer cut into a fixed half for Wi-Fi and a fixed half for mobile data. Before, the network that finished its half first went idle (mobile data dropped to 0 B/s) while the other network was still working, so the total speed fell to one network. Now whichever network is free takes the next waiting part of the file: both stay busy until the end, the faster one carries more, and the total speed is the sum of both.
- **Use both together** is the default again.
- The optional **Wi-Fi first** mode from 0.4.1 is still under Networks (sliders icon, top right) for saving mobile data. It is off unless you choose it.
- Everything else is unchanged.

## Android build

- App version: `0.4.2`
- Android versionCode: `7`
- Application ID: `app.plusnet.mobile`
- Artifact: `PlusNet-0.4.2-debug.apk`

## Upgrade note

The application ID is unchanged, so this installs as an update only if both APKs were signed with the same key. Debug APKs built on GitHub Actions get a new signing key on each build; if Android reports a conflict, uninstall the old version first.

## Network compatibility

Splitting requires a server that supports HTTP byte ranges and accepts requests over each selected network. Server, CDN and carrier restrictions cannot be bypassed by the app.

This is a debug/testing APK, not a production-signed Google Play release.
