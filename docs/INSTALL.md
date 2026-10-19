# Installing Lin

No computer needed: everything below works from a phone. Lin needs **Android 12 or newer**.

## Get the APK

Open the [latest build](../../../releases/latest) on the phone and tap the APK for your
phone:

| File | For |
| --- | --- |
| `lin-…-arm64-v8a.apk` | **Take this one.** Nearly every phone made since 2018. |
| `lin-…-universal.apk` | Works on anything, larger download. |
| `lin-…-armeabi-v7a.apk` | Older 32-bit phones. |
| `lin-…-x86_64.apk` | Emulators and x86 tablets. |

Every change to `main` publishes a new build (`lin-0.1.0-dev.…`); the newest three are kept.
Numbered releases (`lin-v…`) appear on the
same page when a version is worth naming. Each release lists the signing certificate's SHA-256
and a `SHA256SUMS.txt` to check the download against.

## Installing the APK

Android blocks apps that did not come from the Play Store until you allow it, once, per app that
does the installing.

1. Tap the downloaded `.apk`.
2. Android says the source is not allowed. Tap **Settings**.
3. Turn on **Allow from this source** — you are allowing your browser or file manager, not the app.
4. Go back and tap **Install**.

### Samsung: Auto Blocker

On Samsung phones this is usually the real culprit, and it is **not** Play Protect. One UI 6.1 and
newer ship **Auto Blocker** turned on, and it blocks installing apps from outside the Play Store
outright. Its message talks about a harmful app, which sends everyone looking at Play Protect
instead — where nothing they change makes any difference.

Open **Settings**, search for **Auto Blocker**, and turn off either the whole thing or just **Block
app installs from unauthorised sources**. While you are there, **App security check** in the same
screen can also quarantine an app after it installs, so turn that off for the install too.

Install, then turn Auto Blocker back on if you like — switching it on again does not remove an app
that is already installed.

On One UI 5 and 6.0 there is no Auto Blocker; the setting is **Settings → Biometrics and security →
Install unknown apps** instead.

### Play Protect blocking it

The most common thing to hit. Play Protect checks every install against Google's catalogue, and an
app that is not in it — signed by a developer it has never heard of — gets **"Unsafe app blocked"**
or *"Play Protect doesn't recognise this developer"*. It is not a judgement about the app; every
sideloaded build gets it, and nothing inside an APK can prevent it.

Two ways past:

1. On the blocking dialog, tap **More details**, then **Install anyway**. The link is deliberately
   small and easy to miss. Depending on the Android version it may read *Install without scanning*
   or offer **Send for scanning** first — scanning is fine, it just takes a minute and then lets
   you continue.
2. If there is no such option, turn the scan off for the install: **Play Store** → your profile
   picture → **Play Protect** → the gear icon → turn off **Scan apps with Play Protect**. Install,
   then turn it back on. Leaving it off is not worth it for one app.

### Other things that trip people up

- **"App not installed"** usually means a copy signed with a different key is already there.
  Uninstall the old one and try again — note that uninstalling deletes the downloaded models.
  Every test build carries its signing key's fingerprint in the release notes, and a build whose
  key has changed says so at the top.
- Lin installs as `com.lin.agent`, so it sits alongside any other assistant app.
- **A message about a *harmful* app, with no way to continue**, is a hard block rather than a
  warning. Google offers no override for that classification and neither does Samsung's, so
  tapping is not the answer — the switch above is.
- **"There was a problem parsing the package"** means the download did not finish. Check it against
  `SHA256SUMS.txt` on the release, or just download it again.

## First run

1. **Give Lin a model.** Either:
   - **On the phone:** tap **Get a model** and download Gemma 4 E2B (about 2.6 GB; use Wi-Fi).
     E4B is better and larger, for phones with 12 GB of RAM or more.
   - **Your own server:** Settings → Model server. Choose Ollama, vLLM or Unsloth (for another
     OpenAI-compatible server, choose vLLM and give its address), enter
     the address (for example `http://192.168.1.50:11434`) and pick a model. Lin falls back to the
     phone's model when the server cannot be reached.
2. **Make it your assistant** (optional): Android Settings → Apps → Default apps → Digital
   assistant app → **Lin**. The assist gesture or holding the power button then opens Lin.
3. **Tell it about you** (optional): Settings → Who Lin is talking to. A short quiz for your name, tone,
   answer length, units and city. Nothing is set until you answer.
4. **Web search** works straight away through DuckDuckGo. Settings → Looking things up lets you use your
   own SearXNG, or Brave, Firecrawl or Tavily with your key.

## Optional extras

- **Long-term memory:** download EmbeddingGemma in Settings → Models on this phone, and turn on **Learn from
  conversations** on the Memory screen if you want Lin to keep facts from finished chats.
- **Instant small tasks (MobileActions 270M):** Google gates this model. Accept its licence at
  [huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions),
  create a Read token at [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens),
  paste it in Settings → Models on this phone, then download **MobileActions 270M**.
- **Android Auto:** Lin is not from the Play Store, so first enable Android Auto's developer mode
  (Android Auto settings → tap **Version** ten times → ⋮ → Developer settings → **Unknown
  sources**). Then add Lin to the car launcher, and/or turn on Settings → In the car → **Chat with
  Lin in Android Auto** to talk to it through the car's messaging ("Hey Google, read my
  messages", then reply).
- **Photo cut-out:** + → **Cut out from a photo**. The first time, Lin downloads Google's Magic
  Touch model (30 MB). Tap the object; switch to **Remove** to take parts away. Saved cut-outs go
  to Pictures/Lin.
- **Lin's own voice and hearing:** Settings → Voice → **Lin's own voice and hearing**. Download a
  voice (Piper Amy, 67 MB, or Kokoro, 103 MB) and a hearing model (Moonshine Tiny, 108 MB, English;
  or Whisper Base, 208 MB, many languages). Until then the phone's own speech services are used.
- **Widgets:** long-press the home screen → Widgets → Lin. **Ask Lin** is a bar to type or talk
  (make it taller for one-tap questions); **Last answer** shows your latest question and answer.
- **Talk in the background:** Settings → Voice → **Keep talking in the background**. A voice
  conversation then carries on with the screen off or in another app; a notification shows the
  microphone is in use, with Stop.
- **On your watch** (Wear OS 3 or newer: Galaxy Watch 4 and later, Pixel Watch): a watch cannot
  install an APK from a browser, so `lin-…-wear.apk` goes on through the watch's debugging:
  1. On the watch: Settings → About watch → Software information → tap **Software version** five
     times. Then Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**.
  2. From the phone, an installer app such as *Wear Installer 2* or *Bugjaeger* pairs with the
     watch over Wi-Fi and installs the APK. From a computer:
     `adb pair <watch-ip>:<pair-port>`, `adb connect <watch-ip>:<port>`, `adb install lin-…-wear.apk`.
  3. Open Lin on the watch, or add the **Ask Lin** Tile. Tap **Speak** or **Type**; Lin on the phone
     answers. The phone needs the same version of Lin (or newer).
- **More switches with Shizuku:** with [Shizuku](https://shizuku.rikka.app/) running, Lin can set
  Wi-Fi, Bluetooth, NFC and airplane mode directly instead of opening their panels.
