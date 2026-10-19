> **What this is.** The working log from Lin's first months, kept for the reasoning behind
> decisions. It was written as Lin was built: paths in it refer to the old `agent/` folder
> (now the repository root), and early phases describe plans that later changed. For how
> Lin works today, read [ARCHITECTURE.md](../ARCHITECTURE.md).

# Building Lin as its own app

A plan, not a promise. Written after deciding that the Edge Gallery fork is the
wrong chassis for what Lin is becoming — see *Why* below for the reasoning, and
*Risks* for the two things that could make this the wrong call.

## The shape of it

One chat screen. A small model resident on the phone — Gemma 4 E2B — that
answers directly, routes, and holds the conversation. Behind it, two kinds of
help it can reach for without the user asking:

- **A bigger model on a self-hosted server** (Ollama on the LAN), given a
  written brief for a task the 2B cannot do well, running as a sub-agent with
  its own tools and its own budget, returning a result the orchestrator folds
  into its reply.
- **The phone itself**, through a tool layer. MobileActions-270M was planned
  as an intent parser in front of it; see Phase 4 for why that is deferred
  rather than built — the validation it was meant to justify now exists on
  its own.

Everything is local in the sense that matters: the phone, or hardware the user
owns. Nothing goes to anybody's cloud.

## Why not keep adapting the fork

The Gallery's central abstraction is **Task × Model → Screen**: pick a task,
pick a model, load it, do one thing, unload. It is a model-demo harness, and a
good one. An assistant needs close to the inverse — one long-lived
conversation, several specialised models resident at once, tools chosen per
message, work continuing in the background.

Almost every problem that cost real time in the fork traces to that single
mismatch rather than to bloat:

| Symptom | Root cause |
|---|---|
| 44 tool functions, ~3,200 tokens of schemas per turn | One conversation per task, so every capability registers on it at once |
| `Input token ids are too long: 4230 >= 4000` | Context sized by an allowlist written for a chat demo |
| Direct calling existed but was unreachable for weeks | Settings belong to a model-centric UI an assistant user never opens |
| A callback threaded through four layers to place one gear | The chat screen is a task screen |

The decisive point: **this architecture dissolves the tool-budget problem
rather than managing it.** Not because there are fewer tools, but because each
agent only ever sees its own. The orchestrator needs about five. A sub-agent
gets the task-specific set and then dies. The dynamic tool selection work that
has sat at the top of `STATUS.md` for weeks stops being necessary.

## Decisions taken

Reversible unless marked otherwise.

- **Same repository, new top-level directory.** Lifting the engine wrapper is a
  file copy rather than a dependency; one CI, one signing key. Splitting it
  into its own repository later is a `git filter-repo` away.
- **A new application id: `com.lin.agent`.** Originally so that both could be
  installed side by side while this one was a skeleton. That reason has gone -
  the fork is not to be installed at all any more - but the id stays, because
  changing it now is churn with nothing gained. `com.lin.assistant` is free if
  it is ever wanted.
- **The fork is retired, not just unmaintained.** Source only: it is kept in
  the repository as reference material - engine setup, device quirks,
  HuggingFace auth, semantic retrieval, and a hundred small Android details
  easier to read than to rederive - and it is not built, released or installed.
  The workflows that built and published it are reduced to manual dispatch, so
  they cannot fire on their own, and `Android/ARCHIVED.md` says what each part
  of it is still good for.

  Two consequences worth stating. The open crash is still worth diagnosing, but
  only for what it *tells* us: if it is LiteRT-LM on this device, the new app
  inherits it, and Phase 1 needs to know. And PR #37, the upstream port, is
  still worth merging, because Instant Media Search is the reference
  implementation of the embedder and vector store this app is about to need.
- **Toolchain matched to the fork**, so lifted code compiles unchanged: Gradle
  9.2.1, AGP 9.0.1, Kotlin 2.2.21, JDK 21, compileSdk/targetSdk 37, minSdk 31,
  Compose BOM 2026.02.00, Hilt 2.60.1, DataStore 1.1.7, WorkManager 2.12.0,
  LiteRT-LM 0.18.0.
- **`testBuildType = "release"`.** Not a style choice: AGP 9 generates unit
  tests for one variant only, and the fork's CI failed on a missing
  `testReleaseUnitTest` until this was set.
- **Three modules, no more** (not reversible without pain, so chosen
  deliberately): `app`, `core`, `engine`. The one boundary worth enforcing from
  the start is that nothing outside `engine` knows what a LiteRT-LM `Engine`
  is. Over-modularising a solo project early is its own trap.

## Non-goals for v1

Stated so they can be pointed at later rather than argued about. None of the
gallery tasks *as separate screens*: no Prompt Lab, no benchmarking, no model
browsing or comparison, no Tiny Garden, no Scrapbook. No multi-model picker UI.
No image generation.

**Media is in scope, and so is semantic search over it** - but as capabilities
of the one assistant, not as a task you navigate to. Attaching an image or a
clip, asking about it, and searching the library by describing what you want
are things Lin does in the conversation. Instant Media Search is the reference
implementation, not the destination.

The focus is the thing the fork could not be: one assistant that is good at
being an assistant.

## The device it has to run on

A **Samsung Galaxy Z Fold 7**, confirmed rather than assumed, because three of
its properties change decisions here.

| | |
|---|---|
| SoC | Snapdragon 8 Elite for Galaxy, **SM8750** |
| RAM | 12 GB (256/512 GB models) or 16 GB (1 TB) |
| Main display | 8.0", 1968 x 2184, 120 Hz |
| Cover display | 6.5", 1080 x 2520, 120 Hz |
| OS | One UI 8, Android 16 |

**SM8750 is in the NPU variant list.** Of the ten SoC-specific embedder builds,
one is exactly this chip. So if the NPU path becomes usable, this device is
covered - which moves NPU from "interesting someday" to "worth re-testing when
the runtime story improves". It is still gated on the vendor runtime being
present; see below.

**12 GB, confirmed.** That is exactly Gemma 4 E4B's documented minimum, so E4B
is viable rather than blocked - but exactly is not comfortably. 3.66 GB of
weights, plus an 8,192-token KV cache, plus One UI on a Fold 7, is the
footprint where Android's low-memory killer starts taking an interest. And an
OOM kill runs no Java code on the way down, so it produces no stack trace and
looks from the outside exactly like the app vanishing for no reason.

So **E2B (2.59 GB) is the default** and E4B is a setting with that said plainly
next to it. This is also the reason the embedder is transient rather than
resident: holding another 484 MB between indexing bursts is 484 MB of headroom
given away for nothing.

**It is a foldable, so adaptive layout is a requirement, not a nicety.** 8
inches unfolded is tablet-class and 6.5 folded is phone-class, and the app has
to survive moving between them mid-conversation. Two consequences:

- The large screen should earn its size - a conversation pane beside a working
  pane showing tool activity, sub-agent progress, and media. The fork's
  `AssistantTwoPane` is the right idea to carry over.
- **Folding is a configuration change.** One UI has a setting to keep an app
  running across the fold, and it is switched on here, which handles the app
  being stopped. It does not make the app keep its own state: Android still
  recreates the activity on other configuration changes, on memory pressure and
  after process death, and the fork's `MainActivity` passes `null` instead of
  the saved instance state - "start cleanly after an OS kill" - so it would
  discard the conversation whenever that happened.
  
  This app does both sides: `configChanges` declares the fold-related ones so
  the activity is not recreated at all, and the conversation lives in
  `SavedStateHandle` so it survives the cases where it is.

## The models

Verified against upstream's `1_0_20` allowlist and the Hugging Face Hub on
7 October 2026, not from memory.

### On the phone

| | Size | Min RAM | Context | Modalities |
|---|---|---|---|---|
| **Gemma 4 E2B** | 2.59 GB | 8 GB | 32,000 | text, image, audio |
| **Gemma 4 E4B** | 3.66 GB | 12 GB | 32,000 | text, image, audio |

Both offered, E2B the default. **E4B wants 12 GB of RAM**, worth checking
against the actual phone before making it the default, because below that the
memory warning fires and it is right to.

Neither has an NPU build: both are `gpu,cpu` with no SoC variants.

### The embedder

EmbeddingGemma 2 is a family, not one model. All three map into the **same
768-dimensional space** with an 8,192-token context, int4 QAT transformer and
embeddings:

| | Modalities | Params | Generic file | SM8750 NPU build |
|---|---|---|---|---|
| Text 270M | text | 270M | 164 MB | 179 MB |
| Text-Vision 440M | text, images | 440M | not measured | yes |
| **740M** | **text, images, video, audio** | 740M | **484 MB** | **518 MB** |

**The 740M, because media search is in scope.** An earlier draft of this plan
picked Text 270M on the grounds that memory notes are text. That was wrong once
media and semantic search over it became requirements rather than later
features.

The 740M is not a different model so much as a superset: it is the same 270M
text backbone with a 170M vision encoder and a 300M audio encoder bolted on,
and everything lands in one shared vector space. So a single embedder serves
both memory and media, and - the useful part - they are searchable *together*.
"the photo I was talking about on Tuesday" is one query across notes and
images, not two queries and a join.

### Model lifecycle: one resident, one transient

The chat model stays loaded. The embedder does not, and should not.

Embedding is bursty: a library indexing run, or a search, then nothing for
hours. Keeping 518 MB resident between those is 518 MB taken from the chat
model's KV cache for no benefit. So the embedder is loaded on demand and
released when idle.

This is only straightforward because we own the model lifecycle. It is exactly
what the Gallery's one-model-at-a-time design made awkward, and it is a concrete
example of why the spine is worth rewriting.

### NPU: the files are real, the path is not ready

The NPU builds do exist. Both embedder repos carry ten SoC-specific files
alongside the generic one: Qualcomm SM8550, SM8650, SM8750, SM8850, QCS8275;
MediaTek MT6991, MT6993; Google Tensor G5, G6; Intel PTL.

Three things make this an experiment rather than a v1 feature:

1. **The vendor runtime has to be on the device.** Qualcomm NPU through
   LiteRT-LM needs QAIRT runtime libraries present, and Google's own guide pins
   a specific version. An app cannot assume they are there.
2. **The documented path is `adb` and a CLI binary**, pushing the dispatch
   library and running `litert_lm_main --backend=npu`. That is a research
   workflow, not an app integration. A secondary source describes Gemma NPU
   acceleration on Qualcomm and MediaTek as an early-access programme; treat
   that as unconfirmed.
3. **Upstream shipped it GPU/CPU only.** Google wrote both the model and the
   Gallery and still set `accelerators: "gpu,cpu"`, `variantLabel: "GPU"`, no
   SoC mapping. That is the most telling evidence available about readiness.

So: **GPU/CPU in v1**, with the model registry designed to carry a SoC-to-file
mapping from the start, so turning NPU on later is data rather than a refactor.
This reverses one line of the table below: a minimal SoC variant mechanism is
needed after all, just not the Gallery's allowlist machinery around it.

## Architecture

### The one interface that matters

Everything reaches a model through this, and local versus remote stops being a
special case anywhere above it:

```kotlin
interface LlmBackend {
  val id: String
  val capabilities: BackendCapabilities   // tools, images, audio, context window
  suspend fun reachable(): Boolean
  fun converse(request: TurnRequest): Flow<TurnEvent>
}
```

Three implementations: `OnDeviceBackend` (LiteRT-LM), `OllamaBackend` (HTTP),
and `FakeBackend` for tests. That third one is the point — it makes the whole
agent loop unit-testable, which the fork's design made effectively impossible.

Embedding is a second, smaller interface, because it runs on MediaPipe rather
than LiteRT-LM, and because conflating them would put a vector store behind a
chat abstraction:

```kotlin
interface Embedder {
  val dimensions: Int                                   // 768
  suspend fun embed(parts: List<EmbedPart>): FloatArray
}
```

### The agent spine

```
core/agent/
  Orchestrator.kt     resident, on-device, ~5 tools, owns the conversation
  SubAgent.kt         ephemeral, any backend, task-scoped tools, own budget
  ToolRegistry.kt     tools declare which roles may see them
  Brief.kt            the structured delegation payload
  TurnLoop.kt         user message -> tool calls -> final reply
```

`ToolRegistry` is the structural fix: a tool declares `roles: Set<AgentRole>`,
and prompt assembly can only ever include tools for the current role. The
44-tool prompt becomes impossible to build by accident rather than by
discipline.

The orchestrator's entire tool set: `delegate`, `recall`, `phoneAction`, `ask`,
`answer`.

### The brief

The highest-risk component, so it is explicit rather than free text. A 2B model
summarising a conversation into a task description will drop the one constraint
that mattered; that is how most multi-agent systems fail.

```kotlin
data class Brief(
  val goal: String,
  val constraints: List<String>,
  val context: List<MemoryRef>,
  val expects: OutputShape,   // SHORT_ANSWER | LIST | DRAFT_TEXT | PLAN
  val budget: Budget,
)
```

Filled in by the orchestrator under **constrained decoding** — which LiteRT-LM
supports and the fork already enables — so the small model fills slots instead
of writing prose.

### Reliability floor, in the first commit

This is the lesson of the past week and it goes in before any features, not
after. An app that will not open cannot be debugged from a phone with no
computer attached.

- Every proto store gets a `ReplaceFileCorruptionHandler` from the moment it is
  created.
- No blocking disk read on the path before the first frame. Enforced as a rule,
  with the settings repository returning defaults rather than throwing.
- `CrashReport`, `SafeMode` and the launch breadcrumb ported immediately,
  including the copy of the report in `getExternalFilesDir` that a file manager
  can reach.
- `catch (Throwable)` around engine initialisation, because a model too large
  fails with `OutOfMemoryError`, which is an `Error` and not an `Exception`.

## What we lift, port, and write fresh

| Source | What | Where it goes |
|---|---|---|
| **Lift from the fork's upstream** | Engine setup and backend selection from `LlmChatModelHelper` — and specifically the device quirks: vision on GPU and audio on CPU for Gemma 3n, GPU disabled on Pixel 10, Tensor G3 handling. This is scar tissue, not design; it cannot be derived. | `engine/` |
| **Lift** | `HuggingFaceApiClient` and the AppAuth plumbing. Gemma is gated: it needs licence acceptance and a token. This is the one part of model acquisition worth taking. | `core/models/` |
| **Port (ours already)** | `memory/` entire — vault, retention, note merge, digest parser, archive. The least Gallery-coupled code we have; expect to rework the DataStore wiring and the digester's use of a live `Conversation`. | `core/memory/` |
| **Port (ours)** | `tools/` — device actions, the garbled-number guard, the loop breaker. | `core/tools/` |
| **Port (ours)** | `net/LocalNetwork.kt` and `search/SearxngClient.kt`. Private-host detection, the cleartext policy and the SSRF guard transfer directly and are exactly what the Ollama client needs. | `core/remote/` |
| **Port (ours)** | `CrashReport`, `SafeMode`, `StoreHealth`, `EngineErrors`. | `app/` |
| **Port, then rewrite** | The prompts. Shape carries over; content gets much shorter, because an orchestrator with five tools needs far less steering than one with forty-four. | `core/agent/` |
| **Write fresh** | The agent spine, the tool registry, the Ollama backend, the backend interface, model acquisition (two or three known files, resumable, SHA-256 verified), the UI. | — |
| **Write fresh, small** | A SoC-to-file mapping in the model registry. Needed after all, for the NPU embedder variants. A map and a lookup, not the Gallery's allowlist machinery. | `core/models/` |
| **Read, do not take** | Instant Media Search's `services/semanticretrieval`: the `UniversalEmbedder`, `SqliteVectorStore` and `SemanticRetriever` wiring. The reference for Phase 5, arriving via PR #37. | — |
| **Do not take** | The allowlist machinery, the task/model registry, every gallery screen. | — |

