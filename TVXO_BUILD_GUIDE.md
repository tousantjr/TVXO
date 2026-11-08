# TVXO Build Guide

TVXO is a fork of [NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV) (branch `dev`, commit `828b3330`),
which is itself a fork of [Nuvio / NuvioTV](https://github.com/NuvioMedia/NuvioTV). License: GPL-3.0.

All TVXO work is on the local branch **`tvxo-rebrand`** in this folder. Nothing has been pushed anywhere.
The upstream remote is named `upstream`, and its push URL is set to `DISABLED` so you can't push to homelessbrian by accident.

---

## 1. Create the GitHub repo and push

1. On GitHub, signed in as **tousantjr**, create a new **empty** repository named `TVXO`: no README, no license, no .gitignore. Make it **public**. The in-app updater calls the public GitHub API without a token, so a private repo's releases are invisible to it.
2. From this folder:
   ```bash
   git remote add origin https://github.com/tousantjr/TVXO.git
   git push -u origin tvxo-rebrand:main
   ```
3. On GitHub, under Settings > General > Default branch, make sure `main` is the default.

If you use a different owner or repo name, change `tvxo.updateRepo` in `gradle.properties`. The CI workflow also overrides it automatically with the repository it runs in (`TVXO_UPDATE_REPO=${{ github.repository }}`).

## 2. Create your release keystore (once, then keep it safe forever)

```bash
keytool -genkeypair -v -keystore tvxo-release.jks -alias tvxo \
  -keyalg RSA -keysize 4096 -validity 10000
```

- **Back up `tvxo-release.jks` and its passwords.** Android only installs an update if it is signed with the same key. If you lose the key, every user has to uninstall and reinstall.
- Never commit it. `*.jks`, `*.keystore` and `keystore.properties` are in `.gitignore`.
- The upstream keystore (`keystore/livetv-fork.jks`, with its password written in the upstream workflows) was **deleted** from this tree. The hardcoded upstream signing passwords were removed from `app/build.gradle.kts`.

For local release builds, copy `keystore.properties.example` to `keystore.properties` and fill it in:
```
storeFile=/absolute/path/to/tvxo-release.jks   # or a path relative to the project root
storePassword=...
keyAlias=tvxo
keyPassword=...
```
Environment variables take priority over the file: `TVXO_KEYSTORE_FILE`, `TVXO_KEYSTORE_PASSWORD`, `TVXO_KEY_ALIAS`, `TVXO_KEY_PASSWORD`.
If none are set, `assembleFullRelease` produces an **unsigned** APK, which Android will not install.

## 3. GitHub secrets

Go to Settings > Secrets and variables > Actions > New repository secret.

| Secret | Required | Value |
|---|---|---|
| `TVXO_KEYSTORE_BASE64` | yes | output of `base64 -w0 tvxo-release.jks` (on macOS: `base64 -i tvxo-release.jks`) |
| `TVXO_KEYSTORE_PASSWORD` | yes | keystore password |
| `TVXO_KEY_ALIAS` | yes | e.g. `tvxo` |
| `TVXO_KEY_PASSWORD` | yes | key password |
| `TMDB_API_KEY` | recommended | your own TMDB key (movie and show metadata) |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID`, `SIMKL_APP_NAME`, `MDBLIST_CLIENT_ID`, `PREMIUMIZE_CLIENT_ID` | optional | your own app registrations with those services |
| `GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET` | optional | enables Live TV "Backup & Sync" to the user's own Google Drive (see section 9) |
| `INTRODB_API_URL` | optional | defaults to `https://api.introdb.app/` |
| `TVXO_PRIVACY_POLICY_URL` | optional | if set, About shows a "Privacy policy" row that opens this URL |

The workflow stops with an error naming any missing required signing secret.

## 4. Build with GitHub Actions

Workflow file: `.github/workflows/tvxo-release.yml` (manual trigger only). **Active since 2026-11-08**: it was added through the GitHub website together with the 4 Actions secrets. To release, open **Actions > TVXO Release > Run workflow**. `ci/tvxo-release.yml` is the reference copy; if you edit the workflow, keep both copies the same. The GitHub App that publishes this repo still cannot push changes under `.github/workflows/`, so edit the active file on the website.

### Enable automatic CI (already done, kept for reference / re-setup)

1. On github.com open the repo > **Add file > Create new file**, name it `.github/workflows/tvxo-release.yml`, paste the full contents of `ci/tvxo-release.yml`, and commit to `main`. (Files under `.github/workflows/` can be created from the website with your own account; the GitHub App cannot.)
2. Open **Settings > Secrets and variables > Actions > New repository secret** and add these 4 secrets:
   - `TVXO_KEYSTORE_BASE64`: the keystore file base64-encoded on one line, e.g. `base64 -w0 tvxo-release.jks`
   - `TVXO_KEYSTORE_PASSWORD`
   - `TVXO_KEY_ALIAS` (`tvxo`)
   - `TVXO_KEY_PASSWORD` (same as the store password for the current keystore)
3. Optionally add the other secrets from section 3.
4. Run **Actions > TVXO Release > Run workflow**. The build number continues from the highest existing release tag, so after the manually published `1.1.0-beta.13.1` the next CI build is `1.1.0-beta.13.2`.

### First release (published manually)

The first release, `1.1.0-beta.13.1`, was built locally with the real keystore (`./gradlew :app:assembleFullRelease -PlivetvBuildNumber=1 -PlivetvVersionName=1.1.0-beta.13.1`, signing through the `TVXO_KEYSTORE_*` env vars) and published through the GitHub REST API. It used the same tag, title, prerelease flag and asset names the workflow produces. The `downloader` release's `TVXO.apk` is the **arm64-v8a** APK.

Published on 2026-11-08 from commit `59d674eb` (https://github.com/tousantjr/TVXO/releases/tag/1.1.0-beta.13.1). versionCode is `107601`. All APKs are signed with cert SHA-256 `85:D5:6A:EC:96:BB:75:F9:A0:AB:52:B4:85:1E:97:D2:74:F8:8A:1D:84:DD:ED:2B:11:72:49:7B:FA:AC:FB:63` (APK Signature Scheme v2):

| Asset | Size (bytes) |
|---|---|
| `TVXO-1.1.0-beta.13.1-arm64-v8a-release.apk` | 70,613,327 |
| `TVXO-1.1.0-beta.13.1-armeabi-v7a-release.apk` | 68,814,829 |
| `TVXO-1.1.0-beta.13.1-universal-release.apk` | 171,881,674 |
| `downloader/TVXO.apk` (copy of arm64-v8a) | 70,613,327 |

History note: to publish without the `workflows` permission, the branch history was rewritten with `git filter-repo --path .github/workflows --invert-paths`. All other history is kept; 54 commits that only touched workflow files dropped out. The original history is kept only in the local backup branches `tvxo-rebrand-backup-original` / `tvxo-rebrand-backup-with-ci` on the build machine, not on GitHub.

### What the workflow does

1. Open Actions > **TVXO Release** > Run workflow, choose the branch (`main`), and optionally type release notes.
2. The workflow:
   - picks the version: `versionName` in `app/build.gradle.kts` (currently `1.1.0-beta.13`), then `.N`, so the first build is `1.1.0-beta.13.1`, then `.2`, and so on. If versionName has no `-` suffix, e.g. `1.2.0`, it uses `1.2.1-tvxo.N`.
   - sets up Go 1.27.3 (for the VPN native library) and Java 17.
   - writes optional keys to `local.properties` and decodes the keystore.
   - runs `./gradlew :app:assembleFullRelease -PlivetvBuildNumber=N -PlivetvVersionName=VERSION`. versionCode becomes `1076 × 100 + N`.
   - verifies the signature with `apksigner` and fails if no arm64-v8a APK was produced.
   - publishes a GitHub Release with tag = VERSION, marked **prerelease**, with these assets:
     - `TVXO-<VERSION>-arm64-v8a-release.apk` (the one for your Sony Bravia 8 II and most modern Google TVs)
     - `TVXO-<VERSION>-armeabi-v7a-release.apk`
     - `TVXO-<VERSION>-universal-release.apk`
   - updates a fixed release tagged `downloader` whose asset `TVXO.apk` (the arm64-v8a APK, falling back to universal) is replaced on every run. The link `https://github.com/tousantjr/TVXO/releases/download/downloader/TVXO.apk` always gives the newest build. That tag is not a version number, so the in-app updater ignores it.

The upstream workflows (`apply-kit`, `build-release-check`, `build-test-app`, `merge-nuvio-update`, `release-to-users`) were deleted. They targeted homelessbrian repos and the upstream key.

## 5. Install on the TV (first time)

These are general Google TV steps. They were not tested on your specific TV, and menu names can differ slightly by firmware.

1. On the TV: Settings > System > About, then press "Android TV OS build" 7 times to enable Developer options.
2. Install **Downloader** (by AFTVnews) from the Play Store. Allow it to install unknown apps: Settings > Apps > Security & restrictions > Unknown sources > Downloader.
3. In Downloader, enter `https://github.com/tousantjr/TVXO/releases/download/downloader/TVXO.apk`, or a shortened link or Downloader code that **you** register for that URL. The old code 6532491 belonged to homelessbrian and was removed. Then install.
4. Alternative from a computer: `adb connect <tv-ip>` then `adb install TVXO-<VERSION>-arm64-v8a-release.apk`.

TVXO uses applicationId `com.tvxo.tv`, so it installs **alongside** NuvioPlusIPTV rather than replacing it.

## 6. Publish updates (in-app updater)

How the updater works, as verified in the code:
- `app/src/full/java/com/nuvio/tv/updater/UpdateRepository.kt` calls the GitHub API for `BuildConfig.GITHUB_OWNER` / `BuildConfig.GITHUB_REPO`. Those come from `tvxo.updateRepo` in `gradle.properties` (default `tousantjr/TVXO`), overridable with env `TVXO_UPDATE_REPO` or `-Ptvxo.updateRepo=owner/repo`.
- **Channel** (`UpdateChannel.defaultForVersion`): if the installed versionName has a prerelease suffix (`-beta.13.1`, `-tvxo.1`), the default is **Beta**, which lists all releases (`/releases`). Otherwise it is **Stable**, which uses `/releases/latest` and skips prereleases. Every version the workflow produces has a suffix and is published as a prerelease, so installed builds default to Beta and see every new release. If a user switches the channel to Stable in Settings > About, they will see no TVXO updates.
- **Version** (`ReleaseSelector`, `VersionUtils`): taken from the release tag (or its name), parsed as semver `X.Y.Z[-pre][+build]` with an optional leading `v`. Drafts are skipped. An update is offered only if the remote version is newer than the installed `VERSION_NAME`.
- **Asset** (`AbiSelector.chooseBestApkAsset`): only `.apk` assets count. If there is just one APK, it is used. Otherwise the updater picks the asset whose name contains the device ABI (`arm64-v8a`, then `armeabi-v7a`, …), then one containing `universal` or `all`, then one with no ABI in its name. The workflow's asset names satisfy this.
- The download is checked against the asset's sha256 `digest` from GitHub.

To publish an update: commit and push your changes to `main`, then run **TVXO Release** again. Nothing else is needed. To start a new version line, raise `versionName` (and `versionCode`) in `app/build.gradle.kts`. versionCode must never go down.

## 7. Further rebranding: where things live

| What | Where |
|---|---|
| App name | `app/src/main/res/values*/strings.xml` → `app_name` (all locales set to `TVXO`; debug is `TVXO Debug` in `app/src/debug/res`) |
| Other user-visible text | same `strings.xml` files; Live TV screens use Kotlin literals in `app/src/main/java/com/nuvio/tv/ui/screens/settings/LiveTvSettingsContent.kt` and `app/src/main/java/com/nuvio/tv/livetv/**` |
| Live TV brand name constant | `app/src/main/java/com/nuvio/tv/livetv/branding/LiveTvBranding.kt` (`APP_NAME`) |
| Launcher icons | `app/src/main/res/mipmap-*/ic_launcher*.png`, `app/src/full/res/mipmap-*/iptv_ic_launcher*.png` (one file per icon colour variant) |
| TV banners | `app/src/main/res/mipmap-xhdpi/banner*.png` (320×180), `app/src/full/res/mipmap-xhdpi/iptv_banner*.png`, `app/src/main/res/drawable/tv_banner.png`, `drawable-nodpi/tv_banner.png` |
| Logos / wordmarks | `app/src/main/res/drawable-nodpi/app_logo_mark.png`, `app_logo_wordmark*.png`, `app/src/main/res/drawable/livetv_wordmark_iptv*.png` |
| Theme colours | `app/src/main/java/com/nuvio/tv/ui/theme/Color.kt`, `Theme.kt`, `CustomThemePalette.kt` |
| applicationId | `app/build.gradle.kts` → `val tvxoApplicationId = "com.tvxo.tv"` |
| Update repo | `gradle.properties` → `tvxo.updateRepo` |

| Adaptive icons (API 26+) | `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml`, `app/src/full/res/mipmap-anydpi-v26/iptv_ic_launcher*.xml` → foreground `mipmap-*/tvxo_ic_launcher_foreground.png` + background `@color/tvxo_launcher_background` (#000000, `values/tvxo_launcher_background.xml`) |
| Brand logo source | `branding/tvxo_logo_source.jpg` (the master artwork every image above is generated from) |

#### Artwork: regenerate everything from a logo

All launcher icons, adaptive icons, TV banners and wordmarks are generated from **`branding/tvxo_logo_source.jpg`** by `scripts/tvxo_brand_art.py`. The script needs Python 3 with Pillow and numpy:

```bash
pip install pillow numpy             # once
python3 scripts/tvxo_brand_art.py    # from the repo root
```

It overwrites every existing PNG **in place at its original pixel size and colour mode**, so no resource names or references change. It also writes `branding/tvxo_assets_preview.png`, a contact sheet you can open to check the result. What it produces:

| Output | Look |
|---|---|
| Legacy launcher icons (48–192 px, every colour variant) | the full logo (smiley + TVXO + some splatter), square-cropped around the artwork on black; small sizes get light sharpening |
| Adaptive icon foreground (108–432 px) + XML | smiley + TVXO on transparency, sized so all of it sits inside the 66dp safe zone (with a 4dp margin); background solid black |
| TV banners (320×180, 1280×720, 1920×1080) | black, the logo's lime splatter pushed to the left/right edges, horizontal smiley + TVXO lockup in the centre |
| `app_logo_wordmark*.png` (1085×344) | pink TVXO wordmark only, on transparency |
| `livetv_wordmark_iptv*.png` (1700×344, splash/header) | smiley + TVXO lockup, on transparency |
| `app_logo_mark.png`, `drawable/ic_launcher.png` (1080²) | the whole logo, resized |
| Test builds (`src/livetvTest`) | same images with a yellow TEST band |

Transparent images are extracted from the black background by brightness (alpha = brightness, colour un-premultiplied), so they show no black halo on any dark background. Every colour-variant file (arctic_blue, gold, jade, …) gets the same pink/lime artwork. The theme colours were **not** changed: the themes are defined in Kotlin with many palettes and there is no single brand-colour resource to point at the logo pink (#FF0D7D).

**To use a new logo:** replace `branding/tvxo_logo_source.jpg` with a square image on a pure black background, or pass a path: `python3 scripts/tvxo_brand_art.py path/to/logo.jpg`. If the artwork is laid out differently, edit the `LAYOUT` boxes at the top of the script. These are the pixel boxes of the smiley, the wordmark and both together, measured on a 1098 px image and scaled automatically. Then rerun the script, check the preview sheet, rebuild, and commit.

**Why the Kotlin namespace is still `com.nuvio.tv`:** the installed identity of an Android app is its applicationId (`com.tvxo.tv`), not its source package. Renaming the namespace would touch hundreds of Kotlin files, every `R`/`BuildConfig` import and the baseline profile, with high risk and no user-visible benefit. It also keeps future upstream merges feasible.

## 8. GPL v3 obligations (summary, not legal advice)

- Keep `LICENSE` (GPL-3.0) and `NOTICE`, and keep the original copyright notices in source files. Nothing was removed.
- TVXO must remain GPL-3.0. Anyone you give the APK to is entitled to the complete corresponding source of **that exact version**. Keeping the public GitHub repo with a tag per release satisfies this.
- State that it is modified: done in `NOTICE` and `README.md` ("TVXO is a modified version of NuvioPlusIPTV (and Nuvio), modified by TVXO, 2026-11-08"), and in every release body.
- You may charge for distribution or service, but you cannot add restrictions beyond the GPL. Your IPTV service and credentials are separate from the app.
- The in-app Settings > About > Licenses & attributions screen still credits Nuvio.

## 9. Sync features: what was kept or hidden, and why

| Feature | Depends on | TVXO decision |
|---|---|---|
| **Setup Sync** (`livetv/sync/LiveTvCloudSync.kt`) | homelessbrian's Cloudflare Worker `livetv-cloud.nuvioplusiptv.workers.dev` plus a signed-in Nuvio account on `api.nuvio.tv` | **Disabled** (`available = false`, `CLOUD_URL = ""`). Its card is hidden and it never starts. |
| **Nuvio account** (Supabase) | `NUVIO_SUPABASE_URL` / `NUVIO_SUPABASE_ANON_KEY` build keys | Left in code but **inert**: no keys are set, so the app reports the server as not configured and contacts no Nuvio server. The TVXO workflow does not write these keys. Do **not** point them at `api.nuvio.tv`. Users can still enter their own self-hosted server under the app's custom-server option. |
| Nuvio TV-login / link pages, supporters API, support URL, test-update URL | `nuvio.tv`, homelessbrian worker | Defaults blanked in `app/build.gradle.kts`. |
| **Google Drive Backup & Sync** (`livetv/sync/LiveTvDriveSync.kt`) | the user's own Google Drive; needs **your** Google OAuth client | Kept. The card is shown **only** when `GOOGLE_DRIVE_CLIENT_ID` is set. To enable it, create a Google Cloud project, enable the Google Drive API, create an OAuth client of type **"TVs and Limited Input devices"** (the app uses Google's device-code flow, `oauth2.googleapis.com/device/code`, scope `drive.appdata`), and set both secrets. The backup file name is now "TVXO Live TV backup.json". |
| Supporters / Ko-fi screen | nuvio.tv supporters API, Ko-fi links | **Disabled** (`AppFeaturePolicy.supportNuvioEnabled = false` in the `full` flavor). The About row and the navigation route are gone. |

## 10. Live TV source: Xtream only

Settings > Live TV > Playlists & Guides now offers only **"Add Xtream Codes Login"**: name, server, username, password, plus two include toggles. The "Add M3U Playlist" row was removed. The M3U dialog code remains, unreachable, so the diff stays small. Existing M3U entries, e.g. from a restored backup, still appear and can be edited or removed. The Live TV empty-state text now says "Add your Xtream Codes login in Settings › Live TV." The "TV Guides (EPG)" rows were kept: they add extra guide data, not channel sources, and an Xtream login brings its own guide.

## 11. Full change list (branch `tvxo-rebrand`)

- `app/build.gradle.kts`
  - applicationIds: `com.tvxo.tv`, `.debug`, `.test`, playstore `com.tvxo.tv.playstore[.debug]`
  - new signing from `TVXO_*` env vars or `keystore.properties`, with upstream fallback keys and passwords removed
  - `tvxoUpdateRepo` drives `GITHUB_OWNER` / `GITHUB_REPO`
  - nuvio.tv / worker URL defaults blanked
  - new `TVXO_PRIVACY_POLICY_URL` buildConfig field
- `gradle.properties`: `tvxo.updateRepo=tousantjr/TVXO`. `settings.gradle.kts`: root project name `TVXO`.
- `.gitignore`: keystore files and `keystore.properties`. New `keystore.properties.example`. Deleted `keystore/livetv-fork.jks`.
- `local.example.properties`: nuvio.tv URLs blanked.
- Strings: "Nuvio", "NuvioTV", "Nuvio TV", "NUVIO" and "Nuvio + IPTV" replaced with "TVXO" inside `<string>`/`<item>` values in 90 `values*/strings*.xml` files (1,945 replacements), plus all Tamil occurrences. The attribution strings `licenses_attributions_nuvio_*` were intentionally kept.
- User-visible Kotlin literals changed to TVXO: Live TV settings, guide, overlay, crash report screens, phone web pages under `core/server`, library, side-menu and sports settings, the logo User-Agent, and the Drive backup file name.
- `LiveTvSettingsContent.kt`: M3U add row removed; Drive card gated on `drive.available`; initial focus falls back to the Playlists section.
- `LiveTvGuideScreen.kt`: empty-state text is now Xtream only.
- `LiveTvCloudSync.kt`: Setup Sync disabled, worker URL removed.
- `AppFeaturePolicy.kt` (full): supporters/Ko-fi disabled.
- `AboutScreen.kt`: privacy-policy row only when `TVXO_PRIVACY_POLICY_URL` is set (it was hardcoded to nuvio.tv).
- `ReportServer.kt`, `LoadReportDialog.kt`: "Discord message" changed to "chat message".
- `UpdateRepository.kt`: test-channel fallback asset name `TVXO-Test.apk`.
- 124 PNG icons, banners and wordmarks regenerated from the TVXO logo (`branding/tvxo_logo_source.jpg`) by `scripts/tvxo_brand_art.py`, plus new adaptive icons (`mipmap-anydpi-v26`, 10 foreground PNGs, black background colour) and `branding/tvxo_assets_preview.png`.
- `.github/workflows`: removed from the published history (all upstream workflows and ours); our release workflow is kept, inactive, at `ci/tvxo-release.yml`. `.github/ISSUE_TEMPLATE`: homelessbrian link removed, names rebranded.
- `README.md` replaced: the upstream README with its Discord/Ko-fi badges and Downloader code was removed and is still in git history. New `NOTICE` and this guide.

## 12. Local build result (2026-11-08, Linux build machine)

Environment: JDK 17, Android SDK platform 36, build-tools 36.0.0, NDK 29.0.14206865, CMake 3.22.1, Go 1.27.3.
`local.properties` contained only `sdk.dir=...`, so no API keys were set.

- `./gradlew :app:assembleFullDebug` returned **BUILD SUCCESSFUL in 16m 43s** with exit code 0. Outputs:
  - `app-full-arm64-v8a-debug.apk` (85 MB)
  - `app-full-armeabi-v7a-debug.apk` (83 MB)
  - `app-full-universal-debug.apk` (187 MB)
- `aapt2 dump badging` on the arm64 APK:
  - package `com.tvxo.tv.debug`, versionCode 107600, label "TVXO Debug" in every locale
  - leanback launcher entries carry icons and banners whose pixels match the TVXO placeholder art (at that time, before the real logo was added)
  - signed with the standard Android debug key
- The arm64 APK contains `libffmpegJNI.so`, `libgav1JNI.so`, `libiamf*.so`, `libmpeghJNI.so`, `libmpv.so`, and the WireGuard/OpenVPN libs.

- Signed release test, using a throwaway key passed through the `TVXO_KEYSTORE_*` / `TVXO_KEY_*` env vars (the same path CI uses), deleted afterwards:
  - command: `./gradlew :app:assembleFullRelease -PlivetvBuildNumber=1 -PlivetvVersionName=1.1.0-beta.13.1`
  - result: **BUILD SUCCESSFUL in 16m 40s**
  - outputs: `app-full-arm64-v8a-release.apk` (64 MB), `app-full-armeabi-v7a-release.apk` (63 MB), `app-full-universal-release.apk` (166 MB)
  - arm64 APK: package `com.tvxo.tv`, versionCode 107601, versionName `1.1.0-beta.13.1`, label "TVXO"
  - `apksigner` shows the signer is the throwaway key, so the env-var signing works
  - generated BuildConfig: `GITHUB_OWNER="tousantjr"`, `GITHUB_REPO="TVXO"`; `SUPABASE_URL`, `SUPPORT_URL`, `TEST_UPDATES_URL` and `TV_LOGIN_WEB_BASE_URL` are all `""`
- The GitHub Actions workflow itself has **not** been run (the repo doesn't exist yet). Its YAML parses, and it uses the same Gradle command and signing env vars as the local release test.

To build locally yourself: install the same tools, put `sdk.dir=/path/to/sdk` in `local.properties`, and run the command above.

## 13. Known limitations (be aware)

- **Not tested on a TV.** None of these changes have been run on a device yet. Check: launcher icon and banner, Live TV add flow, playback, and the updater (install build N, publish N+1, check for update).
- **Codecs / players are upstream's current set, unchanged.**
  - media3 is pinned at **1.8.0**, while the newest stable on Google Maven is 1.11.1, checked 2026-11-08. `app/libs` contains custom-built media3 AARs (`lib-exoplayer-release.aar`, `lib-decoder-ffmpeg-release.aar`, av1, iamf, mpegh, …) built for that version. Upgrading means rebuilding those AARs and retesting playback, so it was not done.
  - `mpv-android-lib` **0.1.12** is already the latest on Maven Central.
  - The FFmpeg audio extension decodes vorbis, opus, flac, alac, pcm, mp3, amr, aac, ac3, eac3, dts (dca), mlp and truehd (`app/libs/README.md`).
- **Dolby Vision profile 7 conversion (DV7/libdovi)** is enabled only when `DOVI_NATIVE_ENABLED` and related keys are present in `local.properties`. Neither the upstream release workflow nor this one sets them, so CI builds behave like upstream's releases (DV7 bridge off).
- **Sports data** (`livetv/sports/SportsFeeds.kt`) still downloads public JSON from `raw.githubusercontent.com/homelessbrian/nuvio-data`. It shows no branding, but it is a third party's repository and could change or disappear. Fork that repo and change `DATA_URL` if you want control.
- Some identifiers sent to services still say Nuvio: HTTP User-Agent `Nuvio/<version>`, the Supabase device name "Nuvio TV", the Jellyfin/Emby client name, and the Simkl/TorBox app names. They are invisible in the UI and were left to avoid breaking service registrations.
- The unreachable Supporters screen code still contains contributors' Ko-fi URLs.
- Code comments still mention Nuvio, "Nuvio + IPTV" and homelessbrian. They are not user-visible, and the authorship context is preserved.
- No x86 APK, same as upstream.
- The `playstore` flavor was not built or tested.

### Logo artwork rebuild (2026-11-08)

After the real logo was added, an incremental `./gradlew :app:assembleFullDebug` returned **BUILD SUCCESSFUL in 2m 38s** with exit code 0. `app-full-arm64-v8a-debug.apk` is 91 MB.
- `aapt2 dump badging`: package `com.tvxo.tv.debug`, label "TVXO Debug". The application icon now resolves to the adaptive-icon XML (`mipmap-anydpi-v26`).
- `color/tvxo_launcher_background` = `#ff000000`.
- The banner, the xxxhdpi `iptv_ic_launcher` and the `tvxo_ic_launcher_foreground` inside the APK are pixel-identical to the generated files.

