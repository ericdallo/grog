# body

What grog runs on, all in Nix: how it's built, a NixOS module to run it on
any machine, and grognix, a PC set up just for it.

```
packages/        how grog, grog-voice, grog-music and grog-skin are built, and the speech models
modules/grog.nix services.grog, for any NixOS machine
modules/computer.nix  services.grog.computer, for a computer grog acts on
grognix/         grognix
install/         the grognix installer
tests/           VM tests (make check-vm)
```

## grognix

A PC that only runs grog: NixOS with a GNOME desktop where `grog` is logged
in at boot, its voice listening and its skin fullscreen. It answers SSH as
`grognix.local`, and every grognix is the same ([`grognix/`](grognix)):

- It never sleeps, and the power button shuts it down cleanly.
- SSH with keys only. The `grog` user has no password and doesn't need one
  for sudo, so the screen never locks.
- Ethernet or Wi-Fi, and Bluetooth for speakers.
- Super opens GNOME's overview, for Settings or Firefox.
- Old generations are cleaned up weekly.

What's particular to one machine lives on it, in `/etc/nixos`, and never in
this repo:

```
flake.nix     nixosConfigurations.grognix = grog.lib.grognix { modules = [ ./machine.nix ]; }
machine.nix   its disk (grognix.disk), SSH keys, time zone, and anything else NixOS
facter.json   its hardware, as nixos-facter saw it
```

With `grognix.desktop.enable = false;` in `machine.nix` it's headless: no
desktop, and the skin is only served. Set up Wi-Fi, Bluetooth and sound over
SSH then (`nmtui`, `bluetoothctl`, `wpctl`).

Secrets stay out of `/etc/nixos`, which ends up in the world-readable Nix
store: the API key goes in `/etc/grog/secrets.env`, the subscription's
tokens in `/var/lib/grog`.

## Installing

Any x86_64 PC with UEFI and 4 GB of RAM. One of its disks gets erased.

