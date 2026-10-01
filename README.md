# Star Storyteller

An on-device digital assistant for Android, built as a fork of
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery).

Edge Gallery is a showcase: one screen per capability, each a separate
demo. Star Storyteller keeps every one of those capabilities and puts them
behind a single conversation, so the app can be used the way you actually use
an assistant — ask a question, drop in a photo, tell it to call someone — often
in the same message.

[docs/STATUS.md](docs/STATUS.md) is where the project actually is: what works,
what is built but unconfirmed, what cannot be done at all, and what is worth
doing next. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is the map of the code.

## What is different from upstream

| | Edge Gallery | Star Storyteller |
|---|---|---|
| Start screen | Capability gallery | The assistant chat |
| Capabilities | Separate screens: chat, ask image, ask audio, mobile actions, agent | One chat, with a mode button that biases it |
| Attachments | Only on the screens that support them | Images and audio in every mode |
| Web access | None | Live search through your own SearXNG instance |
| Device actions | A demo screen driven by a purpose-built 270M model | Real tools on any model: calls, texts, alarms, timers, maps, apps |
| System integration | Launcher icon | Also registers as the phone's digital assistant |
| Models | One list per capability | Every general-purpose model loadable from the chat, including one of your own |

Everything upstream shipped is still there and still works: the capability
gallery (now one tap away, from the grid button in the mode bar or the star in
the gallery's app bar), Prompt Lab, Tiny Garden, the scrapbook, benchmarks,
model import, skills, MCP servers, and chat history.

## The single chat screen

The chat is the upstream Agent Chat screen — the one with skills, MCP servers,
the JavaScript skill runtime and the progress panel — extended rather than
replaced. Above the text field is a mode bar:

- **Mode chip** — opens the mode picker.
- **Web chip** — SearXNG connection settings; tinted when web search is live.
- **Speaker** — reads replies out loud.
- **Grid** — the capability gallery.

### Modes

A mode does *not* take a capability away. It rewrites the system prompt so the
model reaches for the right thing first, and that is all it does — every mode
keeps the image picker, the voice recorder and the whole tool set. That is what
makes "look at this receipt and text Sam the total" work as one message.

| Mode | Biased toward |
|---|---|
| **Auto** (default) | Decides per message, and combines capabilities within one turn |
| **Chat** | Answering directly, no tools unless asked |
| **Media** | Reading attached photos, screenshots and voice clips closely |
| **Web** | Searching before answering, and citing what it found |
| **Device** | Calls, texts, alarms, timers, maps, app launches |
| **Agent** | Multi-step skills and MCP tools |

Switching mode rebuilds the model session but keeps the conversation, so it
behaves like changing a setting mid-chat rather than starting over.

### Loading any model from the chat

Every model in the allowlist that is usable for general chat is attached to the
assistant task, so the model chip in the app bar lists all of them — including
ones that are not downloaded yet, which offer to download in place. Imported
models show up there too. The choice is remembered across launches.

## Web search with SearXNG

Web search goes to a [SearXNG](https://docs.searxng.org/) instance that you
host. No commercial search API is involved, so queries only ever reach a server
you control — which is the point of running the assistant on-device in the
first place.

The model gets two tools:

- `webSearch(query, categories, timeRange)` — ranked results with titles, URLs
  and snippets.
- `openWebPage(url)` — the readable text of one result, for when a snippet is
  too thin.

Neither tool is offered to the model when no instance is configured, so it is
never in a position to pretend it searched.

**Setup.** Your instance must serve JSON. In its `settings.yml`:

```yaml
search:
  formats:
    - html
    - json
```

Then in the app: mode bar → **Web** (or Settings → Assistant → Web search),
enter the instance URL, enable it, and press **Test connection** — that runs a
real query, which is the only way to catch an instance that is up but only
serving HTML. An `Authorization` header field is there for instances behind an
authenticating reverse proxy.

## Device actions

Available to the model on any capable chat model, not just the 270M
mobile-actions demo model:

`findContact`, `placePhoneCall`, `sendTextMessage`, `setAlarm`, `setTimer`,
`openApp`, `showOnMap`, `getDirections`, `toggleFlashlight`,
`openSystemSettings`, `openUrlInBrowser` — plus upstream's `run_intent` for
email, calendar and scheduled notifications.

**Anything that reaches another person is staged, not sent.** A call opens the
dialer pre-filled and you tap to place it; a text opens the composer with the
draft and you press send. A misheard name should not be able to call the wrong
person. Direct dialing exists behind Settings → Assistant → *Let the assistant
place calls directly*, which also asks for `CALL_PHONE`; it is off by default.

Contacts are only read after you grant `READ_CONTACTS`, at the moment the
assistant first needs a number. When several people match a name, it asks
instead of guessing.

## Remembering you

Tell it to remember something — what you call people, how you travel, anything
worth not repeating — and it goes into every conversation afterwards. "Forget
that…" removes one.

It keeps twenty facts, newest first, and everything it has been told is listed
under **Memory** in the Guide panel, where you can add or delete lines yourself.
That list is worth a look now and then: the model writes most of these, and a
fact remembered wrongly gets repeated confidently until somebody corrects it.

## Staying ready

Everything that summons the assistant from outside the app — the power-button
pop-up, the widget, the car, the wake word — can only answer in place while a
model is loaded, and loading one takes tens of seconds. **Stay ready** in the
Guide panel keeps the loaded model in memory after you close the chat, so they
answer instead of opening the app.

It is off by default because it is not free: the model's memory, often two to
four gigabytes, stays claimed while it holds, and an ongoing notification says
so with a Stop button. Android can still reclaim it under real memory pressure,
and the pop-up falls back to opening the chat when it does.

## Using it as the phone's assistant

The app implements a full `VoiceInteractionService`, so it can be selected under
**Settings → Apps → Default apps → Digital assistant app**. Once it is, every
hands-free route the platform offers reaches it: the assist gesture, holding the
power button, a Bluetooth headset button and a car's voice control. Being
summoned that way opens the microphone straight away rather than landing on an
empty text box — what you say becomes the first message, even if the model is
still loading when you say it.

It also accepts:

- **Share** (`SEND`, `SEND_MULTIPLE`) — share a photo or some text from any app
  and it arrives as the first message. The fastest way to get a picture in front
  of the assistant.
- **Selected text** (`PROCESS_TEXT`) — select text anywhere, share it here.
- **Search hand-off** (`SEARCH`, `WEB_SEARCH`).

Replies can be read out loud with the platform text-to-speech engine; markdown
is flattened first so it does not read asterisks aloud.

### Wake word

Off by default, under the **Wake word** chip in the chat, or the quick-settings
tile of the same name.

Be aware of what it is. Android gives no third-party app a real low-power wake
word — `AlwaysOnHotwordDetector` only serves phrases the device's audio DSP was
built and enrolled with, which means the manufacturer's own. So this keeps the
microphone open and runs ordinary speech recognition over it. It costs
noticeably more battery than a hardware hotword, your phone shows the microphone
indicator the whole time, and a notification with a **Stop** button stays up
while it runs. The quick-settings tile is the fastest way to switch it off in a
room where it should not be listening.

The phrase defaults to "hey star" and can be changed. Matching is on whole
words, so it does not fire on "hey started the car".

### Android Auto

The car screen offers two things: start talking to the assistant, and toggle the
wake word. It is deliberately not the chat — Android Auto renders from fixed
templates with hard caps on text and refresh rate, and reading a model's
paragraph at speed is what those rules exist to prevent. The phone does the
listening and the speaking; the car screen is how you start without picking it
up.

A sideloaded app stays invisible in Android Auto until **developer mode** and
**Unknown sources** are enabled in the phone's Android Auto settings. That is
Android Auto's rule for apps outside the Play Store, not something the app can
arrange for itself.

### Notifications, media, reminders and the house

- **Notifications** — "what did I miss", read messages out, reply, dismiss. Needs
  notification access, granted from system settings; the Guide panel has the
  button. It is the most invasive permission on the phone, and nothing is read
  until it is granted.
- **Media** — play, pause, skip, volume, and what is playing, for whatever app
  owns the audio. Android puts this behind the same notification access.
- **Reminders** — kept by the assistant rather than handed to the clock app, so
  you can ask what is set and cancel it. They survive a reboot.
- **Smart home** — lights, switches, blinds, locks, thermostats and scenes
  through **Home Assistant**: paste its address and a long-lived access token
  into the Guide panel and press *Test connection*.

  **Google Home is not directly supported, and cannot be.** Google exposes no
  API for a third-party Android app to control devices in someone's Home
  structure without registration in the Google Home Developer Console, an OAuth
  consent screen and per-project approval — none of which a sideloaded app can
  satisfy. Nest and Cast devices are reachable through Home Assistant's own
  integrations for them, which arrive through the same tools.

### A widget, and the lock screen

There is a home-screen widget: tap the body to type, tap the microphone to talk.
It works whether or not the app is your default assistant, which the assist
gesture does not. The app can also open over the lock screen.

### Foldables

The app is no longer locked to portrait and handles fold and unfold without
being recreated, so unfolding mid-reply does not restart the screen or drop the
keyboard. On the wide inner screen the conversation is centred and capped at a
readable width rather than stretched across the whole display.

## Download

Every version is published on the
[Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) with prebuilt APKs
attached. Take `arm64-v8a` unless you know your phone is something else, or `universal` if you
would rather not think about it.

Between releases, every push to `main` leaves installable APKs under **Actions → Build Android
APK → Artifacts**.

[INSTALL.md](INSTALL.md) walks through all of it from a browser and a phone — no Android Studio, no
terminal — including cutting a release and setting up signing. [RELEASING.md](RELEASING.md) has the
mechanics.

## Building

```bash
cd Android/src
./gradlew assembleDebug
```

Requires JDK 21 and Android SDK platform 37. Everything else is fetched by
Gradle. `local.properties` needs `sdk.dir=/path/to/android-sdk`.

The app installs as `com.starstoryteller.assistant`, so it sits alongside
upstream Edge Gallery rather than replacing it.

## Where the changes live

Fourteen directories now, so the map lives in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) rather than here: what each one
owns, which upstream files were changed and why, the two patterns in `tools/`
that are easy to undo by accident, and why the tests cover the functions they
cover.

Upstream files were changed as little as possible. The largest edit is to
`customtasks/agentchat/AgentChatScreen.kt`, which grew a handful of optional
parameters so the assistant can reuse it instead of forking 800 lines; its
defaults keep the upstream Agent Chat screen behaving exactly as before.

The Kotlin package is still `com.google.ai.edge.gallery` so that changes can be
merged from upstream without a rename in every file.

## License and attribution

Apache License 2.0, inherited from Google AI Edge Gallery — see
[LICENSE](LICENSE) and [NOTICE](NOTICE). This project is not affiliated with or
endorsed by Google. The upstream commit this fork started from is recorded in
[`.upstream-commit.txt`](.upstream-commit.txt).
