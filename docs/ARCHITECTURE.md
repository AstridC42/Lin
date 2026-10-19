# How Lin works

A map of the code for people who want to change it. For why things are the way they are, the
comments in the code say it at the point it matters, and [the development log](history/BUILD-LOG.md)
has the longer story.

## Modules

| Module | What lives there | Depends on |
| --- | --- | --- |
| `core/` | Plain logic, most of it pure functions with JVM unit tests: web search and page reading, memory, persona, settings (DataStore), reminders, history, model catalog and downloads, text helpers, the instant-command matcher. | Android only where it must be (settings, files). |
| `engine/` | The turn loop and everything that talks to a model: on-device LiteRT-LM, Ollama and OpenAI-compatible servers, tool calling, reasoning, delegation, the MCP protocol. | `core` |
| `app/` | The Android app: Compose UI, the tools, voice, notifications, Android Auto, background workers, Hilt wiring. | `core`, `engine` |

Package names follow the modules: `com.lin.agent.core.*`, `com.lin.agent.engine.*`,
`com.lin.agent.*` (app).

## What happens when you send a message

`ui/ConversationViewModel.send()`:

1. **Instant commands.** `core/tools/quickCommand` matches plain commands ("flashlight on", "open
   camera") and the tool runs at once, with no model.
2. **The small model.** If MobileActions 270M is downloaded, `model/QuickActions` asks it, but only
   when the words point at one of its actions (`core/tools/MobileActions`). Its pick is translated
   into a Lin tool or discarded.
3. **The main model.** `model/ChatBackends.readiness()` picks the backend: the configured server
   if it answers, otherwise the on-device model. A `TurnLoop` (engine) then runs the conversation:
   the model streams text, asks for tools, the loop runs them and feeds the results back, until
   the model answers. Events (`LoopEvent`) drive the screen: tokens, thinking, tool cards, pictures.
4. The conversation is saved into the history (`history/ChatHistory`, a JSON file in app storage).

The system prompt is built per turn (`promptForToday`): today's date, the person's quiz answers
(`core/persona`), and a description generated from the tools actually on offer
(`engine/capabilityPrompt`), so the prompt cannot promise a tool that is not there.

## Tools

Each tool is an `AgentTool` (engine): a name, a description and parameters for the model,
`guidance` (a sentence of prompt about how to use it), and `run()`. The app's tools are in
`app/.../model/` and are collected in `model/Tools.kt`, together with tools from connected MCP
servers. People can switch any tool off in Settings → What Lin can do.

Conventions every tool follows:

- **Staged, not done**, for anything that reaches another person or is hard to undo: messages are
  drafted and wait for Send, calls open the dialer, calendar events open the calendar filled in.
- **Results tell the truth.** A tool that only opened a panel says so, and the model is told not to
  claim more. `ToolResult.Ok.text` is for the model; `shows` is for the screen.
- **Missing permissions are offered as a button** by the screen, not explained by the model.

## Backends

- `engine/OnDeviceBackend`: LiteRT-LM, trying GPU, then CPU (NPU builds where available).
- `engine/ServerBackend`: Ollama's API or OpenAI-compatible (vLLM, Unsloth, others). Handles
  thinking (`think`, inline `<think>` tags), retries without `think` on models that refuse it.
- `model/DelegateTool`: when on-device answers but a server exists, big jobs can be handed to it.

## Background work

| What | Where |
| --- | --- |
| Model downloads (resumable, foreground) | `core/models/ModelDownloadWorker` |
| Learning from finished conversations | `model/DigestWorker` |
| Routines ("Things Lin does on its own") | `model/StandingWorker` |
| Photo index for search by meaning | `model/PhotoIndexWorker` |
| Answering a reply from Android Auto | `car/CarChatWorker` |
| Reminders | `remind/ReminderBook` (AlarmManager) |

## Where data lives

All in the app's private storage; nothing is uploaded.

| Data | Where |
| --- | --- |
| Settings, quiz answers, keys you enter | DataStore (`core/settings`) |
| Conversation history | `files/history.json` |
| Memories and their vectors | `core/memory/MemoryStore` |
| Downloaded models | `files/models/` |
| Documents Lin writes | `files/` (see `core/documents`) |

## Photo cut-out

`ui/CutOutScreen` and `ui/CutOutViewModel`: the photo is decoded at up to 1280 px, each tap
becomes a positive or negative stroke for MediaPipe's interactive segmenter (`model/Segmenter`,
the Magic Touch model from `ModelCatalog.MAGIC_TOUCH`), and the per-pixel confidence it returns
is turned into soft edges, cropped, blurred and blended by plain functions in
`core/media/CutOut` - which is where the tests are.

## Layout on foldables

`core/fold/listPaneWidth` decides: under 600 dp the history is a drawer; wider, it is a pane
beside the conversation, about a third of the width, or exactly at the hinge when the phone is
half open like a book (`ui/Fold.rememberHingeLeftDp`). Tabletop posture keeps its own split:
conversation above the crease, composer below.

## The watch

`wear/` is a separate, small app (same `applicationId` and signing key as the phone app, which is
what lets Wear OS connect them). It cannot run Lin's models, so it is a remote:

1. The watch takes a question by voice (`RecognizerIntent`) or the watch's input picker
   (`RemoteInputIntentHelper`), finds the phone advertising the `lin_phone` capability, and sends
   it on `/lin/ask` (`watch/WatchLink`).
2. On the phone, `wear/WearBridge` (a `WearableListenerService`, woken even with the app closed)
   replies `/lin/working` and queues `wear/WatchWorker`, which answers with the same headless turn
   the Android Auto chat uses (`model/HeadlessTurn`) and sends the short form back on `/lin/answer`.
3. The watch shows it, reads it aloud if the question was spoken, or posts a notification if the
   app was closed. Watch conversations are kept in the phone's history under `watch-` ids.

The message format is `core/wear/WearProtocol`, compiled into both apps so they cannot drift.

## Android integrations

- **Assistant role**: `AssistActivity` and the `ASSIST` intent; `voice/` has dictation, speech and
  the optional "Hey Lin" wake word service.
- **Notifications**: `notifications/` reads notifications (with permission) and sends staged replies.
- **Screen**: `screen/LinAccessibility` reads the screen and types into other apps when allowed.
- **Shizuku**: `privileged/` sets switches Android keeps from ordinary apps.
- **Android Auto**: `car/LinCarService` (a templated car screen) and `car/CarChat` (a messaging
  conversation the car reads aloud).

## Conventions

- Kotlin, 2-space indent, Compose for all UI, Hilt for injection.
- Logic that can be pure goes in `core/` with a test.
- Comments explain *why*, not what. If a decision was bought with a bug, say which.
- User-facing text goes in `app/src/main/res/values/strings.xml`.
