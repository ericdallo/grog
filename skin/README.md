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
- In its actions, what it costs: how much of the subscription's limits is
  used, or what the API key spent.
- With nothing to pay with, how to sign in: a code to enter on a phone or
  computer, with the QR code of the page to enter it at, and what ChatGPT
  has to allow once per account. The QR codes come with the Nix package
  (`body/packages/grog-skin.nix`), for the pages it knows; without one, only
  the address shows.

## Touching it

On a touch screen, or with a mouse:

- Touching grog's face talks to it, no name needed. While it answers, it
  stops it. With its mic off, it turns it on and listens.
- The button by the clock opens its actions: the volume, the mic, a new
  conversation, adding a computer, signing out, updating grog, restarting
  it or the machine, and turning the machine off. Those that can't be
  undone take a second tap. They close by themselves after a while. Its
  version is at the bottom.
- Adding a computer, it shows what to run on it, then the code of the one
  asking, to allow it if it's the same as on the computer, or not.

They go through the brain's actions (`POST /v1/actions/<name>`).

## Running it

The [brain](../brain/README.md) serves it at `http://127.0.0.1:8484/`, from
`skin.dir`. The page follows it through `GET /v1/events`.

On NixOS, `services.grog.skin.enable` also opens it fullscreen when
`services.grog.user` logs in, in a window of its own (`kiosk.js`, GTK and
WebKit) that keeps the screen on. F11 toggles fullscreen, Ctrl+R reloads,
and Ctrl+Q closes it until the next login. With
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
The actions slide in and fade, and that's all.
