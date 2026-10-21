# grognix's disk layout (body/grognix/disk.nix) on a disk with an old FAT32
# at 1 MiB, outside the partitions of the system installed after it, like the
# EFI partition of the Windows a PC came with: the new EFI partition starts
# right there, and must be formatted all the same.
# Run: nix build .#checks.x86_64-linux.disk -L
{ pkgs, diskoScript }:

pkgs.testers.runNixOSTest {
  name = "grognix-disk";

  nodes.machine = { pkgs, ... }: {
    virtualisation.emptyDiskImages = [ 4096 ];
    environment.systemPackages = with pkgs; [ dosfstools e2fsprogs gptfdisk ];
  };

  testScript = ''
    machine.wait_for_unit("multi-user.target")
    machine.succeed(
        "sgdisk --new=1:2048:+200M --typecode=1:EF00 /dev/vdb",
        "udevadm settle",
        "mkfs.vfat -F 32 -i 12345678 /dev/vdb1",
        "sgdisk --clear --new=1:4096:+512M --typecode=1:EF00 --new=2:0:0 /dev/vdb",
        "udevadm settle",
        "mkfs.vfat -F 32 /dev/vdb1",
        "mkfs.ext4 -F /dev/vdb2",
        "udevadm settle",
    )

    machine.succeed("${diskoScript} >&2")
    uuid = machine.succeed("blkid -p -s UUID -o value /dev/disk/by-partlabel/disk-main-ESP").strip()
    assert uuid != "1234-5678", "the old FAT32 was kept as the EFI partition"
    machine.succeed("findmnt -no FSTYPE /mnt/boot | grep -qx vfat")
    machine.succeed("findmnt -no FSTYPE /mnt | grep -qx ext4")
  '';
}
