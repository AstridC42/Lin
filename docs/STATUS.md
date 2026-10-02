# Status

Where the project actually is, as of pre-release `0.3.1-dev.25`.

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
  opening apps, maps and directions, the torch, settings pages, links.
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
- **Remembering you.** Twenty durable facts, written by the model when you tell
  it to remember something or by you directly, injected into every prompt.
  Capped and listed under **Memory** in the Guide panel, because a fact
  remembered wrongly is repeated confidently until somebody corrects it.
- **Staying loaded.** An opt-in foreground service keeps the model in memory
  after the chat is closed, so everything that summons the assistant can answer
  in place instead of apologising and opening the app. It costs the model's
  memory while it holds, and says so in an ongoing notification.
- **Releases.** Every push to `main` publishes a rolling pre-release with four
  ABI APKs, signed with a stable key so they install over each other.

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
| Web search, pictures, pages | "search for…", "show me a photo of…" | A SearXNG instance with `json` in `search.formats` |
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

The real constraint on this app is not features, it is that around 25 tool
schemas plus the system prompt consume roughly three quarters of a 4,000-token
budget before the conversation starts. Every capability added makes the model
slightly worse at choosing between them, and one tool result containing a list
can still push a turn over.

The fix is to stop sending all of them every turn and send the handful the
message plausibly needs. A lexical first pass — the message against each tool's
own description — costs nothing, needs no new runtime, and captures most of the
benefit. It also puts the interface in place that a smarter selector can later
sit behind, which is what makes the next item cheap instead of speculative.

**This should come before any further capabilities**, because it makes
everything already built work better, and because adding tools before it
actively trades against what is there.

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

## What the upstream refresh brought

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
