# Status

Where the project actually is, as of `0.5.2`.

**Upstream base:** `google-ai-edge/gallery` at `70061fd` (version 1.0.20,
1 October 2026). What that merge brought with it is at the bottom of this file.

Three lists, kept apart on purpose: what has been used and works, what has been
built but nobody has confirmed on a real phone, and what cannot be done at all.
The third list is the one most worth reading — several things an assistant
"obviously" ought to do are closed off to a sideloaded Android app, and knowing
which saves asking for them again.

## Working

Confirmed on a real phone.

- **One chat for everything.** Any general-purpose model loadable from the chat;
  images and audio attachable in every mode; a mode button that biases the
  assistant rather than changing screens.
- **Phone actions.** Calls and texts (staged for you to send), alarms, timers,
  opening apps, maps and directions, the torch, settings pages, links. A call
  can also be placed outright rather than staged, via **Settings > Phone > Let
  the assistant place calls directly** - the capability was always there, and
  its only switch was in the model gallery's settings dialog, which an
  assistant user never opens. That is why every "call Dad" stopped at a dialer
  with the number typed in.
- **Reminders** the assistant keeps itself, which survive a reboot — distinct
  from alarms, which are handed to the clock app and cannot be read back.
- **Skills**, including Wikipedia lookups. This took two attempts: the skills
  were installed and listed all along, but listed where a small model had
  stopped reading, and reached through a tool described only as "Loads a skill".
- **The assistant role.** The app can be set as the phone's digital assistant
  and answers the power-button hold in a pop-up over whatever is in front.
- **Voice.** Dictation, spoken replies, a chosen voice with speed and pitch, and
  hands-free conversation in the pop-up: it listens again as soon as it stops
  speaking and gives up after four seconds of silence.
- **Reading the screen.** The pop-up offers **This screen** when the platform
  hands over a screenshot or the text of the app in front, so "what is this" and
  "summarise this" work without copying anything. Whether either is offered is
  the system's decision — a secure screen withholds both — so the button only
  appears when there is something to ask about.
- **Remembering you.** Superseded by the memory vault - see below. The old twenty-fact list is
  carried over on first launch and its contents become notes you wrote yourself.
- **Staying loaded.** An opt-in foreground service keeps the model in memory
  after the chat is closed, so everything that summons the assistant can answer
  in place instead of apologising and opening the app. It costs the model's
  memory while it holds, and says so in an ongoing notification.
- **Releases.** Every push to `main` publishes a rolling pre-release with four
  ABI APKs, signed with a stable key so they install over each other.
- **Instant Media Search** and **Video Moment Finder**, ported from upstream -
  search your own photos by describing them, and find and cut a moment out of a
  long video. Both need **EmbeddingGemma-2**, which arrived in the `1_0_20`
  allowlist. Listed here rather than under *Working* only because nobody has
  run them on a phone yet.

## Built, not yet confirmed

Everything here is implemented, compiles, and ships in the current build.
Nobody has reported it working on a phone, so treat it as untested.

| Capability | What to try | What it needs first |
|---|---|---|
| Weather | "what's the weather", "will it rain Friday" | Nothing. Optionally allow location to drop the town name. |
| Calendar | "what's on tomorrow", "move my 3pm to 4", "cancel the dentist" | **Allow the calendar** in the Guide panel |
| WhatsApp and Telegram | "message Dana on WhatsApp" | The app installed. WhatsApp needs the contact's number in international form. |
| Music | "play Radiohead" | Pick a **Music app** in the Guide panel |
| Notifications | "what did I miss", "reply to Sam" | Notification access, from the Guide panel |
| Media control | "pause", "next", "what's playing" | The same notification access |
| Smart home | "turn off the kitchen light" | A Home Assistant address and token |
| Web search, pictures, pages | "search for…", "show me a photo of…" | A SearXNG instance with `json` in `search.formats`. A plain `http://` address on your own network now works; anything on the internet needs `https://`. |
| What is on the screen | Hold the power button over an article or a message, then **This screen** | A model that can see, for the screenshot; the text works on any |
| Memory | "remember that I take the train", then ask something that needs it | Nothing |
| Staying ready | Close the chat, then hold the power button — it should answer rather than open the app | **Stay ready** in the Guide panel |
| Widget and lock screen | The home-screen widget | Nothing |
| Foldables | Unfolding shows the Guide beside the chat | A screen at least 600dp wide |
| Android Auto | The app in the car's list | Developer mode **and** Unknown sources in Android Auto's own settings. See below. |

