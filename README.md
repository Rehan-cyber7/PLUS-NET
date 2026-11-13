# PlusNet

**PlusNet is an Android download manager that can split a supported file download across Wi-Fi and mobile data, at full speed on both at once.** (An optional "Wi-Fi first" mode under Networks saves mobile data.) It combines a React + TypeScript interface with a native Capacitor plugin that routes individual HTTP range requests over selected Android networks.

> **App version:** `0.4.0`  
> **Android versionCode:** `5`  
> **Release tag:** `v0.4.0`  
> **Android application ID:** `app.plusnet.mobile`

## App preview

<p align="center">
  <img src="docs/screenshots/plusnet-home-screen.jpg" alt="PlusNet Android home screen showing Wi-Fi and mobile-data status, total download speed, Downloads and Complete tabs, and the add-download button" width="340" />
</p>

The screenshot above shows the PlusNet Android home screen. Network availability and download speeds depend on the device, connection and download host.

## Features

- React 19, TypeScript and Vite interface.
- Capacitor 7 Android application shell.
- Native `plusnet-engine` plugin for Android and iOS.
- Independent URL probing and redirect handling per selected network.
- HTTP byte-range downloading across eligible networks.
- Per-network speed, transferred-byte and progress reporting.
- Fallback scheduling when one selected network becomes unavailable.
- Browser preview using a simulated engine.
- GitHub Actions workflow for cloud APK builds and tagged GitHub Releases.

## How multi-network downloading works

1. PlusNet probes the original URL independently over each selected Android network.
2. A network can participate in split downloading only when the host supports compatible HTTP byte-range requests. A successful range response is normally `206 Partial Content`.
3. The native engine divides the file into byte ranges and assigns ranges to eligible networks.
4. Each network follows its own redirect chain. A temporary redirect URL discovered over Wi-Fi is not reused for cellular.
5. If a network becomes unavailable, another eligible network can take over ranges that have not completed.

### Important limitations

PlusNet cannot force a server to accept a request. Multi-network downloading depends on all of the following:

- The host supports HTTP `Range` requests and returns valid `206 Partial Content` responses.
- The server or CDN accepts requests arriving independently over both networks. Some hosts, signed URLs, anti-bot systems and IP-bound links may reject one route.
- Android and the phone manufacturer allow the app to use both Wi-Fi and cellular networks.
- Both networks have working Internet access. Carrier policies, VPNs, private DNS, captive portals and battery settings can affect routing.

If a host refuses cellular requests, PlusNet reports that route as unavailable for that URL. The app does not bypass server or carrier restrictions. A download can continue over another eligible network.

## Technology stack

| Area | Technology |
|---|---|
| UI | React 19, TypeScript |
| Development server / web build | Vite 6 |
| Mobile bridge | Capacitor 7 |
| Android native engine | Java, Android `ConnectivityManager` and `Network` APIs |
| iOS native engine | Swift, Capacitor plugin APIs |
| Styling | Tailwind CSS 4, custom CSS |
| CI / APK builds | GitHub Actions and Gradle |

## Project structure

```text
plusnet-mobile/
├── .github/workflows/android-apk.yml  # Cloud APK builds and tagged releases
├── docs/                              # Release guide and screenshots
├── plugins/plusnet-engine/            # Capacitor native plugin
│   ├── android/                       # Android download engine
│   ├── ios/                           # iOS plugin source
│   ├── PlusNetEngine.podspec
│   └── package.json
├── public/                            # Web assets and app icon
├── resources/                         # Capacitor app icon resources
├── scripts/                           # Build and version helper scripts
├── src/                               # React UI and TypeScript logic
├── capacitor.config.ts
├── package.json
├── RELEASE_NOTES.md
├── CHANGELOG.md
└── README.md
```

## Run the browser preview

The browser preview helps test layout, navigation and UI interactions. It uses the simulated engine; it does **not** test Android's native Wi-Fi/cellular routing.

### Requirements

- Node.js 22 or newer.
- npm.
- VS Code or another code editor.

### Install and run

```bash
npm install
npm run dev -- --host 0.0.0.0
```

Vite prints a local URL and a network URL. To open PlusNet on an Android phone:

1. Connect the phone and laptop to the same Wi-Fi network.
2. Allow Node.js through Windows Firewall on a private network if prompted.
3. Open the Vite **Network** URL in Chrome on the phone.

For example, the address may look like `http://192.168.1.5:5173`. Use the actual address printed by Vite.

### Run project checks

```bash
npm test
npm run test:native
npm run build
```

`npm run test:native` runs lightweight Java checks for the native chunk scheduler. GitHub Actions performs the Android build on a hosted runner.

## install an Android debug APK

1. When it succeeds, open the run and download the `plusnet-android-debug-apk` artifact.
2. Extract the artifact ZIP and transfer `app-debug.apk` to your Android phone.
3. Open the APK and approve installation if Android asks.

**Debug-build note:** This APK is for development and testing. It is not a production-signed Google Play release.

## Troubleshooting native network routes

With USB debugging enabled and ADB installed, run:

```bash
adb devices
adb logcat -c
adb logcat -s PlusNetNets:I
```

Open PlusNet and start a download. Check the diagnostic lines for network discovery and independent HTTP results over Wi-Fi and cellular. Do not post signed URLs, authorization headers or private tokens in public logs or issue reports.

## License and attribution

This project is licensed under the MIT License. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for the retained upstream attribution and third-party notices.
