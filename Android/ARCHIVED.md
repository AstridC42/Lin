# Archived — reference material, not a shipped app

This is the Google AI Edge Gallery fork that Lin was first built inside. **It is no longer built,
released, or meant to be installed.** The app that is being developed lives in
[`../agent/`](../agent/).

It stays in the repository on purpose. Not out of sentiment: it is a working implementation of
several things that are much easier to read than to rederive, and a few that cannot be derived at
all.

## What it is still good for

| Looking for | Read |
|---|---|
| Starting a LiteRT-LM engine, and the device quirks that make it work | `src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt` — vision on GPU and audio on CPU for Gemma 3n/4, GPU disabled on Pixel 10, Tensor G3 handling. Accumulated from real hardware; not in any specification. |
| HuggingFace sign-in for gated models | `HuggingFaceApiClient` and the AppAuth plumbing. The new app does not need it — the models it uses are Apache-2.0 and ungated — but a gated model would. |
| Embeddings, a vector store and semantic search over media | `customtasks/smartalbum/` and `services/semanticretrieval/` — MediaPipe `UniversalEmbedder`, `SqliteVectorStore`, `SemanticRetriever`. This is the reference for the new app's media search. |
| Video trimming and playback | `customtasks/videomomentfinder/` |
| Resumable model downloads with SoC-specific variants | `data/` and `ui/modelmanager/` |
| Phone actions, and the guards around them | `tools/` — including why a call is staged in the dialler by default, and `garbledNumberRefusal` |
| The memory vault | `memory/` — retention windows, note merging, conversation digests |
| Surviving a crash on a phone with no computer attached | `assistant/CrashReport.kt`, `assistant/SafeMode.kt`, `data/StoreHealth.kt` |

## Why it was retired

Its central abstraction is **Task × Model → Screen**: pick a task, pick a model, load it, do one
thing, unload. That is a model-demo harness, and a good one. An assistant needs close to the
inverse — one long-lived conversation, several specialised models resident at once, tools chosen
per message, work continuing in the background.

Nearly everything that cost real time here traces to that mismatch rather than to the code being
bad: forty-four tool schemas in one prompt because there is one conversation per task; a context
window sized by an allowlist written for a chat demo; a direct-call switch that existed and was
unreachable for weeks because settings belonged to a model-centric UI.

The reasoning in full, and what replaces it, is in [`../docs/NEW-APP-PLAN.md`](../docs/NEW-APP-PLAN.md).

## What was turned off

The workflows under `../.github/workflows/` that built and published this app —
`build_android.yaml`, `prerelease.yml`, `release.yml`, `keep-signing-key.yml` — are reduced to
`workflow_dispatch` only. They cannot fire on their own, and nothing was deleted, so the automatic
triggers are a diff away if this ever needs building again.

The releases already published (v0.3.1 through v0.5.3, and the rolling `dev` tag) are untouched.
They are the last builds of this app that will exist.

## One unresolved thing

v0.5.3 shipped a launch breadcrumb and a crash report written somewhere a file manager can reach,
to diagnose a start-up crash that was never identified — the app closed before drawing anything, so
the report it wrote could never be displayed. That diagnosis is still worth having, because if the
cause was LiteRT-LM on the device rather than this code, **the new app inherits it unchanged.** The
report, if one was ever captured, is at `Android/data/com.lin.assistant/files/last-crash.txt`.
