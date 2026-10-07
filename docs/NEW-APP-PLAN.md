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
- **A new application id: `com.lin.agent`.** Not a replacement for the fork's
  `com.lin.assistant`, so both install side by side and the fork stays usable
  while this one is a skeleton. One word to change if you want a different one.
- **The fork is archived, not maintained.** It becomes a reference: a working
  implementation of engine setup, device quirks, HuggingFace auth, semantic
  retrieval and a hundred small Android details, sitting in the same repository
  where it can be read and copied from. No new features, no releases.

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
gallery tasks: no Prompt Lab, no benchmarking, no model browsing or comparison,
no Ask Image / Audio Scribe as separate screens, no Tiny Garden, no Scrapbook,
no Instant Media Search. No multi-model picker UI. No image generation.

The focus is the thing the fork could not be: one assistant that is good at
being an assistant.

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

EmbeddingGemma 2 is a family, not one model. All three map into the same
768-dimensional space with an 8,192-token context, int4 QAT transformer and
embeddings:

| | Modalities | Params | Generic file |
|---|---|---|---|
| **Text 270M** | text | 270M | **164 MB** |
| Text-Vision 440M | text, images | 440M | not measured |
| 740M | text, images, video, audio | 740M | 484 MB |

**Use Text 270M for memory.** Upstream's allowlist lists only the 740M, because
it ships it for photo and video search. Lin's memory notes are text: the 270M is
three times smaller, lands in the same vector space, and sits beside a 3.66 GB
chat model without a fight. The 740M earns its place only for semantic search
over the photo library, which is a later feature and a separate decision.

The embedder does **not** go through LiteRT-LM's `Engine`/`Conversation`. It
runs on MediaPipe `UniversalEmbedder` and `SemanticRetriever` (tasks-retrieval
1.1.0), a different runtime path, hence a separate `Embedder` interface below
rather than squeezing it into `LlmBackend`.

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

### Phase 1b — Model acquisition

Resumable download, SHA-256 verification, HF licence acceptance and token.

**Measurement:** a fresh install on a wiped device gets to a working model
without a computer.

### Phase 2 — The server backend

`OllamaBackend` on the same interface. Host and port in settings, reachability
probe, and an explicit UI state for which backend answered.

The degradation policy is designed here, not bolted on: away from home the big
model does not exist, and an assistant that works at home and fails on the
train loses trust faster than one that is merely weak.

**Deliverable:** you can see which backend answered, and why.
**Measurement:** with the server switched off, the app still answers locally
and says so. With it on, a request routed to it returns.

### Phase 3 — Orchestration and delegation — THE RISKY ONE

The turn loop, the five orchestrator tools, the brief schema under constrained
decoding, sub-agent execution on Ollama, result folding.

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

**Measurement:** the staged-action policy holds — calls open the dialer unless
directly authorised, messages wait for a send, and a notification reply is read
back before it goes.

### Phase 5 — Memory

Port `memory/`. Replace word-overlap search with embedding retrieval using
**EmbeddingGemma 2 Text 270M**, 164 MB and co-resident with the chat model,
through MediaPipe `UniversalEmbedder` and a vector store, with Instant Media
Search as the reference implementation. Background digest after a conversation
ends.

**Measurement:** recall over a set of planted facts, and that nothing invented
appears in a reply when the vault has no answer.

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
2. Voice in v1, or Phase 6 as written?
3. How much RAM does the phone have? It decides whether E4B can be the default
   or stays an option behind a warning.
4. `com.lin.agent` as the application id, or something else?

Answered already: the fork is archived as a reference, the new app takes a new
id, and both Gemma 4 E2B and E4B are offered.
