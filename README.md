# grog

A home voice assistant: an LLM-backed brain written in
[Babashka](https://babashka.org), meant to run on a small home server, its
ears and mouth (`grog-voice`, in Python), and the NixOS config of the server
it runs on at the author's home.

## Status

- [x] Chat server and CLI, with short-term conversation memory
- [x] OpenAI (Responses API, with web search), paid with an API key or a ChatGPT subscription
- [x] NixOS module, and a home server deployed from a notebook (`body/`)
- [x] Voice, hands-free: mic → speech-to-text (Parakeet) → LLM → text-to-speech (Piper) → speaker, streamed
- [x] Wake word ("Grog, ...", its name), and quiet sounds for "got it" and "looking it up"
- [ ] Interrupting it while it talks (needs a speakerphone with echo cancellation)
- [x] Tools: changing its own settings, and remembering things, when asked
- [x] Tools for the machine: the speaker's volume, how it's doing, and a shell, which also reboots it
- [x] A skin: a page, fullscreen on its screen, showing what it's up to (hearing, thinking, its tools, speaking) and what it costs
- [ ] More providers

## How it works

```
mic ─▶ grog-voice ─────HTTP──┐   (a user service, in the session with the mic and speaker;
speaker ◀─┘                  │    it also tells grog what it's doing)
grog chat / ask ──HTTP──▶ grog serve  (a systemd service)
screen ◀─ grog-skin ◀─HTTP───┤   (what grog is up to, as server-sent events, and its skin, a page)
                            │  system prompt: a template with the persona, voice style, date, location
                            │  conversation memory: in RAM, forgotten after 10 min idle
                            │  tools: its own settings, what to remember, and the machine (its hands)
                            ▼
                  OpenAI Responses API (+ web search), streamed
                  (API key, or the ChatGPT subscription through the Codex backend)
```

Answers stream back as plain text, written to be read aloud: short, no
markdown, numbers spelled the way they're spoken. grog-voice speaks them as
they come, see [Voice](#voice); its skin shows them, see [Skin](#skin).

## Layout

```
brain/      grog itself (grog serve) and the grog CLI, which also runs its hands
            (grog hands): a Babashka app (bb.edn, src/grog, test/grog, resources/grog)
voice/      grog-voice, its ears and mouth: Python package (grog_voice, tests)
skin/       its skin, a page showing what it's up to (web: HTML, CSS, JS), the
            fullscreen window for it (kiosk.js, GTK and WebKit), tests (node --test)
body/       what it runs on, all in Nix: packages, speech models, NixOS module
            (services.grog), grognix (a PC dedicated to grog), its installer, VM
            tests (see body/README.md)
flake.nix   packages, overlay, NixOS modules (services.grog, grognix), checks
Makefile    test, lint, check, and the grognix commands (see body/README.md)
```

The brain only uses libraries built into Babashka (no dependencies to fetch), so
the package is a plain copy of `brain/`. grog-voice is in Python because its
models are; it only works out when someone spoke and what they said, never
what to answer: that's grog's job. The skin has no dependencies either: plain
web files, which grog serves.

## Running it

### On a PC of its own

grognix: NixOS with grog and its voice, installed over SSH on a PC booted into
the NixOS installer, erasing one of its disks:

```sh
nix run github:ericdallo/grog#install -- nixos@<its address>
```

See [body/README.md](body/README.md#installing).

### On NixOS

```nix
{
  inputs.grog.url = "github:ericdallo/grog";

  outputs = { nixpkgs, grog, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      modules = [
        grog.nixosModules.default
        {
          services.grog = {
            enable = true;
            settings = {
              location = { city = "São Paulo"; region = "SP"; country = "BR"; };
            };
            # Whose session has the mic, the speaker and the screen.
            user = "alice";
            # Talking to it: see Voice below.
            voice.enable = true;
            # What it's up to, fullscreen on alice's desktop: see Skin below.
            skin.enable = true;
          };
          services.pipewire.enable = true;
        }
      ];
    };
  };
}
```

The service runs sandboxed as its own dynamic user (`grogd`), keeps its state
in `/var/lib/grog`, and listens on `127.0.0.1:8484`. The `grog` CLI is
installed for every user, and finds the server through `$GROG_URL`.

### Anywhere else

```sh
cd brain
OPENAI_API_KEY=... bb serve [--config settings.json]   # or sign in, see below
bb chat                                                 # in another terminal
bb -m grog.main ask "What's the capital of Australia?"
```

## Using it

```sh
grog chat                  # interactive; /new starts over, /quit leaves
grog ask "Will it rain tomorrow?"
grog ask "And on Sunday?"  # follow-ups continue the conversation...
grog ask --new "Hi"        # ...until it's idle for 10 minutes, or --new
grog status                # provider, model and credentials in use, what it consumed, and settings changed by asking
grog settings reset        # puts those back as configured
grog models                # the provider's models; * marks the one in use
grog login / grog logout   # the ChatGPT subscription, see below
```

### Paying for it

- **ChatGPT subscription**: `grog login` prints a link; sign in with your
  ChatGPT account, and the browser comes back to `localhost:1455`, where grog
  waits. On a headless server, forward that port from the machine with the
  browser: `ssh -L 1455:127.0.0.1:1455 <server> grog login`. The tokens stay
  in the state directory (`auth.json`, mode 0600) and refresh on their own.
- **API key**: from platform.openai.com, with prepaid credits, in the
  `OPENAI_API_KEY` environment variable. On NixOS, put `OPENAI_API_KEY=...` in
  `/etc/grog/secrets.env` (mode 0600, out of the Nix store; the
  `environmentFile` option) and restart the service.

With `auth = "auto"` (the default), the subscription is used when signed in,
and the API key otherwise.

`grog status` (and the [skin](#skin)) tells what it consumed:

```
Usage:    5h 23% used (resets in 2h 10m), 7d 41% used (resets in 3d 4h)   # the subscription
Spent:    $0.42 this month, $0.0312 today                                 # an API key
```

With the subscription, that's how much of its usage limits is used, shared
with ChatGPT and Codex: the Codex backend tells it with each answer, and grog
asks every 5 minutes. With an API key, it's what grog's turns cost at
OpenAI's prices for their tokens (cached ones are cheaper) and web searches
(`providers.openai.prices`, set for `gpt-6-luna`: add the model you switch to),
counted per day in `usage.json` in the state directory. Not what OpenAI
bills, which is the authority, but close.

## Voice

`grog-voice` listens to a microphone and answers through a speaker:

1. [Silero VAD](https://github.com/snakers4/silero-vad) tells speech from
   silence, frame by frame; 600 ms of silence ends what you said (1.2 s
   after less than 0.8 s of speech, like its name alone).
2. [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
   (int8, through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx))
   transcribes it, in any of 25 European languages. It starts at each
   pause, in case that's the end, so the text is mostly ready when it is.
   While you talk, a second Parakeet, in a process of its own (some 700 MB
   more, loaded when someone first talks), transcribes what you said so far,
   over and over: to hear its name before you're done and, with captions, to
   show your words as they come. It stops while the end is transcribed,
   which never waits for it. Quiet speech is made louder first (up to
   30 dB): as is, Parakeet makes out little of it, often nothing, from a few
   meters away.
3. Only what starts with the wake word gets an answer, like "Grog, will it
   rain tomorrow?": by default grog's name (its `name` setting). It's matched
   by how it sounds, as speech-to-text hears unusual names as other words
   ("Groga", "Grogue", from afar "Drog"), while it keeps "Droga" or "Bro"
   apart. Up to two words may come first ("Ei, Grog"). Its name gets a sound
   saying it listens as soon as it's heard, while you go on (some 1 to 2 s
   into it on grognix), or alone at a pause ("Grog... will it rain?"). After
   its name alone, the next thing said (within 8 s) needs no wake word, like
   the reply to a question grog asks ("Shall I?").
4. A quiet sound says it got it: as soon as you stop, when it already heard
   its name, or else once it's transcribed. Then grog answers (`POST
   /v1/chat`, in the `voice` conversation), streamed. Another sound says when
   it looks something up, like a web search.
5. [Piper](https://github.com/OHF-Voice/piper1-gpl) speaks the answer a
   sentence at a time as it streams in (the first chunk may end at a comma),
   synthesizing the next one while the last plays.

It's half duplex: what the mic hears while grog speaks is ignored. When grog
can't answer, a low sound says so (the error is in the log). Without a mic
(unplugged, say), it waits for one, models loaded. On grognix (a Celeron
N5095, through a Bluetooth speaker), it says it got it 0.6 s after you stop
(when it heard its name as you talked), and grog starts speaking about 2.5
to 3 s after, 5 to 8 s when it searches the web; most of it is the LLM.

On NixOS, `services.grog.voice` runs it as a systemd user service of
`services.grog.user`, whose session has the microphone and the speaker
(PipeWire): log them in at boot (auto-login), or set
`users.users.<name>.linger = true`. The models are fetched by Nix, pinned
(`body/packages/voice-models.nix`): Parakeet (CC-BY-4.0) and Piper's pt_BR
`faber` voice; `sttModel` and `ttsVoice` pick others. Its log
(`journalctl --user -u grog-voice`) has timings per turn, and how long and
loud what it ignored was (no words heard, or not for grog), or its name
alone: never what was said.

It tells grog what it's doing (`POST /v1/voice`), for its [skin](#skin):
loading, listening, hearing someone talk to it (once it heard its name, or
right away when none is needed), awake (no wake word needed), answering,
speaking, an error, no mic; again every 10 s, so grog knows it's there.
grog answers with its name, the wake word (a new one counts within 10 s),
and whether it shows what's said (`skin.captions`). Only then does
grog-voice tell it what it heard for grog, as it comes (what it makes of it
so far, then all of it): what starts with its name, or needs none (a reply,
in a conversation with it). Without captions, nothing it hears leaves
grog-voice, and it stops transcribing what you said so far once it heard
its name. What anyone else says never leaves it.

To see how it hears your wake word, `grog-voice listen` prints what the mic
hears and whether grog would answer, without answering (next to the service:
both can listen), and how it sounded: how long, how much of it was speech,
its peak and how much louder it was transcribed, to tell a mic that's too
far or too quiet. If your way of saying it gets transcribed otherwise, add
that spelling to `wakeWord`. To try a turn without a mic,
`grog-voice turn question.wav` (16 kHz, mono, 16-bit) takes a recording as
what you said, and prints what it heard and asked, the answer, the sounds and
the timings (`--no-play` keeps quiet).

Its settings (`services.grog.voice.settings`, or a JSON file:
`grog-voice --config`, or `$GROG_VOICE_CONFIG`) are merged over the defaults
in `voice/grog_voice/config.py` and checked at startup:

| Setting | Default | Notes |
| --- | --- | --- |
| `grogUrl` | `"http://127.0.0.1:8484"` | On NixOS, services.grog on the same machine |
| `conversationId` | `"voice"` | The grog conversation turns go to |
| `wakeWord` | `null` | What to start with, like `"Ok computer"`, or a list (other spellings, say). `null`: grog's name. `false`: none, anything heard gets an answer |
| `mic`, `speaker` | `null` | PipeWire node names (`wpctl status -n`). `null`: the session's defaults |
| `stt.model` | `null` | Directory of a Parakeet TDT model for sherpa-onnx. On NixOS: `sttModel` |
| `stt.threads` | `2` | On grognix's 4 cores, 3 are slower (their waiting threads spin) |
| `tts.voice` | `null` | A Piper voice (`.onnx`, with its `.onnx.json` next to it). On NixOS: `ttsVoice` |
| `endSilenceMs` | `600` | Silence that ends what you said |
| `speculateMs` | `250` | A pause this long starts transcribing, in case it's the end. `0`: wait for the end |
| `partialMs` | `300` | While you talk, what you said so far is transcribed again, one at a time, with at least this much more of it each time. `0`: only at pauses, without the second model |
| `maxUtteranceSeconds` | `15` | Longer is cut |

## Skin

grog's skin is a page showing what it's up to, made for a screen next to it
(on grognix, the one it's plugged into):

- a third of the screen in a color for what grog does, readable from across
  the room: its name, model and account, what it's doing in big letters,
  and a sign of it: a mic while it listens, sound waves while you talk to it
  (green after its name alone, when what you say next needs none), dots
  while it thinks, the tool it uses (the web, the volume, the machine, a
  shell...), bars while it speaks; red for an error or no mic;
- the time, in grog's time zone;
- the steps of the last turn, and how long after the first each came: heard,
  thinking, its tools, answering, speaking, done (which can come after
  speaking: grog-voice speaks the end of an answer once grog is done);
- with captions, what you say to it as you say it, a word at a time, then
  its answer as it comes;
- the last 4 turns, with their tools and how long they took;
- what it consumed: how much of the subscription's limits is used and when
  they reset, or what the API key spent today and this month, with the last
  30 days.

grog serves it at its address (`http://127.0.0.1:8484/`), from the files in
`skin.dir`, and it follows grog through `GET /v1/events`. On NixOS,
`services.grog.skin.enable` sets that up, and opens it fullscreen in the
graphical session of `services.grog.user` as soon as it starts (a window of
its own, GTK and WebKit; `services.grog.skin.user = null` only serves it):
on GNOME, it closes the overview GNOME starts in, and keeps the screen on.
F11 toggles fullscreen, Ctrl+R reloads, Ctrl+Q closes it until the next
login; Super still opens GNOME's overview. From another machine, through
SSH: `ssh -L 8484:127.0.0.1:8484 <server>`, then open
`http://localhost:8484/`.

`skin.captions` is off by default: anyone who can reach grog can watch what's
said. On a small CPU, grog-voice comes first: the skin only moves while
something happens (each frame costs CPU, even what the screen's compositor
moves; with captions, the words you say fade in as you say them), and its
window runs at a low priority. Without a GPU (in a VM, say), WebKit draws it
on the CPU.

## Configuration

Settings come from a JSON file (`grog serve --config`, or `$GROG_CONFIG`), or
`services.grog.settings` on NixOS, with lowerCamelCase keys. They're
deep-merged over the defaults in `brain/src/grog/config.clj` and checked at
startup: grog refuses to start, saying what's wrong, when a setting is invalid
(or misspelled, at the top level).

| Setting | Default | Notes |
| --- | --- | --- |
| `name` | `"grog"` | What the assistant calls itself |
| `language` | `null` | e.g. `"English"`. `null`: whatever language you speak |
| `timezone` | `null` | e.g. `"America/Sao_Paulo"`. `null`: the system's |
| `location` | `null` | `{ city; region; country; }` (ISO country code), for the prompt and localized web search |
| `systemPrompt` | `null` | Replaces the built-in system prompt template, see below |
| `systemPromptFile` | `null` | The same, from a file, read on every question |
| `instructions` | `null` | Appended to the system prompt, whichever it is |
| `provider` | `"openai"` | The only one for now |
| `maxHistoryMessages` | `20` | Messages (yours and its) kept per conversation |
| `conversationTtlMinutes` | `10` | Idle time before a conversation is forgotten |
| `server.host`, `server.port` | `"127.0.0.1"`, `8484` | |
| `server.allowedHosts` | `["localhost", "127.0.0.1", "[::1]"]` | Names clients may reach grog by (the `Host` header) |
| `nrepl.enable`, `nrepl.host`, `nrepl.port` | `false`, `"127.0.0.1"`, `1667` | For development: any local user can run code in grog (and through its hands, if any) |
| `hands.socket` | `null` | The unix socket of grog's hands (`grog hands`), which give the model tools acting on the machine, see below. `null`: none |
| `skin.dir` | `null` | Its [skin](#skin)'s files, served at `/`. `null`: none. On NixOS: `services.grog.skin.enable` |
| `skin.captions` | `false` | Whether what's said (questions, answers, errors) goes to whoever watches grog, like its skin |
| `stateDir` | `null` | Where grog keeps credentials (`grog login`), the settings changed by asking, what it remembers, and what it consumed. `null`: `$STATE_DIRECTORY` (systemd) or `~/.local/state/grog` |
| `providers.openai.auth` | `"auto"` | `"auto"`: the subscription when signed in, else the API key. `"subscription"` or `"api-key"` force one |
| `providers.openai.model` | `"gpt-6-luna"` | Model used with an API key |
| `providers.openai.subscription.model` | `"gpt-6-luna"` | Model used with the subscription (`grog models`) |
| `providers.openai.reasoningEffort` | `"low"` | Lower is faster |
| `providers.openai.webSearch.enable` | `true` | |
| `providers.openai.maxTokens` | `2048` | Includes reasoning tokens (API key only) |
| `providers.openai.timeoutMs` | `60000` | How long OpenAI may take to start answering, and to send the next part |
| `providers.openai.apiKeyEnv` | `"OPENAI_API_KEY"` | Variable holding the API key |
| `providers.openai.prices.models` | `{"gpt-6-luna": {"input": 0.10, "cachedInput": 0.01, "output": 0.50}}` | USD per million tokens of each model, to tell what an API key spent. Models without a price are counted, not priced |
| `providers.openai.prices.webSearch` | `0.01` | USD per web search |
| `providers.openai.baseUrl` | `"https://api.openai.com"` | |
| `providers.openai.subscription.baseUrl`, `.authUrl`, `.callbackPort` | Codex backend, `https://auth.openai.com`, `1455` | |

API keys never go in the settings: on NixOS they would end up in the
world-readable Nix store.

### Changed by asking

grog changes some of its settings when you ask it to ("from now on, your name
is Jarvis", "I live in Campinas", "always answer in English", "switch to
gpt-6-astra", "think harder"): its `name` (and so the wake word grog-voice
answers to, within 10 s), `language`, `location`, `timezone`, the model it
runs on (the one of the credentials in use: `providers.openai.subscription.model`
or `providers.openai.model`), and the provider's `reasoningEffort`. They're
checked like at startup, and a new model or reasoning effort is tried with a
short request first: one that doesn't answer, which would leave grog mute, is
refused, naming the models on offer. They take effect from your next message,
and are kept in `settings.json` in the state directory, over the configured
settings, across restarts and deploys. `grog status` lists them, and `grog
settings reset` (or asking) puts them back as configured.

It also remembers things for good when asked ("remember that the trash goes
out on Tuesdays", "from now on, answer more briefly"), and forgets them when
asked: up to 50 short sentences, facts and how you want it to answer, kept in
`memory.json` in the state directory, which go in every system prompt. (The
`instructions` setting is only set in the settings.)

Nothing is changed in a turn where grog searched the web: a page could have
told the model to.

### Its hands on the machine

grog itself can't change anything on the machine it runs on: it's sandboxed.
Its hands can: `grog hands`, a small service of the user with the speaker
(`services.grog.hands` on NixOS, which points the `hands.socket` setting at
it), runs commands for grog over a unix socket only the two of them reach.
With them, the model gets tools for:

- the speaker's volume ("louder", "volume at 30", "mute"), through `wpctl`;
- how the machine is doing ("how are you?"): uptime, load, memory, disk,
  temperature, network and Wi-Fi, the speaker and mic in use, the Bluetooth
  devices connected, its services, the NixOS version and when it was last
  deployed;
- a shell, for anything else ("clean up old Nix generations", "restart"),
  which runs right away: grog only checks with you first when a command could
  lose data, or cut it off, and you didn't clearly ask for that (your answer,
  right after its question, needs no wake word).

That user can usually use sudo, and so then can grog, through its hands,
without asking: think of them as root, and of grog as trusted with the
machine. Nothing is changed in a turn where grog searched the web, though: a
page could have asked for it. Commands time out after 30 s, and the hands log
how they went (exit code, time), never what they ran or printed. What the
shell changes on NixOS doesn't last past the next deploy: that belongs in its
configuration.

### The system prompt

It's a [Selmer](https://github.com/yogthos/Selmer) template (Django-like
syntax): the built-in one is
[`brain/resources/grog/system-prompt.md`](brain/resources/grog/system-prompt.md).
Templates can use `name`, `language`, `now` (e.g. "Thursday, October 15, 2026,
09:05"), `timezone`, `location`, `model` (the AI model answering), `web_search`
and `memory` (what it was asked to remember). Some ways to change it:

```nix
services.grog.settings = {
  # A tweak:
  name = "Jarvis";
  instructions = "Call me sir. You're a bit sarcastic.";

  # Or a whole new prompt, starting from a copy of the built-in one:
  systemPrompt = builtins.readFile ./my-prompt.md;

  # Or a file on the server, which you can edit without restarting:
  # systemPromptFile = "/etc/grog/prompt.md";
};
```

## HTTP API

`grog serve` answers on `server.host:server.port` (`brain/src/grog/api.clj`);
the CLI uses this API. JSON keys are lowerCamelCase, both ways:

| Request | Does |
| --- | --- |
| `POST /v1/chat` `{"message", "conversationId", "new", "said"}` | Answers, streamed as plain text. `"new": true` forgets the conversation first; `said`: what was said aloud, as heard (grog-voice's, with the wake word), shown as the question with `skin.captions`. With `Accept: text/event-stream`, as server-sent events: `text` `{"text"}`, `tool` `{"name"}` (the model uses one, like `web_search`), then `done` `{}` or `error` `{"error"}` |
| `GET /v1/status` | The assistant's `name`; `provider`, `model` and credentials in use (`auth`, `email`...); `changed`: the settings changed by asking; `usage`: what it consumed |
| `DELETE /v1/settings` | Puts those back as configured: `{"changed"}` |
| `GET /v1/models` | The provider's models: `{"models": [{"id", "description", "current"...}]}` |
| `POST /v1/auth/<provider>/login` | Starts signing in to a subscription: `{"url", "redirectUri", "label"}` |
| `GET /v1/auth/<provider>` | Sign-in status: `{"signedIn", "email"...}` |
| `DELETE /v1/auth/<provider>` | Signs out |
| `GET /v1/events` | What grog is up to, as server-sent events: `snapshot` `{"state"}` first, then each change, `{"event", "state"}`, typed `voice`, `turn`, `tool`, `text`, `done`, `failed`, `status` or `usage` (`brain/src/grog/events.clj` has the state). What's said only with `skin.captions` |
| `POST /v1/voice` `{"state", "conversationId", "heard"}` | grog-voice saying what it's doing, and maybe what it heard for grog: `"heard": {"text", "final"}` (`final` false for a partial, what it made of it so far), kept only with `skin.captions`. Answers `{"captions", "name"}`: whether they're on, and grog's name, its wake word |
| `GET /` | Its [skin](#skin), with `skin.dir` |
| `GET /health` | `ok` |

Errors before the answer starts (no credentials, no network, API errors)
return a 502 with `{"error": "..."}`; errors in the middle of an answer are
appended to the text as `[error] ...` (an `error` event, with server-sent
events).

## Security

- grog has no authentication: it listens on localhost only, and any local
  user can use it. Web pages can't: requests need an allowed `Host`
  (against DNS rebinding) and POST/DELETE need `Content-Type:
  application/json` (no cross-site requests without a CORS preflight, which
  grog never allows).
- On NixOS it runs as a dynamic user with a tight systemd sandbox (no devices,
  read-only system, no new privileges, filtered system calls).
- Credentials: the API key comes from the environment; subscription tokens
  live in the state directory, mode 0600, like the settings changed by asking
  and what grog remembers. Logs never include what's said, only the names of
  the tools used.
- The model's tools don't change anything in a turn that searched the web,
  whose pages could carry instructions for it.
- With its hands (`services.grog.hands`), grog can run anything as their user,
  who can usually use sudo, without asking: the model is trusted with the
  machine, except in a turn that searched the web (above). Anyone who can run
  code in grog (`nrepl.enable`) could use them too.
- grog-voice runs as the user whose session has the mic and speaker. It keeps
  no recordings and logs no words, only timings and levels. It tells grog
  what it's doing and, only while grog shows it (`skin.captions`), what it
  heard for grog (said to it); never what anyone else said.
- Any local user can watch what grog is up to (`GET /v1/events`), like they
  can use it; what's said too with `skin.captions`. Web pages elsewhere can't
  read it (no CORS), and its skin can only talk to grog (a Content Security
  Policy).

## Development

```sh
nix develop     # the tools below: bb, clj-kondo, python3, ruff, node, shellcheck
make test       # unit tests: the brain's (bb), grog-voice's (python3, standard library only)
                # and the skin's (node --test, no dependencies)
make lint       # clj-kondo, ruff and node --check
make check      # the packages, built by Nix (running the unit tests in its sandbox)
make check-vm   # VM tests, one at a time: the NixOS module (a voice turn with the
                # real models included), the grognix config (with a screenshot of
                # its skin) and its disk; `nix flake check -L` runs everything at once
```

To work on the skin, point a local grog's `skin.dir` at `skin/web` (its fonts
come with the package: without them, it falls back to the system's), and
open `http://localhost:8484/`.

With `nrepl.enable`, the server runs an nREPL: functions redefined there take
effect on the next request, and `grog.server/current` holds the running
context, e.g.
`(swap! (:config @grog.server/current) assoc-in [:providers :openai :reasoning-effort] "medium")`.

Adding a provider: see the contract in `brain/src/grog/provider.clj`.

## License

[Apache 2.0](LICENSE).
