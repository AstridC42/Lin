# Security review

A review of the attack surface this fork adds, and the tests that hold the
conclusions in place. Dated 1 October 2026, against `0.3.1-dev.25`.

This is a review of **this fork's own code and configuration**. It is not a
penetration test, there is no fuzzing or dynamic analysis here, and no finding
below has been exploited on a device — each one is read out of the code, and the
ones with tests have the test as the evidence.

## What the app actually has to protect

Worth stating first, because it decides which findings matter. The assistant can
read the notification shade — including one-time codes — the contacts, the
calendar, the approximate location, and whatever is on screen. It can place
calls, write messages, and **reply to a notification outright**, with no composer
in between. It holds a Home Assistant long-lived token and, optionally, an
`Authorization` header for a SearXNG instance.

It is also, by design, told what to do by text from outside: MCP servers, web
search results, fetched pages, the screen behind the pop-up. That combination —
sensitive reads, actions that reach other people, and untrusted instructions in
the same context — is the whole of the risk here. Everything below is a specific
case of it.

## Findings

### 1. An MCP server could write its own section of the system prompt — fixed

**High.** A server supplies the name, description and input schema of every tool
it offers, and those strings were interpolated into the system prompt exactly as
received. The prompt is also where this app keeps its own instructions, under
headings like `RULES` and `=== MODE ===`, and nothing distinguishes one line of
it from another. So a tool description containing a newline and a heading of its
own was read by the model as house rules — and on the other side of those rules
sit a notification reply that sends outright, and `openUrlInBrowser`, which will
carry anything the model has seen to any host.

The user must add the server for this to apply, which is the only thing that
stood in the way, and it is not much: adding third-party servers is the point of
the feature and nobody reads a tool's JSON before enabling it.

Fixed in `McpPromptSafety`: every field is flattened to a single line with
structural tokens, control characters and invisible characters removed, and each
field capped separately so one tool cannot fill the prompt. Twelve tests, each
an injection attempt that used to work.

**The limit of the fix, stated plainly.** This stops a server *forging
structure*. It does not stop a server writing persuasive prose in a description,
and nothing at this layer could — a tool description is instructions to a model
by design. The defence against that is the model knowing where its instructions
come from, which is a property of the prompt and the runtime, not a filter.

### 2. Credentials were included in cloud backup — fixed

**Medium.** `allowBackup` is on and both backup-rules files were still the
template's commented-out sample, so the settings store went into cloud backups
and device transfers — Home Assistant token, SearXNG `Authorization` header, and
everything the assistant has been told to remember.

Android 12+ backups are encrypted under a key tied to the device lock, so this
was a wider blast radius than the feature needs rather than an exposure on its
own. Now excluded, along with imported models and skills.

### 3. Any installed app can put words in the assistant's mouth

**Low, and accepted.** `MainActivity` is exported and answers `ACTION_SEARCH`,
`WEB_SEARCH`, `PROCESS_TEXT`, `SEND` and `SEND_MULTIPLE`, each of which carries
a query that the assistant sends as a message on arrival. Any app on the phone
can therefore make the assistant act on an instruction.

Declining those intents would remove sharing, text selection and the launcher
search box — the feature *is* the exposure. Two things limit it: the chat is in
front of the user with the injected text visible as a message, so it is not
silent; and the deep-link route is closed, which is the one that would have let a
*web page* do this. `ACTION_VIEW` on the `lin://` scheme is
`BROWSABLE`, and `AssistantIntents.parse` deliberately extracts no query from it.

Worth revisiting if the assistant ever acts without the screen being on.

### 4. A page opened in the chat can ask for device permissions

**Low.** `showWebPageInChat` renders a URL the model chose in the shared
`GalleryWebView`, which has JavaScript enabled, file access enabled, and is
passed `allowRequestPermission = true` — so an arbitrary page can prompt for the
camera, microphone or location. The prompt is still the system's and the user
still has to accept, and there is no JavaScript bridge on this path (the
`AiEdgeGallery` interface is attached only to the hidden skill-runner WebView, not
to this one), which is what would have made it serious.

Not changed: the WebView is shared upstream code and narrowing it needs a flag
plumbed through the chat message type. Noted rather than fixed.

### 5. Cleartext HTTP is permitted, and narrowed in code

**Accepted, with the narrowing as the mitigation.** `usesCleartextTraffic` is on. Without it the
two services a user brings themselves — a SearXNG instance and Home Assistant — cannot be reached
at all: both normally live on the home network at a plain `http://` address, and nobody issues a
certificate for `192.168.0.9`. Android has refused cleartext by default since API 28, so every
such request failed before leaving the phone, reported as though the server were down.