## Phases

Each one ends with an installable APK, tests, and a number. No phase is "done"
on the strength of it compiling.

### Phase 0 — A skeleton that opens

New Gradle project, three modules, Hilt, Compose, one screen that says hello.
CI building a signed APK. The entire reliability floor above.

**Deliverable:** installs alongside the fork and opens.
**Measurement:** survives a deliberately thrown crash — report written, safe
mode trips on the second failed launch, breadcrumb names the phase.
**Gate:** none. If this is not solid, nothing after it is diagnosable.

### Phase 1 — One model, one conversation

`LlmBackend` with `OnDeviceBackend` behind it. Gemma 4 E2B or E4B resident,
chosen in settings, streaming replies, one continuous conversation persisted
across restarts.

For development the model file is already on the phone from the fork, so point
at it and defer the download path to 1b. That removes HF auth from the critical
path of proving the engine works.

**Deliverable:** a working local chatbot.
**Measurement:** cold start to first token; conversation survives a restart;
memory held while resident.

### Phase 1b — Model acquisition — DONE

Resumable download into a `.part` file, length verification before the file
takes its real name, and a foreground worker so it survives the app being
backgrounded.

Two things planned here turned out not to be needed, and two unplanned things
were: the Gemma and EmbeddingGemma builds are Apache-2.0 and ungated, so there
is no licence screen and no token store; and length verification does the job
SHA-256 was there for, because the catalogue is code and the sizes are read off
the Hub. What was needed instead was the foreground service type in the
manifest — without it `startForeground` kills the process, and WorkManager does
not catch that — and the discovery that selecting an NPU build by chip name
fetches a file whose length can never match.

**Measurement:** a fresh install on a wiped device gets to a working model
without a computer.

### Phase 2 — The server backend

`OllamaBackend` on the same interface, and an explicit UI state for which
backend answered.

**Done already:** the address and headers in settings, the reachability probe,
and a picker over what the server has pulled (`GET /api/tags`). Listing is the
smallest thing that proves the address, the tunnel and the header all work,
which had to be answerable before anything could be delegated — and it starts
no inference, so it costs the GPU nothing. Two things that would have made it
fail silently and are worth remembering: Android blocks cleartext HTTP by
default, so a LAN `http://…:11434` needs a network security config, and Ollama
binds to localhost unless `OLLAMA_HOST=0.0.0.0` says otherwise.

**Also done:** the chat call (`POST /api/chat`, streamed), and a "who answers"
choice in settings — the phone or the server — with the phone taking over, and
saying so, when the server cannot be reached.

The choice is explicit rather than automatic, and that is the Phase 3 boundary
holding: deciding *for* the user which requests go to the big model is the
orchestration, and it needs a model that can judge "hard" first. Guessing in
the meantime would mean nobody ever knowing which model answered.

The degradation policy is designed here, not bolted on: away from home the big
model does not exist, and an assistant that works at home and fails on the
train loses trust faster than one that is merely weak.

**Deliverable:** you can see which backend answered, and why.
**Measurement:** with the server switched off, the app still answers locally
and says so. With it on, a request routed to it returns.

### Phase 3 — Orchestration and delegation — THE RISKY ONE

The turn loop, the five orchestrator tools, the brief schema under constrained
decoding, sub-agent execution on Ollama, result folding.

**Done:** the spine — `AgentRole`, `AgentTool`/`ToolRegistry` with role scoping,
`Brief` with its schema and parser, and `TurnLoop` with a budget, a loop
breaker and visible tool activity. One tool so far, `delegate`, which runs a
brief on the server model and folds the answer back. Tool calling reaches both
backends: Ollama's `tools`/`tool_calls`, and LiteRT-LM's via `OpenApiTool` with
`automaticToolCalling = false` so the loop keeps the role scoping and the
budget.

Written so a turn with no tools never touches any of it — that path is the code
already answering on the device, and tool calling is the part that cannot be
checked without a phone.

**A tool call the engine cannot read is given back to the model, once.** Seen
on a phone: asked to describe a photo, Gemma wrote
`call:find_photo{describe:<|"|>…<|"|>, with:<|"|>,look:true}` — the
formatter's own quote markers inside the arguments, a parameter the tool does
not have, and the opening tag used to close the block. The parse threw, the
turn died, and the user got a page of ANTLR internals.

Nothing in this app produced that and nothing in it can prevent it, but it is
the one failure the model itself can fix. So the loop tells it exactly what
was wrong — only the parameter names that tool has, plain values, no markers
of its own — and lets it write the call again. Once: a third exchange is the
same slow failure with somebody watching. Every other failure stays terminal,
because retrying an out-of-memory or a model that is still loading only fails
again, more slowly, and which is which is a tested function rather than a
guess at the call site.

**Still to do:** the other four tools (`recall`, `phoneAction`, `ask`,
`answer`), the brief under `ResponseFormat.json` rather than as tool arguments,
and the eval set. The gate below needs a device, so it is the user's to run.

**Deliverable:** "research X and summarise it" delegates and comes back.
**Measurement:** an eval set of ~20 requests, scored on whether the brief
preserved the binding constraint.
**Gate: below 80%, stop and fix the schema or the decoding before building
anything on top.** If it cannot be fixed, see *Kill criteria*.

**A tool with nothing behind it is not offered.** Found on a phone: "tell me a
short story" spent a whole exchange writing a delegation brief, was told
there was no server configured, and then generated the story locally in a
second exchange. Twice the wait and a red tool card, for a question a 2B
model was always going to answer itself. A 2B model handed a tool named
`delegate` reaches for it on anything that smells like work, and no amount of
"not worth it for anything you can answer yourself" in the description fixes
that. So the turn loop takes a set of names to withhold, and the conversation
withholds `delegate` whenever no server and model are configured - from the
list the model is given *and* from the lookup, because a model that saw the
name earlier in the conversation will ask for it again.

**A failure that follows a success must not become the answer.** Also found on
a phone: "what have I got on today?" read the calendar successfully, failed on
the weather, and answered "I cannot tell you the weather because I do not know
your location" - with the calendar result sitting unused two messages above
and its own card showing Done on screen. A small model reads the most recent
message and answers from it. So a tool failure that arrives after something
worked says so, in the failure text itself, which is where the mistake is
made.

### Phase 4 — The phone

Port `tools/`. The tool registry with role scoping. MobileActions-270M as an
intent parser with its output validated before it reaches anything — a 270M
model is a translator, not a decider, and the fork's `garbledNumberRefusal`
exists because small-model output cannot be trusted with a phone call.

*As built, the validation arrived without the second model, which is what
left the 270M with nothing to justify it — see the deferral below.*

Deliberately after Phase 3, so tools arrive into a system that already routes.

**Done:** `phone_action` — calling and messaging — with the staged-action
policy as a pure function in `core/phone/` rather than as care taken inside the
Android plumbing, so it is tested rather than inspected. A call opens the
dialer unless direct calling is both opted into *and* permitted; a message uses
`ACTION_SENDTO`, which cannot send on its own, so "waits for a send" is a
property of the mechanism. The near-miss guard is ported: digits that are
nearly a contact's, reordered or one off, are queried rather than dialled, and
opting in does not bypass it.

Porting that guard introduced a bug the tests caught — running the edit
distance on the trimmed tails made `+972 50 123 4567` and `050 123 4567` read
as a one-digit slip, which would have queried every correctly-copied
international number.

**Also done: notification replies.** `unread_messages` lists what can be
answered and `reply_to_message` writes a reply onto the screen — it does not
send. The confirmation is the screen's rather than the model's, deliberately:
the obvious design is for Lin to read the wording back and send when the user
agrees, and that puts a 2B model in charge of deciding what agreement sounds
like, on an action with no composer and no undo. The button is ground truth;
"yes" is an interpretation. The model cannot reach the send path at all.

The listener keeps only notifications that carry a reply action, in memory,
for as long as the notification exists. Other people's messages passing
through a phone are not something to collect.

**Also done: the calendar, timers and alarms.** `whats_on` reads what is
coming up, `add_to_calendar` opens the calendar app with an event filled in,
and `set_timer_or_alarm` does the obvious thing.

The staged-action rule again, and here it costs nothing to honour: the app
holds **no** calendar write permission at all. An event goes through
`ACTION_INSERT`, so the calendar app's own editor opens populated and the
user saves — a model that misreads a date can open an editor on the wrong
day and nothing else.

A timer is set outright and an alarm is not, which looks inconsistent and is
not. The test is what the mistake costs: a timer runs for minutes, is visible
the moment it is set and is cancelled with a tap, while an alarm fires hours
later — often in the night — and a misheard "seven" for "eleven" is found out
by being woken by it. That decision is a tested function rather than care
taken in the Android plumbing.

**The model did not know what day it was**, which made "put it in the calendar
tomorrow" impossible to get right; it was guessing, and a 2B model's guess at
the date is a year out as often as not. Today's date is now in front of the
orchestrator prompt — at *day* resolution, which is load-bearing: the prompt
is part of what keys an on-device conversation, so a prompt carrying the time
of day would rebuild the conversation every minute and throw the history away
with it.

Almost every bug here was a time zone. An all-day event is stored at midnight
**UTC**, so reading it locally puts somebody's birthday on the wrong day
everywhere west of Greenwich; "the start of today" written as
`now - now % DAY_MS` is UTC midnight, which answers for yesterday in New York
before dawn; a lookahead window added in milliseconds loses an hour across a
clock change and drops the last event of the final evening; and a lenient
`Calendar` turns the 31st of February — which is what a model produces when
asked for the end of the month — into the 3rd of March. All four fail tests
when reintroduced.

**Also done: media control.** `media` sees and changes what is playing —
what, play, pause, next, previous, louder, quieter.

Not staged, and that is the point of having a rule rather than a reflex:
pausing music is seen the instant it happens and undone by saying so. The
staged-action rule is for things that reach other people or cannot be taken
back, and this is neither.

Volume is the exception, upwards only. It is the one media control that can
actually hurt somebody — headphones at full on a misheard "turn it up" is an
injury rather than an annoyance — so a step is a step and a run of them stops
at four fifths. Getting to the top stays possible by hand, where the person
can hear what they are doing.

Choosing *which* session is the whole difficulty: three apps hold one on an
ordinary day, and "pause" reaching the podcast stopped last Tuesday instead
of the music playing now is the difference between useful and infuriating.
Something playing wins; among several, the most recent; with nothing playing,
the most recent, so "play" resumes what was last listened to. A command the
session cannot take is refused rather than attempted, because a transport
control that goes nowhere is silent and the model would report a skip that
never happened.

Reaching the sessions needs notification access — the same grant that lets
Lin answer messages. The alternative, `MEDIA_CONTENT_CONTROL`, is for system
apps.

**Deferred, not dropped: MobileActions-270M.** Looked at properly in October
2026 and parked, with the reasoning kept here so it is not re-derived.

*What it actually is.* Not an intent parser in the sense this plan assumed:
it is **FunctionGemma-270M fine-tuned for mobile actions**, a ~271 MB
`.litertlm` function-calling model. In the archived fork, "MobileActions" is
not a model at all — it is a per-model capability flag that unlocks a
Gallery demo task. The plan conflated the two.

*Why not now.* Four reasons, in order of weight:

- **The premise expired.** The rationale was "output validated before it
  reaches anything — a 270M is a translator, not a decider". That validation
  now exists unconditionally: `clockTime`, `timerMinutes`, `startOfEvent`,
  `dayFromWords`, `decideCall`, `looksLikeAGarbledCopy`, `fileNameFor`,
  `volumeAfterStep`, all tested in `core`. Gemma's output already goes
  through every one. A 270M would remove no check.
- **The repertoire barely overlaps.** It covers about seven functions —
  flashlight, alarms, contacts, calendar creation, maps, email, Wi-Fi. Lin
  has fourteen tools and only two overlap. Its own card says it "may not
  generalize well to other function calling tasks", so `delegate`,
  `find_photo`, `remember`, `read_document` and `media` would stay with
  Gemma regardless.
- **Accuracy.** An arXiv benchmark puts Google's Mobile Actions variant at
  17.2% end-to-end, against 29.3% for base FunctionGemma. That is the
  paper's own extended benchmark rather than the vendor's metric — harder
  cases than the model was tuned for — but it is the only end-to-end figure
  found.
- **Cost.** +271 MB on top of 2.6 GB and 0.5 GB, a second resident model,
  and a routing layer that is itself a new failure mode.

*What it knows that Lin does not* — flashlight, Wi-Fi, maps — are plain
intents. They are worth adding as ordinary tools, with validation and no
download, if they are wanted.

*What would change the answer.* Any one of: Gemma observably picking the
wrong tool or mangling arguments in daily use; tool latency being the thing
that annoys, where a 270M is several times quicker; or wanting those seven
actions specifically as a fast path, accepting the accuracy. None of those
is measured yet, and the measurement is what should decide it.

Sources: the litert-community model cards for
`FunctionGemma_270M_Mobile_Actions` and
`functiongemma-270m-ft-mobile-actions`, arXiv 2609.25373, and Google's
on-device function calling post.

**Measurement:** the staged-action policy holds — calls open the dialer unless
directly authorised, messages wait for a send, and a notification reply is read
back before it goes.

### Phase 5 — Memory and media retrieval

**Done (text half):** a vector store on SQLite with the embeddings beside the
text, `LiteRtEmbedder` over LiteRT-LM's own `EmbeddingEngine`, and the
`remember` / `recall` tools. The embedder loads for a burst and closes, which
is the "one resident, one transient" budget working as intended.

Two corrections to what was planned here. The embedder runs on LiteRT-LM
rather than MediaPipe's `UniversalEmbedder` — 0.18 ships an `EmbeddingEngine`,
so one runtime and one AAR serve both models instead of two native stacks. And
writing is an explicit tool rather than a background digest to begin with: a
digest decides on the user's behalf what about their day is worth keeping, and
getting that wrong is a privacy problem rather than a bug.

