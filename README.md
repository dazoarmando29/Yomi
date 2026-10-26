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
