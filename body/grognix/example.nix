# The machine.nix of a made-up grognix, like the installer writes on a real
# one: what `make build`, `make vm` and the VM test build.
{
  # On a real one, hardware.facter.reportPath = ./facter.json says it.
  nixpkgs.hostPlatform = "x86_64-linux";
  grognix.disk = "/dev/disk/by-id/example";
  users.users.grog.openssh.authorizedKeys.keys = [ ];
  time.timeZone = "UTC";
  # The NixOS release it was installed with. Don't change it on upgrades.
  system.stateVersion = "26.05";
}
