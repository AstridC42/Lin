# Releasing Star Storyteller

Every version from `v0.2.0` on is published on the repository's
[Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) with prebuilt APKs
attached. Nothing is built by hand.

There are two kinds of release, and they do not interfere with each other:

| | Test build | Numbered release |
| --- | --- | --- |
| Tag | `dev`, moved to each new commit | `v0.2.3`, permanent |
| Published by | `prerelease.yml`, automatically | `release.yml`, when you ask |
| When | every change that lands on `main` | when a version is worth naming |
| Shows as | one pre-release, always the newest build | a normal release |

## Test builds

Nothing to run. Any change to `Android/**` that lands on `main` rebuilds the app and **updates the
`dev` pre-release in place**, so the top of the Releases page always holds the newest build of
`main`, named for the release it is heading towards — `0.2.3-dev.14`.

It is one pre-release rather than one per commit on purpose: there is a single place to look, and
no list of stale test builds to scroll past on a phone. To rebuild without a code change, run
**Actions → Test build → Run workflow**.

## Cutting a release

Push a tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

That is the whole process. The `Release` workflow then:

1. derives `versionName` (`0.2.0`) and `versionCode` (`20000`) from the tag,
2. runs the unit tests,
3. builds one APK per ABI plus a universal one,
4. writes release notes from the commits since the previous tag,
5. publishes the release with the APKs and a `SHA256SUMS.txt` attached.

If you would rather not touch git, run **Actions → Release → Run workflow**, type the version, and
the workflow creates the tag itself. Tick *pre-release* to mark it as such; a tag with a suffix
(`v0.3.0-beta1`) is treated as a pre-release automatically.

### Version numbers

Android refuses to install an APK whose `versionCode` is lower than the installed one, so the
number has to increase across test builds and numbered releases alike. Both workflows pack the
semantic version into the high digits and a build number into the low four:

| Version | `versionCode` |
| --- | --- |
| `0.2.3-dev.7` | `203 * 10000 + 7` = `2030007` |
| `0.2.3` | `203 * 10000 + 9999` = `2039999` |
| `0.2.4-dev.1` | `204 * 10000 + 1` = `2040001` |

`9999` is reserved for numbered releases, so a release always outranks every test build that led
to it. The build number is the workflow run number, which never repeats. This holds as long as no
version component goes past 99 and the run number stays under 9999; the workflow fails loudly
rather than silently wrapping if it ever gets there.

## What gets published

| Asset | For |
| --- | --- |
| `star-storyteller-<version>-arm64-v8a.apk` | Nearly every phone made since 2018 |
| `star-storyteller-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `star-storyteller-<version>-x86_64.apk` | Emulators and x86 tablets |
| `star-storyteller-<version>-universal.apk` | Any phone; roughly three times the size |
| `SHA256SUMS.txt` | Checksums for all of the above |

The split exists because the LiteRT and MediaPipe native libraries dominate the download. A
device-matched APK is about a third of the universal one.

## Signing

Signing is optional, and the workflows work without it — the build falls back to the Android debug
key. That APK installs fine, but it cannot be installed *over* a copy signed with a different key,
so anyone on the old build has to uninstall first, losing their downloaded models.

That matters more than it sounds, because Gradle *generates* the debug key wherever
`~/.android/debug.keystore` does not already exist — which on a fresh CI runner is every single
run. Left alone, every test build would be signed with a brand new key and every install would
mean an uninstall first. Both workflows therefore cache that keystore under a fixed key, so one
debug key is reused across builds and each one installs over the last as an ordinary upgrade.

The cache is a stopgap, not a fix: GitHub evicts a cache after a week without a hit, and the build
after that has a new key and needs one manual uninstall. Setting the four secrets below ends it
for good, and takes priority over the cached key automatically. Every unsigned build says which
situation it is in, in its own release notes.

To sign properly, create an upload key once:

```bash
keytool -genkey -v -keystore upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
base64 -w0 upload.jks           # macOS: base64 -i upload.jks
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 output above |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | the key password |

**Keep `upload.jks` and its passwords somewhere safe and backed up.** Losing them means every
future release is signed with a different key, and no existing install can upgrade to it.

## The download page

There is a ready-made download page at `site/index.html`. It is **not published** — GitHub Pages
needs either a public repository or a paid plan, and this repository is private, so the Pages
workflow was removed rather than left failing on every run. The
[Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) works on a private
repository and is where the APKs live.

To turn the site on later, once the repository is public or the account is on GitHub Pro:

1. Restore the workflow from history:
   `git show ddc4a8d:.github/workflows/static.yml > .github/workflows/static.yml`
2. Push it to `main`, then run **Actions → Deploy static content to Pages → Run workflow**.

It publishes `site/` at the root and `skills/` at `/skills/`. The page reads the latest release
from the GitHub API when someone opens it, so releases appear on it without the workflow running
again.

## Building a release locally

```bash
cd Android/src
STAR_VERSION_NAME=0.2.0 STAR_VERSION_CODE=200 ./gradlew assembleRelease
```

APKs land in `Android/src/app/build/outputs/apk/release/`. To use a real key locally, either set
`STAR_KEYSTORE_PATH`, `STAR_KEYSTORE_PASSWORD`, `STAR_KEY_ALIAS` and `STAR_KEY_PASSWORD`, or drop a
`keystore.properties` next to `Android/src/settings.gradle.kts` with a `storeFile=` line and set the
three password environment variables. `keystore.properties` and `*.jks` are gitignored.

Omitting the environment variables builds `0.1.0` / versionCode 1, which is fine for local testing
and never for something you hand to someone else.
