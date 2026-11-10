# Releasing the AmazCGM phone app

Releases are built and signed on the maintainer's machine and published to [GitHub Releases](https://github.com/vstoianov/amazcgm-android/releases) with the `gh` CLI. One command does it all: `scripts/release.sh`.

## Files

| Path | Purpose |
|---|---|
| `scripts/release.sh` | Checks, version bump, tests, signed build, commit, tag, push, GitHub release |
| `scripts/gen-keystore.sh` | Creates the release signing key once |
| `scripts/changelog-notes.sh` | Prints one version's section of `CHANGELOG.md`; used as release notes |
| `CHANGELOG.md` | What changed in each version |
| `keystore.properties` | Local signing config. Git-ignored, never commit it |
| `keystore.properties.example` | Template for `keystore.properties` |
| `dist/` | Built APK and checksum. Git-ignored |

## One-time setup

1. Install the build prerequisites from the [README](README.md#1-prerequisites): JDK 17, Android SDK with build tools, `local.properties`.
2. Install the GitHub CLI and log in:

   ```sh
   sudo pacman -S github-cli      # or see https://cli.github.com
   gh auth login
   ```

3. Create the release key:

   ```sh
   scripts/gen-keystore.sh
   ```

   It writes `~/.android/amazcgm-release.jks` and `keystore.properties`, and prints the certificate SHA-256.

4. **Back up** the `.jks` file and its password somewhere offline, such as a password manager and an encrypted USB stick. Android only installs an update when it is signed with the same key. If the key is lost, users must uninstall the app to install a new build, and that deletes their stored unlock codes.
5. Put the certificate SHA-256 in the README **Download** section so users can check it.

Instead of `keystore.properties`, the build also reads the environment variables `AMAZCGM_KEYSTORE`, `AMAZCGM_KEYSTORE_PASSWORD`, `AMAZCGM_KEY_ALIAS` and `AMAZCGM_KEY_PASSWORD`. Without either, `assembleRelease` falls back to the debug key, which `release.sh` refuses to publish.

## Versions

- `versionName` is `MAJOR.MINOR` or `MAJOR.MINOR.PATCH`, for example `1.10` or `1.10.1`.
- `versionCode` goes up by one with every release. `release.sh` does this. Never reuse one: Android refuses to install a lower code over a higher one.
- The git tag is `v` + `versionName`, for example `v1.10`.
- Do not edit either value by hand.

## Release checklist

1. Make sure `main` holds everything for the release and is pushed.
2. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [1.10] - YYYY-MM-DD`, add a new empty `## [Unreleased]` above it, and update the links at the bottom. Commit and push.
3. Dry run. It tests, builds and signs into `dist/`, then undoes the version bump:

   ```sh
   scripts/release.sh 1.10 --dry-run
   ```

4. Install `dist/amazcgm-1.10.apk` on a phone and check it with a real sensor: scan, Share JSON, and **Sync from phone** on the watch.
5. Release:

   ```sh
   scripts/release.sh 1.10
   ```

   This commits `Release 1.10`, creates tag `v1.10`, pushes both, and creates the GitHub release with the APK, its `.sha256` file, and the changelog section as notes. It prints the release URL.

If any step fails before the commit, `app/build.gradle.kts` is restored and nothing is pushed.

## What release.sh checks

- Version format, and that the tag does not exist yet.
- On `main`, clean working tree, same commit as `origin/main`.
- `gh` logged in, JDK 17+, Android SDK and `apksigner` found, signing key configured.
- `CHANGELOG.md` has a section for the version.
- Unit tests pass (`testDebugUnitTest`).
- The APK is not signed with the Android debug key.

## Verifying a release

```sh
sha256sum -c amazcgm-1.10.apk.sha256
apksigner verify --print-certs amazcgm-1.10.apk
```

The certificate SHA-256 must match the one in the README.

## Fixing a bad release

Do not move or reuse a published tag. Delete the release and its tag, fix the problem, and release a new patch version:

```sh
gh release delete v1.10 --cleanup-tag --yes
git tag -d v1.10
scripts/release.sh 1.10.1
```

Leave the `Release 1.10` commit in history. Its `versionCode` is used up.

## First release as AmazCGM

Up to 1.9 the app was called Libre2 Key (`com.vstoianov.libre2key`) and was signed with each machine's debug key. 1.10 has a new application ID, `com.vstoianov.amazcgm`, and the release key. It installs as a separate app, so users uninstall Libre2 Key and scan their sensor again. The 1.10 changelog and the README say this.
