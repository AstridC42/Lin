# Installing Star Storyteller

You do not need Android Studio, a terminal, or a computer at all for the normal path. Everything
below can be done from a browser and a phone.

## Getting the app

Three ways, best first.

### 1. From a release — best for installing on a phone

Open the [Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) on the phone
itself and tap the APK. Release files download as-is, so Android offers to install it straight
away.

Which file:

| File | For |
| --- | --- |
| `…-arm64-v8a.apk` | **Take this one.** Nearly every phone made since 2018. |
| `…-universal.apk` | Works on anything, about three times the download. |
| `…-armeabi-v7a.apk` | Older 32-bit phones. |
| `…-x86_64.apk` | Emulators and x86 tablets. |

If there is no release yet, see *Cutting a release* below — it takes two clicks.

### 2. From a build — any commit, no release needed

Every push to `main` builds APKs and keeps them for 14 days.

1. **Actions** → **Build Android APK** → click the most recent successful run.
2. Scroll to **Artifacts** at the bottom.
3. Download `apk-arm64-v8a-<commit>`.

GitHub always zips artifacts, so you get a `.zip` — unzip it and you have the APK. Easy on a
computer, fiddly on a phone. Use a release when the target is a phone.

To build a commit that has not been built yet: **Actions** → **Build Android APK** → **Run
workflow** → pick the branch → **Run workflow**.

### 3. Build it yourself

Only if you want to. See *Building without Android Studio* at the end.

## Installing the APK

Android blocks apps that did not come from the Play Store until you allow it, once, per app that
does the installing.

1. Tap the downloaded `.apk`.
2. Android says the source is not allowed. Tap **Settings**.
3. Turn on **Allow from this source** — you are allowing your browser or file manager, not the app.
4. Go back and tap **Install**.

Two things that trip people up:

- **"App not installed"** usually means a copy signed with a different key is already there.
  Uninstall the old one and try again. This is common while releases are still unsigned — see
  *Signing* below.
- Star Storyteller installs as `com.starstoryteller.assistant`, so it sits **alongside** Google AI
  Edge Gallery rather than replacing it. Having both is fine.

Requires **Android 12 or newer**.

## First run

1. **Make it your assistant** — Settings → Apps → Default apps → Digital assistant app → Star
   Storyteller. The assist gesture and the power-button hold now open the chat.
2. **Pick a model** — tap the model chip at the top of the chat. It downloads in place. Gemma-4-E2B
   is a good first choice; the larger ones want a recent phone with plenty of RAM.
3. **Connect web search** (optional) — tap the **Web** chip and enter your SearXNG instance URL.
   The instance needs `json` under `search.formats` in its `settings.yml`; the **Test connection**
   button tells you if it does not.

## Cutting a release from the browser

No git needed:

1. **Actions** → **Release** → **Run workflow**.
2. Type the version, e.g. `0.2.0`. Tick *pre-release* if it is one.
3. **Run workflow**.

It creates the tag, builds the four APKs, writes notes from the commits since the last release, and
publishes it all. Takes 15–25 minutes.

## Signing, from the browser

Until a signing key is set up, releases are signed with Android's debug key. They install fine, but
**a debug-signed build cannot be upgraded to a properly signed one** — people have to uninstall
first. Worth doing before you give a build to anyone.

Normally this means running `keytool` locally. Instead:

1. **Actions** → **Create signing key** → **Run workflow**. Tick the confirmation box.
2. When it finishes, download the **signing-key-DOWNLOAD-THEN-DELETE** artifact.
3. Open `READ-ME-FIRST.md` inside and follow its three steps: save the keystore somewhere safe, add
   four repository secrets, delete the artifact.

Every release after that is signed automatically.

**Run it once.** A second key is a *different* key, and it strands everyone on the first one. Keep
`upload.jks` and its password backed up — losing them has the same effect.

## Building without Android Studio

Android Studio is an IDE around a command-line build; the build works without it. You need a JDK
and the Android SDK command-line tools — no IDE, no emulator.

```bash
# 1. JDK 21 (apt, brew, or https://adoptium.net)
sudo apt install -y openjdk-21-jdk        # or: brew install openjdk@21

# 2. Android SDK command-line tools
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=~/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.1.0"

# 3. Build
cd Android/src
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

APKs land in `Android/src/app/build/outputs/apk/release/`. The first build downloads a few GB of
dependencies and takes a while; later ones are a couple of minutes.

Use `assembleDebug` for a faster build while developing, and `testReleaseUnitTest` to run the
tests. On macOS swap the `commandlinetools-linux-…` URL for the `-mac-` one; on Windows use
`gradlew.bat`.
