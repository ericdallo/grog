# skin

grog's skin: a page showing what grog is up to, for a screen next to it.

- A third of the screen takes a color, and grog's face, for what it's doing:
  listening, hearing you, thinking, using a tool, speaking, its mic off,
  restarting, or something wrong. Big enough to read from across the room. It looks pleased when a
  turn went well, dozes when nobody talked to it for a while, and gets
  sleepy at night, when the screen goes dark.
- The time, and the steps of the last turn with how long each took.
- Under the time, how an update goes while it does, and how it went.
- The last turns, as a chat: with captions, what you say as you say it,
  its answer as it comes, and the tools it uses in between, with what (a
  search, a command); without, only which tools it used, and how long each
  turn took. It keeps to the latest, but you can scroll back, and a button
  (or leaving it alone a while) brings it back.
- While grog's player plays, or is paused, a bar under the rest: the song
  and its artist, its time (counted on by the skin between grog's updates),
  and its buttons. Hidden while a card shows, under the sheets.
- In its actions, what it costs: how much of the subscription's limits is
  used, or what the API key spent.
- Connecting something (signing in to ChatGPT, adding a computer), a card:
  the link to grog's page for it, and its QR code (grog makes them, `GET
  /v1/qr`), what to do now, a code to enter or to compare, Allow and Deny,
  Cancel, and then how it went. With nothing to pay with, it shows how to
  sign in by itself: a code to enter on a phone or computer, and what
  ChatGPT has to allow once per account.
- The same card when grog asks something of whoever is at it: whether to
  go ahead with a tool that asks first (Allow, Deny), what an MCP server
  needs in the middle of a call (a form, on grog's page, behind the link
  and QR code), or a page a server wants opened (its QR code, then Done).
  Only when nothing else is on the card.
- An MCP server's tools (`<server>__<tool>`) show as the tool in words,
  with the server when it fits: "Create issue · github". `lights`, grog's
  own tool for them, shows as "Seeing to the lights", with a bulb, and
  `music` as "Seeing to the music", with a note.

## Touching it

On a touch screen, or with a mouse:

- Touching grog's face talks to it, no name needed. While it answers, it
  stops it, and pauses the music. With its mic off, it turns it on and
  listens.
- The button by the clock opens its actions: the volume, the mic, a new
  conversation, its integrations (signing in to ChatGPT or out of it,
  adding a computer, forgetting one, connecting YouTube Music with a code
  entered at Google's page, connecting an MCP server, with how
  many tools it has, whether you added it, which grog didn't check, and
  whether it's in the cloud), updating grog, restarting it or the
  machine, and turning the machine off. Those that can't be undone take a
  second tap. They close by themselves after a while. Its version is at
  the bottom.
- The music's bar: previous, play or pause, next, and the volume down or
  up by 10. On a laptop's speaker, grog can't hear its name over its own
  music, so this is where it's controlled while it plays. Its list button
  (or Music, in the actions, once grog's player answers) opens what to
  play: liked songs, shuffled or not, then your playlists, asked of grog
  as it opens (`GET /v1/music/library`). Without your YouTube Music
  account, it says so, with the entry connecting it. Tapping one plays it
  and closes it. What fails shows in red, for a moment.
- Adding a computer, it shows what to run on it (`curl ... | bash`: nothing
  to install there), then the code of the one asking, to allow it if it's
  the same as on the computer, or not.

They go through the brain's actions (`POST /v1/actions/<name>`), its
API for what's connected (`/v1/connect`, `/v1/connections`), and its
music's (`/v1/music`).

## Running it

The [brain](../brain/README.md) serves it at `http://127.0.0.1:8484/`, from
`skin.dir`. The page follows it through `GET /v1/events`.

On NixOS, `services.grog.skin.enable` also opens it fullscreen when
`services.grog.user` logs in, in a window of its own (`kiosk.js`, GTK and
WebKit) that keeps the screen on. F11 toggles fullscreen, Ctrl+R reloads,
and Ctrl+Q closes it until the next login. When the GPU hangs and the
kernel resets it (i915 says so), the window can't draw anymore: it exits,
and its service starts it again. With
`services.grog.skin.user = null`, it's only served.

To see it from another machine, `ssh -L 8484:127.0.0.1:8484 <server>` and
open `http://localhost:8484/`.

`skin.captions` is off by default, since anyone who can reach grog could
watch what's said, and what its tools are used with. grognix turns it on:
grog is its only user, and whoever is in the room hears it anyway.

## Development

Plain HTML, CSS and JS in `web/`, with no build and no dependencies.
`view.js` decides what to show and is tested with `node --test`, `skin.js`
draws it, and `face.js` draws grog's face and makes it move.

Point a local grog's `skin.dir` at `skin/web` and open
`http://localhost:8484/`. The fonts come with the Nix package; without them
it falls back to the system's.

It shares a small CPU with grog-voice, so it only moves while something
happens (at rest, the face only blinks or looks around now and then), keeps
still while someone talks to grog, and only animates transform and opacity.
The sheets (actions, music) slide in and fade, and that's all: the music's
time changes once a second, as text, and no bar moves with it.
