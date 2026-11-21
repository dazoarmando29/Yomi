# Yomi iOS Compatibility Matrix

Legend: ✅ **Reusable** (logic shared or feature shipped as-is) · 🔁 **Requires porting** (rewritten
natively for iOS) · ⛔ **Unsupported on iOS** (platform restriction; see notes).

## Feature matrix

| Feature | Android implementation | iOS status | Notes |
|---|---|---|---|
| Library (grid/list, favorites) | Compose + SQLDelight | 🔁 Ported | `YomiKit.LibraryStore` + SwiftUI grid/list |
| Categories (CRUD, ordering, per-category sort) | `tachiyomi.domain.category` | 🔁 Ported | Same semantics; flags preserved |
| Library search (text + query syntax) | `LibrarySearchAst` | 🔁 Ported (subset) | Text/quoted/negation subset; full AST later |
| Sorting & filters | `LibrarySortMode`, `Flag.kt` | 🔁 Ported | Flag bit values kept for backup parity |
| Unread indicators / badges | `LibraryManga` counts | 🔁 Ported | |
| Continue reading | History + progress | 🔁 Ported | |
| Reading history (read duration) | `history.sq` | 🔁 Ported | |
| Persistent reading progress | `chapter.user_last_page_read` | 🔁 Ported | Saved per page + on background |
| Source browsing (popular) | `CatalogueSource` | 🔁 Ported | `SourceProvider` protocol |
| Source search | `GetRemoteManga` | 🔁 Ported | |
| Manga details + chapter list | Manga screen | 🔁 Ported | |
| Refresh chapter information | `FetchInterval`/sync | 🔁 Ported (manual refresh) | Background auto-update queued post-v1 |
| Source configuration screens | `ConfigurableSource` (extension prefs) | ⛔ Partial | Only for native providers; APK prefs are meaningless on iOS |
| Reader: L2R / R2L paged | `PagerViewer` | 🔁 Ported | |
| Reader: vertical / webtoon | `WebtoonViewer` | 🔁 Ported | Long-strip scrolling with lazy loading |
| Reader: continuous vertical (gapped) | `WebtoonViewer(isContinuous)` | 🔁 Ported | |
| Zoom / pinch / double-tap | `PhotoView`/gestures | 🔁 Ported | SwiftUI gestures |
| Page preloading + image caching | Coil + `HttpPageLoader` | 🔁 Ported | `ImagePipeline`: memory LRU + disk cache |
| Reader settings (mode, scale, brightness…) | `ReaderPreferences` | 🔁 Ported | Core set shipped |
| Resume reliably | DB progress | 🔁 Ported | |
| Safe areas / Dynamic Island | Compose insets | 🔁 Ported | SwiftUI safe-area aware |
| Downloads (queue, retry, cancel) | `DownloadManager` + WorkManager | 🔁 Ported | Foreground queue + opportunistic background |
| Offline library / storage cleanup | Downloads screen | 🔁 Ported | |
| Backup create (proto.gz) | `BackupCreator` | 🔁 Ported | Same protobuf schema/field numbers |
| Backup restore (Mihon/Tachiyomi/Yomi) | `BackupRestorer` | 🔁 Ported | Import preview + missing-source report; never destructive |
| Missing/incompatible source reporting | `BackupImportInspector` | 🔁 Ported | Shown before apply; nothing silently dropped |
| Preferences backup | `PreferenceBackupCreator` | ⛔ Unsupported | Android SharedPreferences keys are not portable |
| Tracking: MyAnimeList / AniList | OAuth in-app | 🔁 Framework only | Needs registered OAuth clients (user-supplied); UI + PKCE flow shipped, activation requires credentials |
| Tracking: Kitsu / Shikimori / Komga / Suwayomi | Various | ⛔/Later | Komga/Suwayomi are HTTP APIs — feasible later |
| Local notifications | `NotificationExtensions` | 🔁 Ported | Download completion + "next chapter" style alerts |
| App shortcuts / edge panels | Android | ⛔ Unsupported | |
| Home screen widgets (Glance) | `presentation-widget` | ⛔ Unsupported | iOS widgets would be a separate WidgetKit target (post-v1) |
| Extension APK install (shared/private) | `ExtensionLoader`, PackageInstaller, Shizuku | ⛔ Unsupported | See §Extension below |
| Dynamic extension code execution | `DelegateLastClassLoader` | ⛔ Unsupported | Prohibited by iOS platform policy |
| Extension stores (index.json repos) | `ExtensionStore` | ⛔ Unsupported (as APKs) | Concept mapped to "source registry" docs |
| Local source (CBZ/directory) | `source-local` | 🔁 Scheduled post-v1 | Protocol-ready |
| Incognito mode / biometric lock | Compose | 🔁 Post-v1 | iOS LocalAuthentication feasible |
| Telemetry / Crashlytics | Firebase | ⛔ Optional | Not included (no unnecessary permissions) |
| Split-screen / tablet layouts | Compose adaptive | 🔁 Partial | iPad layout deferred; iPhone 18 Pro Max is the target |
| Tachiyomi 1.x legacy backups | Legacy parsers | ⛔ Unsupported | 1.x format predates Yomi; no parser exists in this codebase either |