The discipline that matters is the similarity floor. Cosine always ranks, so
without one every query returns its five nearest memories however unrelated —
and a model handed three irrelevant "memories" states them as fact in the
user's own words, with nothing downstream marking them false.

**Also done:** a screen for reading and deleting what is held, built before
anything writes unprompted. That order is the point: a digest filing away what
it thinks is worth keeping is only acceptable if the user can see what it
decided and undo it, so shipping the writing first would leave a period where
the app holds things about somebody they cannot look at.

**Also done: the media half.** The photo library is indexed on the device into
the same vector store and the same 768 dimensions as memory, which is what the
740M multimodal embedder was chosen for — a description and a picture compare
directly, so "the one from the beach" is one query rather than a captioning
pipeline and a join.

The run is a resumable foreground worker: thousands of photos take real time,
the phone sleeps, and anything already indexed is skipped so the next start
continues rather than begins again. Nothing is uploaded and no picture is
copied; what is stored is a vector and the content URI the system already has.

Matches go to the screen as pictures and the model is told only how many there
are. It cannot see them, and letting it describe one would be inventing a
memory with the evidence sitting unexamined next to it. The photo threshold is
looser than memory's on purpose — a wrong picture costs a glance, a wrong
memory gets stated as fact.

**Also done: the background digest**, last because it writes things about
somebody without being asked each time and that needed a screen to see it on
first. Off by default — the one default here chosen against convenience, since
the other switches decide what Lin may *do* and this decides what it *keeps*.

The quality risk is the whole of it. Ask a 2B model what is worth remembering
and it answers "the user said hello": a fact about the conversation, not about
the person, and a store full of those is worse than an empty one because they
crowd out the real entries in every search. So the schema is narrow, the
prompt says what not to produce, and everything that comes back is filtered
again — a prompt is a request and a filter is a rule. New facts are also
checked against what is already stored, because a digest after every
conversation rediscovers the same few things forever.

Phase 5 is complete.

Port `memory/`. Replace word-overlap search with embedding retrieval using
**EmbeddingGemma 2 Text 270M**, 164 MB and co-resident with the chat model,
through MediaPipe `UniversalEmbedder` and a vector store, with Instant Media
Search as the reference implementation. Background digest after a conversation
ends.

Then the same retrieval over media: photo library access, background indexing
into a vector store, and search by description. One embedder, one vector space,
both sources - so a query can return a note and a photo together.

**Measurement:** recall over a set of planted facts; that nothing invented
appears in a reply when the vault has no answer; and indexing throughput and
battery cost over a real library, because an indexing run that flattens the
phone is not shippable however good the search is.

### Phase 6 — Voice and ambient

Dictation, spoken replies, wake word, resident service, the assistant role, the
power-button summon, notification replies.

**Done:** dictation, spoken replies, the assistant role and the power-button
summon — which between them are the point of the whole project stated in one
intent filter. Lin is selectable under Android's digital assistant app, and
being summoned that way opens listening, because there is no keyboard in that
gesture.

**Done: the summon is a panel, not a launch.** Being summoned brings up a strip
at the bottom of the screen over a dimmed view of whatever was already there.
A full screen is the wrong shape for a ten-second question — it replaces what
somebody was reading, and coming back out of it is a second deliberate act.
The panel grows with its contents up to a fraction of the window: a fixed
height shows an expanse of empty surface before the first word and then clips
the reply, and a fraction rather than dp keeps it a panel on a Fold's inner
screen as well as on a phone.

It is its own activity, which is a platform constraint rather than a
preference: a window's theme is fixed when it is created, so one activity
cannot be both a full-screen app and a translucent panel. What is shared is
everything below the chrome — one `ConversationBody`, with a height for the
list where the full screen uses a weight, so the two frames cannot drift
apart. The panel holds its own conversation rather than the app's, so there is
no "expand this into the full app" yet; the conversation lives in the screen's
saved state, and sharing it across both windows is a change to where it lives.

**Done: saying whether Lin holds the role.** Android does not let an app take
the assistant role — it cannot be requested, and the request intent is refused
*silently* on the phones that refuse it, so a button using it would appear to
do nothing. What the role does do is go back to whichever app came with the
phone whenever Lin is uninstalled, which is the likely answer to "summoning
stopped working". So settings says which app holds it, re-read whenever that
screen returns to the front, in three states — the third being the phone
declining to say, where guessing "no" would be a made-up fact on the one
screen meant to clear this up. The button that goes there asks the package
manager which of those screens this phone has before starting one; starting an
intent nothing handles throws, and that throw was being swallowed. There is
also a button that opens the panel directly, which is half a feature and half
a diagnosis: if it appears from there but not from the power button, the
problem is the role or the phone's own side-button and gesture settings.

**The summons kept opening the old screen.** Moving the ASSIST filter to the
panel was not enough, and the reason is worth keeping: Android stores the
chosen assistant as a *component* - `com.lin.agent/.MainActivity` - rather
than as a package. A phone told "Lin" before the panel existed goes on
starting the activity it was told about, by name and explicitly, so no
manifest change reaches it and the whole app opens over whatever somebody was
doing. The main activity now hands any summons straight to the panel, so it
works whatever the phone remembers; settings says when a phone is pointing at
the old door, because re-picking Lin there skips the hop.

**A summon happens once.** A screen's start-up effect runs again whenever the
screen is recreated, and on a foldable that is every fold — so unfolding the
phone after "Hey Lin, what's the time" asked it a second time. Keying the
effect on the words cannot fix it: the words are the same and it is the
composition that is new. Each summon carries a token, the view model remembers
which one it acted on, and the wake service mints a fresh one per wake, so a
recreated screen does not re-ask and somebody repeating themselves still does.

The microphone rules are a tested function rather than care taken in the UI.
It opens only when summoned as the assistant or when the button is pressed,
never because the app came to the front, and the permission is asked for at
the button rather than at launch. Replies are spoken only when the question
was spoken: a switch set last week should not answer a typed question out loud
in a quiet room.

Dictation prefers the on-device recogniser where the platform has one. That is
a privacy choice rather than a performance one — the rest of this app keeps
what is said on the phone unless the user pointed it at their own server, and
a cloud recogniser would quietly undo that for every spoken word.

**The bug underneath all of it.** The voice work shipped and then appeared
not to exist, which was one mistake wearing three faces. A `TextToSpeech`
reports readiness in a callback well after its constructor returns, and every
method here checked a plain flag and gave up when it was still false - which
it always was on the first call. So the first spoken reply after each launch
was dropped in silence, every voice preview was silent, and the settings
screen asked for the voice list once, got an empty one, and showed "no
voices" for ever. The fix is to wait for the engine rather than to check on
it, which is the difference between a feature and a feature-shaped hole.

**Done: a conversation, not a series of questions.** With it turned on, the
microphone reopens once Lin has finished answering, so a spoken exchange
carries on without reaching for the phone. Three conditions, each ruling out
a way of getting it wrong: only when asked for, only when the question was
spoken, and only when Lin actually spoke - reopening after a silent answer
would leave somebody talking to a phone that said nothing and is now
recording them. It waits for the engine's own "finished" callback rather than
a guessed delay, because a microphone opened a moment too early hears Lin.

**Done: which voice, and when.** Speaking was one switch and one voice - the
engine's default, whatever that happened to be.

When is three ways now: never, when spoken to, or always. The middle one is
still the default and still the one worth having, because a phone that answers
a typed question out loud in a quiet room is why people turn these off; but
somebody who wants a voice assistant rather than a chat can say so. "Never"
keeps one exception, the car: a question asked out loud while driving is
answered out loud, because an answer only on screen is no answer there.

Which voice is a list, with a sample spoken the moment one is picked - a name
like `en-gb-x-gbb-local` is not something anybody can judge by reading - plus
speed and pitch, each previewed as it is let go of. The ordering is the part
with a rule behind it: **offline voices come before better-sounding online
ones**, which is the opposite of ranking by the engine's own quality score. A
network voice means every spoken reply is sent to somebody's server to be read
out, and that is the one thing this app does not do with anything else. They
are still offered - it is the user's phone - but they are second and labelled.

More voices are the system's to install rather than Lin's, so settings opens
the phone's speech-engine screen and says what is there: Google's and
Samsung's engines download extra voices, and a neural engine installed from
elsewhere - a Piper or Sherpa-ONNX one, running on the phone and sounding far
better than either - appears in Lin's list as soon as it is the chosen engine.
That is the honest answer to "find a better voice online": the voice is a
model, it belongs to an engine, and Lin's job is to use whichever one the
phone has rather than to ship its own.

*Still open:* speaking through the user's own server, which is where a
generated or cloned voice would come from. Their stack already speaks an
OpenAI-compatible API; a `/v1/audio/speech` call and a player is the work,
and the trade is latency against a voice the phone cannot produce.

**Done, with a caveat worth reading: "Hey Lin".** Off by default, and of all
the defaults here that is the one least open to argument — it holds the
microphone open indefinitely.

It is **not a wake-word engine**. A real one listens to audio at almost no
cost; there is no pre-trained model for "Hey Lin" (the open ones ship "Hey
Jarvis" and "Okay Nabu", and a custom one means an account with somebody's
cloud), so this runs the phone's recogniser continuously and reads the
words. The battery cost is real and the setting says so. The matcher is kept
apart in `WakeWord` so a proper model can replace the listening half without
touching anything else.

Where the care went is the matching. A recogniser returns "hey Lynn", "hey
line", "hey len" and "heylin" for the same two syllables, so insisting on
the spelling means it never fires; being loose means it fires in a pocket,
which opens a microphone nobody asked to open. Both halves must be present
and adjacent, each allowed one *substitution or insertion* — never a
deletion, because that is what lets "he li" through, and two ordinary
syllables must not wake it. A test caught exactly that.

"Hey Lin, what's the time" asks the question rather than only opening the
microphone, or the feature would be slower than picking the phone up.

Listening restarts when the app is opened rather than at boot, which is a
platform constraint: from Android 14 a microphone foreground service cannot
be started from the background, so a `BOOT_COMPLETED` receiver would throw
rather than listen. After a restart, Lin has to be opened once.

**A second platform rule, worse because it is silent.** An app in the
background may not start an activity, and a foreground service is *not* an
exception — the microphone notification does not buy a window. A blocked
launch is dropped rather than refused, so "Hey Lin" heard the question, had
nowhere to put it, and looked deaf with nothing in the logs anybody reads.

Two of the documented exceptions apply. "Appear over other apps", granted by
hand, lets the launch through, which is what makes the wake word hands-free;
and an app with a window already on screen may start another, so it works
with Lin open and nothing granted. With neither, the question goes into a
heads-up notification that opens the panel when tapped — a tap rather than
hands-free, which is the honest cost of not having the permission. Lin draws
no overlays; the permission is asked for only for this, and only once the wake
word is on.

**Lin in the car: checked properly, and the answer is still no.**

This was asked twice, so here is the evidence rather than the conclusion.

*Android Auto has no assistant category.* The published list of what a
third-party app may be on Android Auto is: media (audio), navigation, point
of interest, internet of things, weather, games (parked only), messaging
notifications, and — in closed testing only — templated messaging and
calling. There is no assistant category, so Lin cannot be an Android Auto app
at all, let alone its assistant. In Google's own documentation the assistant
appears only in the other direction: "Gemini or Google Assistant can launch
installed apps with voice commands."

*The voice stack belongs to Google.* Android's own page on media voice
actions says the query is started "with the steering wheel button or the
'OK Google' hotword", that "Android Auto or AAOS detects and interprets the
voice action", and that what reaches an app is an already-interpreted media
action — `onPlayFromSearch(query, extras)` on its media session. No app
receives the audio, the transcript, or the button press. So when the wheel
button appears to do nothing, it is the Google app or Gemini being summoned
and not being there; nothing Lin declares can intercept that.

*2026 changes nothing here.* Google is replacing Assistant with Gemini
across Android Auto this year. Every announcement is about which Google
assistant answers; none of them opens the seat to another one.

*The one real opening, and its price.* A media app on Android Auto does
receive voice through `onPlayFromSearch` — so Lin could answer "play
something" from the wheel button by being a car media app with a
`MediaBrowserService`. That is media-shaped and nothing else: no questions,
no messages, no general assistant. And Android Auto refuses to run apps that
did not come from a trusted store in a real car, which a GitHub release is
not; it takes Android Auto's own developer "unknown sources" setting to run
a sideloaded one. Worth doing if the car is the point, and worth knowing it
buys "play X" rather than Lin.

*What does work in a car today.* The older Bluetooth path: a car kit or
headset's voice-dial button sends `VOICE_COMMAND` — or
`VOICE_SEARCH_HANDS_FREE`, which Lin now also answers, because which of the
two a head unit sends belongs to the head unit — and Lin is summoned, so the
microphone opens exactly as it does for the power button. This is the
hands-free profile rather than projection, so it works when the phone is
connected by Bluetooth and Android Auto is not running. And "Hey Lin" on the
phone itself, which works alongside Android Auto because it uses the phone's
own microphone rather than the car's.

*The rule that shapes all of it.* A microphone foreground service may not be
created from the background. This is not the ordinary background-start
restriction, which several things exempt an app from — it is the stricter
while-in-use rule on top, and the documentation is explicit that the general
exemptions do not cover it. So Lin cannot start listening when it notices a
car, however it notices. What *is* allowed is a service started by somebody
interacting with a notification, which is why "in the car" is a notification
with one button rather than something automatic. (The other exemption on that
list is an app providing a `VoiceInteractionService` — the deferred half of
this phase, and the only route to true hands-free starting.)

*What car mode changes, once listening.* A spoken question gets a spoken
answer whatever the replies switch says, because an answer only on screen is
no answer to somebody driving. Calls can dial straight out — the setting is
three ways now rather than a switch, and "only in the car" exists because the
tap that makes staging safe is the one thing nobody should be doing at 70mph.
The panel may appear over the lock screen, off by default, because Android
Auto locks the phone while it projects and otherwise there is nothing to see.
Car mode itself is the system's own: `UI_MODE_TYPE_CAR`, which Android Auto
sets while projecting. It is not true in a Bluetooth-only car, so nothing
depends on it that is unsafe to get wrong in the careful direction.

*Android Automotive* — the Android built into a car rather than projected to
it — does allow a third-party `VoiceInteractionService` to take the wheel
button. Different product surface, different build; noted rather than
attempted.

**Decided: no rename.** `com.lin.agent` stays. See Phase 7.

Notification replies shipped under Phase 4, and not with the read-back this
planned. Lin writes the reply onto the screen and the person sends it: the
read-back design puts a 2B model in charge of deciding what agreement sounds
like, on an action with no composer and no undo.

### Phase 9 — The phone itself

Asked for after a build where "turn the flashlight on" was answered, correctly
and uselessly, with "I do not have a tool to control the flashlight directly."

