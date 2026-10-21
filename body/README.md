# body

grog's body, what it runs on, all in Nix: how grog is built and run on any
NixOS machine, and grognix, a PC dedicated to it.

```
packages/        how grog, grog-voice and grog-skin are built, and the speech models
modules/grog.nix services.grog: grog, grog-voice and its skin on any NixOS machine
grognix/         grognix, a PC dedicated to grog
install/         the command that installs grognix on a machine
tests/           VM tests of the module, of grognix and of its disk (make check-vm)
```

## grognix

A PC dedicated to grog: NixOS running grog and grog-voice, with a GNOME
desktop where grog is logged in at boot, reachable as `grognix.local`. It's
the same on every machine ([`grognix/`](grognix), `nixosModules.grognix`):

- It never suspends (GNOME would put it to sleep when idle, dropping it off
  the network), and the power button powers it off cleanly (after a minute,
  when no one confirms it at the screen).
- SSH with keys only; the user `grog` has no password, and sudo needs none.
  So the screen never locks, whatever GNOME's settings say: there's nothing
  to unlock it with. (A password typed into GNOME's "new keyring" prompt
  goes to the keyring, not to grog.)
- Ethernet or Wi-Fi (NetworkManager, power saving off), mDNS, Bluetooth.
- grog-voice listens in grog's session, through its default mic and speaker.
- grog's hands work in the same session: the speaker's volume, how the
  machine is doing, and shell commands as `grog` (who can use sudo).