The platform's own mechanism cannot express "the local network" — a network security config lists
domains, and the address is only known once the user types it. So the rule is in `LocalNetwork`
and enforced by the only two clients that make these requests: plain HTTP to a private, loopback,
link-local or carrier-grade-NAT address or a reserved local name; TLS required for anything else.
`SearxngClient` enforces it at its single request choke point and again on the separate image
download; `HomeAssistantClient` enforces it before each request, which is what keeps a long-lived
access token off the open wire.

What remains is real and worth stating: traffic to the user's own network is unencrypted, so
anybody already on that network can read their searches and see the Home Assistant token. That is
the same exposure as using those services from a browser on the same network, and it is the
exposure the user chose by running them without TLS.

`LocalNetworkTest` holds the boundary down, including the case a prefix check gets wrong —
`192.168.0.9.example.com` resolves on the internet.

### 6. Exported components

Reviewed all ten. The ones without a permission are:

| Component | Assessment |
|---|---|
| `MainActivity` | Finding 3 above. |
| `OssLicenses*Activity` | Google library, displays licence text. |
| `LinCarAppService` | Guarded in code by `HostValidator` against the Car App Library's list of Google-signed hosts, not by `ALLOW_ALL_HOSTS_VALIDATOR`. |
| `AssistantWidgetProvider` | Launches the app. No data in, nothing to redirect. |
| `BootReceiver` | Upstream. Checks the action and only reschedules the app's own notifications; a forged broadcast achieves nothing. Our own `ReminderBootReceiver` is not exported. |

### 7. Secrets in logs

**None found.** Nothing logs the Home Assistant token or the SearXNG
`Authorization` header. Upstream's `DownloadWorker` logs the first ten characters
of a Hugging Face token, which is not enough to use. `SearxngClient` logs the
full request URL, so a search query reaches logcat — on a non-debuggable release
build, only the app itself can read that.

## What the tests cover

Security properties worth asserting rather than reviewing once, all in
`app/src/test`:

| Test | What it stops |
|---|---|
| `McpPromptSafetyTest` | A third-party MCP server forging prompt structure: twelve injection attempts, including Unicode line breaks and invisible characters. |
| `MessagingAppsTest` | A local phone number opening a WhatsApp conversation with a stranger who happens to share those digits. |
| `SystemPromptBudgetTest` | A prompt placeholder reaching the model unsubstituted, and a capability that is off being described anyway. |
| `LocalNetworkTest` | Cleartext carrying a Home Assistant token or a SearXNG `Authorization` header to a host on the internet, now that the platform's own ban had to be lifted. |
| `DeviceActionsToolTest` (garbled copy) | A call placed to a stranger because the model wrote a contact's digits out in the wrong order. |
| `MemoryBookTest` | Unbounded growth of what is injected into every prompt, and a deletion taking the wrong line when the instruction is ambiguous. |
| `ScreenTextTest` | Unbounded screen content being sent, and a line cut in half that the model would answer about. |
| `DeviceActionsToolTest`, `ReminderTimeTest`, `CalendarTimeTest` | Actions landing on the wrong contact, the wrong time or the wrong day. |

One of these found a real bug while being written: the first version of the MCP
sanitiser used Java's `\p{Cntrl}` and `\s`, both ASCII-only, so U+0085 and U+2028
— a next-line and a line separator — went straight through a filter whose entire
job was stopping a server from starting a line of its own.

### 8. The memory vault is a new store of personal data

**Noted, not a finding.** The vault holds what a model inferred about the user from their
conversations, plus every conversation in full under `files/memory/archive`. Both are app-private
and both are excluded from cloud backup along with the rest of the settings store (finding 2).

Two things are worth stating rather than discovering later. The transcripts are plain Markdown
rather than encrypted, so anything that can read the app's private files can read them - which on
an unrooted device means the app itself. And the vault is reachable by the model through
`searchMemory` and `readPastConversation`, which means a prompt injection that reaches the model
can ask it to read old conversations aloud; that is the same exposure as finding 1 and is bounded
by the same sanitiser, but the thing on the other side is now larger.

Clearing it is one action: **Memory** in the Guide panel deletes notes individually, and
uninstalling takes the archive with it.

## What this review does not cover

- **The on-device model and the LiteRT-LM runtime.** Native code, not ours.
- **Prompt injection from content**, as opposed to structure. Discussed under
  finding 1; it is a design problem, not a filter problem.
- **The MCP transport.** Server URLs are user-entered and fetched over whatever
  scheme the user typed; nothing enforces HTTPS.
- **Anything dynamic.** No device, no instrumentation tests, no fuzzing.