**Done: the switches.** The flashlight and Do Not Disturb are Lin's to set and
are set outright. Wi-Fi, Bluetooth, NFC and aeroplane mode are not, and the
reason is worth writing down because the code looks like it should work:
`WifiManager.setWifiEnabled` and `BluetoothAdapter.enable` are still in the
SDK and still compile, and they return `false` rather than throwing. Android
took Wi-Fi from apps in 10 and Bluetooth in 13; NFC never had an API and
aeroplane mode never will. So for those four, Lin opens the system's own
panel — one tap, and the user sees exactly what is being changed — and the
tool result says it is ready to tap rather than done. The table is a tested
function rather than a comment, because the failing version of this is an
assistant that says "Wi-Fi is off" having changed nothing.

A question is answered rather than acted on. A small model hands the whole
sentence through as the argument, so "is the flashlight on?" arrived as the
state to set; reading that as an instruction turns the torch on in somebody's
pocket.

**Done: opening apps**, by the name somebody says, matched in a tested
function because opening Facebook Messenger for "messages" is not a near miss.
Nothing is opened on a guess: an app that is not installed is said.

**Done: playing music**, in the app the user chose — which Lin asks for rather
than guessing, because somebody with three services installed pays for one of
them. Three ways in, tried in order: Android's `MEDIA_PLAY_FROM_SEARCH`, then
a search link into the app, then opening the app. Support for the first is
uneven and undocumented per app and there is no way to ask an app which it
does, so afterwards Lin looks at what the phone is actually playing and
reports that — "it is open on the search" when that is what happened, rather
than "I played it" because an intent did not throw.

**Done: the weather.** Asked for after a build where "what is it doing
outside" had no answer at all - the model either declined or made one up from
training data that is months old and somewhere else.

Open-Meteo, because it needs no account and no key. Every other free weather
service wants a sign-up, and an assistant that cannot say whether you need a
coat until its owner has registered with somebody is not finished.

**It is also the first thing in this app that sends anything about the user to
a third party**, and that is worth stating rather than burying. What goes is a
pair of coordinates rounded to about a kilometre - the same weather, a
different street - and nothing else: no identifier, no question, no
conversation. It happens only when somebody asks about the weather. The
setting says exactly that, in those words, and the permission is coarse
location, asked for there rather than at launch.

The parsing is a tested function for the usual reason: a response cut short
gives arrays of different lengths, and reading past the shortest is how a
forecast gains a day with somebody else's temperature in it. A weather code
Lin does not know stays unsaid rather than becoming "clear", which is the one
answer somebody might walk out in.

**Not MobileActions-270M.** The question came back with this work: would a
small action model help? No, and the reason is the same as when it was parked.
Nothing here was ever blocked on understanding "turn the flashlight on" — a 2B
model does that from the tool name alone. What was missing was the tool, and
what limits the rest is Android's permission model, which no model changes.

### Phase 8 — Thinking, and documents

Neither was in this plan. Both were asked for, and both turned out to be
smaller than they looked because the pieces were already in place.

**Done: controllable reasoning.** Gemma 4 can think before it answers, and on
a phone that is a trade rather than an improvement — thinking tokens are
generated at the same speed as answer tokens, so a budget is paid for in
silence before the first word. So it is the user's choice, off by default,
with the levels named by what they cost. A turn bound to a response schema
never thinks whatever the setting says, because its decoder is constrained to
a grammar with no room for a chain of thought. The thinking reaches the
screen, collapsed, and is kept apart from the answer at every layer: it never
enters the history sent back, never reaches a digest, and is dropped when the
turn ends.

Finding this one exposed a worse bug. `OnDeviceBackend` had been accepting
`systemPrompt` and `responseSchema` and dropping both — so the orchestrator
prompt had never reached the on-device model at all. See the commit; the
decision is now a tested function rather than a method, because both symptoms
look like ordinary behaviour.

**Done: documents.** Lin reads PDF, Word (.docx) and plain text, and writes
.docx, .pdf, .txt and .md. All of it local — PdfBox for PDF, and .docx read
and written by hand, since it is a zip of XML.

The access model is the point. The app holds no storage permission for
documents: a file is readable because its owner handed over that one file
through the picker or a share, and the persisted URI permission is the whole
of the access. There is no browsing to be found anywhere, and the Documents
screen is therefore a complete and truthful answer to "what can Lin open".

Writing follows the staged-action rule, as calls and notification replies do.
Lin writes only into its own private directory; the step that puts a document
among somebody's own files is a button on the screen, which no tool can
reach. Revising never writes over the original — it produces a new file
beside it, because non-destructive by construction beats a confirmation
dialog when the alternative is a 2B model replacing somebody's contract.

Reading a .docx is the part with real bugs in it, and they are tested: Word
splits one word across several runs whenever anything changes mid-word, so
anything joining runs with a separator turns "assistant" into "assi st an t";
field codes are instructions rather than prose; and text deleted under
tracked changes is still in the file, so including it would have Lin answer
from a draft its author rejected. A round-trip through this app's own writer
caught a fourth: an empty paragraph is written self-closing, by Word as well,
so a reader that counts only closing tags loses every blank line.

**Corrected later: attachments in the chat.** "Add documents" was read as a
library managed in settings, and what was wanted was attaching a photo or a
file to a message and asking about *that*. The library stays — it is what
`read_document` searches — but the composer now has an attach button, and
that is the thing somebody reaches for.

The two kinds are handled completely differently, on purpose. A document is
turned into text before it reaches any backend, so it needs nothing from any
of them and works identically whichever model answers; it is fenced and
named, so a file containing "ignore your instructions" cannot read as the
user typing it. A picture cannot be turned into anything — it travels as a
picture, which means three wire formats (LiteRT `Content.ImageBytes`,
Ollama's bare base64, OpenAI's data URI) and a model that can actually see.
When the model cannot, Lin says the picture was not sent, because a model
with no vision answers about the question alone and sounds entirely
confident.

**And the other half of it: finding a photo and reading it.** `find_photo`
already searched by what is in a picture, but the model could only be told
how many matched — "you cannot see them, so never describe what is in one"
was in the prompt because it was true. It no longer has to be. The tool
takes a `look` argument, and when set, the best match is handed to the model
as well as to the screen.

Everything found still goes on screen, because a person takes in six
thumbnails at a glance; at most **one** goes to the model, because each is a
slice of a context window that has to hold the conversation too. When the
model cannot see, the tool result says so and says not to describe it — a
model handed nothing and told nothing describes the picture anyway, from the
words that found it, and nothing on screen would suggest otherwise.

The picture travels on a user-role message rather than the tool result,
because that is the role every backend accepts one on: an OpenAI tool
message takes a plain string. It is labelled as coming from the tool so the
model does not read it as the person having spoken.

Photos are sampled down on the way in. A phone camera makes twelve
megapixels; a vision encoder sees a few hundred pixels a side whatever it is
handed, and the rest is a base64 string big enough to make the request fail.

**Not done, and worth saying:** editing a PDF in place. A PDF places every
glyph at a coordinate and has no reflowing text to change, so "edit this PDF"
means rewriting it, which loses its layout. Lin says so rather than
pretending.

**Also done: vLLM and Unsloth as server options.** Both speak OpenAI's chat
completions, so they share one backend; they are separate entries in the
picker because what a person has to type differs, not because the wire
format does. vLLM listens on 8000 with no key, Unsloth issues an
`sk-unsloth-…` key and gets its port assigned. Presenting one
"OpenAI-compatible" option and leaving somebody to work out which fields
their server needs would be the tidier enum and the worse product.

`OllamaBackend` and `OllamaServer` became `ServerBackend` and `ModelServer`,
keyed on the dialect, because the streaming loop is identical for all three —
only the address, the request body and the shape of a chunk differ, and each
of those is one call.

Two things in the OpenAI dialect were established by reading the current
documentation rather than from memory, and both would have failed silently.
The reasoning field was renamed: older vLLM returns `reasoning_content`,
newer returns `reasoning`, so both are read — one would have shown no
thinking at all on half the installations in the wild. And vLLM has a known
bug where a response schema is not enforced while a reasoning parser is
active, the output escaping into the unconstrained reasoning channel; the
documented way around it is to turn thinking off explicitly, which this app
already does on every structured turn, so it now says so on the wire.

### Phase 9 — A corpus of its own use

Asked for: a copy of every conversation, packed in thirties, as training data
for the user's own models. Taken off the phone by hand for now; an uploader to
their own server comes later.

The output is JSONL with a `messages` key — one conversation per line, each a
list of role/content turns. That is the conversational format TRL's SFT
trainer reads directly, applying the model's own chat template; ShareGPT-style
data has to be converted into it anyway. Getting the shape wrong would make
the whole corpus useless, so it is pinned by tests rather than assumed.

Three kinds of example are refused, and each would damage a fine-tune rather
than merely bulk it out. Blank turns teach the model to say nothing. A
trailing question with no answer is what a failed turn leaves behind — train
on that and the next thing in the file becomes the answer to it. And the
system prompt is not content: the trainer applies its own template, and a
prompt repeated across every example is a prompt being learnt as data.

**This needed a conversation boundary, and there was none.** Nothing in the
app ended a conversation — the thread simply continued, which also meant it
grew past the model's context eventually. Half an hour away now starts a new
one, which is both how people think about it and what bounds the thread. The
visible cost is that the scrollback goes after a gap; what it buys is a corpus
of complete conversations rather than thirty prefixes of one, and turns that
do not get slower all week.

One file per conversation while it is open, named by its id, so continuing a
conversation replaces its record instead of leaving a second partial copy.
Only closed conversations count towards a pack.

Two bugs the tests found, both the same shape — a number silently reused:
deleting an exported pack made the next one take its number, and so did
clearing the archive. Either would have had `lin-0002-….jsonl` overwrite an
earlier `lin-0002-….jsonl` the moment both were copied into one folder:
thirty conversations gone from a corpus nobody counts the lines of. The
counter is now remembered rather than derived, and survives a clear.

**Said plainly on the screen, because it is the honest part:** everything said
to Lin is written to the phone in readable text, and a conversation that used a
tool carries whatever the tool returned — which can include other people's
messages and the contents of documents. On by default, unlike the digest,
because this one was asked for.

**Not captured:** the model's thinking. It is dropped at the end of each turn
and never enters the message history, so recording it would mean carrying it
through `Message` — worth doing if reasoning traces are wanted in the corpus,
but not quietly half-done.

**The seam for later:** packs are plain files in one directory with stable
names, and are never deleted on being sent — sharing a file does not report
whether it arrived. An uploader reads that directory; nothing about the API
has been invented here.

### Phase 7 — Replace

As written: take over `com.lin.assistant`, migrate the memory vault out of the
fork, retire the fork.

**Two thirds of that is already true and the last third is now a bad trade.**
The fork is archived — not built, not released, not meant to be installed —
so it is retired in substance. What remains is the rename, and it has
inverted since this was written. `com.lin.agent` is no longer the sketch: it
holds 2.6 GB of models, the memories, the photo index, the documents and the
training corpus. Changing an applicationId is a fresh install to Android, a
different data directory, so the rename costs every one of those to gain a
package name nothing is competing for. The identity worth keeping is now the
new app's.

So the recommendation is to **keep `com.lin.agent`** and treat the fork's
package as abandoned rather than inherited. If the rename is wanted anyway —
for the name itself — it is now survivable rather than destructive, because
the thing that made it destructive has been built.

**Done: backup and restore.** The prerequisite for any identity change, and
worth having on its own: until now every irreplaceable thing in this app
existed in exactly one place, on one phone, with no way off it.

A zip of JSON, deliberately dull — somebody opening it in a year, after the
app has been rebuilt or renamed, should be able to see what is in it without
this code. What goes in is decided by what cannot be regenerated: the
memories (with their vectors, so a restored memory can be found by meaning
without re-embedding), the training corpus, and the settings. The models are
not in it — gigabytes, re-downloadable, and a backup nobody can email is a
backup nobody makes — and neither is the photo index, which rebuilds from
photos still on the phone.

Restoring is additive and never deletes. The common case is an empty app
where it makes no difference; the dangerous case is restoring onto a
populated one, where replacing would throw away everything learnt since.
Settings are the exception and are replaced, because half of an old server
setup and half of a new one is a state nobody chose.

A newer backup is refused rather than half-read: reading what this build
understands and silently dropping the rest, from the only copy of somebody's
memories, is worse than saying it cannot be read. And a zip entry called
`training/../../evil` is ignored rather than trusted — the oldest bug in
archive handling, and the one worth a test even when the current reader
never writes to a path from the file.

### Phase 10 — Staying inside the window

Not planned; found by looking for what would break next.

**`contextWindow` was declared, carried around and never read.** A
conversation grew until the engine refused it, which is the failure this
plan's own table blames for the fork becoming unworkable
(`Input token ids are too long: 4230 >= 4000`). Attachments brought it
closer: a picture is worth a few hundred tokens and the history keeps them.

Three rules, in order of what they cost. The newest message is always kept,
whatever its size — it is the question being asked, and a turn without it is
not a shorter conversation but a different one. Then pictures go from older
messages, because a picture is expensive and almost never what a later
question is about, so dropping one buys back the most history per token.
Then whole messages, oldest first. A tool result left stranded at the front
is dropped as well: an answer with no visible question reads as something
the user said.

Trimming happens in the loop rather than where the conversation is held,
because that is where it grows fastest — a turn with four tool calls adds
four results to the same request, so the overflow arrives mid-turn rather
than mid-conversation.

An unknown window (zero, which is what a server reports) trims nothing.
Guessing one would cut conversations that were fine on a machine with far
more room than a phone.

**Honest limit:** this governs what is *sent*. On-device, LiteRT holds the
conversation's history itself and only the new message is sent, so trimming
here cannot shrink what the engine is already carrying. Starting a new
conversation is what does that — which is also why there is now a button
for it.

**Also done: starting a new conversation.** The half-hour boundary left
nobody any way to say "that is finished"; the only way to begin again was to
put the phone down and wait. The conversation being left is recorded for the
corpus first, so starting a new one never loses the last.

### Phase 12 — The Galaxy, swept properly

Asked twice, and answered thinly the first time: three dead ends reported,
nothing built. This is the whole surface, checked, with what came of each.

**No, with sources.**

- *Good Lock and Modes and Routines* have no third-party trigger API. Tasker
  reaches Modes and Routines through hidden content providers that need a
  privileged permission and change between One UI versions. Shipping that
  would break on an update and look like Lin's fault.
- *Edge panels*: Samsung's Edge SDK was retired on 5 December 2023 - it can no
  longer be downloaded, and new panels can no longer be registered in the
  Seller Portal. Third-party panels were never supported on folds anyway.
- *Bixby, SmartThings Find, DeX control, Samsung Health's own SDK*: closed, or
  partner-only.
- *SmartThings*: possible but newly expensive - personal access tokens last 24
  hours now, so it needs full OAuth, and free API access ends this month in
  favour of a paid personal plan. A cloud dependency with a subscription,
  against this project's grain. Home Assistant is the local answer, and is the
  recommendation whenever home control is wanted.

