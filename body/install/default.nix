# `nix run github:ericdallo/grog#install -- <user@host>`: installs grognix on
# a machine over SSH (install.sh).
{ lib
, writeShellApplication
, coreutils
, nix
, nixos-anywhere
, openssh
}:

writeShellApplication {
  name = "grognix-install";
  runtimeInputs = [ coreutils nix nixos-anywhere openssh ];
  runtimeEnv = {
    # What the machine follows, in its /etc/nixos/flake.nix.
    GROG_FLAKE = "github:ericdallo/grog";
    # Its system.stateVersion.
    NIXOS_RELEASE = lib.trivial.release;
  };
  text = builtins.readFile ./install.sh;
  meta.description = "Installs grognix, a PC dedicated to grog, on a machine over SSH";
}