1. Boot it into the [NixOS installer](https://nixos.org/download/#nixos-iso),
   get it online (`nmtui` for Wi-Fi), and give it a password with `passwd`.
   If it already runs Linux, with SSH as root or a user with sudo, and is on
   Ethernet, skip this.
2. From any computer with [Nix](https://nixos.org/download/):

   ```sh
   nix run github:ericdallo/grog#install -- nixos@<its address>
   ```

   It asks which disk to erase, a Wi-Fi network and an OpenAI API key (both
   optional), then installs it with
   [nixos-anywhere](https://github.com/nix-community/nixos-anywhere) and
   reboots. You log in as `grog` with your SSH keys.
3. Say "Grog, will it rain tomorrow?"

Without an API key, it offers to sign in to a ChatGPT subscription at the
end, with a code entered on any phone or computer; its screen shows one too.
ChatGPT has to allow that once per account, on its website (its app doesn't
have it): in Settings, Security and login, turn on "Enable device code
sign-in for Codex, Excel, PowerPoint, and Word". From a checkout,
`make install-grognix TARGET=nixos@<address>` installs the checkout's grog
instead.

## Day to day

From a checkout:

- `make deploy` builds grognix's own config with the checkout as grog, and
  switches to it over SSH. Use `SERVER=grog@<ip>` if `grognix.local` doesn't
  resolve.
- `make login` prints a code to sign it in to a ChatGPT subscription with, on
  any phone or computer. Its screen shows one too, while it has nothing to
  pay with.
- `make vm` boots a made-up grognix ([`example.nix`](grognix/example.nix)) in
  a VM.

On grognix:

- To update grog, ask it, tap Update on its screen, or run `grog update`
  ([Updating](#updating)).
- After changing `/etc/nixos/machine.nix`, `sudo nixos-rebuild switch`. To
  undo, `sudo nixos-rebuild switch --rollback`.
- `journalctl -u grog -f` shows the brain's log, and
  `journalctl --user -u grog-voice -f` the voice's. Neither has what was said.
  `journalctl --user -u grog-music -f` shows the player's, without what it
  played.
- Wi-Fi, in the actions on its screen, joins another network, its password
  typed there; what it saves works with any Wi-Fi dongle, on any port. A
  profile made in GNOME only works on the USB port the dongle was in.
  `sudo nmcli connection modify <name> connection.interface-name ""` makes
  it work on any.

To add an API key, kept out of git and the Nix store:

```sh
read -rs KEY && printf 'OPENAI_API_KEY=%s\n' "$KEY" | ssh grog@grognix.local \
  'sudo install -D -m 600 /dev/stdin /etc/grog/secrets.env && sudo systemctl restart grog'
```

## Updating

grog updates itself when asked, by voice, on its screen, or with `grog
update` (`services.grog.update`, on in grognix). It builds the system from
its flake with grog's input at the latest grog pushed, as the flake's owner,
switches to it once nobody is talking to grog, and goes back to the previous
system if the new grog doesn't answer. Only then does the flake get the new
lock. On another machine:

```nix
services.grog.update = {
  enable = true;
  flake = "/home/alice/nixos"; # its flake, a repository of alice's, say
  host = "my-machine";         # its nixosConfigurations.<host>; default: the host name
};
```

A machine running grog in a specialisation stays in it. The new lock isn't
committed anywhere. Whoever can push to grog's input can run code as root
there, once someone asks for an update.

By hand, on grognix: `sudo nix flake update --flake /etc/nixos`, then
`sudo nixos-rebuild switch`.

## Computers

grog can act on your other computers ([Computers](../brain/README.md#computers)),
with nothing of grog installed there: let one pair at grog (Add a computer,
on its screen), and on the computer, as the user grog acts as, run what its
screen says, `curl -fsS <grognix's address>:8485 | bash`. It takes an SSH
server running there. On a NixOS one, with grog's flake as an input:

```nix
imports = [ grog.nixosModules.computer ];
services.grog.computer = {
  enable = true;
  user = "alice"; # who grog acts as there
};
```

That turns its SSH server on, and lets alice suspend it and turn it off from
anywhere, through polkit (`power = false` doesn't).

grog listens for it on port 8485, opened in its firewall
(`services.grog.integrations.openFirewall`), only while someone at it
connects something: it serves what pairs a computer there, and the page a
phone follows the steps of a sign-in on, or fills in what an MCP server
needs. Signing in to Home Assistant comes straight back there too, from its
own page.

## Home Assistant

grog finds a [Home Assistant](../brain/README.md#home-assistant) on the
network with `avahi-browse`, on its service's path: through grognix's avahi
daemon, as the firewall wouldn't let the replies to grog's own queries in.
Without avahi, it asks itself, and the firewall may drop the answers. Its
sign-in comes back to grog's port 8485 on the network
(`services.grog.integrations.openFirewall`).

## MCP servers

grog runs [MCP servers](../brain/README.md#mcp-servers) from npm and PyPI
through its runner (`grog mcp`, `services.grog.mcp`, on in grognix), a
service of its own, `grog-mcp`. It's apart from grog for two reasons:
Node's JIT needs memory that's writable and executable, which grog's
sandbox forbids, and code fetched at run time shouldn't share a user or
files with grog's tokens.

```nix
services.grog.mcp.enable = true;
```

It has grog's sandbox but for that memory: a dynamic user, the network, a
cache in `/var/cache/grog-mcp` and a state directory in
`/var/lib/grog-mcp`, and no access to grog's state or anyone's home. It
installs the versions grog asks for with `npx` and `uvx`, from
`services.grog.mcp.packages` (Node, uv and Python by default: uv uses that
Python rather than downloading its own). grog reaches it on
`/run/grog-mcp/mcp.sock`, through the `grog-mcp` group. Some packages
bring prebuilt binaries, which expect a standard Linux loader that NixOS
doesn't have: grognix turns on `programs.nix-ld` for them.

## Music

grog plays [music](../brain/README.md#music) with grog-music
([`music/`](../music/README.md)), a service in the session of the user with
the speaker (`services.grog.music.user`, `services.grog.user` by default),
like the voice, playing through that session's default sink (through its
echo canceller, below). grog reaches
it on `/run/grog-music/music.sock`, through the `grog-music` group. It's on
in grognix; on another machine:

```nix
services.grog.music = {
  enable = true;
  settings.youtubeMusic.location = "BR"; # what YouTube Music finds; the rest in music/README.md
};
```

### Echo cancellation

grog's music comes out of the speaker next to its mic, and would drown its
name. So it plays into an echo canceller (`services.grog.music.echoCancel`,
on by default): PipeWire's echo-cancel module, WebRTC's canceller alone (a
high-pass filter, no noise suppression or gain control), in a PipeWire
process of its own, a user service of grog's music user
(`grog-echo-cancel`), so only grog's session has it, not a desktop user's
on the same machine. It makes two nodes there:

- `grog-music-out`, a sink: grog-music plays into it (its `audioDevice`,
  `pipewire/grog-music-out`), and it plays on to the default speaker.
  What goes in is what's taken out of the mic.
- `grog-mic`, a source: the default mic, mixed to mono, without the music.
  grog-voice records from it, unless `services.grog.voice.settings.mic`
  names another.

Only the music goes through it. grog's own voice and earcons go straight
to the speaker: tried on them, the canceller lowered grog's echo by ~11 dB
but the speaker beside the mic clipped it, and it ate the words of someone
talking over grog ([plans/voice.md](../plans/voice.md)), so grog-voice
stays half duplex. Both nodes rank below any real device, so they never
become the session's defaults (don't pick them in GNOME's sound settings).
grog also tells grog-voice when its music plays, to listen for its name in
short windows over it, and ducks the music once it heard it. The
canceller's thread flushes denormal floats to zero: once the music paused,
WebRTC's filters decayed into them, the canceller fell behind for minutes,
and grog-voice heard in pieces, missing grog's name.

If the canceller isn't running (restarting, or failed), WirePlumber plays
and records through the defaults instead, and moves both streams back once
it's there. `journalctl --user -u grog-echo-cancel` as grog shows its
logs, `wpctl status` its nodes.

To play on a Bluetooth speaker, pair it with the machine (GNOME's
Bluetooth settings, or `bluetoothctl` over SSH), then pick it for the
music in Speakers, in the skin's actions (it becomes the session's
default sink, as with `wpctl set-default <id>` as grog): grog plays there.
grog-voice speaks there too, unless another speaker is picked there for
its voice (the machine's own, say), or `services.grog.voice.settings.speaker`
pins one. Both picks stay across restarts.

## Audio

grog-voice uses the default mic and speaker of grog's session. USB audio
comes first when plugged in, then Bluetooth and the built-in audio. Pick
another in Speakers, in the skin's actions (one for the music, the
default, and maybe another for grog's voice), in GNOME's sound settings, or
with `wpctl set-default <id>` as grog. To pin one by name, set
`services.grog.voice.settings.mic` or `.speaker` in `machine.nix`.

`grog-voice listen`, as grog, shows what the mic hears and how loud. From
where you usually talk, speech should peak between -30 and -10 dBFS, and
never reach 0, where it clips. `wpctl set-volume @DEFAULT_SOURCE@ 80%` sets
the mic's volume.

## Picking hardware

- The mic matters most. Mics that clean up the sound themselves, as most
  speakerphones and conference mics do, mute what they take for noise:
  words lose their start, and grog misses its name even from up close.
  Plain mics do better. Try one with `grog-voice listen` before settling.
- Keep the speaker away from the mic. grog doesn't listen while it talks,
  but the sound saying it heard its name plays while you're still talking,
  and if it reaches the mic louder than you, it covers your words.
- Bluetooth speakers add about 0.3 s before grog is heard.
- A CPU with AVX2 makes speech 3 to 6 times faster. Without it, Parakeet
  takes about 0.9 s to write down what you said, and Supertonic 0.5 s per
  second of speech. grognix speaks with Supertonic: grog's default voice,
  Pocket TTS's deep-calm, was only measured on a Core Ultra 7 155H (0.5 s
  per second of speech, ~3 GB of RAM). On a CPU as fast, set
  `services.grog.voice.ttsEngine = "pocket"` in `machine.nix`.
- 8 GB of RAM leaves room: grog-voice takes 2 to 4 GB. With a few GB more,
  `services.grog.voice.sttPrecision = "fp32"` hears better, from afar most
  of all. Give it 4 threads (`services.grog.voice.settings.stt.threads = 4`)
  to keep it as fast.
- No Wi-Fi? A USB dongle works.
