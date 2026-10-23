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
grog mic off / grog mic on # off, nothing is heard until it's on again
grog update                # to the latest grog pushed, saying how it goes
grog integrations          # what it can connect to, and what's connected
grog connect openai        # sign in to the ChatGPT subscription (--browser: in a browser); so does grog login
grog connect computer      # pair one of your computers
grog connect home-assistant  # your Home Assistant: its lights and the rest
grog connect youtube-music # your playlists and liked songs; it plays without it too
grog disconnect ID         # computer:NAME forgets one; openai signs out, like grog logout
grog integrations add WHAT # an MCP server: its name in the MCP Registry, npm:pkg@1.2.3, pypi:pkg==1.2.3 or https://...
grog integrations search TEXT  # servers of the MCP Registry (it can be slow)
grog integrations remove ID    # one you added
grog tools ID              # an MCP server's tools, and what grog does with each
grog tools ID TOOL allow   # or ask, deny, show, hide, default
grog music                 # what's playing
grog music play legião urbana  # or pause, resume, next, previous, stop; play alone resumes
grog music volume 40       # or +10, -10
```

It finds the server through `$GROG_URL`.

## Paying for it

- **ChatGPT subscription**: sign in with a code, on any phone or computer
  ([connecting](#connecting-things) `openai`): `grog login` prints it, with
  where to enter it (`auth.openai.com/codex/device`), and the link to grog's
  page, which has both. With a skin (`skin.dir`), grog shows that page's QR
  code whenever it has nothing to pay with, and the code. ChatGPT has to
  allow it once per account, on its website (its app doesn't have it): in
  Settings, Security and login, turn on "Enable device code sign-in for
  Codex, Excel, PowerPoint, and Word". Without that, sign in in a browser
  (`grog login --browser`, or on grog's page): it comes back to
  `localhost:1455`, which works on grog's own screen; elsewhere, paste the
  address it ended at on grog's page, or forward that port (`ssh -L
  1455:127.0.0.1:1455 <server> grog login --browser`). The tokens stay in
  the state directory (`secrets/openai.json`) and refresh on their own. Once
  they can't, the sign-in needs doing again, and the API key pays meanwhile,
  if there's one.
- **API key**: in `OPENAI_API_KEY`. On NixOS, in `/etc/grog/secrets.env`
  (mode 0600), never in the Nix config.

When signed in, the subscription is used. Otherwise, the API key.

`grog status` and the skin show how much of the subscription's limits is
used (shared with ChatGPT and Codex), or what the API key spent today and
this month. The spending is an estimate, at the prices in
`providers.openai.prices`: add the model you switch to.

## Changing it by asking

Some settings change when you ask: its `name` (and so its wake word),
`language`, `location`, `timezone`, the model and its reasoning effort,
and what plays [music](#music) (`music.provider`). "From now on your name
is Jarvis", "I live in Campinas", "switch to gpt-6-astra", "think harder",
"play music on YouTube Music from now on". Without a `location`, it asks which city you
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

In a turn it uses as many tools as it needs, one after another: it looks a
song up on the web, then plays it. Nothing it read holds back what it does
next, a page included ([Security](#security)). After 29 calls it has to
answer, which only stops a model that wouldn't.

## Hands

The brain is sandboxed and can't touch the machine. Its hands can. They're
`grog hands`, a small service of the user with the speaker
(`services.grog.hands` on NixOS), taking commands from the brain over a unix
socket (`hands.socket`). With them, the model can:

- set the volume of the speaker it speaks on
- tell how the machine is doing: load, memory, disk, temperature, network,
  audio and Bluetooth devices, services, last deploy
- run shell commands. It only asks first when a command could lose data or
  cut it off.
- restart the machine, or turn it off, once its answer was said.

And whoever's at its skin can see and change its Wi-Fi ([below](#wi-fi)).

On NixOS, that user gets sudo without a password, and polkit lets it do
anything (`services.grog.hands.sudo`): grog can do anything on the machine.
Commands time out after 30 s, and the logs only say how they went, never
what they ran or printed. On NixOS, what the shell changes is gone at the
next deploy.

### Wi-Fi

At its skin (Wi-Fi, in its actions), the hands also tell, through
NetworkManager, whether Wi-Fi is on, the networks the machine sees and
which it's on, and join one, with its password typed on the skin's
keyboard when it needs one ([`wifi.clj`](src/grog/wifi.clj),
[`wifi.sh`](resources/grog/wifi.sh)). It joins on the Wi-Fi device seeing
the network best, and NetworkManager keeps it, for any Wi-Fi device (a
USB adapter plugged in elsewhere too); one that didn't work isn't kept.
Networks wanting more than a password (802.1X) can't be joined there. The
password only goes to nmcli: never in a log, nor in an answer.

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

## Connecting things

What grog connects to, its integrations ([`integrations.clj`](src/grog/integrations.clj)),
all connect the same way ([`connect.clj`](src/grog/connect.clj)): the
ChatGPT subscription (`openai`), your computers (`computer`),
[YouTube Music](#youtube-music) (`youtube-music`), and
[MCP servers](#mcp-servers), [Home Assistant](#home-assistant) among them.
Someone at grog opens a window for one, on its screen (Integrations, in its
actions), by asking it ("connect my computer"), or with `grog connect ID`.
For 10 minutes, grog listens on the network (`integrations.host`, `.port`),
where a page follows the steps (sign in, enter a code, run a command),
behind a random token, only in the link and QR code on grog's screen and in
the CLI: `http://192.168.15.10:8485/c/K7Q2M9AB4X`. Open it on any phone or
computer at home. Once it's connected, or the window closes, grog leaves
the network again. One at a time: opening another closes the first.