### Android Auto, specifically

Reported missing three times. The declaration is correct — verified by dumping
the merged manifest out of the built APK, not by reading the source: the
`CarAppService`, the `androidx.car.app.category.IOT` category, `minCarApiLevel`
and the `automotive_app_desc` descriptor are all in the shipped package.

What decides whether a sideloaded app appears is Android Auto itself, in another
process, from settings this app cannot read or change, and it reports nothing
when they are off. The Guide panel now says whether Android Auto is installed,
spells out the sequence, and offers one tap into its settings.

Two things are easy to miss: this is a **parked** app, so it appears in the car's
list when the car is stopped, not while driving; and Android Auto caches its app
list, so reconnect the phone after changing anything.

The one remaining variable under this app's control is the declared category.
If the settings are right and it is still absent, that is the next thing to try.

## The memory vault

The newest part, and the least confirmed on a phone. Three layers:

- **Notes.** What Lin knows about you. Written by the model after a conversation, or by you in the
  **Memory** panel. A couple of dozen go into every prompt; the rest wait behind a search.
- **Conversation summaries.** One paragraph per conversation, linked to the notes it produced and
  to the full transcript.
- **The archive.** Every conversation in full, on the phone, read only when a tool asks for it.

Notes cool rather than expire. Anything not mentioned for three weeks drops out of the prompt and
stays searchable; identity lasts a year, and what you wrote yourself never cools. Repetition buys
time, so what keeps coming up keeps being known.

The summarising happens in the background, a few minutes after a conversation ends, and again
once a day. It borrows the model only when one is loaded and you are not mid-turn; when it cannot,
it writes a placeholder marked in italics in the Memory panel and upgrades it later.

**What to try:** have a conversation, close it, wait a few minutes, then open **Memory** in the
Guide panel. The conversation should be listed, and anything durable you said should have become
a note. Then start a fresh conversation and see whether it knows.

**What would show it is wrong:** a note that is subtly not what you said, a summary written about
a conversation you did not have, or the panel staying empty an hour after several conversations.
The first is the one to look hardest for - a wrong note is repeated with complete confidence.

## Not possible

Not "not done yet". These are closed off to an app in this position, and the
reasons are worth keeping written down.

- **A custom wake word.** `AlwaysOnHotwordDetector` only recognises keyphrases
  the device's own low-power audio DSP was built with and enrolled — the
  manufacturer's phrase, not one an app can add. Anything else means holding the
  microphone open from a foreground service, which is what the wake word option
  does, and it says so.
- **Google Home device control.** The Home APIs require registration in the
  Google Home Developer Console, an OAuth consent screen and per-project
  approval. A sideloaded app satisfies none of those. Home Assistant's own Cast
  and Nest integrations are the practical route to the same devices.
- **Sending a WhatsApp or Telegram message outright.** Neither publishes a way
  for another app to send on someone's behalf. The deep links they do publish
  open the conversation with the text written, for the sender to send. (Replying
  to a notification already on screen *does* send — different mechanism, and it
  works for any app with a reply button.)
- **Avoiding a manufacturer's install block.** Samsung's Auto Blocker, on by
  default since One UI 6.1, refuses sideloaded installs outright and describes
  the app as harmful while doing it - which sends people to Play Protect, where
  nothing they change helps. Only its own switch turns it off. Other makers add
  similar checks. Nothing in an APK affects any of them.
- **Avoiding Play Protect warnings.** Play Protect checks every install against
  Google's catalogue, and an app signed by a developer it has never heard of
  gets "Unsafe app blocked". Nothing inside an APK changes that, and the app id
  is already the fork's own rather than Google's, so there is no collision to
  fix. The way through is on the device: **More details** then **Install
  anyway**, or turn the scan off for the one install. Both are written up in
  INSTALL.md and in every release's notes.
