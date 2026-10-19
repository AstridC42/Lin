# Building Lin

## Requirements

- **JDK 21**
- **Android SDK** with platform `android-37.0` (compileSdk is 37, minSdk 31)
- Android Studio (latest stable) is the easiest way, but not required

## With Android Studio

1. **File → Open** the repository root (the folder with `settings.gradle.kts`).
2. Let Gradle sync. If Studio offers to install SDK Platform 37, accept.
3. Run the `app` configuration on a phone with USB debugging, or an emulator (x86_64).

On-device models need real hardware to be useful; an emulator is fine for UI work with a model
server configured in Settings.

## From the command line

```bash
# Android SDK command-line tools, if you do not have the SDK yet
export ANDROID_HOME=~/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --licenses
latest/bin/sdkmanager "platforms;android-37.0"

# Lin
cd path/to/lin
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

The debug APKs land in `app/build/outputs/apk/debug/`, one per ABI plus a universal one.
The watch app is `./gradlew :wear:assembleDebug` (`wear/build/outputs/apk/debug/`). The first
build downloads a few GB of dependencies; later ones take a couple of minutes. On macOS use the
`-mac-` tools archive; on Windows use `gradlew.bat`.

## Tests

```bash
./gradlew :core:testDebugUnitTest :engine:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintVitalRelease   # the lint checks a release build runs
```

Most logic lives in `core/` as plain functions precisely so it can be tested on the JVM without a
device. Two things to know when writing tests there:

- Unit tests run against the desktop `org.json`, which returns `""` for a missing string where
  Android's returns `"null"`. Code that reads JSON guards for both.
- A test is only useful if it fails when the code is wrong. When you add one, break the line it
  covers and check that the test goes red before you restore it.

## Release builds

`./gradlew :app:assembleRelease` signs with the debug key unless these environment variables
point at a keystore: `LIN_KEYSTORE_PATH`, `LIN_KEYSTORE_PASSWORD`, `LIN_KEY_ALIAS`,
`LIN_KEY_PASSWORD`. `LIN_VERSION_NAME` and `LIN_VERSION_CODE` set the version. Published builds
are made by CI; see [RELEASING.md](RELEASING.md).
