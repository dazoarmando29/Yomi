# Yomi iOS — Build & Install Guide

This guide covers building the native Yomi iOS app (`ios/`) and installing it on a
physical iPhone (primary test target: **iPhone 18 Pro Max**). Read the "Where builds
can happen" section first — it explains why the app cannot be compiled on Windows.

---

## 1. Where builds can happen

| Task | Windows (this PC) | macOS (required) | GitHub Actions macOS runner |
|---|---|---|---|
| Edit Swift sources, run `YomiKit` unit tests | ✅ with Swift for Windows (`swift test`) | ✅ | ✅ (`ios-ci.yml`) |
| Generate/verify `Yomi.xcodeproj` | ✅ (`python ios/tools/generate_xcodeproj.py`) | ✅ | ✅ |
| Compile the SwiftUI app (`xcodebuild`) | ❌ | ✅ | ✅ (unsigned simulator build) |
| Run in iOS Simulator | ❌ | ✅ | not persisted |
| Signed install on a physical iPhone | ❌ | ✅ | possible via exported IPA |

Windows cannot run Xcode or produce installable iOS binaries — this is an Apple
platform restriction, not a limitation of this project. Everything else is fully
scripted and reproducible from this repository.

## 2. Repository layout

```
ios/
  YomiKit/          Pure-Swift core (models, persistence, sources, backups, downloads)
    Tests/          Unit tests — run headlessly: swift test --package-path ios/YomiKit
  YomiApp/          SwiftUI application sources + Assets.xcassets (app icon)
  Yomi.xcodeproj/   Generated, committed Xcode project (open directly in Xcode)
  tools/
    generate_xcodeproj.py   Regenerates the project (run after adding/removing files)
    make_app_icon.py        Rebuilds AppIcon.appiconset from the Android launcher art
```

## 3. macOS prerequisites

1. macOS 14+ with **Xcode 15+** (Xcode 16 recommended). Install from the App Store,
   then run `xcode-select --install` if needed and accept the license:
   `sudo xcodebuild -license accept`.
2. Command line tools: `xcode-select -p` should print the Xcode developer dir.
3. Python 3 (preinstalled on macOS) for the project generator.
4. An **Apple ID** signed into Xcode (free accounts work for device deployment with
   a 7-day provisioning validity; Apple Developer Program membership ($99/yr) gives
   1-year certificates and App Store distribution).

## 4. Build commands (run on macOS)

### 4.1 Unit tests (no signing, no simulator)

```bash
swift test --package-path ios/YomiKit
```

### 4.2 Verify the committed project matches the sources

```bash
python3 ios/tools/generate_xcodeproj.py --check
```

If it reports staleness after you add files, regenerate and commit:

```bash
python3 ios/tools/generate_xcodeproj.py
```

### 4.3 Simulator build (unsigned — cannot be installed on a real iPhone)

```bash
xcodebuild -project ios/Yomi.xcodeproj -scheme Yomi \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Run interactively instead: open `ios/Yomi.xcodeproj` in Xcode, pick an iPhone
simulator (e.g. "iPhone 16 Pro Max" or newer), press ⌘R.

### 4.4 Physical device install (iPhone 18 Pro Max)

1. Connect the iPhone via USB; unlock it; tap **Trust** if prompted.
2. Open `ios/Yomi.xcodeproj` in Xcode.
3. Select the iPhone 18 Pro Max as the run destination.
4. First run requires signing setup:
   - **Xcode → Settings → Accounts** → add your Apple ID if not present.
   - Target **Yomi → Signing & Capabilities** → enable *Automatically manage signing*,
     select your Team. Xcode creates the development certificate + provisioning
     profile automatically (free accounts: app expires after 7 days; re-run to renew).
   - Bundle ID is `app.yomi.reader` (same identity as the Android app id). If your
     team requires a unique id, change `PRODUCT_BUNDLE_IDENTIFIER` or add a suffix.
5. If the device shows "developer mode required" (iOS 16+): on the iPhone open
   **Settings → Privacy & Security → Developer Mode** and enable it, then reboot.
6. Press ⌘R. First install may require **Settings → General → VPN & Device
   Management** → trust the developer certificate.
7. To verify launches: Xcode console / Devices window (⇧⌘2) shows runtime logs and
   crash reports.

### 4.5 Release install via IPA (optional, no Mac required at install time)

The manual `build-signed-ipa` job in `.github/workflows/ios-ci.yml` archives and
exports an IPA when signing secrets are configured in the repository settings:

| Secret | Content |
|---|---|
| `IOS_SIGNING_CERTIFICATE_P12_BASE64` | base64 of your development certificate `.p12` |
| `IOS_SIGNING_CERTIFICATE_PASSWORD` | password of that `.p12` |
| `IOS_PROVISIONING_PROFILE_BASE64` | base64 of the `.mobileprovision` for `app.yomi.reader` |
| `IOS_TEAM_ID` | your Apple Developer Team ID |

Run the workflow (Actions → iOS CI → Run workflow), download the `Yomi-device-ipa`
artifact, then install with one of:

- **Xcode** (Devices window → select device → drag `.app`/IPA into installed apps),
- **Apple Configurator** (free on the Mac App Store),
- `ios-deploy` / `cfgutil` on macOS.

**Never commit** certificates, provisioning profiles, passwords or Apple account
secrets to the repository. The workflow reads them only from GitHub encrypted
secrets.

## 5. Unsigned simulator build vs signed device build — the difference

- **Simulator build** (`CODE_SIGNING_ALLOWED=NO`): x86_64/arm64 binaries that run
  in the iOS Simulator only. They are *not* code-signed and **cannot be installed
  on a physical iPhone**. Good for CI verification and UI work.
- **Device build** (signed): compiled for `iphoneos`, signed with an Apple-issued
  development certificate and embedded provisioning profile covering your device's
  UDID. Only these can be installed on a real iPhone. Apple verifies the signature
  at install and launch; this cannot and must not be bypassed.

## 6. Windows-side verification (what you can run here)

```powershell
# Project structure check
python ios\tools\generate_xcodeproj.py --check

# Unit tests with Swift for Windows (winget install Swift.Toolchain)
$env:PATH = "$env:LOCALAPPDATA\Programs\Swift\Toolchains\6.4.3+Asserts\usr\bin;$env:PATH"
swift test --package-path ios\YomiKit
```

Note: Swift for Windows also needs the MSVC linker (`link.exe`) from the
"Desktop development with C++" workload of Visual Studio Build Tools.

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `xcodebuild: error: scheme 'Yomi' not found` | Regenerate the project: `python3 ios/tools/generate_xcodeproj.py` (it writes the shared scheme) |
| Signing error "no matching provisioning profiles" | Enable automatic signing and select your Team (§4.4), or configure CI secrets (§4.5) |
| App installs but crashes on launch | Check the device console in Xcode (⇧⌘2). Report the crash log in `IOS_TEST_RESULTS.md` |
| "Developer Mode required" on device | Settings → Privacy & Security → Developer Mode → on → reboot |
| Free account: app expires after 7 days | Re-run from Xcode to refresh; or use a paid account for 1-year profiles |
| Stale project after adding Swift files | `python3 ios/tools/generate_xcodeproj.py` and commit the result |