- grog's skin fills the screen as soon as grog's session starts, keeping it
  on: what grog hears, the steps and tools of its turns, what's said, and what
  it costs (see the [README](../README.md#skin)). Super opens GNOME's
  overview (Settings, Firefox), F11 leaves fullscreen, Ctrl+Q closes it until
  the next login. To keep what's said off the screen,
  `services.grog.settings.skin.captions = false;`. GNOME's welcome tour is
  left out: it would pop up over it.
- Old generations are collected weekly, after 30 days.

What's particular to one machine lives on it, in `/etc/nixos`, and never in
this repo:

```
flake.nix     nixosConfigurations.grognix = grog.lib.grognix { modules = [ ./machine.nix ]; }
              (github:ericdallo/grog, pinned in flake.lock)
machine.nix   its disk, SSH keys, time zone, and whatever else it changes
facter.json   its hardware, as nixos-facter saw it
```

`machine.nix` is a NixOS module: anything NixOS goes there, like
`services.tailscale.enable = true;` or `services.grog.settings`. Also:

- `grognix.desktop.enable = false;` goes headless: grog's session (PipeWire,
  grog-voice) starts at boot without logging in, the skin is only served,
  and Wi-Fi, Bluetooth and sound are set up over SSH (`nmtui`,
  `bluetoothctl`, `wpctl`). Bluetooth speakers haven't been tried that way
  yet.
- `grognix.disk` is the disk the installer formats (disko: an EFI system
  partition, and ext4 for the rest).

Secrets never go in `/etc/nixos` (it ends up in the world-readable Nix
store): the API key lives in `/etc/grog/secrets.env`, the ChatGPT
subscription's tokens in `/var/lib/grog/auth.json`, Wi-Fi passwords in
`/etc/NetworkManager/system-connections`.

## Installing

Any x86_64 PC that boots with UEFI, with 4 GB of RAM or more (aarch64 ones
should do too, but haven't been tried). One of its disks is **erased**.

1. Boot it into the [NixOS installer](https://nixos.org/download/#nixos-iso)
   (the minimal ISO is enough), connected to the network (Ethernet, or
   `nmtui` for Wi-Fi), and give it a password with `passwd`. Note its address
   (`ip -4 addr`). Or, if it runs another Linux already, reachable over SSH
   as root or as a user with sudo (and on Ethernet: switching to the
   installer drops Wi-Fi), skip this.
2. From a computer with [Nix](https://nixos.org/download/) (Linux or macOS):

   ```sh
   nix run github:ericdallo/grog#install -- nixos@<its address>
   ```

   It asks for the password once, which disk to erase (if it has more than
   one) and to confirm, a Wi-Fi network (optional), and an OpenAI API key
   (optional: without one, it offers to sign in to a ChatGPT subscription
   when done). You log in as grog with your SSH keys (your agent's, or
   `~/.ssh/id_*.pub`), and it gets your time zone. Then it detects the hardware
   (nixos-facter), formats the disk (disko), installs and reboots it
   ([nixos-anywhere](https://github.com/nix-community/nixos-anywhere)).
3. Talk to it: "Grog, will it rain tomorrow?"

From a checkout of this repo, `make install-grognix TARGET=nixos@<address>`
installs the checkout's grog (the machine still follows
`github:ericdallo/grog` afterwards, and `make deploy` brings the checkout's
again).

## Day to day

On grognix:

- `grog chat`, `grog ask ...`, `grog status`.
- `journalctl -u grog -f` shows each question's latency and token usage (not
  what was said).
- `journalctl --user -u grog-voice -f` (as grog) shows each voice turn's
  timings, also not what was said; `systemctl --user restart grog-voice`
  restarts it. `grog-voice listen` (`ssh -t`) prints what the mic hears and
  whether grog would answer, to check how the wake word ("Grog, ...") comes
  out; `grog-voice turn question.wav --no-play` runs a turn with a recording
  (16 kHz, mono, 16-bit) instead of the mic.
- `systemctl --user restart grog-skin` (as grog) brings the skin back, or
  restarts it; it reloads by itself after a deploy. From another machine,
  `ssh -L 8484:127.0.0.1:8484 grog@grognix.local` and
  `http://localhost:8484/` show it there.
- After changing `/etc/nixos/machine.nix`, `sudo nixos-rebuild switch`. To
  update grog, `sudo nix flake update --flake /etc/nixos` first.
- To undo a change, pick the previous generation in the boot menu, or
  `sudo nixos-rebuild switch --rollback`.
- GNOME reads its settings (dconf, as in `desktop.nix`) when grog's session
  starts: a deployed change to them takes effect after a reboot.
- Should the screen lock anyway, `sudo loginctl unlock-sessions` over SSH
  unlocks it (or ask grog to, through its shell).

From a checkout of this repo:

- `make deploy` builds grognix's own config (its `/etc/nixos`) with the
  checkout as grog, and switches grognix to it over SSH (grognix fetches what
  it can from the binary cache itself). `SERVER=grog@<ip>` if
  `grognix.local` doesn't resolve; extra ssh options go in `NIX_SSHOPTS`,
  e.g. `NIX_SSHOPTS="-i ~/.ssh/<deploy key>"`.
- `make login` signs grog in to the ChatGPT subscription: it runs `grog login`
  on grognix through an SSH tunnel for the browser's redirect to
  `localhost:1455`. Open the printed link here and sign in. (At grognix's
  screen, `grog login` and Firefox do it too.)
- `make vm` boots a made-up grognix ([`example.nix`](grognix/example.nix)) in
  a local VM (console login grog/grog, `ssh -p 2222 grog@localhost`).

### An API key instead of the subscription

The service starts without credentials, answering with what's missing. To
add an API key (it stays in `/etc/grog/secrets.env`, mode 0600, out of git
and of the Nix store):

```sh
read -rs KEY && printf 'OPENAI_API_KEY=%s\n' "$KEY" | ssh grog@grognix.local \
  'sudo install -D -m 600 /dev/stdin /etc/grog/secrets.env && sudo systemctl restart grog'
```

While signed in to the subscription, it's used instead of the key.

### Wi-Fi

Profiles created by GNOME are bound to the interface name, which for a USB
dongle depends on the port: plugged into another port, grognix stays offline
until it gets a profile for the new name (from the GNOME settings at the
machine). To make a profile work on any port:

```sh
sudo nmcli connection modify <name> connection.interface-name ""
```

## Audio and voice

grog-voice uses the default microphone and speaker of grog's session. On
grognix, USB audio comes first whenever some is plugged in, mic and speaker,
like a speakerphone (its echo cancellation only works with grog speaking
through it): Bluetooth and the built-in audio are for when there's none. A
device picked in GNOME's sound settings, or over SSH with `wpctl status` and
`wpctl set-default <id>` (as grog), still wins; `wpctl clear-default` goes
back to the USB first. To pin one by name instead, set
`services.grog.voice.settings.mic` or `.speaker` in `machine.nix` to a node
name from `wpctl status -n`.

## Tried on

A Bematech RC-8400 mini PC: Celeron N5095 (4 cores, no AVX2), 8 GB of RAM,
256 GB SSD, onboard Ethernet and Realtek ALC897 audio, and no Wi-Fi of its
own.

- Wi-Fi: a TP-Link Archer T2U Nano USB dongle (Realtek RTL8811AU, mainline
  `rtw88` driver).
- A Focusrite Scarlett 2i2 (4th gen, USB) with a Shure SV200 mic, and an
  EDIFIER R1010BT speaker over Bluetooth (CSR8510 dongle; pair it with
  `bluetoothctl` or GNOME's settings). Keep the Scarlett's gain low enough not
  to clip: peaks around -30 dBFS (grog-voice logs each turn's peak) were
  understood fine. The Bluetooth speaker adds ~0.3 s before grog is heard.
- A USB speakerphone (mic and speaker in one), a CMTECK ZM-350, the closest
  thing to an Echo: it shows up as "MV-SILICON SAN MIX" (`1238:17b8`), and
  with its echo cancellation, grog could be interrupted while it talks.
- The onboard Realtek ALC897 is plain HD Audio (`snd_hda_intel`) and just
  works: headphone and mic jacks at the front, line out and mic at the back.
  It reports an internal speaker, but nothing plays from it.
- Without AVX2, some speech models are out: Whisper is slow there (4 s for a
  short question with faster-whisper base, and far worse with int8 or
  whisper.cpp), while Parakeet takes ~0.9 s and gets more right. Piper speaks
  ~5 times faster than real time. Back-to-back benchmarks run into the CPU's
  power limit and look 2x slower than real use.