**A correction to the above.** This section was once summarised as "Samsung is
a dead end", and that was wrong in two places and incomplete in a third. The
list above is accurate about Samsung's *own* surfaces; it is not accurate about
the features people mean when they name them.

- *SmartThings is not closed.* Its OAuth API is real and documented, and is how
  Home Assistant's SmartThings integration works. The constraint is the fee,
  not the access.
- *Quick Share is not closed either, and Lin already has it.* Quick Share is a
  target in the ordinary share sheet - `ACTION_SEND` with a MIME type - and Lin
  already declares a SEND filter, so it both receives shares and can offer
  them. No private API is involved; that is what every other app is doing too.
- *Two mechanisms were missing from the list entirely*, and they are the ones
  other apps actually use for the things called impossible here: **Shizuku**,
  for system calls at the ADB shell's privilege, and an **AccessibilityService**,
  for driving another app's interface. Both are now built - see Phase 13.

**Yes, and done.** What Modes and Routines *can* do is open an app, and
Routines+ adds double-press and long-press of the volume and side keys as
triggers. So Lin now has a second launcher entry - "Lin · Listen" - that opens
the panel already listening. A routine can be pointed at it, which gives a
hardware-button summon without Lin being the phone's assistant at all: the
thing the assistant role kept failing to deliver. The same alias is what a
home screen icon, a folder, an edge panel's app shortcuts and a Bixby "open
Lin" all target, so one entry serves every route in. And because a routine
opening an app is a visible, user-started launch, the microphone is allowed to
start - which it is not when something tries this from the background.

**Yes, and done: Flex mode.** Half open with the fold across the middle, the
conversation takes the top half and everything you touch goes below the
crease, where a hand resting on the table reaches. Read through Jetpack's
`WindowInfoTracker` rather than Samsung's SDK: the same answer on every
foldable, already on the classpath, and not tied to one manufacturer. Every
other posture, and every phone that does not fold, is the single column it
was.

**Yes, and still to do.** Samsung Health reaches Health Connect once its own
sync is turned on, which makes Health Connect the honest route to steps and
sleep - Samsung's Health SDK is partner-only. That is the next Galaxy-shaped
piece of work and it is not started.

### Phase 13 — Borrowed privileges

Two mechanisms, both heavy, both opt-in, both the honest answer to things the
plan had previously written off. Neither is a trick: each is a documented way
an app on an unrooted phone does something the SDK does not offer, and each is
granted by the person, visibly, and revocable in two taps.

**Shizuku, so a switch is really set.** Android took Wi-Fi from apps in 10,
Bluetooth in 13, and never gave NFC an API; aeroplane mode never had one. It
took none of them from the ADB shell, where `svc wifi disable` has worked since
Android 4. Shizuku is a separate free app the user installs and pairs once over
Wireless debugging; it runs a service under the `shell` user and lends that
identity to apps the user allows, one at a time, with no root.

Lin uses it through its own code, not through hidden system interfaces. The
alternative - taking the Binder for `IWifiManager` and calling `setWifiEnabled`
through a wrapper - works and breaks on the next Android version that changes
the signature, silently, on somebody's phone rather than in a build. So
`PrivilegedService` is Lin's class, started by Shizuku in a shell-privileged
process, running the commands a person could type. `Shizuku#newProcess` is on
its way out and is not used; this is the `UserService` route its changelog
points at.

What is decided lives in `core` with tests:

- `shizukuState` - five situations, kept apart because from the outside they
  all look the same (a switch that did not move) and "install it", "start it",
  "allow Lin" and "allow Lin from inside Shizuku" are four different things to
  do. The order of the checks is the content of the function: a stale
  permission grant on a phone where Shizuku has been uninstalled must not read
  as READY.
- `commandsFor` - the command table, best first, a list rather than one
  command because which form a given build accepts is not knowable from here.
- `shellWorked` - whether it actually worked, which is **not** `exitCode == 0`:
  `svc` answers a subcommand it does not have by printing its usage and exiting
  zero, so a tool trusting the exit code would say "done" to a help message.
  This is the honest-results rule in a single function.
- `reachOf` gains a `privileged` flag, and the four taken-away switches become
  `DIRECT` instead of `OPENS_IT`. Without Shizuku nothing changes: they still
  open the phone's own panel, which is what they did before.

Do Not Disturb deliberately does **not** go through the shell. It has a real
API behind a grant the user gives once, and borrowing a privilege to avoid
asking would be going round the person rather than asking them.

**An accessibility service, so a message can be written.** Replying to a
notification is all Android lets an app do unaided, which is why Lin could
answer a message but never start one. An AccessibilityService is the way round
it - the same one Tasker and AutoInput use - and it is the widest permission on
the phone: while it is on, the service can read the contents of every screen
the person looks at and act on their behalf.

Two things keep it to what it needs:

- **Nothing is remembered.** `onAccessibilityEvent` is empty, which is unusual
  for such a service and is the point: the tree is read when a tool asks,
  turned into plain data, and dropped. Nothing is written to disk and no index
  of what crossed the screen exists. The config asks for window content and
  view ids and nothing else - no key events, no touch exploration, no
  fingerprint gestures.
- **Nothing in the service decides what to press.** That is `core/screen`, with
  tests, and the test that matters is this one: in WhatsApp, Telegram and
  Signal alike the microphone button sits exactly where the send button sits
  and swaps with it depending on whether the box has text in it. A rule that
  found "the round button at the end of the composer" would, on a screen that
  had not caught up with the typing, record audio and send somebody a voice
  note. So `sendButton` applies a danger list *before* anything else, matched
  on whole words - because "dynamic" contains "mic" and a substring match would
  refuse perfectly good buttons.

`composerField` returns null rather than a guess when two editable boxes are on
screen and neither is identified. The other editable box on a messaging screen
is the search field, and a message typed into search and sent with the button
beside it goes to whoever search happened to select. One declined screen costs
a sentence; one wrong guess costs somebody a message they did not write going
to somebody they did not choose.

Sending follows the same rule as calling: **typed, not sent**, unless the
person has chosen otherwise. The middle setting - "when I asked for it to be
sent" - reads their own words through `soundsLikeSendIt` rather than asking the
model, because a 2B model asked whether sending was wanted says yes, that being
the helpful answer, and this action has no undo. A sentence containing both a
drafting word and a sending word reads as a draft.

Where there is a documented deep link - `wa.me`, `t.me`, `smsto:` - Lin uses it,
because it lands on the right conversation with the words already in the box
and the step that could reach the wrong person never happens. Where there is
not, Lin will only type into a conversation the person already has open, and
says so.

`read_screen` is the cheaper half of the same privilege, and the one that is
asked for most: "what does this say", "read me that", "what's the code in that
message".

### Phase 14 — Tools you can switch, and tools from elsewhere

**A switch per tool.** Every tool Lin has, listed in Settings → Tools, each
with a switch. Two reasons pointing the same way: a 2B model offered twenty
tools picks the wrong one often enough to be worth a switch, and every tool in
the list costs tokens in every prompt, on a phone, before the first word.
Switched off rather than removed — the tool stays listed and described, and
the model is simply not told it exists. It reuses the `withhold` set built for
`delegate`, so a switched-off tool is kept out of the list *and* out of the
lookup.

Stored as the **off** list. A stored on-list goes wrong the first time a new
tool ships: it is in nobody's saved list, so it arrives switched off for every
existing user, invisibly, and reads as the feature not working.

This needed the tool list to move out of `ConversationViewModel`, where it had
lived since Phase 3, into a singleton holding a `StateFlow`. A list built in
one ViewModel cannot serve a settings screen, and cannot grow at runtime —
which MCP requires.

**MCP.** Lin speaks the Model Context Protocol over Streamable HTTP, so any
server the user adds contributes its tools to the same list, each with the
same switch.

The thing worth recording is that **MCP has two eras**, and a client written
from memory would get this wrong. Revision `2026-07-28` removed the
`initialize` handshake and protocol-level sessions outright: every request now
carries its own protocol version, client identity and capabilities in
`params._meta`, mirrored into `MCP-Protocol-Version`, `Mcp-Method` and
`Mcp-Name` headers so intermediaries can route without parsing bodies — and a
server **must** reject a request whose headers and body disagree. Everything
`2025-11-25` and earlier opens with `initialize` and keeps a session id. Most
servers deployed today are the older kind.

So Lin is dual-era: it tries modern, and on a `4xx` inspects the body before
deciding. A recognised modern error (`-32020` header mismatch, `-32021`
missing capability, `-32022` unsupported version) means the server is modern
and the request should be corrected; anything else — including an empty body —
means it is a handshake server. That one call is `looksLikeModernError`, and
it is tested, because getting it backwards fails invisibly in both directions.

A near-mistake worth keeping: when a modern server rejects a version it lists
what it does support, and the tempting move is to take the first entry and
retry. The list can contain only handshake-era versions, and `2025-11-25` in a
modern request's `_meta` is not a thing — that version *is* the handshake. So
`bestModernVersion` returns null for such a list and the client falls back
rather than retrying forever.

Other decisions, each tested:

- **Tool names are prefixed with the server.** Names are unique only within
  one server, and two servers each offering `search` is the collision the
  specification itself gives as the example. Unprefixed, the second one loaded
  would silently disappear.
- **The server's JSON Schema is passed through unchanged.** `AgentTool` gained
  a `parametersSchema` override for this; flattening a schema into Lin's own
  parameter list and rebuilding it would lose nesting, enums and defaults the
  server meant the model to see.
- **A malformed tool is excluded, not fatal.** A bad `x-mcp-header`
  annotation costs that one tool, not the server's whole list.
- **Images are named, not pasted.** Content bytes are base64 in the JSON, and
  a few hundred kilobytes of it would end a conversation on a 2B model.
- **`structuredContent` is used only when there is no text**, because a server
  returning both is required to put the same data in the text block.
- **A tool execution error is a result, not a failure.** The server ran it and
  it did not work: the model can act on that. A connection problem says so
  instead, because retrying will not fix a server that is not there.

Not built: resources, prompts, sampling, elicitation and roots. The last three
are things a server would ask the phone to do, and none of them is something
to hand a remote server on the strength of a capability flag.

### Phase 15 — Controls for the server model, and not delegating to yourself

**Delegating to the model already answering.** Found on a phone with a server
model loaded: the orchestrator called `delegate`, which opened a second
connection to the same server and asked the same model a restatement of the
question it was already in the middle of. Slower than not delegating, with a
tool card on screen saying a tool ran for nothing.

The first version of the rule only asked whether a server was configured,
which was never the right question. The point of delegation is the
*difference* between two models — a small one on the phone holding the
conversation and a bigger one on a server doing the heavy piece — so
`delegationIsWorthIt` now rules out three cases: nothing configured, the
server already answering, and the server having been asked to answer and not
having (which only happens when it was unreachable, so a delegation to it
would fail too, after a timeout the person waits through).

Note the third flag is read from the backend that was actually chosen rather
than from the `answerOnServer` switch, because the switch can say "use the
server" on a turn where the phone took over.

**Sampling.** temperature, top-p, top-k, context size and longest reply, set
per server and sent with every request. The shape of the type is the design:
every field is nullable and **null means do not send it**. A server owner has
configured their model; a default invented here and sent on every request
would quietly override that with a number nobody chose. So Lin sends only what
the person actually set, and the boxes read "The server decides" when empty.

Three decisions worth keeping:

- **A schema-constrained reply is always greedy, whatever the sliders say.**
  Not Lin overriding the user for its own convenience: a constrained decode
  sampled at temperature produces output that fails the schema, so the setting
  could only make that request worse. Nobody asking for a creative
  conversation is asking for creative JSON.
- **`top_k` is never sent to an OpenAI-dialect server.** It is not an OpenAI
  field. vLLM accepts it and llama-server accepts it, but a faithful
  implementation rejects the whole request over an unknown property — which
  would turn a slider nobody needed into a server that stopped answering. The
  settings screen says which servers it applies to rather than this silently
  dropping it.
- **`num_ctx` is now sent when set**, reversing the earlier decision not to
  override the server's own configuration. The person who owns that server
  asked to be able to, and a value they deliberately typed is not this app
  second-guessing them. When it is set it also becomes what Lin trims the
  conversation to, so the phone never sends more than the window it just asked
  for.

Stored as text rather than numbers, because the distinction that matters is
set against not-set and no numeric type has a spare value for it: zero is a
real temperature and means greedy, so it must not also mean "the user did not
say".

Thinking already had a control and did not need another; it is on the Models
tab and reaches both dialects.

### Phase 16 — The web, the phone itself, and a model that would not stop

**Looking things up.** The largest single thing Lin could not do. An assistant
with a calendar, a torch and no way to find out anything it was not trained on
is a toy, and the model behind this one is small and was trained a while ago.
So: `search_web` and `read_page`.

**Not scraped**, and that was tried first. Fetching a search engine's HTML and
pulling the results out is the obvious shortcut; both of the obvious endpoints
answered a plain request with a block page rather than results. Shipping that
would mean a feature that works until it quietly does not, for reasons nobody
holding the phone could diagnose. Lin talks to an interface somebody publishes
on purpose - the user's own SearXNG, or Brave's documented API - and says
plainly when neither is set up.

Two traps, each now a test. **`Accept-Encoding` is never set**, although
Brave's own examples send it: HttpURLConnection asks for gzip itself and
decompresses transparently, but only while the caller has not set the header -
setting it hands back compressed bytes that the JSON parser reads as nothing.
And **SearXNG serves JSON only when it is switched on**, otherwise answering
403 or a web page; the settings screen says so before the user hits it, and
the tool recognises both shapes and explains them.

`read_page` reduces a page to the words on it. The order of the steps is the
design: script and style bodies go before tags are stripped, or the JavaScript
survives as "text"; `&amp;` is decoded last, so a page showing example markup
still shows it; the article is preferred when the page marks one, but only
when it is at least an eighth of the page, because a site that wraps a
breadcrumb in `<article>` would otherwise have Lin read the breadcrumb.

**The phone itself.** `contact` (the address book, read-only and never
cached), `navigate` (directions in whatever maps app is installed), and
`device_status` (battery, storage, and whether the network actually reaches
the internet, which is the question somebody asking "is the internet down"
means).

**A model that would not stop.** Watched on a phone: asked "play something",
Gemma 4 12B wrote the same paragraph of reasoning over and over for as long as
anybody looked. Two causes, both fixed.

The immediate one is that `play_music` required a `what`, so "play something"
named nothing the tool would accept and the model could neither satisfy it nor
decide to stop. It is optional now, and nothing named means resume whatever
was last playing - which is what somebody saying that to a phone with a paused
album actually wants.