What's connected stays in `integrations.json`, in the state directory, and
the tokens apart, in `secrets/` (both only grog can read). `grog
integrations` lists it, and `grog disconnect ID` disconnects it (signing
out, forgetting a computer, stopping a server).

## Computers

grog can act on your other computers: tell how one is doing, show a
notification on it, open a link or a file there, control what plays and the
volume, lock its screen, suspend it, turn it off, wake it up (Wake-on-LAN,
if it's wired and set up for it), or run a command. Linux ones, for now.

To pair one, let it at grog: "Add a computer" on its screen, or `grog
connect computer` ([connecting](#connecting-things)). For 10 minutes, grog
listens on the network for it, and serves what pairs it. In a terminal on
the computer, run what grog's screen says:

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

`grog integrations` lists them. `grog disconnect computer:NAME` forgets one,
which then stops letting grog in, if it can be reached. Once grog changes
how it talks to its hands, the computers paired before say so: pair them
again.

## MCP servers

grog can use the tools of [MCP](https://modelcontextprotocol.io) servers,
each one an integration, connected [like the rest](#connecting-things).
Its catalog ([`catalog.clj`](src/grog/catalog.clj)) has them as the
official MCP Registry's `server.json`: those that come with grog, in
`resources/grog/integrations/` ([Home Assistant](#home-assistant), for
now), and those you add, in the state
directory (`catalog/`):

```sh
grog integrations search weather                # the MCP Registry: it can take 20 s or more
grog integrations add io.github.foo/weather-mcp # its latest version there
grog integrations add npm:weather-mcp@1.2.3     # a package, its version pinned
grog integrations add pypi:weather-mcp==1.2.3
grog integrations add https://mcp.example.com/mcp
grog connect weather-mcp
```

grog doesn't check what you add: a package runs code from npm or PyPI, and
a remote server gets what grog sends it. Its screen and `grog integrations`
say which you added, and which are in the cloud (remote, outside your
network). `grog integrations remove ID` disconnects one and forgets it.

Connecting one asks on grog's page for what it needs, if anything: its
environment variables, headers, or parts of its address, warning about the
secret ones, which cross the network as they are. A remote server that
wants grog to sign in gets OAuth ([`mcp_auth.clj`](src/grog/mcp_auth.clj)):
grog finds its authorization server, which knows grog by its client ID
metadata document (the relay page's `client.json`), or lets grog register
(dynamic registration), or else takes a client id you made there, asked in
a form. The browser signs in, on any phone or computer, and comes back
through the project's relay page ([`pages/`](../pages/README.md),
`integrations.relay`), which sends it on to grog's page on the network.
PKCE always, the issuer checked, the resource named. The tokens refresh on
their own; once they can't, connect it again.

Packages run in grog's runner (`grog mcp --socket PATH`,
[`runner.clj`](src/grog/runner.clj), `integrations.runner`), apart from
grog, which installs them with `npx` and `uvx` the first time they run.
Without it, grog only has remote servers. Those are reached over
Streamable HTTP: https, or http on the local network. grog speaks MCP
2026-07-28, and the handshake of the revisions before (down to 2025-03-26)
with servers that don't know it ([`mcp.clj`](src/grog/mcp.clj)). A server
starts the first time it's used, and stops after 10 minutes without calls.
What its tools are is kept in the state directory (`tools/`), so the model
sees them without starting it.

### What the model sees

A server's tools are named `<prefix>__<tool>` (its id, 64 characters at
most), and the model sees the first 40, unless its catalog entry says
which. Their annotations say what they do (read-only, destructive,
open-world: what it answers may carry someone else's text, like mail or a
page), but open-world only counts for servers that come with grog: the
tools of those you add are taken as open-world, whatever they say.

- Each tool is allowed, asked first, or denied. Destructive ones ask by
  default, the rest are allowed.
- Asked first, a call doesn't run: the model asks you, and once you say
  yes, calls it again, which runs (the same call, in the same
  conversation, within 5 minutes). grog's screen asks too, unless
  something else is on its card: Allow there says yes for you.

`grog tools ID` lists a server's tools, what grog does with each, and
whether the model sees it; `grog tools ID TOOL allow|ask|deny|show|hide`
changes that (`default` puts it back), kept in `tools.json`.

A server may ask for something in the middle of a call
([`elicit.clj`](src/grog/elicit.clj)): a form, filled in on grog's page
(its link and QR code on the card), or a page to open (its QR code on the
card, then Done). Nobody answering in 3 minutes cancels it.

### Home Assistant

The first integration that comes with grog
([`home-assistant.json`](resources/grog/integrations/home-assistant.json)).
Home Assistant needs its "Model Context Protocol Server" integration
(Settings, Devices & services, Add integration), and grog only sees the
entities exposed to Assist there. grog uses `/api/mcp/assist`, or
`/api/mcp` on older ones, which may only let Home Assistant's admins in:
then sign in as one, or update it.

To connect it, ask grog ("connect Home Assistant"), tap it in Integrations
on its screen, or run `grog connect home-assistant`. grog looks for it on
the network ([`mdns.clj`](src/grog/mdns.clj): avahi's daemon, with
`avahi-browse`, else a query of its own), and its page asks for its
address, filled in with what it found. Then whoever connects signs in on
Home Assistant's own page, which comes straight back to grog's page: grog
is a client there by its address on the network
(`http://192.168.15.10:8485/`), so there's no relay page and nothing to
register. The page's other way is pasting a long-lived token (in Home
Assistant, your profile, Security), which crosses the network as it is.
The connection takes the name Home Assistant has for the home. Its tokens
refresh on their own, and disconnecting revokes them (a pasted token stays:
delete it in Home Assistant).

### Lights

The model has one tool for lights, `lights`
([`capabilities.clj`](src/grog/capabilities.clj)), whatever is behind it:
it turns them on or off, sets their brightness, color or color temperature,
and tells how they are. It goes through the first connected integration
that comes with grog and maps lights (Home Assistant, for now). It's grog's
own, so it never asks first, though like any tool that changes things it
doesn't run after a web search.

Next to it are Home Assistant's own tools, `ha__...`, for the rest of the
home, under the rules [above](#what-the-model-sees): those that change
things and that Home Assistant doesn't mark as non-destructive ask first,
but for those its entry allows (`GetLiveContext`, `HassLightSet`, setting
the temperature).

### What grog reads in its own entries

The `_meta` of the entries that come with grog (`io.github.ericdallo.grog`)
says more than `server.json` does:

- `id`, `prefix`, `icon`, `about` (a line for the screen), `cloud`.
- `connect`: its ways, each a list of steps (`discover`, what answers on
  the network; `form`; `oauth`, at an authorization server on the
  network) and the `headers` they make (`Bearer {token}`), the
  `{variables}` in them and in its remotes' addresses filled in by the
  steps. Its remotes are tried in order, the next on a 404.
- `name`: where to read what the connection is called.
- `errors`: what to say when it answers a status (404: no MCP server yet).
- `capabilities`: what `lights` and `music` call of it, and with what.
- `policy` and `show`: its tools' default modes, and which the model sees.

A tool named there also matches a server's ending in `__` and that name
(`intent__HassTurnOn`), as Home Assistant's change by version.

## Music

The model has one tool for music, `music` ([`music.clj`](src/grog/music.clj)),
whatever plays it: `play`, `pause`, `resume`, `stop`, `next`, `previous`,
`volume` (0 to 100, or up or down by some) and `status` (what's playing).
What to play comes as you said it, with its `kind` when the model knows it:
`song`, `album`, `playlist`, `artist`, `my-playlist` (one of yours, by its
name) or `liked` (your liked songs), maybe shuffled. Like `lights`, it's
grog's own: it never asks first, though it doesn't change anything after a
web search.

What plays it, its providers, are integrations:

- **YouTube Music**, grog's own ([below](#youtube-music)), playing through
  grog-music, its player ([`music/`](../music/README.md), `music.socket`),
  on grog's speaker. Only there with grog-music.
- **MCP servers** whose entry, one that comes with grog, maps `music` in its
  `capabilities` ([above](#what-grog-reads-in-its-own-entries)), as Home
  Assistant's maps `lights`: `play` (`{query}`, `{kind}`), `pause`,
  `resume`, `next`, `previous`, `stop`, `volume` (`{level}`, `{change}`)
  and `status`, at least `play`. A Spotify server would plug in that way,
  with an entry and no code. None comes with grog yet.

What plays is the one `music.provider` names, changed by asking ("play
music on Spotify from now on"); else the first connected (YouTube Music with
your account, then the servers in catalog order); else the first there. The
tool only offers what that one can do.

### YouTube Music

It searches and plays with no account: a song (then what YouTube Music
plays after it), an album, a playlist, an artist's top songs, up to 100 in
the queue. Connecting your Google account adds your playlists and liked
songs ([`youtube_music.clj`](src/grog/youtube_music.clj)): ask grog
("connect YouTube Music"), tap it in Integrations on its screen, or run
`grog connect youtube-music`. The card shows a code, to enter at Google's
page (`google.com/device`) on a phone or computer.

That takes a Google client of type "TVs and Limited Input devices": the
one grog was given in its environment
([for every grog of a home](#a-google-client-for-grog)), or one you made,
which grog's page asks for first: make it the
[same way](#a-google-client-for-grog).
While its consent screen is in testing, only its test users can sign in,
and the sign-in lasts 7 days.

YouTube Music refuses grog's sign-in for its own API (ytmusicapi's), so
your library comes through the YouTube Data API v3: your playlists, a
playlist's songs, and the music you liked (your likes on YouTube in its
Music category, as YouTube Music shows them: not the podcasts or other
videos). Not your saved albums or history, for now. The tokens refresh on
their own; once they can't, connect it again. Disconnecting revokes them.

### While grog listens

While grog-voice hears someone after its name (or a touch), waits for them
after being called or asking something, and while grog answers aloud, the
music goes down, to 20 of 100, then back once it waits for its name again.
It's the brain's doing, from what grog-voice says it's doing: grog-voice
knows nothing of the music. Touching grog's face while it answers stops it,
and pauses the music (`POST /v1/actions/stop` does too).

grog also tells grog-voice whether its player plays (`GET
/v1/voice/commands`, `music`), as it listens for its name differently over
music: it asks the player right after each music action it takes and after
the stop action, every 5 s while it last saw it playing (the queue ends,
someone pauses it), and once a minute otherwise (something played it
without grog). Paused, stopped, or no player answering: not playing. On
NixOS, grog's music also goes through an echo canceller, which keeps it
out of grog-voice's mic ([body/](../body/README.md#music)); elsewhere,
grog-voice still hears it, and over loud music grog may hear its name less.

### On its screen

Even so, on a laptop's speaker grog can't hear its name over its own music
([plans/voice.md](../plans/voice.md#music)): while it plays, it's
controlled at grog's screen. The same checks tell whoever watches grog
(`/v1/events`, `music`) what its player plays: `{"state", "title",
"artist", "album", "positionS", "durationS", "volume", "ducked", "index",
"count", "provider", "at"}`, `state` being `playing`, `paused` or `idle`
(without a song then), `positionS` where the song was `at`; `null` without
a player, or while it doesn't answer. It's sent when anything but the
position changes, and while it plays every 5 s or so: the skin moves the
clock itself in between. Titles aren't what anyone said, so they go
without captions, but grog never logs them. The skin's buttons go through
`POST /v1/music`, and it lists what to play with `GET /v1/music/library`:
the user's playlists (or liked songs) through what plays, with their
account, then plays one by its `id`.

### Speakers

At its skin (Speakers, in its actions), the music and grog's voice can play
on different speakers: the music on a Bluetooth speaker, say, and grog on
the machine's own. grog-voice says which there are, as its session's
PipeWire has them ([voice/](../voice/README.md)). The music plays on the
session's default speaker, and picking one for it makes it the default
(WirePlumber keeps it, and goes back to it when it's back). grog's voice
plays with the music, or on the one picked for it, kept in `voice.json`
(PipeWire plays it on the default while that one isn't there). The volume
grog changes, through its hands, is the one of the speaker it speaks on;
the music has its own.

### A Google client for grog

Given to grog, a client lets whoever connects YouTube Music do it with just
a code. To make it, once:

1. Create a project in the [Google Cloud console](https://console.cloud.google.com),
   and enable the YouTube Data API v3 in it.
2. Configure its OAuth consent screen: External, with the scopes
   `youtube`, `openid` and `email`, and publish it. In testing, only the
   test users listed can sign in, and their refresh tokens expire after 7
   days. Until Google verifies the app, its sign-in shows a warning, and
   it's capped at 100 users.
3. Create an OAuth client ID of type "TVs and Limited Input devices".
4. Give grog its ID and secret in its service's environment, like an API
   key: `GROG_GOOGLE_CLIENT_ID` and `GROG_GOOGLE_CLIENT_SECRET`, in
   `/etc/grog/secrets.env` on NixOS, then restart grog. Never in the
   repository: though Google doesn't treat this kind of client's secret as
   confidential, scanners take it for a leak, and Google may revoke it.

The Data API's daily quota is the project's, shared by every grog using its
client.

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
| `music.socket` | `null` | Unix socket of grog-music, the player it plays [music](#music) with. `null`: it plays nothing itself |
| `music.provider` | `null` | What plays music, by its id: `youtube-music`, or a connected integration's. `null`: the first connected, else the first there. Also changed by asking |
| `computers.ssh` | `"ssh"` | The ssh it reaches its [computers](#computers) with. The `ssh-keygen` next to it makes grog's key |
| `integrations.host`, `.port` | `"0.0.0.0"`, `8485` | Where it listens while someone at grog [connects something](#connecting-things), and only then: its page, and what pairs a computer |
| `integrations.runner` | `null` | Unix socket of its runner (`grog mcp`), which runs [MCP servers](#mcp-servers) from npm and PyPI apart from it. `null`: only remote ones |
| `integrations.relay` | `"https://ericdallo.github.io/grog/oauth/"` | The relay page OAuth comes back through, ending in `/`: its `client.json` is grog's client ID metadata document. A fork has its own ([`pages/`](../pages/README.md)) |
| `stateDir` | `null` | What's connected and its tokens, changed settings, memory, the mic's switch, usage, computers, the MCP servers you added (`catalog/`), their tools (`tools/`) and your choices for them (`tools.json`). `null`: `$STATE_DIRECTORY` or `~/.local/state/grog` |
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
| `GET /v1/integrations` | `{"integrations": [{"id", "title", "heading", "about", "icon", "ways", "multiple", "connections"}], "connect"}`: what it can [connect to](#connecting-things), [MCP servers](#mcp-servers) with `"mcp": true`, `added` (you added it) and `cloud`; what's connected (`{"id", "name", "detail", "status", "addedAt"}`, `status` being `connected` or `signed-out`, `detail` like `12 tools`); and the last session |
| `POST /v1/integrations` `{"name"}`, `{"package"}` or `{"url"}` | Adds an MCP server: its name in the MCP Registry, `npm:pkg@1.2.3` or `pypi:pkg==1.2.3`, or its address. `{"id", "integration"}`; a 400 saying why it can't run here, a 502 when the registry doesn't answer |
| `DELETE /v1/integrations/<id>` | Removes an MCP server you added, disconnecting it first: `{"id", "removed"}` |
| `GET /v1/integrations/search?q=...` | Servers of the MCP Registry whose name holds it, up to 20: `{"servers": [{"name", "title", "description", "version", "runs"}]}`, `runs` being `npm`, `pypi`, `remote`, or null when grog can't run it. The registry can take 30 s; a 502 when it doesn't answer |
| `POST /v1/integrations/<id>/connect` `{"way"}` | Opens a session connecting it (`way`: its usual one without), or goes on with the one open: `{"integration", "heading", "way", "status", "step", "link", "until"...}`, `step` saying what to do now (`{"kind": "device-code", "code", "url"}`, `{"kind": "oauth", "url", "backTo"}`, `{"kind": "form", "fields"}`, `{"kind": "script", "command"}`, `{"kind": "confirm", "question", "detail"}`, `{"kind": "link", "url", "message"}`...), `link` its page on the network. Asking first and what a server asks for open sessions too (`integration` `ask` and `elicit`) |
| `GET /v1/connect` | The last session, the same way (`status`: `open`, `done`, `denied`, `expired`, `cancelled` or `failed`), without what only its page shows; `{}` before any |
| `DELETE /v1/connect` | Cancels it |
| `POST /v1/connect/allow`, `POST /v1/connect/deny` | Allows what its step asks to be allowed (a computer, its `code` the same on both screens; a tool call asked first; a page opened, done), or not |
| `DELETE /v1/connections/<id>` | Disconnects it: signs out (`openai`), forgets a computer (`computer:NAME`) after asking it to stop letting grog in, if it can be reached (`{"name", "unpaired"}`), or stops an MCP server, revoking its tokens when it can |
| `GET /v1/connections/<id>/tools` | An MCP server's tools: `{"tools": [{"name", "title", "description", "mode", "defaultMode", "shown", "defaultShown", "readOnly", "destructive", "openWorld"}]}`, `mode` being `allow`, `ask` or `deny`. A 404 when it isn't one |
| `PUT /v1/connections/<id>/tools/<name>` `{"mode", "show"}` | Sets either or both (null: back to the default), kept in `tools.json`: the tool, as above |
| `GET /v1/qr?text=...` | Its QR code, as SVG, for the skin |
| `GET /v1/events` | What it's up to, as server-sent events: a `snapshot`, then each change ([`events.clj`](src/grog/events.clj)), what its player plays included (`music`, [on its screen](#on-its-screen)) |
| `POST /v1/voice` `{"state", "conversationId", "heard"}` | grog-voice saying what it's doing, and with captions what it heard. Answers `{"captions", "name"}` |
| `GET /v1/voice/commands` | What grog-voice is asked, as server-sent events: a `snapshot` with whether its `mic` is `"on"` or `"off"`, whether grog's `music` plays (`true` or `false`) and the `speaker` it speaks on (a name, or `null`: its own), then `mic`, `music` or `speaker` when one changes, `listen` (now, without its name), `stop` (answering) and `speakers` `{"id", "music"}` (which there are, making `music` the default first, if given) |
| `POST /v1/voice/speakers` `{"id", "speakers"}` or `{"id", "error"}` | grog-voice saying which [speakers](#speakers) there are, as asked: `[{"name", "label", "kind", "default"}]`, `kind` being `bluetooth`, `usb`, `hdmi`, `headphones` or `speaker` |
| `POST /v1/music` `{"action", "query", "kind", "shuffle", "level", "change", "id"}` | Does it through what plays [music](#music), as the `music` tool takes them: `{"provider", "text"}`, `text` saying what it did. `id`, with its `kind` (`song`, `album`, `playlist` or `my-playlist`), plays exactly that, like one from the library. A 400 without a known `action`, with a bad `id`, or saying why it can't |
| `GET /v1/music/library?kind=playlists` | The user's playlists (or `kind=liked`, their liked songs) through what plays [music](#music), with their account: `{"items": [{"kind", "id", "title", "artist", "count"}]}`. A 409 without it (`{"error", "connect": "youtube-music"}`), or without a player; a 502 when YouTube doesn't answer |
| `POST /v1/actions/<name>` | Does it now: `listen` and `stop` (grog-voice; `stop` also pauses the music), `mic` `{"on"}`, `speakers` (`{}` tells which there are, `{"music": name}` plays the music on one, `{"voice": name}` grog's voice, `null` back with the music: `{"speakers", "voice"}`, see [Speakers](#speakers)), `volume` (of the speaker grog speaks on: `{"level"}`, `{"change"}`, `{"mute"}`, or `{}` to tell it), `wifi` (with hands: `{}` tells it, `{"rescan": true}` after looking again, `{"on": true}` after turning it on, `{"connect": a network's name, "password"}` after joining it: `{"radio": "on" or "off", "devices": [{"device", "state", "connection"}], "networks": [{"ssid", "signal", "security", "device", "active", "saved"}], "joined"}`, `security` being `open`, `password` or `enterprise`, see [Wi-Fi](#wi-fi)), `new-conversation` (forgets them all), `connect` `{"integration", "way"}` (opens a session, like `POST /v1/integrations/<id>/connect`), `update` (starts [updating](#updating), unless it is: `{"started"}`), `restart` (grog, as a systemd service), `reboot` or `power-off` (the machine). Answers `{"ok": true}` or what changed, or a 400 saying why it can't here. `/v1/events` has the ones it can (`actions`) |
| `GET /` | The skin, with `skin.dir` |
| `GET /health` | `ok` |

An error before the answer starts is a 502 with `{"error"}`. One in the
middle of it ends the text with `[error] ...`, or comes as an `error` event.

While a session is open, grog also listens on `integrations.port`, on the
network: the session's page at `/c/<token>`, which asks `/c/<token>/state`
how it goes, and posts what's entered there as JSON; `/c/callback`, where
a browser signing in may come back to (straight, or through the relay
page); and while a computer pairs, what
pairs it at `/` (and a page saying to run it, for browsers), then answers
it in text the script reads, a line of `key=value` each
([`pairing.clj`](src/grog/pairing.clj)).

## Security

- There's no login: it only listens on localhost, and any local user can
  use it. Web pages can't, since requests need an allowed `Host` and POST,
  PUT and DELETE need `Content-Type: application/json` (no CORS).
- On NixOS it runs as a dynamic user, in a tight systemd sandbox.
- Tokens, changed settings and memory are only readable by it. Logs never
  have what's said, only which tools ran.
- Its hands can do anything their user can (on NixOS, anything at all),
  without asking. So can anyone who runs code in grog with `nrepl.enable`,
  and any local user can take its actions, like restarting the machine
  (`POST /v1/actions/<name>`).
- What the model reads can steer it: a page found searching the web, what
  an MCP server answers. Nothing it read holds back what it does next in
  that turn (it once did, and grog couldn't look a song up and play it),
  so a page written to fool it could have it use any of its tools, its
  hands included. Destructive MCP tools still ask first.
- Updating runs what was pushed to grog's flake input, as root: whoever can
  push there runs code on the machine, once someone asks for an update.
- grog only listens on the network while someone at it connects something,
  for 10 minutes at most, its page behind a random token that only its
  screen and its CLI show. It's plain HTTP: codes to enter at a provider,
  and those a browser brings back from signing in, which are useless without
  what grog keeps (PKCE), cross it safely.
- A computer pairing has to be allowed at grog, with the same code on both
  screens: one in between swapping keys can't make them match. But the
  script grog serves for that goes over plain HTTP: someone who can
  intercept the network in those minutes could swap it for anything, which
  would run as the computer's user, and show the right code. Pair on a
  network you trust. Then anyone who can talk to grog, or reach it as a
  local user, can ask it for what that computer allows, as its user. What
  it asks first needs someone at the computer.
- MCP servers you add aren't checked by grog: they're your call. Packages
  run in the runner, apart from grog: on NixOS a dynamic user of its own,
  with no access to grog's state or anyone's home, and the only place
  memory may be both writable and executable (Node's JIT). Remote servers,
  those in the cloud above all, see what grog sends them. A server only
  gets its own secrets, from `secrets/`: in its environment, or its
  headers. What it asks for in a form crosses the network in plain HTTP,
  like a secret typed while connecting it.
- What servers answer is untrusted, like a web page (above): destructive
  tools ask first. What tools are given and answer is never logged.
- grog-music runs in the session of the user with the speaker, apart from
  grog, which reaches its socket through a group. Your Google tokens stay
  in grog's `secrets/`, and go to grog-music with each request that needs
  them, which keeps them in memory for that request only, never on disk.
  Neither logs what's asked or played.
- The relay page sees OAuth codes, never tokens: a code is useless without
  the verifier grog keeps (PKCE). The state it reads has grog's address on
  your network, which isn't secret. Signing in to Home Assistant doesn't go
  through it: its page sends the browser straight back to grog.
- Any local user can watch `/v1/events`, which has what's said, and what
  tools were used with (commands, searches), with `skin.captions`; and
  while something connects, the link to its page, with its token.

## Development

`bb test` runs the tests. With `nrepl.enable`, redefined functions take
effect on the next request, and `grog.server/current` holds the running
context. A new provider implements [`provider.clj`](src/grog/provider.clj).
