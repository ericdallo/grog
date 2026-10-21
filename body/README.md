# body

What grog runs on, all in Nix: how it's built, a NixOS module to run it on
any machine, and grognix, a PC set up just for it.

```
packages/        how grog, grog-voice and grog-skin are built, and the speech models
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
- A Wi-Fi profile made in GNOME only works on the USB port the dongle was
  in. `sudo nmcli connection modify <name> connection.interface-name ""`
  makes it work on any.

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

grog can act on your other computers ([Computers](../brain/README.md#computers)).
On a NixOS one, with grog's flake as an input:

```nix
imports = [ grog.nixosModules.computer ];
services.grog.computer = {
  enable = true;
  user = "alice"; # who grog acts as there
};
```

That turns its SSH server on, installs grog, and lets alice suspend it and
turn it off from anywhere, through polkit (`power = false` doesn't). Then
let a computer pair at grog (Add a computer, on its screen), and as alice,
run `grog pair grognix.local`. On other Linux machines, start an SSH server
and install grog (`nix profile install github:ericdallo/grog`) first.

grog listens for it on port 8485, opened in its firewall
(`services.grog.computers.openFirewall`), only while it lets one pair.

## Audio

grog-voice uses the default mic and speaker of grog's session. USB audio
comes first when plugged in, then Bluetooth and the built-in audio. Pick
another in GNOME's sound settings, or with `wpctl set-default <id>` as grog.
To pin one by name, set `services.grog.voice.settings.mic` or `.speaker` in
`machine.nix`.

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
  second of speech.
- 8 GB of RAM leaves room: grog-voice takes 2 to 4 GB. With a few GB more,
  `services.grog.voice.sttPrecision = "fp32"` hears better, from afar most
  of all. Give it 4 threads (`services.grog.voice.settings.stt.threads = 4`)
  to keep it as fast.
- No Wi-Fi? A USB dongle works.
