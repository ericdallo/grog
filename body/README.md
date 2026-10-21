# body

What grog runs on, all in Nix: how it's built, a NixOS module to run it on
any machine, and grognix, a PC set up just for it.

```
packages/        how grog, grog-voice and grog-skin are built, and the speech models
modules/grog.nix services.grog, for any NixOS machine
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
end. From a checkout, `make install-grognix TARGET=nixos@<address>` installs
the checkout's grog instead.

## Day to day

From a checkout:

- `make deploy` builds grognix's own config with the checkout as grog, and
  switches to it over SSH. Use `SERVER=grog@<ip>` if `grognix.local` doesn't
  resolve.
- `make login` signs it in to a ChatGPT subscription, through an SSH tunnel.
- `make vm` boots a made-up grognix ([`example.nix`](grognix/example.nix)) in
  a VM.

On grognix:

- After changing `/etc/nixos/machine.nix`, `sudo nixos-rebuild switch`. To
  update grog, `sudo nix flake update --flake /etc/nixos` first. To undo,
  `sudo nixos-rebuild switch --rollback`.
- `journalctl -u grog -f` shows the brain's log, and
  `journalctl --user -u grog-voice -f` the voice's. Neither has what was said.
- A Wi-Fi profile made in GNOME only works on the USB port the dongle was
  in. `sudo nmcli connection modify <name> connection.interface-name ""`
  makes it work on any.

To add an API key, kept out of git and the Nix store:

```sh
read -rs KEY && printf 'OPENAI_API_KEY=%s\n' "$KEY" | ssh grog@grognix.local \
  'sudo install -D -m 600 /dev/stdin /etc/grog/secrets.env && sudo systemctl restart grog'
```

## Audio

grog-voice uses the default mic and speaker of grog's session. USB audio
comes first when plugged in, then Bluetooth and the built-in audio. Pick
another in GNOME's sound settings, or with `wpctl set-default <id>` as grog.
To pin one by name, set `services.grog.voice.settings.mic` or `.speaker` in
`machine.nix`.

## Tried on

A Bematech RC-8400 mini PC: Celeron N5095 (no AVX2), 8 GB of RAM, and no
Wi-Fi of its own. With it:

- a TP-Link Archer T2U Nano USB dongle for Wi-Fi
- a Focusrite Scarlett 2i2 with a Shure SV200 mic, and an EDIFIER R1010BT
  speaker over Bluetooth, which adds about 0.3 s before grog is heard
- a CMTECK ZM-350 USB speakerphone, the closest thing to an Echo, with the
  echo cancellation that interrupting grog will need
- the onboard Realtek ALC897 jacks, which just work

Without AVX2, Whisper is too slow. Parakeet takes about 0.9 s and gets more
right.
