# Architecture

A map of what this fork owns, written because the fork is now large enough that
"where does X live" is not answerable by reading the directory names.

The Kotlin package is still `com.google.ai.edge.gallery`, deliberately: it keeps
upstream changes mergeable without a rename in every file. So the package name
tells you nothing about who wrote a file — this document does.

## The shape of it

Upstream Edge Gallery is a gallery of demos: one screen per capability, each
with its own model list, its own prompt, its own tools. Star Storyteller puts a
single conversation in front of all of them. That one decision is what every
directory below exists to serve.

The assistant is a `CustomTask` — the same extension point upstream uses for
Agent Chat and Tiny Garden — so the model manager, chat history, benchmarks and
the gallery work on it unchanged. What is different is that it is also the app's
start destination, and that every general-purpose model in the allowlist is
attached to it.

## Directories this fork owns

```
Android/src/app/src/main/java/com/google/ai/edge/gallery/
├── assistant/        the screen, and everything that configures it
├── tools/            what the model can actually do  (shared with upstream)
├── voice/            being summoned, listening, speaking, the pop-up
├── car/              Android Auto
├── search/           SearXNG
├── weather/          Open-Meteo
├── smarthome/        Home Assistant
├── calendar/         reading the time out of what a model wrote
├── messaging/        which messaging app was meant, and whether a number is safe
├── media/            which app music goes to
├── notifications/    the notification listener  (shared with upstream)
├── reminders/        the alarm that delivers a reminder
├── widget/           the home-screen widget
└── intents/          share targets and the assist intent  (shared with upstream)
```

### `assistant/`

The screen and its settings. The parts worth knowing about:

| File | What it is |
|---|---|
| `AssistantTask.kt` | The `CustomTask`. Builds the system prompt, owns the executor. |
| `AssistantPrompts.kt` | The system prompt. **The highest-leverage file in the fork** — see below. |
| `AssistantScreen.kt` | The chat, the mode bar, the dialogs. Wraps upstream's `AgentChatScreen`. |
| `AssistantRoute.kt` | What is shown before the chat: loading, crash, memory warning, no models. |
| `AssistantTools.kt` | Which tools exist right now. Reads settings, so the set changes with them. |
| `AssistantMode.kt` | The modes, as prompt fragments. A mode is a bias, not a different screen. |
| `AssistantManual.kt` | The Guide panel: what the app can do, and the buttons that set it up. |
| `AssistantSpeaker.kt` | Text to speech, markdown stripped, with reliable completion. |
| `VoiceCatalog.kt` | Making engine voice names (`en-us-x-sfg#male_1-local`) readable. |
| `CrashReport.kt` | Records the exception that killed the app, for the next launch to show. |

`AssistantPrompts.kt` deserves the warning. The prompt shares a token budget
with the tool schemas and the conversation, on a model whose whole budget is
around 4,000 tokens. Two separate bugs have lived in that file: one where the
prompt was long enough to leave nothing for the conversation, and one where the
skill list was positioned where a small model had stopped reading. Both looked
like the model being stupid. `SystemPromptBudgetTest` guards the first; the
ordering assertions in `AssistantPromptsTest` guard the second.

### `tools/`

One class per capability, each a LiteRT-LM `ToolSet` whose `@Tool` methods are
discovered by reflection. `AssistantTools` decides which are registered.

Two patterns here are load-bearing and easy to undo by accident:

**A missing permission is not an empty result.** Every tool that can be refused
reports the refusal and says how to fix it, separately from "there is nothing
there". Collapse the two and the model cheerfully reports an empty notification
shade to someone who simply has not granted access.

**A tool that cannot work is sometimes better present than absent.** When web
search has no instance configured, `WebSearchSetupTool` stands in under the same
name and explains what is missing. The alternative — leaving it out — was tried,
and produced a model that answered from memory with nothing anywhere to say a
search had not happened. `NotificationAccessSetupTool` does the same for
notifications, collapsing five tools into one while they cannot work, because
every schema is charged against that same budget.

### `voice/`

Everything about being talked to. `StarVoiceInteractionService` and its session
are what make the app selectable as the phone's assistant; the session is the
pop-up that appears when the power button is held. `WakeWordService` is the
always-listening option, kept separate and explicit because it holds the
microphone open and that is a real cost.

The session is plain Android views, not Compose. A voice interaction session is
not a lifecycle owner, so a `ComposeView` needs a hand-written owner shim before
it renders at all — and that failure would land on the one surface that has to
work when the phone is locked or in a car mount.

## Upstream files that were changed

Kept as few as possible. The ones that matter:

| File | Change |
|---|---|
| `customtasks/agentchat/AgentChatScreen.kt` | Gained optional parameters so the assistant can reuse it instead of forking 800 lines. Defaults keep upstream's screen identical. |
| `ui/modelmanager/ModelManagerViewModel.kt` | Attaches every general-purpose model to the assistant task; validates that a downloaded file is actually a model; pins the allowlist version. |
| `ui/navigation/GalleryNavGraph.kt` | The assistant is the start destination; the gallery is one tap away. |
| `skills/SkillExtensions.kt` | The skill list is written as the call that loads it, one line each. |
| `tools/LoadSkillTool.kt` | A description that says when to use it. "Loads a skill" did not. |
| `data/DataStoreRepository.kt` | The four Star Storyteller settings readers share one snapshot instead of going to disk on every call. |
| `proto/settings.proto` | `AssistantSettings`, `WebSearchSettings`, `SmartHomeSettings`, `Reminders`. |

## Tests

Unit tests only — no instrumentation, because CI has no device. That shapes what
is testable: the pure decision in the middle of a feature is extracted and
tested, and the Android plumbing around it is not.

What that looks like in practice: `WeatherCodes.describe` is tested and
`WeatherClient` is not; `CalendarTime.parse` is tested and `CalendarTool` is
not; `MessagingApps.whatsAppNumber` is tested and the intent that uses it is
not. In each case the extracted function is where being wrong is silent and
expensive — a forecast about different weather, an appointment on the wrong day,
a message written to a stranger who shares a phone number.

Run them with `./gradlew :app:testReleaseUnitTest` from `Android/src`. The release variant
is the only one with unit tests: AGP 9 builds them for a single variant, and `testBuildType`
names the one that ships.

## Releases

Every push to `main` rebuilds and replaces one rolling pre-release tagged `dev`,
with four ABI-split APKs. It clears the previous build's files first — left
alone they accumulated to seventy APKs across eighteen versions.

Builds are signed with a key that lives only in the Actions cache, so they
install over each other. `RELEASING.md` has the detail, including how to make
the key permanent.
