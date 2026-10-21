# The disk the installer formats, with disko: an EFI system partition, and
# ext4 for the rest.
{ config, lib, ... }:

let
  # A new partition can start where an old file system did, outside the old
  # partitions disko wipes (like the EFI partition of the Windows a PC came
  # with): it would seem formatted, and disko would leave it as is. So on a
  # new partition table (the disk had none, as after wiping it), the new
  # partitions are wiped before formatting.
  wipeIfNewTable = ''
    if [ -n "''${grognixNewTable:-}" ]; then wipefs --all --force "$device"; fi
  '';
in
{
  options.grognix.disk = lib.mkOption {
    type = lib.types.nullOr lib.types.str;
    default = null;
    example = "/dev/disk/by-id/nvme-Samsung_SSD_980_500GB_S64DNL0T123456";
    description = ''
      The disk grognix lives on, which the installer wipes. null: the file
      systems come from elsewhere, like a hardware-configuration.nix.
    '';
  };

  config = lib.mkIf (config.grognix.disk != null) {
    disko.devices.disk.main = {
      type = "disk";
      device = config.grognix.disk;
      content = {
        type = "gpt";
        preCreateHook = ''
          if ! blkid "$device" >/dev/null 2>&1; then
            grognixNewTable=1
            # Nor should blkid's cache remember what was there.
            export BLKID_FILE=/dev/null
          fi
        '';
        partitions = {
          ESP = {
            type = "EF00";
            size = "1G";
            content = {
              type = "filesystem";
              format = "vfat";
              mountpoint = "/boot";
              mountOptions = [ "umask=0077" ];
              preCreateHook = wipeIfNewTable;
            };
          };
          root = {
            size = "100%";
            content = {
              type = "filesystem";
              format = "ext4";
              mountpoint = "/";
              preCreateHook = wipeIfNewTable;
            };
          };
        };
      };
    };
  };
}
