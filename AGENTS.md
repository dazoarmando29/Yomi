# Yomi — agent instructions

## Release policy (standing rule)

**Always create a GitHub release whenever a bug fix or new feature is finished.**

- Build `:app:assembleRelease` from the verified green tip (tests + assembleDebug must pass first).
- Verify the APK actually contains the change (e.g. `aapt2 dump strings`) before uploading.
- Tag format: `vX.Y.Z` (patch bump for fixes, minor for features). Title: "Yomi vX.Y.Z - <short summary>".
- Upload all 5 APKs (universal + arm64-v8a + armeabi-v7a + x86_64 + x86) via `gh release create --repo dazoarmando29/Yomi`.
- Release notes: what changed, honest limitations, build commit, signing note (debug certificate).
- Commits are authored as `dazoarmando29 <137482620+dazoarmando29@users.noreply.github.com>`.

## Verification rules

- Run `gradlew :app:testDebugUnitTest :app:assembleDebug` after every change; never commit red.
- Do not claim UI changes are visually verified unless an emulator/device was used.
- When adding a ViewModel used via `metroViewModel`, register it with
  `@ViewModelKey @ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`
  and sweep all `metroViewModel<...>` usages against registered classes (missing registration
  compiles fine but crashes at runtime).
- SQLDelight: never create a migration file without listing the existing `*.sqm` files first
  (pick max+1; never overwrite an existing migration).

## Repo layout

- Fork of Mihon v0.21.3 (`upstream` remote); work happens on `ui/yomi-premium-redesign`
  (UI) and `develop/yomi-initial` (integration). Origin: github.com/dazoarmando29/Yomi.