The general one is that **nothing was counting**. A server generates until its
own limit, and `num_predict` is unset by default because overriding a server's
configuration is not this app's business. So the stream is watched:
`looksLikeARut` finds the length of the repeating block from the text itself -
taking the last few dozen characters and asking where they last appeared gives
the size of the circle - and requires three copies **back to back at the end**.
That precision is the whole difference between a useful guard and an
infuriating one: merely counting how often a stretch appears flags numbered
lists, tables and templated paragraphs, all of which are ordinary output. A
rut has nothing between the repeats.

Cut off, the model is told once to stop thinking and answer. Twice means it
cannot answer this question, and the person is told that instead of watching a
third attempt. Checked every couple of hundred characters rather than every
token, because the search is over a window of thousands and a rut runs for far
longer than that.

### Phase 17 — Thinking levels, which Ollama had and Lin was not using

Reported from a phone: thinking "had not been added" for Ollama. It had, as a
boolean - and that turned out to be two bugs wearing one coat.

**Brief and Thorough were the same request.** Ollama's `think` takes a level -
`low`, `medium`, `high`, `max` - as well as a boolean, and Lin sent `true`
whatever was chosen. So picking the cheaper level changed nothing on a server,
and somebody who asked for a little thinking got as much as the model felt
like. Which, on the occasion that produced Phase 16's runaway guard, was the
same paragraph forever.

**Off did not turn thinking off.** The field was omitted entirely when no
thinking was wanted, reasoning that an older Ollama rejects a field it does
not know. But a model that reasons by default reasons when nothing is said, so
"off" meant "off, unless the model feels otherwise". `false` is now sent
explicitly. That is a deliberate trade and worth stating: a server too old to
know the field answers 400, which is visible and fixable, where thinking that
will not switch off is neither.

A test asserted the old behaviour, with a comment explaining why it was
right. It was not, and the comment is now the record of why.

`medium` and `max` go unused on purpose: three choices on the screen map to
three values, and a fourth nothing can pick is not a feature.

The control stays on the Models tab rather than being duplicated onto the
Server tab, because it is one setting that applies to both models - but the
Server tab now says so, since that is where somebody configuring a server
looks for it.

The OpenAI dialect is unchanged: it carries `enable_thinking` through
`chat_template_kwargs`, which is a switch and not a level, and inventing a
`reasoning_effort` that vLLM may or may not accept would risk the whole
request for a setting nobody asked for there.

### Phase 18 — The turn that asked for the tool

The bug underneath several others, and invisible from every angle except the
wire.

Tool calls were **read** out of a server's reply and never **written back**
into the conversation sent to it. So after a tool ran, the server saw:

```
user:      search the web about doctor who lego set
tool:      Results for "doctor who lego set" …
```

with no assistant turn in between that had asked for it. Chat templates render
a tool result in the context of the call it answers; with no such call the
model does not recognise it as the answer to anything. So it asks again. And
again, until the loop's budget stops the turn. From the outside: a tool card
saying **Done**, followed by "this is taking more steps than it should -
stopped after 6 tool calls".

It also explains a report from several phases earlier - Gemma saying "I cannot
read the result of the last tool call because it was not executed" - which was
read at the time as a formatting problem and partly patched as one. The model
was describing its situation accurately.

`Message` now carries `toolCalls` on an assistant turn and `toolCallId` on a
tool result, the loop writes the asking turn into the conversation before the
results, and both dialects render it. The one real difference between them is
worth recording: **Ollama takes the arguments as an object, OpenAI takes them
as a serialised string.** A server handed the wrong one either refuses the
request or sees a call with no arguments. OpenAI additionally requires each
tool result to name the call it answers, so the loop mints ids - a server that
sends none still needs the pair matchable on the way back.

The ids are deliberately not part of what makes two calls the same: a model
asking twice for the same thing has asked twice, whatever the ids are.

**The budget went from six to ten** in the same change. Six was generous for a
turn whose tools were the torch and the calendar, and tight for one that can
search and then read what it found: search, read, search again, read again and
answer is five, and a second look at anything spent the budget on a task that
was going fine. The wall clock and the repetition guard are still underneath
it.

### Phase 19 — A prompt that is asked for rather than written down

**The capability half could not stay true.** The system prompt was a constant
listing every tool and how to treat it, and by the time it was replaced it
still said Lin could not change Wi-Fi — which Shizuku had made untrue phases
earlier — and said nothing at all about the six tools added after it was
written. Nothing enforced the correspondence; keeping it was a matter of
remembering, and it was not remembered.

So `AgentTool` gained `guidance`: a sentence or two about how to use that
tool and what not to claim about it, living on the tool rather than in a
paragraph somewhere else. `capabilityPrompt` assembles the prompt from the
tools a turn is **actually** given — after the role filter and after anything
the user switched off — so it can neither promise a tool that is not there nor
stay silent about one that is. Sorted by name, which is not tidiness: an
unchanged prefix is what lets a server reuse its cache of it, and a prompt
that reshuffled itself every turn would throw that away on every question.

**The personal half is asked.** A free-text "system prompt" box would be
cheaper and worse: most people leave it empty, and the rest write something
that fights the capability half. A ten-question quiz asks only what the app
genuinely cannot work out — what to call them, which language, where they are,
how much to say, what Lin must always ask about first — and composes the rest.

The rule the composition turns on: **an unanswered question contributes
nothing**. Not a default sentence. A prompt that says "answer in English" to
somebody who never said so is a prompt making a decision on their behalf and
hiding it in text they cannot see.

The screen shows the composed text verbatim, not a summary. Somebody handing
an assistant standing instructions about themselves should be able to read
exactly what it was told, and a preview that paraphrased would be the one part
of that screen able to lie quietly.

Two small decisions worth keeping. Picking an already-selected radio option
clears it, because every question is optional and a radio group with no way
back makes that untrue. And the option labels are an exhaustive `when` over
the question-and-option pair rather than a resource name built from strings,
so an option added in `core` without a label is a compile error instead of a
blank row.

**Settings are cards now.** One UI's shape: each group in a rounded container
with its name above it in the accent colour, rather than one flat column where
the only thing separating "which model answers" from "what Lin may do to the
phone" is a slightly larger line of text. The heading sits outside the card,
which is the detail that makes it read as grouping rather than as a list of
boxes.

Twenty-one sections were converted by a mechanical transform rather than by
hand. It produced three compile errors — one `val` declared in one section and
used in another, and two missing imports — which is the argument for doing it
that way: the compiler found every real problem, and a careful manual pass
over two thousand lines would not have.

### Phase 20 — Things Lin does without being asked

The feature that makes "assistant" mean something other than "chat window". A
question somebody asks every morning is a question they should have to ask
once: a routine is a question, a time and some days, and Lin runs a real turn
with the real tools and puts the answer in a notification.

**Periodic, not exact.** The worker wakes every quarter of an hour and usually
finds nothing to do. That is cheaper than it sounds and much simpler than an
exact alarm per task — exact alarms need a permission Android grants
grudgingly, and a routine is not worth asking somebody for that.

Lateness is handled by the rule instead of the schedule, and that rule is the
interesting part. A task late by up to three hours still runs: a briefing at
08:40 because the phone was asleep at 08:00 is still the briefing somebody
wanted. Later than that is skipped until tomorrow, because **a morning
briefing delivered at eleven at night is not a late briefing, it is a wrong
one.**

Three smaller decisions, each with a test:

- **Once a day, not once per check.** Without that, a daily briefing would
  arrive twelve times before lunch. The mark is written *after* an answer, so
  a task that failed is retried rather than lost for the day.
- **Empty days means every day**, rather than all seven being stored. A task
  created before the app knew about weekdays keeps working instead of quietly
  matching nothing.
- **Delegation is withheld in a routine.** A background task is the worst
  place to spend a minute on a server round trip nobody is waiting through.

The settings written after each task rather than at the end of the batch, so
one that answered is not run again because a later one crashed the worker.

This is the first thing to use the tool registry as a singleton rather than
through the conversation's ViewModel — which is what Phase 19's refactor was
for, though it was done for the settings screen.

### Phase 11 — The design pass

Four directions were drawn side by side on a canvas - a quiet page with no
bubbles, an instrument with a status strip and mono tool rows, cards, and an
answer-first layout where the reply is the page - and **cards** was chosen.

What follows is what that pass decided, and what it deliberately kept.

**Done: a look of its own, rather than the wallpaper's.** Dynamic colour is
gone. Taking the palette from the wallpaper is a good default for an app with
no look of its own; this one now has one, and a wallpaper that turns Lin mint
green undoes it. The cost is stated plainly: Lin no longer matches the rest of
the phone. It is built from the dark side first, because that is where this
app is read - at night, in a car, on a phone that is dark the rest of the
time.

**Done: the reply is laid out as what it meant.** Models write Markdown
whether or not anybody asked, and the screen used to show the asterisks. The
parser is in core and tested, and the rule it is built on is that it never
loses a character: a marker that does not pair up stays on screen as text. Two
bugs in it were found by its own tests before it ever rendered anything -
`2 * 3 * 4` read as italics, and a closing `**` that reopened bold.

**Done: the first screen.** An empty conversation was a title bar and a text
box. It is now a greeting and four things Lin can actually do, sent as if they
had been typed - a suggestion that produced "I cannot do that" would be worse
than a blank screen.

**Done: what a tool did.** Running, done and could-not are three different
things, shown as three different cards, each carrying its own word rather than
only a colour. The view model used to hold the running one in one field and
the failed ones in another, which is how a tool that failed while another
started showed as neither; it is one list now.

**What the interface was before this, for the record.** It grew a feature at a
time and it showed. The conversation screen is Material defaults with bubbles; settings
was one scrolling page until it had fourteen sections and is now five tabs;
the panel is new and is the only part designed as a shape rather than
assembled from controls. Nothing has had a pass where someone asked what this
app should feel like to use rather than what it needs to contain. Four
screens behind buttons on a settings page — memory, documents, training,
backup — are there because they had to live somewhere.

**Still open after this pass:**

- Whether the panel and the full screen are two views of one conversation or
  two conversations. They are two today, and that is a layout decision
  standing in for a product one — see the note in Phase 6.
- What the big screen is for. On a Fold's inner screen the conversation is
  width-capped and the rest is empty; the plan has always said a two-pane
  layout arrives when there is tool activity worth showing beside the
  conversation, and there now is.
- Whether settings is five tabs or something else once the screens behind it
  are part of the design rather than appended to it.

**The constraint worth keeping through any of it:** every screen in this app
says what is actually true - which model answered, whether a thing was done or
only opened, whether Lin can see a picture it is being asked about. A prettier
interface that smooths those over would be a worse one.

### Phase 21 — Who Lin is by default, and reminders that actually arrive

Two things, and the second is the one that was missing outright.

**The prompt is asked for once, not written down — but it ships with answers.**
Phase 19 made the personal half of the system prompt come from a ten-question
quiz, with the rule that *an unanswered question contributes nothing*. Correct,
and it had a consequence nobody wanted: a fresh install had no personal half at
all, so Lin had to be told the same four things in every conversation. So the
quiz was asked in the chat instead, and the answers it produced are what Lin
now ships with — a name, a voice, a city, metric units, what the person mostly
asks about, what to do when it does not know, and what to do with work the
phone is too small for.

Two of the eleven questions are deliberately *not* filled in, and that is the
part worth keeping:

- **Language** is blank because "whichever I asked in" is the absence of an
  instruction, not an instruction to answer in English.
- **What to always ask about first** is blank because the answer was "trust the
  switches". That question overrides the Tools tab, so a default of "always ask
  before sending" would quietly make those switches a lie.

Replacing the shipped set is all-or-nothing: answer one question of your own
and none of the shipped answers apply. Merging them would give somebody who
said "call me Sam" a prompt that also claims they live in Lisbon — worse than
either prompt alone, and invisible from the quiz screen.

One question was changed rather than added to: **tone is now a multiple
choice**, because the first person to answer it picked two. "Warm" and
"playful" describe one voice, and forcing a single pick threw half the answer
away. The sentences were reworded so two of them stack into a voice rather than
reading as two contradictory orders — none of them opens with "Speak".

**Reminders.** Lin could already open the clock app with an alarm half filled
in, which is not a reminder — it is Lin asking somebody else to set one. Now
there is `remind_me`, `list_reminders` and `cancel_reminder`: a moment, some
words, and a notification from Lin that arrives whether or not anything is
open.

The parsing is the part with the tests. `whenItFires` turns "in 25 minutes",
"18:30", "tomorrow morning", "monday at 9am" or "2026-10-17 07:00" into one
instant, and **returns nothing when the phrase names no moment** — null rather
than a guess, because a model asked for a time will happily produce "soon", and
a reminder that fires at a time nobody chose is worse than no reminder: it
teaches the person not to trust the ones that are right. Null makes the tool
ask "when?", which is a question with an answer.

What rolls forward and what does not is the other half of that rule. "19:00"
with no day means the next 19:00 — a time already past today is obviously meant
for tomorrow. But "today at 09:00" said in the afternoon is returned *in the
past*, so the tool can say so: moving it to tomorrow would be the parser
deciding what somebody meant. A weekday that is today and gone rolls a week,
not a day. A written date is never moved at all, and one nobody could have
meant — `2026-13-45` — is nothing rather than a fallback.

Four smaller decisions, each with a test that was proven able to fail:

- **The written date comes out of the text before the clock is looked for.**
  "2026-10-17 07:00" holds three pairs of digits that could pass for an hour and
  two of them are the date, which is how seven in the morning becomes five in
  the afternoon.
- **Weekdays are matched as whole words.** "sat" is in "saturate" and "mon" is
  in "money", and a reminder that quietly lands next Monday is a bug nobody
  reports — they just stop setting them.
- **An ambiguous cancellation cancels nothing.** Cancelling the wrong reminder
  fails silently: nothing happens at the moment something should have. Two
  possible matches come back as a question.
- **The id is the moment plus the flattened words**, so the same reminder asked
  for twice is one reminder rather than two notifications a second apart.

On the phone side, the words are stored and the moment goes to `AlarmManager`,
and both are needed: **an alarm does not survive a reboot**, so without the
boot receiver every reminder set before a restart would be listed as pending
and never arrive — the worst of the available failures, because the person has
no way of telling. One that came due while the phone was off is delivered up to
three hours late and dropped after that, which is Phase 20's rule.

`SCHEDULE_EXACT_ALARM` rather than `USE_EXACT_ALARM`: the first is asked for
and can be refused, the second is granted at install and cannot. Refused, a
reminder is still set — inexactly — and the tool says the notification may be a
few minutes late. Refusing to set a reminder at all over a permission about
punctuality would be the app being precious.

### Phase 22 — Lin on the car's screen

Android Auto does not show an app; it draws its own screens from templates the
app hands it. So this is not Lin's interface shrunk — it is a different
interface with two things on it, which is all a screen read at 70mph should
have: **one button that opens the microphone**, and the few things somebody
would otherwise pick the phone up to check.

What the button does is worth being exact about. It starts the same listening
service the car notification starts, and then "Hey Lin, what's the traffic
like" is answered **out loud**. It does not run a question by itself: the
answer has to be spoken to be any use while driving, and the thing that speaks
it is the conversation on the phone.

