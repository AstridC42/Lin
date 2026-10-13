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
- **The phone itself**, through a tool layer, with MobileActions-270M as an
  intent parser where that helps.

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

**Still to do:** notification reply with its read-back, calendar and reminders,
media control, and MobileActions-270M as the intent parser. The remaining
orchestrator tools (`recall`, `ask`, `answer`) belong with Phase 5's memory.

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

**Still to do:** the background digest.

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

### Phase 7 — Replace

Take over `com.lin.assistant`, migrate the memory vault out of the fork, retire
the fork.

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
