# Releasing

QuickTube releases are built and signed in this repository. The owner publishes them; everyone else contributes through
pull requests to `main`.

## Branches

| Branch | Who can push | What happens |
|---|---|---|
| `main` | The owner directly; everyone else via pull request | CI builds the app and runs the unit tests |
| `production` | **Only the owner** | Pushing it triggers the **Release** workflow, which builds the signed APKs and publishes a GitHub Release |

## One-time signing setup

The release key is a JKS keystore at `C:\Users\abdul\.quicktube-keys\release.jks` (alias `quicktube`, RSA 4096). Its path and
passwords go in the git-ignored `local.properties`:

```properties
RELEASE_KEYSTORE_PATH=C:\\Users\\abdul\\.quicktube-keys\\release.jks
RELEASE_KEYSTORE_PASSWORD=<keystore password>
RELEASE_KEY_ALIAS=quicktube
RELEASE_KEY_PASSWORD=<key password>
```

**Keep a backup.** Every release must use the same key so installed apps can update in place; if the keystore or its passwords
are lost, users can't update in place and would have to uninstall and reinstall. Never commit the keystore, `local.properties`
or its passwords.

## Versioning

Every release is named and tagged `v<appVersionBase>.<versionCode>`, for example `v1.0.9`. `appVersionBase` lives in
`gradle.properties`; bump it (for example to `1.1`) for a feature release. In CI the version code is **1 + the highest last
number among the existing `v*` release tags** (`v1.0.8` → `9`), so updates always go forward. The Release workflow also accepts
an optional `version_code` input to override that. Local builds pass both numbers explicitly:

```powershell
.\gradlew.bat :app:assembleRelease -PappVersionCode=9 -PappVersionName=1.0.9
```

## Publishing

### A. Local build, uploaded by hand

1. Build the signed APKs with the next version code and name (see above). The outputs are in `app/build/outputs/apk/release/`.
2. Rename/copy them to the updater's naming scheme and generate a `.sha256` for each:
   - `app-arm64-v8a-release.apk` → `QuickTube-v1.0.9-arm64-v8a.apk`
   - `app-armeabi-v7a-release.apk` → `QuickTube-v1.0.9-armeabi-v7a.apk`
   - `app-universal-release.apk` → `QuickTube-v1.0.9.apk`
3. Create a draft release, upload the per-ABI APKs and their checksums, upload the universal APK and its checksum **last**, then
   publish it (that order matters: older updaters take the last `.apk` asset):

   ```powershell
   gh release create v1.0.9 --draft --title "QuickTube v1.0.9" --target main --generate-notes `
     QuickTube-v1.0.9-arm64-v8a.apk QuickTube-v1.0.9-arm64-v8a.apk.sha256 `
     QuickTube-v1.0.9-armeabi-v7a.apk QuickTube-v1.0.9-armeabi-v7a.apk.sha256
   gh release upload v1.0.9 QuickTube-v1.0.9.apk QuickTube-v1.0.9.apk.sha256
   gh release edit v1.0.9 --draft=false --latest
   ```

   A `.sha256` file is a `sha256sum`-style line: the lowercase hash, two spaces, then the APK file name.

### B. Automated (recommended)

1. Store the signing secrets in the repository **once**. The script reads them from `local.properties` and uploads
   `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`:

   ```bash
   scripts/setup-release-secrets.sh
   ```

2. Optionally pin the signing key: set the repository variable `EXPECTED_CERT_SHA256` to the release certificate's SHA-256
   fingerprint (Settings → Secrets and variables → Actions → Variables). The workflow then refuses an APK signed with a
   different key; without the variable it prints a warning and only runs the apksigner checks.
3. Publish by pushing `main` to `production`, or start the workflow by hand:

   ```bash
   git push origin main:production
   ```

   Or Actions → **Release** → **Run workflow** (optionally fill in `version_code`).