- **A system-wide default music app.** Android has no such setting for an app to
  read. The in-app choice is the available substitute.
- **Reading or answering an incoming call.** Requires being the phone's dialler.

## Proposed next

In the order I would do them, with the reasoning rather than just the list.

### 1. Fewer tools in front of the model at once

The real constraint on this app is not features, it is the fixed overhead. The
count in this section used to say "around 25 tool schemas"; it is **44 tool
functions with 66 parameters**, and measured on a real phone the system prompt
and those schemas come to **4,230 tokens before the user types anything**.

Against the 4,000 the allowlist asks for, that does not merely crowd the
conversation - it means a brand new conversation cannot start at all. The first
message came back as `Input token ids are too long: 4230 >= 4000`, the session
was thrown away and rebuilt, and the same thing happened again. Three separate
bug reports - web search not working, no reply arriving, a loop after a search -
were all this, because the turn died before any of it ran.

Raising the context to 8,192 (the model advertises 32,000) buys room now. It
does not change the arithmetic: every capability added still makes the model
slightly worse at choosing between them, and the overhead is still most of a
small model's budget.

The prompt's own share of that has since been cut roughly in half, to **~426
tokens** for a mode with nothing optional switched on and **~580** with
everything on, from ~560 and ~700. Most of it came from one place: the default
mode's block had been restating the routing procedure capability by capability,
so the model read the whole ordering twice in different words and paid for it
both times. `SystemPromptBudgetTest` now guards 500 and 700 rather than 800 and
1,000, so drifting back a sentence at a time fails there instead of on a phone.
Worth doing, but it is a few hundred tokens against the thousands below - the
tool schemas are still the problem.

Past that limit the next thing to go wrong was the tool call itself. Upstream
sets TopK to 1 for agent chat - greedy decoding, "for better tool call quality"
- from a screen effect, which for this app runs after the conversation has
already been built with the allowlist's TopK of 64 at temperature 1.0. Sampling
64 candidates at full temperature while emitting an exact grammar produced
`call:web_search{query:doctor who lego}`, which is not a valid call, and the
turn died in the parser. It is now set where the conversation is created.

The fix is to stop sending all of them every turn and send the handful the
message plausibly needs. A lexical first pass — the message against each tool's
own description — costs nothing, needs no new runtime, and captures most of the
benefit. It also puts the interface in place that a smarter selector can later
sit behind, which is what makes the next item cheap instead of speculative.

**This should come before any further capabilities.** That was already written
here before anything broke; it is now the difference between an assistant that
works on its recommended model and one that does not. Forty-four tools is well
past the point where sending all of them every turn is defensible.

### 2. EmbeddingGemma, behind that same interface

Once selection is dynamic, replacing lexical matching with embedding similarity
is a drop-in: embed the message, compare against precomputed tool and skill
descriptions, take the top few. It handles the cases lexical matching cannot —
"I'm running late" needing the messaging tool without naming it.

The cost is real and should be stated before anyone commits to it: a new runtime
dependency (MediaPipe's text embedder, or LiteRT with a SentencePiece tokenizer
— neither is currently in the build), a second model to download, and the memory
to hold it alongside the conversational model. Worth it only after step 1 proves
the selection interface is right, which is the whole reason to do them in this
order.

### 3. MobileActions-270M as a fast path

The allowlist already carries a 270M model fine-tuned for phone actions, and
upstream has a screen for it. It is excluded from the assistant today, correctly:
alone it could not hold a conversation or choose between 25 tools.

The valuable shape is both models at once — the small one answering "call Sam"
and "set an alarm" immediately, the conversational one taking everything else.
At around 300MB extra it is affordable on a phone that can already hold a 4B
model. The work is in the routing decision and in a second engine's lifecycle,
and it needs the memory-pressure handling to be as careful as the single-model
path already had to become.

### 4. Bring your own model — reachable, and documented

