# grog's packages, built with `pkgs`: the flake's packages and overlay, and
# the NixOS module's defaults.
pkgs: {
  grog = pkgs.callPackage ./grog.nix { };
  grog-voice = pkgs.callPackage ./grog-voice.nix { };
  grog-skin = pkgs.callPackage ./grog-skin.nix { };
}
