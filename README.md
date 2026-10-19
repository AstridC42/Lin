<p align="center">
  <img src="docs/images/lin-mark.svg" width="96" alt="Lin">
</p>

<h1 align="center">Lin</h1>

<p align="center">
  <b>A private AI assistant for Android that runs on your phone or on your own server.</b><br>
  Ask, search, and get things done on the phone, with no account, no cloud and no tracking.
</p>

<p align="center">
  Built by <a href="https://github.com/AstridC42"><b>AstridC42</b></a>
</p>

<p align="center">
  <a href="../../releases/latest">Download</a> ·
  <a href="docs/INSTALL.md">Install guide</a> ·
  <a href="docs/ARCHITECTURE.md">How it works</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

---

Lin is an open-source replacement for Google Assistant and Gemini. It answers in a chat (or by
voice), and it can act on the phone: turn on the flashlight, open apps, set reminders, read and
draft messages, search the web and read the pages it finds, add calendar events, play music and
more.

The model that answers is yours. Lin runs **Gemma on the phone itself**, or talks to **your own
server** (Ollama, vLLM, Unsloth or another OpenAI-compatible server). Nothing goes to
a company's cloud unless you point it there.

## Features

**Conversation**
- Chat with streaming answers, Markdown, sources as chips, copy / read aloud / retry / share.
- Conversation history in a side drawer, with search.
- Attach photos and documents (PDF, Word, text).
- **Photo cut-out:** tap anything in a photo to cut it out, save it as a transparent PNG, blur
  the background, share it, or ask Lin about it. Runs on the phone (Google's Magic Touch model).
- Voice in and out; Lin can be set as the phone's default digital assistant. Voice conversations
  can carry on in the background, with the screen off or another app open.
- Home screen widgets: an "Ask Lin" bar (type, talk, or one-tap questions) and Lin's last answer.
- Shows the model's thinking when the model supports it.

**Acting on the phone**
- Flashlight, Wi-Fi, Bluetooth, Do Not Disturb, airplane mode; open any app by name or by kind
  ("open gallery", "open clock").
- Reminders that notify, timers and alarms, calendar events, contacts, calls (staged by default).
- Read notifications and draft replies; nothing is sent until you tap Send.
- Find photos by what is in them, read the screen, navigate, weather, media controls.
- Simple commands ("flashlight on", "open camera") run **instantly**, without waiting for a model.

**Knowledge**
- Web search with fallbacks: DuckDuckGo (no setup), SearXNG, Brave, Firecrawl, Tavily, Wikipedia.
- Reads the top pages, answers from their content with numbered citations, and shows pictures.
- Long-term memory: remembers facts you tell it, and can learn from finished conversations.
- A short "about you" quiz sets tone, length, units and more.

**Everywhere**
- Android Auto: a car screen with a mic button, and a hands-free chat through the car's messaging.
- Wear OS (Galaxy Watch 4 and newer, Pixel Watch): ask from your wrist by voice or keyboard, with
  a Tile for one-tap access. The phone does the thinking; the watch shows the answer and reads it out.
- Routines: questions Lin asks itself on a schedule and notifies you with the answer.
- MCP client: connect Lin to any Model Context Protocol server for more tools.
- Optional Shizuku integration for switches Android normally keeps from apps.
- Built for foldables: unfolded, the chat list sits beside the conversation (split on the hinge
  when half open like a book); in tabletop mode the conversation goes above the crease.

## Models

| Where | What | Notes |
| --- | --- | --- |
| On the phone | Gemma 4 E2B / E4B (LiteRT-LM) | Downloads in the app, 2.6–3.7 GB. Works offline. |
| On the phone | MobileActions 270M (optional) | Handles short commands in about 0.3 s. Gated: needs a Hugging Face token. |
| On the phone | EmbeddingGemma (optional) | Memory and photo search by meaning. |
| On the phone | Magic Touch (optional) | Photo cut-out. 30 MB, downloads the first time you use it. |
| Your server | Any model on Ollama, vLLM, Unsloth or another OpenAI-compatible server | Settings → Model server. |

Each message goes to the fastest thing that can handle it: an instant command, then the small
on-phone model, then your main model.

## Install

Grab `lin-…-arm64-v8a.apk` from the [latest build](../../releases/latest) and open it on the
phone. Android 12 or newer. The [install guide](docs/INSTALL.md) covers the warnings Android and
Samsung show for apps from outside the Play Store, and the first-run setup.

## Privacy

- No account, no analytics, no ads. Lin has no servers of its own.
- Conversations, memory and settings stay on the phone, in the app's private storage.
- Lin talks only to what you configure: your model server, the search service you choose, and
  Hugging Face for model downloads. Each key you enter is stored on your phone only.
- Actions that reach other people (messages and calls) are staged for you to confirm.

## Build from source

```bash
git clone https://github.com/AstridC42/Lin.git
cd Lin
./gradlew :app:assembleDebug
```

Needs JDK 21 and the Android SDK (platform 37). See [docs/BUILDING.md](docs/BUILDING.md).

## Project layout

```
app/      Android app: UI (Jetpack Compose), tools, voice, car, notifications, DI (Hilt)
engine/   The turn loop, on-device (LiteRT-LM) and server backends, tool calling, MCP
wear/     The Wear OS watch app: a remote for Lin on the phone
core/     Plain Kotlin logic with unit tests: search, reading, memory, persona, settings, …
docs/     Install, building, architecture, releasing, and the development log
```

## Contributing

Contributions are welcome: bug reports, ideas and pull requests. Start with
[CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

Lin is licensed under the [Apache License 2.0](LICENSE). Models are downloaded by you from their
publishers and are covered by their own terms (for Gemma, the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms)).

## Author

Lin is created and maintained by [AstridC42](https://github.com/AstridC42).

## Thanks

Lin was born from [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery), which showed
what on-device models can do on a phone and where the idea for Lin started. Lin is now its own app,
written from scratch, and runs on Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
and [Gemma](https://ai.google.dev/gemma) models.

Lin is not affiliated with, endorsed by, or sponsored by Google.