**The order of the rows is the feature**, so it lives in `core` with tests
rather than in the screen. A car screen is read in the half second somebody is
not looking at the road, and the host decides how many rows fit — so what gets
dropped when there is room for two is not a layout detail, it is which two
things Lin tells a driver. The declared order is: something is wrong, Lin is
listening, the next reminder, who is waiting for a reply, what is playing —
last, because it is the one thing already audible.

A limit of zero rows is **a real answer rather than an error**: it means the
screen has room for the microphone and nothing more, which is the collapsed
state the feature was asked for. Two things follow from that. The guard catches
a negative limit too, because `take(-1)` throws and a host reporting nonsense
is not a reason for a driver's screen to crash. And since **a pane with no rows
is not a legal template**, the no-rows case falls back to a message template
carrying the same button — which is also the case on a fresh install with no
reminders, no messages and nothing playing, so it is reachable rather than
theoretical.

Three decisions that came from reading the library rather than guessing:

- **The host is not trusted by default.** Anything on the phone could claim to
  be a car and bind the service, and what it would get is a button that turns
  on the microphone. The release build accepts only the hosts the library ships
  signatures for; a debug build accepts anything, which is what makes the
  desktop head unit work.
- **The coloured button is marked primary.** `ActionsConstraints` restricts a
  background colour to the primary action, so a coloured action without
  `FLAG_PRIMARY` is a template the host rejects rather than draws.
- **The category is `IOT`.** Android Auto only shows templated apps in the
  categories it supports — navigation, points of interest, internet of things
  and weather — and an assistant is none of them. IoT is the closest true
  description: Lin's car screen is one control surface for things on the phone.

**The caveat, written down rather than discovered in a car park.** Google's own
testing guide says the Android Auto "unknown sources" developer setting *does
not* apply to apps built with the Car App Library. A side-loaded Lin may
therefore not appear on a real head unit even with developer mode on, and the
documented paths are the desktop head unit or a Play internal track. Nothing in
the app can change that; it is a distribution rule, and the existing car
notification remains the way to start listening without it.

### Phase 23 — Three things that were quietly wrong

All three came from one afternoon's screenshots, and all three had the same
shape: Lin said something confident and untrue because a tool failed quietly.

**"I can't find an app called Clock"**, on a phone with a clock on its home
screen. The app list was read with `queryIntentActivities(launchable,
MATCH_DEFAULT_ONLY)`, and that flag keeps only activities whose filter declares
`CATEGORY_DEFAULT`. A launcher icon declares `MAIN` and `LAUNCHER`; most do not
add `DEFAULT`. So **whole apps were missing from the list, and which ones
depended on whether their author had added a category nobody needs.** The fix
is to ask with no flags, which is how a launcher does it. The matching code was
never the problem — it never saw the app.

**A search that found nothing became a claim about the world.** Asked about
Doctor Who Lego sets, Lin answered *"it's possible there aren't any official
sets currently available"*. It had searched, got zero results, and the tool
returned that as a **success** reading "Nothing came back" — so the model did
what a model does with an empty result and reasoned about why the thing might
not exist.

An empty web search is almost never news about the world; it is news about the
search. So zero hits is now a failure, and the message says what actually came
back — an empty reply, data with no results in it, or a page rather than data,
quoting its first forty characters — and tells the model, in those words, **not
to say the thing does not exist, because nothing here says that.**

**And a way to tell the four faults apart.** A search that returns nothing has
four usual causes: no provider chosen, a wrong address, a key the service
rejects, and a SearXNG serving HTML because its JSON format is off. From the
conversation all four arrive identically, as "I couldn't find anything". So the
settings screen now runs one real search and reports the fact rather than a
verdict: `HTTP 200 · 0 results · 4812 bytes starting "<!DOCTYPE html>"`. That
is one line somebody can read out, or screenshot, and it names the fault.

### Phase 24 — Seeing what the tool said, and a search that needs no setting up

Phase 23 made an empty search tell the truth, and the next screenshot showed
the truth arriving as *"it's possible the search settings need checking under
Tools"*. Which is correct, and useless. The tool had said which of four faults
it was; **the model paraphrased it away**, because that is what models do with
a sentence they are given.

**So the tool's own words are now on the screen.** `ToolFinished` carries
`said` — what the model was told, word for word — and a tool row can be tapped
to show it. Nothing summarises it on the way up. This is not a search feature:
every tool failure in the app was previously visible only as whatever the model
made of it, and a paraphrase of a diagnosis is not a diagnosis.

**And a provider that needs nothing.** The honest finding from an afternoon of
probing is that there is no free, keyless, reliable *web* search API:
DuckDuckGo's HTML endpoints answer a challenge page to anything that is not a
browser, and every general API wants a key or a server. What does exist is
Wikipedia's search API — documented, keyless, stable — so that is now a
provider, and the default.

The default changing from "nothing" to Wikipedia is a change of mind worth
recording. The first default was chosen on the grounds that an app should not
pick a third party on somebody's behalf. What it produced in practice was an
assistant that could not look anything up until its owner had found an API key,
which is a worse imposition than an encyclopaedia lookup nobody asked for.
Anybody who wants their own SearXNG or a Brave key is two taps away.

It is **not** a web search and is never sold as one. The results carry a note
saying so — *"Wikipedia only, not the whole web — say so if the question was
about prices, news, releases or anything from the last few months"* — because
six encyclopaedia articles and a question about this week's prices is a wrong
answer waiting to happen, and the model cannot see which search it is holding
unless the result tells it.

Two details the API forced:

- **Links by page id, not title.** A title has to be percent-encoded and gets
  it wrong for exactly the pages whose names contain the interesting
  characters. `?curid=` is the id the API just handed back.
- **The highlighting markup comes out.** Snippets arrive with
  `<span class="searchmatch">` around every matched word, because the API is
  written for a web page. Left in, a model reads the tags as part of the
  sentence and repeats them. The ampersand is decoded **last**, or `&amp;lt;` —
  the four characters `&lt;` — turns into a less-than sign.

### Phase 25 — "It worked before, and nothing changed"

The tap-to-see row from Phase 24 did its job on the first try. The tool had
said:

> The search service (searxng) answered with data with no results in it.

Which named the provider and the shape, and still missed the fault — because
**a SearXNG that answers with an empty list is usually not saying "no
results".** It is saying its own upstream engines would not talk to it, and it
says exactly which ones, in a field nothing in Lin was reading.

`webutils.get_json_response` in SearXNG builds `unresponsive_engines` as a list
of `(engine, message)` pairs. An instance whose address has been blocked
answers 200, with an empty result list and a full list of excuses:
`[["google","CAPTCHA"],["startpage","Suspended: CAPTCHA"]]`. That is the whole
explanation for "it worked for weeks and then stopped with nothing changed at
either end" — engines block the address a self-hosted instance calls from, and
nothing on the server has to change for it to start happening.

So Lin reads the field. The failure message now names the engines and says
whose problem it is, and the settings trial leads with them —
`HTTP 200 · 0 results · 812 bytes · engines down: google (CAPTCHA), startpage
(Suspended: CAPTCHA)` — because once the engines are named, the first bytes of
JSON tell nobody anything. Entries that are a bare string rather than a pair
are accepted too: the field is in no specification, other clients have met both
shapes, and an engine name with no reason is still worth saying.

**And a fallback, because a correct diagnosis is not an answer.** When the
chosen search returns nothing, Lin tries Wikipedia — which needs no setting up
and was already in the app — and labels it every time: *"the searxng search
returned nothing, so these are from Wikipedia instead — say that, and say it is
not a web search"*. If Wikipedia is empty too, the original diagnosis is what
the model gets. The point is that somebody whose server has been blocked by
Google should still be able to ask their assistant a question tonight.

### Phase 26 — "The other agent worked, so it's Lin"

The previous two rounds put the blame on the user's SearXNG, on the strength of
`unresponsive_engines` naming four engines that had refused it. That is what the
server said about its own calls, and it was true — and it was **not** an answer
to the actual report, which was that the same instance, at the same address,
answered another client. Defending the server twice was the wrong move; a
report that a different client works against the same endpoint is a report
about *the request*, and the request is the one thing Lin controls and I had
not yet compared.

