# brain

grog itself: a [Babashka](https://babashka.org) server that takes what you
said, asks the model and streams the answer back, short and written to be
read aloud. The `grog` CLI talks to it, and also runs its [hands](#hands).

It only uses libraries built into Babashka, so there's nothing to fetch.

## Running it

```sh
OPENAI_API_KEY=... bb serve [--config settings.json]
bb chat
```

On NixOS, `services.grog` runs it as a sandboxed service with its own user,
its state in `/var/lib/grog`, listening on `127.0.0.1:8484`.

## The CLI

```sh
grog chat                  # /new starts over, /quit leaves
grog ask "Will it rain tomorrow?"
grog ask --new "Hi"        # follow-ups continue the conversation for 10 min, unless --new
grog status                # model, account, usage, and settings changed by asking
grog settings reset        # puts those settings back
grog models                # the models on offer, * marks the one in use
grog login / grog logout   # the ChatGPT subscription (--browser: in a browser here)
grog mic off / grog mic on # off, nothing is heard until it's on again
grog update                # to the latest grog pushed, saying how it goes
grog computers             # the computers it acts on (computers add, computers forget NAME)
```

It finds the server through `$GROG_URL`.

## Paying for it

- **ChatGPT subscription**: sign in with a code, on any phone or computer:
  `grog login` prints it, with where to enter it
  (`auth.openai.com/codex/device`). With a skin (`skin.dir`), grog shows one
  whenever it has nothing to pay with, renewed as it expires. ChatGPT has to
  allow it once per account, on its website (its app doesn't have it): in
  Settings, Security and login, turn on "Enable device code sign-in for
  Codex, Excel, PowerPoint, and Word". Without that, `grog login --browser`
  prints a link instead, and the browser comes back to `localhost:1455`: on a
  server, forward that port (`ssh -L 1455:127.0.0.1:1455 <server> grog login
  --browser`). The tokens stay in the state directory and refresh on their
  own.
- **API key**: in `OPENAI_API_KEY`. On NixOS, in `/etc/grog/secrets.env`
  (mode 0600), never in the Nix config.

When signed in, the subscription is used. Otherwise, the API key.

`grog status` and the skin show how much of the subscription's limits is
used (shared with ChatGPT and Codex), or what the API key spent today and
this month. The spending is an estimate, at the prices in
`providers.openai.prices`: add the model you switch to.

## Changing it by asking

Some settings change when you ask: its `name` (and so its wake word),
`language`, `location`, `timezone`, the model and its reasoning effort.
"From now on your name is Jarvis", "I live in Campinas", "switch to
gpt-6-astra", "think harder". Without a `location`, it asks which city you
live in the first time an answer needs it, like the weather, and keeps
that. A new model gets a quick test first, and is refused if it doesn't
answer. These stay in `settings.json`, in the state directory, until `grog
settings reset`.

It also remembers things when asked ("the trash goes out on Tuesdays",
"answer more briefly"): up to 50 short notes in `memory.json`, part of every
prompt. It forgets them when asked, too.

Asked to stop listening, it turns its mic off: nothing is heard until it's
turned on again, at its skin or with `grog mic on`, even after restarts
(`voice.json`).

Nothing changes in a turn where it searched the web, since a page could have
asked for it.

## Hands

The brain is sandboxed and can't touch the machine. Its hands can. They're
`grog hands`, a small service of the user with the speaker
(`services.grog.hands` on NixOS), taking commands from the brain over a unix
socket (`hands.socket`). With them, the model can:

- set the speaker's volume
- tell how the machine is doing: load, memory, disk, temperature, network,
  audio and Bluetooth devices, services, last deploy
- run shell commands. It only asks first when a command could lose data or
  cut it off.
- restart the machine, or turn it off, once its answer was said.

On NixOS, that user gets sudo without a password, and polkit lets it do
anything (`services.grog.hands.sudo`): grog can do anything on the machine.
Commands time out after 30 s, and the logs only say how they went, never
what they ran or printed. On NixOS, what the shell changes is gone at the
next deploy.

## Restarting

Asked to, grog restarts itself once its answer was said: its voice, through
its hands, then its brain, by exiting with status 75. That only works as a
systemd service that starts it again then: the NixOS module's does
(`RestartForceExitStatus=75`). Its skin can ask too, right away (`POST
/v1/actions/restart`).

## Updating

Asked to, at its skin, or with `grog update`, grog updates itself to the
latest grog pushed. On NixOS that's `services.grog.update`: a root service
(`grog updater`) locks the system's flake with grog's input at its latest,
in a lock of its own, and builds the system with it, as the flake's owner.
With something new, once nobody is talking to grog, it switches to it
(staying in the specialisation the machine runs, if it's one), and waits for
the new grog to answer, as its new revision. Then the flake gets the new
lock; otherwise the machine goes back to the previous system.

Asked by voice, it only says it's updating, unless there was nothing new or
it failed, and grog-voice plays a sound once it's back. `grog update`
follows it to the end, and starts the service itself when grog doesn't
answer. How it goes is in the `update.status` file, which whoever watches
grog sees (`update` in `/v1/events`).

## Computers

grog can act on your other computers: tell how one is doing, show a
notification on it, open a link or a file there, control what plays and the
volume, lock its screen, suspend it, turn it off, wake it up (Wake-on-LAN,
if it's wired and set up for it), or run a command. Linux ones, for now.

To pair one, let it at grog: "Add a computer" on its screen, or `grog
computers add`. For 5 minutes, grog listens on the network for it
(`computers.pairing`), and serves what pairs it. In a terminal on the
computer, run what grog's screen says:

```sh
curl -fsS 192.168.15.10:8485 | bash
```

Or open that address in its browser, which says so. There's nothing to
install: it takes bash, curl and an SSH server. Both screens show a code:
when it's the same, allow it at grog.

The computer then lets grog in over SSH, with a key of grog's own (in its
state directory), only from grog's network, and only to run its hands
there: a copy of what grog served
([`computer.sh`](resources/grog/computer.sh), bash), which pairing keeps
in `~/.local/share/grog`. It decides what grog may do there, in
`~/.config/grog/hands.json`. By default:

```json
{"allow": ["status", "notify", "open", "media", "volume", "lock"],
 "ask": ["suspend", "power-off", "shell"],
 "deny": []}
```

What's asked shows a notification there (`notify-send`): clicking it (with
dunst, the middle button) or its Allow button allows it, and nobody doing
so in 30 s denies it. Pairing with `| bash -s -- --trust` allows
everything; `--name` says what grog calls it (its host name by default),
`--ssh-port` where its SSH server listens, and `--host-key` its host key,
when it isn't in `/etc/ssh`. Suspending it and turning it off also take
sudo without a password there, or polkit allowing them.

`grog computers` lists them. `grog computers forget NAME` forgets one, which
then stops letting grog in, if it can be reached. Once grog changes how it
talks to its hands, the computers paired before say so: pair them again.

## The prompt

The system prompt is a [Selmer](https://github.com/yogthos/Selmer) template,
[`system-prompt.md`](resources/grog/system-prompt.md), which can use `name`,
`language`, `now`, `timezone`, `location`, `model`, `web_search` and
`memory`.

```nix
services.grog.settings = {
  instructions = "Call me sir. You're a bit sarcastic."; # added to the prompt
  # Or a whole new one:
  # systemPrompt = builtins.readFile ./my-prompt.md;
  # Or a file on the server, read on every question:
  # systemPromptFile = "/etc/grog/prompt.md";
};
```

## Settings

From a JSON file (`grog serve --config`, or `$GROG_CONFIG`), or
`services.grog.settings` on NixOS, merged over the defaults in
[`config.clj`](src/grog/config.clj). It won't start with a bad setting, and
says which one.

| Setting | Default | Notes |
| --- | --- | --- |
| `name` | `"grog"` | What it calls itself, and its wake word |
| `language` | `null` | Like `"English"`. `null`: whatever you speak |
| `timezone` | `null` | Like `"America/Sao_Paulo"`. `null`: the system's |
| `location` | `null` | `{ city; region; country; }`, for the prompt and web search. `null`: it asks when it needs it |
| `systemPrompt` | `null` | Replaces the prompt template |
| `systemPromptFile` | `null` | The same, from a file read on every question |
| `instructions` | `null` | Added to the prompt |
| `provider` | `"openai"` | The only one for now |
| `maxHistoryMessages` | `20` | Messages kept per conversation |
| `conversationTtlMinutes` | `10` | Idle time before a conversation is forgotten |
| `server.host`, `server.port` | `"127.0.0.1"`, `8484` | |
| `server.allowedHosts` | `["localhost", "127.0.0.1", "[::1]"]` | Names clients may reach it by (the `Host` header) |
| `nrepl.enable`, `.host`, `.port` | `false`, `"127.0.0.1"`, `1667` | For development: any local user can run code in grog |
| `hands.socket` | `null` | Unix socket of its [hands](#hands) |
| `skin.dir` | `null` | Files of its [skin](../skin/README.md), served at `/` |
| `skin.captions` | `false` | Whether the skin shows what's said |
| `update.status` | `null` | Where the update service says how an update goes: with it and hands, grog can [update itself](#updating) |
| `computers.ssh` | `"ssh"` | The ssh it reaches its [computers](#computers) with. The `ssh-keygen` next to it makes grog's key |
| `computers.pairing.host`, `.port` | `"0.0.0.0"`, `8485` | Where it listens for a computer to pair, and serves what pairs it, only while someone at grog lets one |
| `stateDir` | `null` | Credentials, changed settings, memory, the mic's switch, usage and computers. `null`: `$STATE_DIRECTORY` or `~/.local/state/grog` |
| `providers.openai.auth` | `"auto"` | `"subscription"` or `"api-key"` to force one |
| `providers.openai.model` | `"gpt-6-luna"` | Model with an API key |
| `providers.openai.subscription.model` | `"gpt-6-luna"` | Model with the subscription |
| `providers.openai.reasoningEffort` | `"low"` | Lower is faster |
| `providers.openai.webSearch.enable` | `true` | |
| `providers.openai.maxTokens` | `2048` | Reasoning included (API key only) |
| `providers.openai.timeoutMs` | `60000` | How long OpenAI may take to start answering, or to send more |
| `providers.openai.apiKeyEnv` | `"OPENAI_API_KEY"` | |
| `providers.openai.prices.models` | `{"gpt-6-luna": {"input": 0.10, "cachedInput": 0.01, "output": 0.50}}` | USD per million tokens, to estimate what the API key spent |
| `providers.openai.prices.webSearch` | `0.01` | USD per web search |
| `providers.openai.baseUrl` | `"https://api.openai.com"` | |
| `providers.openai.subscription.baseUrl`, `.authUrl`, `.callbackPort` | Codex backend, `"https://auth.openai.com"`, `1455` | |

API keys never go in the settings: on NixOS they'd end up in the
world-readable Nix store.

## HTTP API

What the CLI, grog-voice and the skin use ([`api.clj`](src/grog/api.clj)).
JSON keys are lowerCamelCase.

| Request | Does |
| --- | --- |
| `POST /v1/chat` `{"message", "conversationId", "new", "said"}` | Answers as streamed text, or as server-sent events with `Accept: text/event-stream` (`text`, `tool`, then `done` or `error`). `new` starts over; `said` is the question as heard, shown with captions |
| `GET /v1/status` | `name`, its `revision`, `provider`, `model`, the account, `changed` settings, `usage`, whether someone is talking to it (`busy`), and how the last update goes (`update`) |
| `DELETE /v1/settings` | Puts the settings changed by asking back |
| `GET /v1/models` | `{"models": [{"id", "description", "current"...}]}` |
| `POST /v1/auth/<provider>/login` `{"method"}` | Starts signing in, or goes on with the sign-in in progress: `"device"` (the provider's way when it can) answers `{"code", "url", "expiresAt", "help"...}`, the code to enter at `url`; `"browser"`, `{"url", "redirectUri"...}`, the link to sign in at |
| `GET /v1/auth/<provider>` | `{"signedIn", "email"...}`, and the last sign-in: `login` (`pending`, `exchanging`, `done`, `error` or `expired`), `method`, and while it waits for one, the `code`, `url` and `codeExpiresAt` |
| `DELETE /v1/auth/<provider>` | Signs out |
| `GET /v1/events` | What it's up to, as server-sent events: a `snapshot`, then each change ([`events.clj`](src/grog/events.clj)) |
| `POST /v1/voice` `{"state", "conversationId", "heard"}` | grog-voice saying what it's doing, and with captions what it heard. Answers `{"captions", "name"}` |
| `GET /v1/voice/commands` | What grog-voice is asked, as server-sent events: a `snapshot` with whether its `mic` is `"on"` or `"off"`, then `mic` when that changes, `listen` (now, without its name) and `stop` (answering) |
| `POST /v1/actions/<name>` | Does it now: `listen` and `stop` (grog-voice), `mic` `{"on"}`, `volume` (`{"level"}`, `{"change"}`, `{"mute"}`, or `{}` to tell it), `new-conversation` (forgets them all), `pair` (lets a computer pair, like `POST /v1/pairing`), `update` (starts [updating](#updating), unless it is: `{"started"}`), `restart` (grog, as a systemd service), `reboot` or `power-off` (the machine). Answers `{"ok": true}` or what changed, or a 400 saying why it can't here. `/v1/events` has the ones it can (`actions`) |
| `GET /v1/computers` | `{"computers": [{"name", "user", "host", "addedAt"}], "pairing"}`: the [computers](#computers) paired with it, and how pairing one goes |
| `DELETE /v1/computers/<name>` | Forgets it, after asking it to stop letting grog in, if it can be reached: `{"name", "unpaired"}` |
| `POST /v1/pairing` | Lets a computer pair for 5 minutes: `{"status", "until", "addresses", "port"}` |
| `DELETE /v1/pairing` | Stops letting one |
| `POST /v1/pairing/allow`, `POST /v1/pairing/deny` | Allows the computer asking, or not (its `name`, `user` and `code` are in `pairing`) |
| `GET /` | The skin, with `skin.dir` |
| `GET /health` | `ok` |

An error before the answer starts is a 502 with `{"error"}`. One in the
middle of it ends the text with `[error] ...`, or comes as an `error` event.

While pairing, grog also listens on `computers.pairing`, for the computer:
it serves what pairs it at `/` (and a page saying to run it, for browsers),
then answers it in text the script reads, a line of `key=value` each
([`pairing.clj`](src/grog/pairing.clj)).

## Security

- There's no login: it only listens on localhost, and any local user can
  use it. Web pages can't, since requests need an allowed `Host` and POST
  and DELETE need `Content-Type: application/json` (no CORS).
- On NixOS it runs as a dynamic user, in a tight systemd sandbox.
- Tokens, changed settings and memory are only readable by it. Logs never
  have what's said, only which tools ran.
- Its hands can do anything their user can (on NixOS, anything at all),
  without asking, except in a turn that searched the web. So can anyone who
  runs code in grog with `nrepl.enable`, and any local user can take its
  actions, like restarting the machine (`POST /v1/actions/<name>`).
- Updating runs what was pushed to grog's flake input, as root: whoever can
  push there runs code on the machine, once someone asks for an update.
- grog only listens on the network to pair a computer, while someone at it
  lets one, and someone there has to allow it, with the same code on both
  screens: one in between swapping keys can't make them match. But the
  script it serves for that goes over plain HTTP: someone who can intercept
  the network in those minutes could swap it for anything, which would run
  as the computer's user, and show the right code. Pair on a network you
  trust. Then anyone who can talk to grog, or reach it as a local user, can
  ask it for what that computer allows, as its user. What it asks first
  needs someone at the computer.
- Any local user can watch `/v1/events`, which has what's said, and what
  tools were used with (commands, searches), with `skin.captions`.

## Development

`bb test` runs the tests. With `nrepl.enable`, redefined functions take
effect on the next request, and `grog.server/current` holds the running
context. A new provider implements [`provider.clj`](src/grog/provider.clj).
