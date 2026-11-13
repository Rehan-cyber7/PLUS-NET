# Changelog

## [0.4.2] - 2026-11-11

- Fix: Wi-Fi and mobile data now both download at full speed until the file is finished. Previously each network was given a fixed half of the file and a network that finished its half sat idle while the other was still working (so a file ended up exactly half and half, and mobile data showed 0 B/s part-way through). Now any network that is free takes the next waiting part, so the faster network does more and the total speed is the sum of both.
- **Use both together** is the default again. The **Wi-Fi first** option from 0.4.1 stays available under Networks for people who want to save mobile data.

## [0.4.1] - 2026-11-11

- Add a **Wi-Fi first** mode, now the default. With Wi-Fi connected a download uses only Wi-Fi; mobile data is used only if Wi-Fi is not available, and the download goes back to Wi-Fi when it returns. Nothing already downloaded is lost when it switches.
- Keep the previous behavior as **Use both together** (Networks sheet, top right).
- With a wired (Ethernet) connection present, it is preferred over Wi-Fi in Wi-Fi first mode.
- Setting is remembered across restarts, on Android and iOS.

## [0.4.0] - 2026-11-04

- Rebrand the app and native engine from Plexo to PlusNet.
- Change Android application ID to `app.plusnet.mobile`.
- Rename the local Capacitor plugin and its Android/iOS identifiers to PlusNet.
- Replace the app icon and update the README, release assets and GitHub Actions workflow.
- Carry forward per-network redirect handling, range validation, chunk scheduling and fallback improvements.

## [0.3.7] - 2026-11-04

- Prepare the previous app and local Capacitor plugin for the `0.3.7` release line.
- Include independent network-specific redirect handling for Wi-Fi and cellular downloads.
- Validate byte-range support per network before splitting a download.
- Improve chunk scheduling, fallback behavior and Android network diagnostics.

## [0.1.2] - 2026-11-04

- Introduce network-specific redirect handling, per-network range validation and route diagnostics.
