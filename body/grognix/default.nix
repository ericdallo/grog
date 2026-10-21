# grognix: a PC dedicated to grog, the same on every machine. What's
# particular to one machine (its disk, hardware, SSH keys, time zone, and
# anything else it changes) lives on it, in /etc/nixos/machine.nix, written by
# the installer: see body/README.md, and example.nix for a made-up one.
{ lib, pkgs, ... }:

{
  imports = [
    ./desktop.nix
    ./disk.nix
  ];

  networking.hostName = lib.mkDefault "grognix";

  # UEFI only. The installer runs on a NixOS installer (the ISO, or the one
  # nixos-anywhere boots), which can write the boot entry.
  boot.loader = {
    systemd-boot.enable = true;
    systemd-boot.configurationLimit = 10;
    efi.canTouchEfiVariables = lib.mkDefault true;
  };

  hardware.enableRedistributableFirmware = true;
  # Bluetooth speakers.
  hardware.bluetooth.enable = lib.mkDefault true;
  zramSwap.enable = true;
  services.fstrim.enable = true;

  # A server never sleeps, even when asked to: GNOME suspends idle machines by
  # default, which takes it off the network. (desktop.nix also tells it not
  # to.)
  systemd.sleep.settings.Sleep = {
    AllowSuspend = "no";
    AllowHibernation = "no";
    AllowHybridSleep = "no";
    AllowSuspendThenHibernate = "no";
  };

  # Ethernet or Wi-Fi (`nmtui`, or GNOME's settings). Wi-Fi profiles hold
  # passwords: they live in /etc/NetworkManager/system-connections, out of
  # this config.
  networking.networkmanager.enable = true;
  # Power saving makes SSH and mDNS laggy and flaky on Wi-Fi.
  networking.networkmanager.wifi.powersave = false;
  # NetworkManager does DHCP, rather than the scripted networking that
  # hardware.facter would set up.
  hardware.facter.detected.dhcp.enable = false;
  # Reachable as grognix.local.
  services.avahi = {
    enable = true;
    nssmdns4 = true;
    publish = {
      enable = true;
      addresses = true;
    };
  };

  services.openssh = {
    enable = true;
    settings = {
      KbdInteractiveAuthentication = false;
      PasswordAuthentication = false;
      PermitRootLogin = "no";
    };
  };

  # The login user: SSH with the keys in machine.nix, and no password.
  users.users.grog = {
    isNormalUser = true;
    description = "grog";
    extraGroups = [ "audio" "networkmanager" "wheel" ];
  };
  # Deploys (nixos-rebuild --sudo) run sudo non-interactively.
  security.sudo.wheelNeedsPassword = false;

  # The console follows the keyboard layout (services.xserver.xkb).
  console.useXkbConfig = true;

  nix = {
    settings = {
      auto-optimise-store = true;
      experimental-features = [ "nix-command" "flakes" ];
      # Lets deploys copy unsigned store paths.
      trusted-users = [ "root" "grog" ];
    };
    gc = {
      automatic = true;
      dates = "weekly";
      options = "--delete-older-than 30d";
    };
  };

  environment.systemPackages = with pkgs; [
    alsa-utils # arecord, aplay, speaker-test: to try the mic and speaker
    curl
    git
    htop
    jq
    vim
  ];

  # Paid with a ChatGPT subscription (its screen shows a code to sign in
  # with, as does `grog login`) or an API key in /etc/grog/secrets.env.
  services.grog = {
    enable = true;
    # Its voice, hands and skin run in grog's session.
    user = "grog";
    # Hands-free, through the default mic and speaker of grog's session
    # (GNOME's sound settings, or wpctl).
    voice.enable = true;
    # The volume, how the machine is doing, and a shell (as grog, who can use
    # sudo).
    hands.enable = true;
    # What it's up to, at its address, and on the desktop fullscreen, over
    # GNOME's overview, keeping the screen on: Super still opens the
    # overview, F11 leaves fullscreen, Ctrl+Q closes it. With what's said:
    # grog is the only user here, and whoever is in the room hears it anyway.
    skin.enable = true;
    settings.skin.captions = lib.mkDefault true;
  };

  services.pulseaudio.enable = false;
  security.rtkit.enable = true;
  services.pipewire = {
    enable = true;
    alsa.enable = true;
    pulse.enable = true;
    # grog speaks and listens through USB audio whenever some is plugged in,
    # like a speakerphone (which cancels its own echo), rather than through
    # Bluetooth or the built-in audio. A device picked in GNOME's sound
    # settings (or `wpctl set-default`) still comes first.
    wireplumber.extraConfig."50-grognix-usb-audio"."monitor.alsa.rules" = [{
      matches = [
        { "node.name" = "~alsa_output.usb-.*"; }
        { "node.name" = "~alsa_input.usb-.*"; }
      ];
      actions.update-props."priority.session" = 5000;
    }];
  };

  # `nixos-rebuild build-vm` (`make vm`) only: console login grog/grog,
  # ssh -p 2222 grog@localhost.
  virtualisation.vmVariant = {
    users.users.grog.password = "grog";
    virtualisation.forwardPorts = [{ from = "host"; host.port = 2222; guest.port = 22; }];
  };
}
