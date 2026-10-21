# GNOME, with grog logged in at boot: its session has the microphone and the
# speaker (Bluetooth included), and so grog-voice; and with a screen,
# setting up Wi-Fi, Bluetooth and sound is a few clicks. Without it, grognix
# is headless, and grog's session starts at boot anyway.
{ config, lib, pkgs, ... }:

{
  options.grognix.desktop.enable = lib.mkOption {
    type = lib.types.bool;
    default = true;
    description = ''
      A GNOME desktop with grog logged in at boot. Off, grognix is headless:
      grog's session (PipeWire, grog-voice) starts at boot without logging
      in (linger), and Wi-Fi, Bluetooth and sound are set up over SSH (nmtui,
      bluetoothctl, wpctl).
    '';
  };

  config = lib.mkMerge [
    (lib.mkIf config.grognix.desktop.enable {
      services.displayManager.gdm.enable = true;
      # The login screen would otherwise suspend the machine when idle.
      services.displayManager.gdm.autoSuspend = false;
      # And so would grog's session, after 15 idle minutes (only to fail, as
      # default.nix forbids it), as well as the power button, which then did
      # nothing. Nor does the screen lock: grog has no password to unlock it
      # with. Locked, so that GNOME's settings can't bring them back. (GNOME
      # reads them at login: a deployed change takes effect after a reboot.)
      programs.dconf.profiles.user.databases = [
        {
          lockAll = true;
          settings = {
            "org/gnome/settings-daemon/plugins/power" = {
              sleep-inactive-ac-type = "nothing";
              sleep-inactive-ac-timeout = lib.gvariant.mkInt32 0;
              sleep-inactive-battery-type = "nothing";
              sleep-inactive-battery-timeout = lib.gvariant.mkInt32 0;
              # Asks, and with no one at the screen, powers off after a minute.
              power-button-action = "interactive";
            };
            "org/gnome/desktop/screensaver".lock-enabled = false;
            "org/gnome/desktop/lockdown".disable-lock-screen = true;
          };
        }
      ];
      services.displayManager.autoLogin = {
        enable = true;
        user = "grog";
      };
      # Logging in automatically, GDM can lose tty1 to a login prompt
      # (https://github.com/NixOS/nixpkgs/issues/103746).
      systemd.services."getty@tty1".enable = false;
      systemd.services."autovt@tty1".enable = false;
      services.desktopManager.gnome.enable = true;
      # Without its tour, GNOME doesn't offer it in a dialog over everything,
      # at the first login and after each upgrade.
      environment.gnome.excludePackages = [ pkgs.gnome-tour ];
      # Also to sign in to the ChatGPT subscription right there: `grog login`.
      programs.firefox.enable = true;
    })

    (lib.mkIf (!config.grognix.desktop.enable) {
      users.users.grog.linger = true;
      # No screen to fill: the skin is only served, at grog's address.
      services.grog.skin.user = null;
    })
  ];
}
