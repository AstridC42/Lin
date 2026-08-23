# Releasing Star Storyteller

Every version from `v0.2.0` on is published on the repository's
[Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) with prebuilt APKs
attached. Nothing is built by hand.

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

`versionCode` is packed from the semantic version as `major * 10000 + minor * 100 + patch`, so
`0.2.0` is `200` and `1.4.12` is `10412`. It only ever increases as long as no component goes past
99, which keeps Android's upgrade check happy without anyone tracking a separate counter.

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

Signing is optional, and the workflow works without it — the release falls back to the Android
debug key. That APK installs fine, but it cannot be installed *over* a differently signed copy, so
users have to uninstall first. Every unsigned release says so in its notes.

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

`site/index.html` is published to GitHub Pages by the `Deploy static content to Pages` workflow,
alongside the `skills/` directory at `/skills/`. The page reads the latest release from the GitHub
API when someone opens it, so a new release shows up on it without the workflow running again — you
only need to touch the page when the page itself changes.

**Pages has to be switched on once.** The workflow passes `enablement: true` to
`actions/configure-pages`, which creates the Pages site on its first successful run. If that is
refused — some org policies do not allow it — turn it on by hand under **Settings → Pages →
Build and deployment → Source: GitHub Actions**, then re-run the workflow. Without either, the run
fails at *Setup Pages* with `Get Pages site failed … Not Found`.

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
