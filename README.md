# Yomi

### Modern manga reader for Android

Yomi is a fast, polished manga, manhwa, manhua, and webtoon reader for Android.
It is based on [Mihon](https://github.com/mihonapp/mihon) and preserves its reading engine,
source/extension system, library management, downloads, and backup compatibility.

Yomi uses a distinct application ID (`app.yomi.reader`) and can be installed alongside Mihon
without overwriting it.

## Features

- Library with categories, unread badges, download indicators, filters, sorting, and grid/list layouts
- Continue reading row on the library screen
- Source browsing, search, filters, and global search through compatible extensions
- Manga detail pages with covers, metadata, chapter lists, and download actions
- Extension manager: browse, search, filter, install, update, uninstall, and trust source
  extensions from repositories you add yourself
- Configurable reader: paged (LTR/RTL) and webtoon/vertical modes, scaling, preloading,
  brightness, orientation, and reading position saving
- Chapter downloads and offline reading with a download queue, retry, and cancel
- Tracker support: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi, MangaBaka, and Hikka
- Backup create/restore, including **Import Mihon backup** (`.tachibk`) and legacy Tachiyomi
  `.proto.gz` backups with preview, progress preservation, and missing-source handling
- Dark-first Yomi theme with deep charcoal surfaces and subtle purple accents

## Backup import (migrating from Mihon or Tachiyomi)

Settings → Backup & Restore → **Import Mihon backup**

1. Pick a backup file (`.tachibk` or legacy `.proto.gz`).
2. Yomi validates the file and identifies the format. The original file is never modified.
3. A preview shows what the backup contains: manga entries, categories, chapters, read status,
   saved reading progress, history, and tracker links.
4. Choose what to restore and confirm. Restoration progress and a summary of imported/failed
   items are shown in the notification.
5. After importing, Yomi resumes at the last chapter and page you were reading where that
   information exists. Entries whose source is missing are preserved with their reading history
   and can be migrated manually — they are never silently deleted.

Downloaded chapter image files are not contained in backup archives and must be migrated
separately.

## Extension manager

Yomi ships Mihon's source-extension engine unchanged in behavior. All manga content comes from
extensions that **you** install — nothing is bundled or scraped into the app itself.

### Adding an extension repository

Settings → Browse → **Extension repositories** → add a repository index URL.

Extension repositories are **third-party software providers**. Only add repositories you trust.
A well-known community repository is Keiyoushi (`https://keiyoushi.github.io/extensions/`).
Yomi ships with no preconfigured repository and never silently trusts or installs one.

Supported index formats:

- Legacy JSON (`repo.json` metadata + `index.min.json` extension listings)
- Protobuf store indexes (current format, gzip auto-decompressed)

Each repository is identified by its **signing key fingerprint**; extension updates are only
offered from repositories whose signing key matches the installed APK.

### Installing extensions

Discover (Browse) → **Extensions**:

- Browse available extensions grouped by language; search by extension, source name, or URL
- Filter by language (overflow menu) and view installed / updates / not-loaded sections
- Install, update (single or all), uninstall, and per-extension actions (settings, WebView)
- Enable/disable individual manga sources inside each extension (extension details screen)
- Untrusted extensions are not loaded until you explicitly trust the signing certificate;
  changed signing certificates are rejected

Installation uses Android's standard package installers (`PackageInstaller`, legacy installer,
or Shizuku) and always surfaces Android's own installation prompts. Yomi verifies the APK
signature against the repository signing key before installation and never disables signature
or platform security checks.

### Supported extension API versions

Yomi's loader accepts extension library (API) versions **1.4 and 1.6** (the exact supported set
of the Mihon 0.21.3 engine it is built from). Extensions declaring any other `extensionLib`
version are listed as "not loaded" with an `UnsupportedLibVersion` reason and are never
executed. Extensions are discovered via the `tachiyomi.extension` package feature and
`tachiyomi.extension.class` / `tachiyomix.*` manifest metadata — no host application ID
dependency, so extensions built for Mihon load in Yomi unchanged.

### Missing extensions after a backup import

When an imported backup references sources whose extensions are not installed, Yomi:

1. Preserves the manga entries, read chapters, history, categories, and page progress.
2. Lists the missing sources in the import preview and in the manga's "Source not installed"
   screen (entries are never silently deleted or reset).
3. Offers **Find missing extensions** / **Find extension** actions that open the Extensions
   screen so you can install a compatible extension from a repository you have already added
   and trusted.
4. Reconnects restored entries automatically once a matching extension (same source ID) is
   installed. Where no exact match exists, the existing source-migration flow is offered
   manually — Yomi never substitutes an unrelated source on its own.

### Migrating from Mihon — full checklist

1. Install Yomi (it installs alongside Mihon).
2. Add your trusted extension repository (Settings → Browse → Extension repositories).
3. Install your preferred manga source extensions (Discover → Extensions).
4. Import your Mihon backup (Settings → Backup & Restore → Import Mihon backup).
5. Review the preview, restore, and check the summary notification for failed items.
6. Open a manga — it resumes at your last read chapter and page.
7. Download authorized chapters for offline reading as usual.

## Development requirements

- JDK 21+ (Android Studio's bundled JBR works)
- Android SDK with platform `android-37.2`, build-tools `37.0.0`, NDK `29.0.14206865`
  (Gradle will offer to install missing packages, or install via `sdkmanager`)
- Gradle 9.8.2 via the included wrapper

## Building

```bash
# Debug APK (applicationId app.yomi.reader.dev)
./gradlew :app:assembleDebug

# Unit tests
./gradlew test

# Release APK (signed with your own keystore via keystore.properties, or debug key)
./gradlew :app:assembleRelease
```

APKs are written to `app/build/outputs/apk/<buildType>/`.

## Installing

1. Build a debug or release APK (see above), or download a release artifact.
2. Transfer the APK to your device and allow installation from unknown sources when prompted.
3. Yomi installs as a separate app (`app.yomi.reader`) and does not replace Mihon.

## Upstream project and attribution

Yomi is a fork of **Mihon** (<https://github.com/mihonapp/mihon>), which itself continues the
work of Tachiyomi. Yomi keeps the upstream copyright notices, license texts, and credits.
All credit for the core reader, library, download, extension, and backup systems belongs to the
Mihon and Tachiyomi authors and contributors. Please consider supporting the upstream project.

Upstream integration is maintained through an `upstream` git remote; Yomi-specific work lives on
`develop/yomi-initial`.

## License

    Copyright 2015 Javier Tomás
    Copyright 2024 Mihon contributors
    Copyright 2026 Yomi contributors

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

See [LICENSE](/LICENSE) for the full license text.

## Known limitations

- Legacy Tachiyomi **JSON** backups are not supported (protobuf `.proto.gz` is supported).
- Tracker OAuth flows reuse the upstream `mihon://` and `app.mihon` redirect schemes for
  compatibility with registered OAuth applications; if Mihon and Yomi are both installed and
  signed in to trackers, Android may ask which app handles the redirect.
- Downloaded chapters are not included in backup archives; migrate them manually.
- The built-in updater is disabled in local builds; update Yomi manually or via your own
  release channel.
- Yomi ships without crash telemetry by default in local builds.
- Universal compatibility with every extension repository is **not** claimed. Verification in
  this repository covers: build and unit tests (including repository index parsing for legacy
  JSON and protobuf formats, backup migration/restore regression tests), APK generation, and
  code-path review of install/signature/trust handling. On-device verification of extension
  installation against a live repository (e.g., Keiyoushi) is recommended before wide use.
