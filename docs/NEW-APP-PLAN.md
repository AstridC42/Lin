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