The workflow builds `:app:assembleRelease` with the computed version, verifies the signatures, creates the tag and a **draft**
release, uploads the per-ABI APKs and checksums, uploads the universal APK and its checksum last, and finally publishes the
release as **Latest**.

## Required release assets (in-app updater)

The app reads `https://api.github.com/repos/abdulrabbt/QuickTube/releases/latest`, so only a release that is **published**
(not a draft or pre-release) and marked **Latest** is offered. It must have:

- the tag `v<appVersionBase>.<versionCode>` (e.g. `v1.0.9`),
- at least one `*.apk` asset, optionally with a matching `*.apk.sha256`,
- for per-ABI updates: `QuickTube-v<version>-arm64-v8a.apk`, `QuickTube-v<version>-armeabi-v7a.apk`, plus the universal
  `QuickTube-v<version>.apk`.

## How in-app updates work

- Release builds check `/releases/latest` on launch (at most every 6 hours) and on Settings → **Check for updates**.
- The last number of the tag (the version code) is compared with the installed app's version code; a higher one shows the
  update sheet with the release notes.
- The download must have the same package name (`com.edureminder.quicktube`) and be signed with the **same key** as the
  installed app, or Android refuses to install it.
- The app downloads the APK matching the phone's ABI (or the universal one when there's no match) and verifies its SHA-256 when
  a `.sha256` asset exists.
- Android asks once for "install unknown apps" for QuickTube; after the update the downloaded APK is deleted and downloads,
  history and settings are kept.

## FFmpeg video decoder pack (prerequisite)

The on-demand FFmpeg video decoder pack is published as a **pre-release** tagged `ffmpeg-video-<nextlib version>` in this
repository by `scripts/publish-ffmpeg-pack.sh` (run it whenever `nextlib` changes in `gradle/libs.versions.toml`). It is not an
app release: the in-app updater ignores pre-releases (`/releases/latest` skips them) and only downloads the pack for playback.

## Testing an update locally, without publishing

Build with the update URL pointed at a local server:

```powershell
.\gradlew.bat :app:assembleRelease -PappVersionCode=99 -PappVersionName=1.0.99 -PupdateApiUrl=http://localhost:8765/latest.json
```

Put a GitHub-release-shaped `latest.json` next to the APK and its `.sha256` in one folder, then serve it and forward the port to
the phone:

```bash
python3 -m http.server 8765
adb reverse tcp:8765 tcp:8765
```

`latest.json` needs `tag_name`, `draft`/`prerelease` set to false, and an `assets` array with the APK's `name`, `size` and
`browser_download_url` (e.g. `http://localhost:8765/QuickTube-v1.0.99-arm64-v8a.apk`); the `.sha256` asset name is the APK name
plus `.sha256`.

## Branch and tag protection

The repository has three rulesets (Settings → Rules, or `gh api repos/abdulrabbt/QuickTube/rulesets`):

| Ruleset | Targets | Rules | Bypass |
|---|---|---|---|
| Main quality gates | `main` | pull request required, the **Build & unit tests** check must pass, linear history | owner (direct pushes still work) |
| Protect release branches | `main`, `production` | no branch deletion, no force-push | none |
| Protect release tags | every tag | no tag deletion, no moving/force-updating an existing tag | none |

Tag **creation** is still allowed (needed for `v*` app releases, `desktop-v*` desktop releases and
`ffmpeg-video-*` decoder packs). If a branch or tag really must be corrected, temporarily switch the matching
ruleset to **Disabled** (Settings → Rules), fix it, and re-enable it right away. Never move or delete a published
app release tag: installed apps update from it.

## Notes

- Release builds from this repository have **no Firebase** unless `app/google-services.json` is added before building (then set
  the optional `GOOGLE_SERVICES_JSON` secret so the workflow restores it; Crashlytics and Analytics are documented in
  [PRIVACY.md](../PRIVACY.md)).
- Never commit the keystore or its passwords; `local.properties`, `*.jks` and `google-services.json` are git-ignored.
- Never force-push `production`, and never move or delete a published release tag — the in-app updater depends on it.