Already works: a `.litertlm` or `.task` file imported from the phone, or a
Hugging Face link, appears in the chat's model picker beside the rest. It was
three screens away from the assistant; the Guide panel now has a **Models**
button that goes straight there, and a section saying what the formats are and
that a model too large for the phone is killed while loading rather than
refused.

## If something breaks

The app records the exception that killed it and shows it on the next launch,
with a **Copy report** button. There is no computer attached to a phone running
a sideloaded build, so that report is the only account of what happened — and
the difference between a fix and a guess.

## Caught up with upstream

Asked whether the fork was current, because features visible in the Edge
Gallery were missing from Lin. It was not, and the obvious way to measure it
was wrong: commit ancestry said 94 commits behind, but the earlier merge was
squashed, so git cannot see upstream commits as ancestors even where the code
is present - Scrapbook and the Hugging Face explore screen are both "after"
that base and both were already here. Compare content, not history.

Content comparison found 51 files, and these gaps:

- **Instant Media Search** (`smartalbum`) and **Video Moment Finder**
  (`videomomentfinder`), both open-sourced upstream on 5-6 October. Two whole
  tasks, genuinely absent. Ported with their supporting
  `services/semanticretrieval` and `services/photolibrary`, the onboarding
  images and 189 strings.
- **The allowlist pin.** Upstream names these files after *its* version, so
  this fork pins one; it was on `1_0_19` and upstream had published `1_0_20`.
  That matters more than a version number: `1_0_20` adds **EmbeddingGemma-2**,
  and its `taskTypes` are exactly `smart_album` and `video_moment_finder`. Both
  ported tasks would have had no model at all without the bump.
- **LiteRT-LM 0.11.0 to 0.18.0**, required because the semantic retrieval code
  uses `litertlm.ModelInfo`. The upgrade removed the `Capabilities` class, so
  the two places this fork checked for speculative-decoding support were
  migrated to `ModelInfo.from` with a type check, the way upstream did.
- **The external-prompt confirmation.** Upstream holds a prompt that arrived
  via deep link until the user has read it, because any app, web page, QR code
  or notification can open one. That matters more here than upstream, since Lin
  can place a call, send a message and switch things off in a house. Wired so
  only *external* prompts go through it - dictation, a wake-word summon and the
  suggestion chips all arrive by another path and are sent as before, because
  the user has just spoken or tapped and hands-free would break otherwise.

Two differences were left, deliberately:

- **Upstream's model-downloader refactor** (`ModelDownloader`,
  `LiteRtLmModelDownloader`, `AICoreModelDownloader`, and an `UNAVAILABLE`
  status with structured unavailability metadata). No user-visible feature
  attached, and taking it means rewriting this fork's download path - while an
  unexplained crash reported as happening *after a model download* is still
  open. Changing that path now would confound the one diagnosis in progress.
  The three files were copied and then deleted; nothing here referenced them.
- **Visual token budget configuration.** A model-settings dropdown. It touches
  thirteen files including `ConfigDialog.kt`, `ChatView.kt` and
  `ModelPageAppBar.kt`, all heavily diverged here, so it is a hand-merge of
  central files for a secondary setting.

Also note upstream **removed** Android Auto. This fork uses it, so the
dependency was kept rather than taking that deletion.

## What the earlier upstream refresh brought

The fork started as a snapshot and was 79 upstream commits behind. Merged now,
and three of those commits matter here rather than just upstream:

- **Conversation compaction.** Upstream added a summarizing compactor to the
  session manager, in the inference path and independent of which task is
  running - so the assistant gets it. It is the first real answer to the token
  budget problem described under *Proposed next*: a long conversation is now
  summarized rather than simply running out of room. Still worth doing the tool
  selection work, which is about the prompt rather than the history.
- **Model initialization as a flow.** Per-model `initStatusFlow` replaced the
  map of statuses on the view model. The recovery screen that makes a fatal
  model load survivable reads that instead, and its test moved with it.
- **One definition of an imported model's tasks.** Adding and removing an
  imported model now go through the same set, so the assistant can no longer be
  left holding a model that every other screen has dropped.

Nothing in the lists above changed behaviour because of the merge. The release
builds without minification, so upstream's ProGuard work does not apply to this
fork either way.