So the request was compared, against the SearXNG clients people actually use
(`mcp-searxng`'s `buildSearchUrl`) and against the instance's own bot detection
(`searx/botdetection/`). Lin differed in four ways, each one a real way for the
same instance to answer differently:

- **No `safesearch`.** The instance default applies when nothing is sent, and a
  strict default filters a result list to nothing — which reads exactly like the
  engines refusing. Lin now sends `safesearch=0`. A search tool returns what is
  there and lets the person decide.
- **No `language`.** Same shape: a default language narrows what engines return,
  which is an odd thing for an app that answers in whatever language it was
  asked in. `language=all`, and `pageno=1` made explicit.
- **`Accept: application/json` only.** `http_accept.py` rejects any request whose
  Accept header has no `text/html` in it, so an instance running the limiter
  would turn an app that asked only for JSON away. Lin now asks for both.
- **A stock Android user agent.** The limiter matches a list of bot agents and
  `Dalvik/2.1.0 …` is the shape it looks for. Lin now sends a browser-shaped one
  that still names itself (`… Lin/1.0`) — not a pretence of being a browser.

**And the question itself.** The model's queries were long — `NVIDIA Jetson Orin
Nano specs performance 2024 2026` — because that reads as thorough. A search
engine reads four extra constraints, and on a small set of engines that returns
nothing. When a search comes back empty, Lin now retries once with the first few
real words: years the model added, words that describe the kind of answer wanted
rather than the subject, and anything past the fifth word come out. `null` when
that changes nothing, so it never pays for a second identical search. The
stop-word list is deliberately short, and "day" was taken off it after writing
it: *Day of the Jackal* is a real query, and a list that mangles it is worse than
no list.

**What this does not claim.** I cannot reach the user's instance, so none of the
four differences is *proven* to be the cause on that machine. They are the
concrete differences from clients known to work, each fixed. The failure message
now carries **the exact URL requested**, and the settings trial prints it too, so
the next report can be settled by pasting that URL into the other agent's
environment rather than by anybody's reasoning.

### Phase 27 — Read the pages, and keep the links

Once search returned results, the next report was about what Lin did with them:
asked for the Orin Nano's specifications, it found NVIDIA's own product page and
answered from **its title**, with no figures and no links. "More like Google
search without any useful info."

That was structural, not a prompt problem. `search_web` handed the model titles
and the search engine's snippets — sentences chosen to make somebody click — and
a model that only ever sees a results page can only ever paraphrase one.
Reading a page was a second tool call the model rarely made.

**So `search_web` now reads the top three pages itself**, in parallel with a
nine-second deadline (a page that misses it falls back to its snippet, marked
*not read*, so the model knows the difference), and hands the model what each
page actually says, numbered `[1]`, `[2]`, `[3]` with its link.

Which part of the page is the interesting problem, because the budget is ~2,500
characters a page to fit a small context. The top of a product page is the
slogan; the specification table is far below. And the table's rows — `AI
Performance | 67 TOPS` — never mention the product by name, so selecting lines
that match the question misses exactly the figures that were asked for. What
does match is **the heading above the table**. So each matching line brings the
lines after it along, up to about a table's worth; that one rule is what gets
the table in. The opening is kept too, so the answer can say what the page is.
Lines are kept whole, so a row is never cut from its label. Table cells now
keep a ` | ` divider, so `GPU 1024-core Ampere CPU 6-core Arm` is no longer one
unreadable sentence. And on NVIDIA's actual page, the section menu arrived
twice, as forty lines at the very top; bare bullets and repeated short lines
now come out, while repeated table rows stay.

Run on the real page, the result carries 67 TOPS, 102 GB/s, the 1.7 GHz CPU and
the 1.7× improvement — the things that were asked for.

**The links.** The result ends by telling the model to answer from the page text
rather than the titles, give figures as the page gives them with `[n]` citations,
and finish with a *Sources* list of the links it used — at the end, where a small
model still sees it. And links are now **tappable**: markdown `[label](url)` and
bare addresses both become real links on screen, with the sentence-ending full
stop left out of the address and an address's own brackets kept. A source list
nobody can tap is a list nobody follows.

### Phase 28 — The fetcher only ever read the first chunk

The page-reading screenshot settled it: under *What the page says*, Lin had
been given `Tegra - Wikipedia` and half of a `<link rel="stylesheet"` tag. The
same page, parsed in full, gives twelve thousand characters of article.

`Web.get` read the response with **one** `reader.read(buffer, 0, MOST_CHARS)`.
That reads as "up to the cap" and means "whatever is buffered right now" — on a
network stream, the first few kilobytes. So since Phase 16 every page Lin read
was its first chunk, which for most sites is the `<head>`: a title and an
unterminated tag. It is also, almost certainly, the SearXNG fault the previous
four phases chased: a SearXNG reply *with* results is tens of kilobytes, so Lin
got the first part of a JSON document, which does not parse, and reported "no
results". An empty reply is small enough to arrive whole — so the only answers
that survived intact were the failures, which is why every diagnosis looked
like the server's fault. Those phases were not wasted, the diagnostics are real,
but they were downstream of this. The bug was mine, written in Phase 16.

`readAtMost` now reads in a loop to the end or the cap, tested against a reader
that hands back a thousand characters at a time — the shape of a real network
stream, and the shape the single read could never have survived.

**And the loop.** Lin searched six times in a row with reworded queries and got
the same three pages each time. A small model does not notice it is going round,
so the result now says so: when every page in a search was already returned
together within the last three minutes, the pages are not fetched again and the
model is told it has these already, not to search again, and to answer from
them or say plainly what it could not find. A new page among old ones is
progress and is not stopped.

### Phase 29 — Search from the phone, and do not read a CAPTCHA

With the fetcher reading whole responses, the next answer still had no
specifications, and its only source was Wikipedia: the SearXNG search had come
back empty and the fallback had taken over. Whatever the instance is doing on a
given minute, the lesson of the last several phases is that Lin cannot depend on
it alone.

**The phone searches for itself.** A self-hosted SearXNG searches from its
server's address, and the engines behind it block that address. The phone has an
address of its own. So when the chosen search returns nothing (after the
plainer-words retry), Lin asks DuckDuckGo's lite page directly, from the phone,
before falling back to Wikipedia — a real web search ahead of an encyclopaedia.

Built against the live page rather than memory: the lite page answers a **GET**
with a 202 bot challenge and a **POST** — the way its own form asks — with
results; each result is an `<a class='result-link'>` whose `href` is the page's
own address, with the snippet in the next `result-snippet` cell. The real page
fetched for "orin nano super specs" is kept as a test fixture, so the parser is
pinned to markup that exists. Its first result is NVIDIA's product page and its
second TechPowerUp's spec database.

**And a bot check is not a page.** Running the whole pipeline on those real top
three showed TechPowerUp answering *"Automated bot check in progress… Drag the
handle to the target"* — which Lin would have handed to the model as the page,
and a small model will dutifully summarise a CAPTCHA. Short pages that say they
are a challenge are now treated as unread (snippet only, marked), and one extra
result is fetched so the next one takes its place. On the real results the
briefing then carries NVIDIA's 67 TOPS, 102 GB/s and 1.7 GHz, and the developer
guide's ports, M.2 slots and storage.

### Phase 30 — A model that thinks without saying so

With search working, the next turn failed outright: Ollama answered 400 —
`"hf.co/unsloth/gemma-4-12B-it-qat-GGUF:UD-Q4_K_XL" does not support thinking`
— for a model the user runs with visible thinking in OpenWebUI every day.

Both are true. Ollama accepts `think` only for a model whose template declares
it, and a GGUF pulled from Hugging Face can reason perfectly well without
declaring it: the reasoning arrives inline, as `<think>…</think>` in the reply.
OpenWebUI never sends `think`; it reads the tags. Phase 17 made Lin always send a
level, which is right for models that declare thinking and fatal for this one.

So a refusal is now a request to ask again, not a failure: on exactly that 400,
the turn is sent once more without the field, and the server-and-model pair is
remembered for the life of the process so no later turn pays for the refusal
again. Any other 400 is still reported as it was.

And the tags are read. `InlineThinking` takes `<think>…</think>` out of the
streamed reply and sends it where the server's own thinking goes, so the
model's talking-to-itself is not the first thing on the screen or the first
thing spoken. Streamed, which is the difficulty: a tag can arrive as `<thi` then
`nk>`, so text that could be the start of a tag is held back until the next
chunk says whether it was one. The blank line a model leaves after its thinking
is not counted as the start of its answer.

### Phase 31 — More search services, and a floor under all of them

Asked for after the phone-side fallback proved itself: more ways to search, and
the fallback built in rather than bolted on.

**Three more providers.** *DuckDuckGo from the phone* is now a choice in its own
right, and the default: it is the only keyless option that is a real web
search. *Firecrawl* — hosted with a key, or self-hosted at the user's own
address, which is the reason that field exists — and *Tavily*, with a key. Both
send each result's page along with the result, so Lin uses the page it was given
instead of fetching it, and a page Lin never fetches cannot answer Lin with a bot
check. Request and response shapes are from each service's API reference:
Firecrawl `POST /v2/search` with `scrapeOptions.formats = [{type: markdown}]`,
results in `data.web` (the v1 `data` array is read too, because a self-hosted
instance is whatever version its owner last pulled); Tavily `POST /search` with
`include_raw_content`, results in `results`.

**One key per provider.** There was one shared key field, which would have sent
a Brave key to Tavily the moment the radio button moved. Each service now has its
own.

**A floor under every choice.** The fallback used to run only when a search came
back empty. Now it runs for every kind of failure — not set up, unreachable,
refusing, rate-limited or empty: the chosen service, then (if it answered with
nothing) the same service with plainer words, then DuckDuckGo from the phone,
then Wikipedia. Each fallback result says which search failed and why, and the
model is asked to mention it in one line — enough for the person to know their
chosen search is broken, without the answer becoming about the failure.

A caveat recorded rather than hidden: the guards that keep a JSON `null` page from
becoming the text "null" cannot be proven by the unit tests, because the desktop
`org.json` they run on already returns "" there, while Android's returns "null".
The guards are for the phone.

### Phase 32 — A conversation that reads like the assistants people already use

Asked for after search started working: refine the experience against how
Gemini, ChatGPT, Claude, Copilot and Perplexity behave on Android, iOS and the
desktop. The screenshots of the previous few phases were the brief — a search
that read four pages produced four full-width cards, and they sat *under* the
answer they had produced.

**The steps go before the answer, in one line.** A finished answer joined the
message list while its tool cards rendered after the whole list; now the latest
answer is held back until after its steps. The steps themselves collapse into a
summary in plain words — *"Searched the web · Read a page ×3"*, or *"Reading a
page…"* while it runs, with a count of anything that failed at the end — and
expand into the per-tool cards, whose tap-to-see detail is still the diagnosis
on a bad day. Tool names become verbs ("Searched the web", not "search web ·
Done"); an MCP tool falls back to its own name, readable and never wrong.

**Sources are chips.** Lin ends a web answer with a source list, which arrived
as a block of long addresses at the bottom of a chat bubble. The trailing list is
lifted off the body and shown as numbered chips with the domain — `1 nvidia.com`
— matching the `[1]` in the text. Only a trailing list of addresses counts; a
list with prose in it is left exactly where it was.

**What every answer can do**: copy, read aloud, ask again (the latest only —
retrying an older one would rewrite history later turns were built on, and the
old answer is removed rather than kept beside the new one so the model is never
shown its own contradiction), and share.

**Stop.** Send becomes Stop while an answer is written, where every assistant
puts it, and what had arrived is kept as the answer — somebody who stopped it
half way may have had what they needed. A step running when stopped is marked as
not finished rather than spinning forever.

**Smaller things that make it feel finished:** a jump-to-latest button when
scrolled away from a reply still streaming in, a confirming tick of haptics on
send, stop and copy, and a greeting that uses the time of day and the person's
name — *"Good evening, Sam."*

The summary, source-splitting and greeting logic is in `core` with tests; the
layout is compile-checked, not yet seen on a device.

### Phase 33 — Open the gallery, whatever it is called

"Open gallery" and "open clock" still failed after Phase 23's fix to the app
list. Matching by name was always going to be fragile for the apps every phone
has: the gallery is "Gallery" on one phone, "Samsung Gallery" on another,
"Photos" on a third and "גלריה" on any phone set to Hebrew.

Android already knows which app does each of these jobs. So `open_app` now goes:
an app called exactly what was said first (somebody's own app named "Clock" is
the one they mean); then the **kind** of app — gallery, clock, camera, settings,
calendar, calculator, contacts, messages, email, maps, browser, music, files,
phone, store — opened with the standard request every Android phone answers
(`makeMainSelectorActivity` with `CATEGORY_APP_GALLERY` and friends,
`AlarmClock.ACTION_SHOW_ALARMS`, `INTENT_ACTION_STILL_IMAGE_CAMERA`,
`Settings.ACTION_SETTINGS`), which picks the person's own default and involves no
label at all; and only then the looser name matching. A kind is only recognised
when it is the whole request — "clock widget pro" is somebody's app and is not
hijacked into the system clock.

A failure now says what Lin can see — how many apps, and the nearest names by
edit distance — so a phone where Lin can see almost nothing shows up as that
rather than as "there is no such app". And the tool says outright that opening
the gallery is its job, because "open gallery" reads to a small model like a
request about photos and it reached for the photo search instead.

### Phase 34 — The digest that threw everything away

"Nothing has been added to long-term memory since the last test." The automatic
digest — the one that reads a finished conversation and keeps lasting facts —
ran its saving loop inside `withEmbedder { … }`, which returns null when no
embedding model is downloaded. Without one, the loop never ran, every fact from
every conversation was discarded, and the worker reported success. "Remember X"
had been fixed for exactly this long ago (save first, embed if possible); the
digest was the half that was missed.

Facts are now kept with or without a vector, and get one when a model arrives.
Duplicates are caught by their words always, and by meaning when there is a
vector to compare.

And the digest no longer fails silently. Every way it can end — no model ready,
the model refusing, nothing lasting in the conversation, kept two of three —
writes one line, and the memory screen shows it, beside the switch that allows
learning at all. That switch is off by default, and this is the screen somebody
opens when Lin has remembered nothing, so it belongs here as well as in settings.

### Phase 35 — The redesign you can see

"I don't see any of the UX/UI improvements I asked for." Phase 32 changed what
happens inside a conversation — steps, sources, actions, Stop — but the screen
that opens looked the same. This phase changes the frame, after Gemini, ChatGPT
and Claude:

- **Conversation history.** A drawer from the left (menu button) lists past
  conversations under Today, Yesterday, Previous 7 days and Older, with search,
  New chat, tap to reopen and delete (asks first). Before this a conversation
  vanished when the next began, after half an hour away or on New chat. Kept in
  one file on the phone (`history.json`, the latest 200), titled by the first
  question; only what either side said is kept, not tool output.
- **Header.** Menu, "Lin" and a short model chip ("gemma-4-12B · server")
  instead of the full download path under the name.
- **Answers on the page.** Lin's answers are no longer in a box; a small Lin
  mark sits beside them, with sources and actions aligned under the text. The
  person's messages stay as bubbles.
- **One pill composer.** Attach inside on the left, microphone and send inside
  on the right, instead of four separate shapes.
- **Suggestion cards with icons**, each in its own colour.

### Phase 36 — Android Auto could not see Lin

The car screen from Phase 30 never appeared. Android Auto only binds a Car App
Library service after it finds the `com.google.android.gms.car.application`
meta-data pointing at an `automotive_app_desc.xml` that declares
`<uses name="template"/>`. Lin had the service and not the declaration, so the
host never looked. Now added. A build installed from outside the Play Store also
needs Android Auto's developer settings → "Unknown sources" turned on.

### Phase 37 — Each phone's own Hugging Face token

Settings → Models has an optional Hugging Face token field, masked, entered per
phone. It is sent only with model downloads, only to huggingface.co over HTTPS
(`ModelDownload.authFor`), so an APK handed to somebody else carries nobody's
token and theirs goes nowhere but Hugging Face. A 401/403 now says what to do
(accept the licence, add a token). Every other key — server, search providers,
MCP — was already per-phone; nothing is built into the APK.

### Phase 38 — Lin as a conversation in Android Auto

Android Auto's microphone button belongs to Google's assistant, and an ongoing
notification never reaches the car screen. What Android Auto does carry for any
app is messaging: it announces a message, reads it aloud, and sends a spoken
reply back as text. So with "Chat with Lin in Android Auto" on, Lin posts a
MessagingStyle notification (reply action with a RemoteInput, mark-as-read,
semantic actions, no UI), declared with `<uses name="notification"/>`.

A reply goes to `CarChatReceiver`, which updates the notification at once (the
car shows a spinner until it changes) and queues `CarChatWorker` — expedited,
appended so replies are answered in order. The worker continues the car
conversation from the last half hour, runs a turn told the person is driving
(one to three spoken sentences), and posts the answer with sound so the car
announces it. The car hears `forTheCar(answer)`: no sources, citations,
addresses or markdown, cut at a sentence. The full answer and the whole drive
are kept in the history drawer under ids starting `car-`.

The greeting that gives the driver something to reply to is posted when the
setting is turned on, when Lin opens, and when Lin's car screen opens.

### Phase 39 — Instant commands, and pictures with search

**The lag on "flashlight on".** Every command went through the model twice: once
to choose the tool, once to say it was done — on a 12B server model, a second or
two before the light came on. `quickCommand` now matches the plain ones (switch
X on/off; open/launch an app) and runs the tool at once, answering with the
tool's own first sentence (`quickReply`). It is deliberately narrow — questions,
"and"/"then", commas, timing words and long phrases go to the model — and a
command that fails falls through to the model, which can explain or try another
way. Considered instead: Google's FunctionGemma "Mobile Actions" fine-tune
(270M, ~290 MB, ~0.3 s to first token on CPU, 85% on its own eval). It would
still be a model call, 15% wrong on its own test set, and its function names are
not Lin's; a matcher is instant and never wrong on what it matches. It remains
an option for fuzzier phrasings later.

**Pictures in web answers.** The pages a search already reads name their own
preview picture (`og:image`, `twitter:image`; or the first image in a page a
service sent as Markdown). Up to four are shown beside the answer, tappable,
with icons and logos skipped and large images decoded at display size. On by
default; Settings → Web search → "Show pictures from search results".

### Phase 40 — A small model goes first

The routing asked for: little tasks on the phone, everything else to the server
or the main local model. Order per message: the instant matcher (Phase 39,
no model), then MobileActions 270M if downloaded, then the main model.

MobileActions is FunctionGemma 270M fine-tuned on Google's Mobile Actions set.
It knows seven functions; four map to Lin (flashlight on/off →
`phone_setting`, `create_calendar_event` → `add_to_calendar`, which only opens
the calendar filled in, `show_map` → `navigate`). It is given those four, in its
dataset's words and its trained prompt (`mobileActionsPrompt`). Two guards keep
chat out of it: it is asked only when the request's words point at one of its
actions (`actionsFor` — flashlight/torch, calendar/meeting/event, map/directions),
and a pick outside those is discarded (`fromMobileAction`). It runs on the CPU
with a 4 s deadline, is loaded at launch, and anything it does not settle goes
on to the main model. Gated on Hugging Face: accept the licence, add a token
(Phase 37), then download it from Settings → Models. A switch there turns it off.

Not verified on a device: that the `.litertlm` bundles FunctionGemma's template
so LiteRT-LM's tool API returns structured calls. If it does not, the model's
answer has no tool call and every request simply falls through.

## Risks and kill criteria

**Brief quality is the whole architecture.** If a 2B model cannot write a
usable brief even with a schema and constrained decoding, delegation does not
work. The fallback is worth having in mind now: the orchestrator stops
delegating and simply *routes* — one backend per turn, chosen by the
orchestrator, no sub-agents. Simpler, still valuable, still better than the
fork. We would know within Phase 3 rather than at the end.

**"Fully local" versus a server that is not always there.** Addressed in Phase
2 by design. Reaching the server from outside the house means Tailscale or a
VPN, not port forwarding.

**Fine-tuning Gemma for mobile functions: last, if at all.** It is doable with
LoRA, but it means synthesising an intent-to-call dataset, owning the
`.litertlm` conversion and quantisation, and redoing it on every base model
upgrade — for a gain that is usually small next to a tight schema and
constrained decoding. It also partly contradicts the design: if the local model
handles everything well, the delegation is pointless. Prove the routing first.

**The open crash.** The fork currently will not open on the one device that
matters, and the cause is unknown. This is load-bearing for *this* plan, not
just for the fork: if it is in the Gallery's own code, a new app is free of it.
If it is LiteRT-LM on that specific device, **a new app on the same engine
inherits it unchanged.** v0.5.3 ships the breadcrumb that answers this.
Phase 0 does not depend on the answer; Phase 1 does.

## Open questions

1. Does an Ollama server already exist, and which model on it? This decides
   what Phase 2 is tested against.
2. **The Ollama base URL and the model on it.** The server is live; Phase 2
   needs the address to test against, and the model name decides whether tool
   calling is available on the remote side.
3. Voice in v1, or Phase 6 as written?

Answered already: the fork is archived as a reference; the new app is
`com.lin.agent`; the device has 12 GB, so E2B is the default and E4B a setting;
the embedder is the 740M multimodal one because media search is in scope; NPU
waits, but the registry is built for it and this device's SoC is covered when
it lands; folding is handled on both sides.