## Extension compatibility (the hard constraint)

### Why Mihon/Tachiyomi extensions cannot run on iOS

Yomi (Android) loads extensions exactly like Mihon (`app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`):

1. An extension is a **signed Android APK** declaring the `tachiyomi.extension` feature and
   metadata (`tachiyomi.extension.class`, `tachiyomix.name`, `tachiyomix.extensionLib`, …).
2. It is installed either through the **system PackageInstaller/Shizuku** (shared extensions) or
   copied into `filesDir/exts` as a private `.ext` file.
3. Its classes are loaded **at runtime** through `DelegateLastClassLoader` (a `PathClassLoader`
   descendant) and instantiated via reflection (`SourceFactory` / `Source` classes).
4. Extensions call back into `extensions-lib` (OkHttp-based `HttpSource`), i.e. they are ordinary
   JVM bytecode executing inside the host app.

On iOS this is impossible *by platform design*, not by missing effort:

- iOS requires **all executable code to be signed by Apple-verified provisioning**; apps may not
  load downloaded/mutated machine code. JIT is limited to Apple's JavaScriptCore for interpreted
  JS under strict rules; loading arbitrary native/JVM bytecode (or a JVM/Dalvik runtime) is
  prohibited and would be rejected by App Review (Guideline 2.5.2).
- There is **no APK installer, no PackageManager, no Dalvik/ART, no class loader** equivalent.
- Even an interpreter-driven "APK reimplementation" would amount to executing untrusted third-party
  code inside the app — a security and App Store policy violation. **Yomi iOS does not attempt
  this, and does not attempt to circumvent code signing or platform security.**

### What iOS gets instead: a legitimate source-provider architecture

`YomiKit.SourceProvider` mirrors the `HttpSource` contract (popular/search/details/chapters/pages)
as native Swift async APIs. Ships today:

- **`SampleSource`** — deterministic, procedurally generated sample content (original artwork
  rendered on-device). It exercises the complete pipeline end-to-end: discovery → details →
  chapter list → page loading → downloads → offline reading. Source ID derives from the same
  MD5 scheme as `HttpSource.generateId`, so backups map correctly.
- **`SourceRegistry`** — the trust boundary. Providers are compiled into the app (or added by the
  user through explicit, data-only configuration). No remote code is ever downloaded or executed.

### Path to real-world sources (documented, in order of practicality)

1. **Native Swift connectors** for sites whose terms permit it, implemented as `SourceProvider`
   conformances (same role an extension plays on Android, but compiled and signed).
2. **Self-hosted bridge** — users running Suwayomi/Komga-style servers can be supported by an HTTP
   provider against those documented APIs (legitimate user-owned content).
3. **Declarative data-only source packs** (a JSON manifest describing request/parse rules) *may* be
   evaluated in a sandboxed, data-only interpreter; this is explicitly **not** remote code
   execution and remains future work with explicit trust prompts.

## Backup compatibility

| Format | Import | Export | Notes |
|---|---|---|---|
| Yomi/Mihon `proto.gz` (current, kotlinx ProtoBuf wire format) | ✅ | ✅ | Field numbers mirrored 1:1; gzip via pure-Swift inflate |
| Mihon older `.proto.gz` (pre-fork schema, superset of Tachiyomi) | ✅ | — | Unknown fields are skipped (wire-format tolerance) |
| Tachiyomi legacy JSON (1.x) | ⛔ | — | Not present in the Android codebase either |
| Preferences inside backups | skipped + reported | — | Android-only keys; listed in the import report, never silently dropped |
| Tracking entries | preserved as data | ✅ | Restored as linkage metadata; sync requires tracker activation |
| Source preferences / extension stores | reported as unsupported | — | Appear in the import report |

Import safety rules (identical intent to `BackupImportInspector`):

- Existing library data is **never overwritten or deleted** by an import; duplicates are merged by
  `(sourceId, url)` and conflicts are reported before applying.
- Sources referenced by the backup but unavailable on iOS are listed as **missing sources** with
  counts, and their manga are still imported as catalog entries ("offline catalog") so no data is
  lost.
- Every import requires explicit user confirmation after the preview.
