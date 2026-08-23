# Star Storyteller

An on-device digital assistant for Android, built as a fork of
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery).

Edge Gallery is a showcase: one screen per capability, each a separate
demo. Star Storyteller keeps every one of those capabilities and puts them
behind a single conversation, so the app can be used the way you actually use
an assistant — ask a question, drop in a photo, tell it to call someone — often
in the same message.

## What is different from upstream

| | Edge Gallery | Star Storyteller |
|---|---|---|
| Start screen | Capability gallery | The assistant chat |
| Capabilities | Separate screens: chat, ask image, ask audio, mobile actions, agent | One chat, with a mode button that biases it |
| Attachments | Only on the screens that support them | Images and audio in every mode |
| Web access | None | Live search through your own SearXNG instance |
| Device actions | A demo screen driven by a purpose-built 270M model | Real tools on any model: calls, texts, alarms, timers, maps, apps |
| System integration | Launcher icon | Also registers as the phone's digital assistant |
| Models | One list per capability | Every general-purpose model loadable from the chat |

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

## Using it as the phone's assistant

The app registers for `ASSIST` and `VOICE_COMMAND`, so it can be selected under
**Settings → Apps → Default apps → Digital assistant app**, after which the
assist gesture and the power-button hold open the chat.

It also accepts:

- **Share** (`SEND`, `SEND_MULTIPLE`) — share a photo or some text from any app
  and it arrives as the first message. The fastest way to get a picture in front
  of the assistant.
- **Selected text** (`PROCESS_TEXT`) — select text anywhere, share it here.
- **Search hand-off** (`SEARCH`, `WEB_SEARCH`).

Replies can be read out loud with the platform text-to-speech engine; markdown
is flattened first so it does not read asterisks aloud.

## Download

Every version is published on the
[Releases page](https://github.com/AstridC42/Star-Storyteller-/releases) with prebuilt APKs
attached. Take `arm64-v8a` unless you know your phone is something else, or `universal` if you
would rather not think about it. See [RELEASING.md](RELEASING.md) for how releases are cut.

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

New code:

```
Android/src/app/src/main/java/com/google/ai/edge/gallery/
├── assistant/          the unified screen: modes, prompts, TTS, intents, wiring
├── search/             SearxngClient
└── tools/
    ├── WebSearchTool.kt      webSearch + openWebPage
    └── DeviceActionsTool.kt  calls, texts, alarms, apps, maps, torch
```

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
