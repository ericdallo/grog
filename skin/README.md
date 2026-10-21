# skin

grog's skin: a page showing what grog is up to, for a screen next to it.

- A third of the screen takes a color, and grog's face, for what it's doing:
  listening, hearing you, thinking, using a tool, speaking, or something
  wrong. Big enough to read from across the room. It looks pleased when a
  turn went well, dozes when nobody talked to it for a while, and gets
  sleepy at night, when the screen goes dark.
- The time, and the steps of the last turn with how long each took.
- With captions, what you say as you say it, then its answer.
- The last few turns, and what it costs: how much of the subscription's
  limits is used, or what the API key spent.
- With nothing to pay with, how to sign in: a code to enter on a phone or
  computer, with the QR code of the page to enter it at, and what ChatGPT
  has to allow once per account. The QR codes come with the Nix package
  (`body/packages/grog-skin.nix`), for the pages it knows; without one, only
  the address shows.

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
watch what's said. grognix turns it on: grog is its only user, and whoever
is in the room hears it anyway.

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
