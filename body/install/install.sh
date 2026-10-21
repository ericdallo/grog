# shellcheck shell=bash
# Installs grognix on a machine over SSH, with nixos-anywhere, wiping one of
# its disks: see body/README.md. Packaged by default.nix, which puts the
# commands it needs on the PATH and sets GROG_FLAKE and NIXOS_RELEASE.

usage() {
  cat <<EOF
Usage: nix run github:ericdallo/grog#install -- [options] <user@host>

Installs grognix on the machine at <host>: one booted into a NixOS installer
(the ISO; set a password with passwd there first), or running any other
Linux, reachable over SSH as root or as a user with sudo. One of its disks is
ERASED: it asks which, and asks before erasing it.

Options:
  --disk <path>     the disk to install on, like /dev/nvme0n1
  --ssh-key <file>  the public key grog lets in (default: your SSH agent's
                    keys, or ~/.ssh/id_*.pub)
  --grog <flake>    the grog to install (default: $GROG_FLAKE). From a
                    local checkout, the machine still follows $GROG_FLAKE
  -p, --port <n>    the SSH port
  -y, --yes         ask nothing: erase the disk (--disk, unless there's only
                    one), no Wi-Fi, no API key
  -h, --help
EOF
}

die() {
  echo "$*" >&2
  exit 1
}

say() {
  printf '\n\033[1m%s\033[0m\n' "$*"
}

disk="" key_file="" grog=$GROG_FLAKE port="" yes="" target=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --disk) disk=${2:?--disk needs a path}; shift ;;
    --ssh-key) key_file=${2:?--ssh-key needs a file}; shift ;;
    --grog) grog=${2:?--grog needs a flake}; shift ;;
    -p | --port) port=${2:?--port needs a number}; shift ;;
    -y | --yes) yes=1 ;;
    -h | --help) usage; exit 0 ;;
    -*) die "Unknown option $1 (see --help)." ;;
    *) [[ -z $target ]] || die "Only one machine at a time (see --help)."; target=$1 ;;
  esac
  shift
done
[[ -n $target ]] || { usage >&2; exit 2; }
host=${target#*@}
if [[ -z $yes && ! -t 0 ]]; then
  die "Not a terminal: pass --yes (and --disk) to install without asking."
fi

# A local checkout (for grog's own development) can't be followed from the
# machine: its /etc/nixos follows GROG_FLAKE instead.
case $grog in
  path:* | git+file:* | /* | .*) follow=$GROG_FLAKE ;;
  *) follow=$grog ;;
esac

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

nix() {
  command nix --extra-experimental-features 'nix-command flakes' "$@"
}

# A key of its own for the install, so that SSH asks for a password once at
# most (ssh-copy-id), and the machine trusts it until it reboots.
ssh-keygen -q -t ed25519 -N "" -C grognix-install -f "$tmp/key"
ssh_opts=(-o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -o LogLevel=ERROR)
[[ -z $port ]] || ssh_opts+=(-p "$port")
on_target() {
  ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$tmp/key" "$target" "$@"
}

say "Connecting to $target"
if ! out=$(ssh-copy-id -i "$tmp/key.pub" "${ssh_opts[@]}" "$target" 2>&1); then
  printf '%s\n' "$out" >&2
  die "Couldn't log in to $target over SSH."
fi
# Its address from now on, as its name may stop resolving when it switches to
# an installer (like grognix.local, a name it publishes itself).
addr=$(ssh -v -o LogLevel=DEBUG1 "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$tmp/key" "$target" true 2>&1 </dev/null |
  sed -n 's/^debug1: Connecting to .* \[\([^]]*\)\] port .*/\1/p' | tail -n 1)
if [[ -n $addr && $addr != "$host" ]]; then
  echo "($host is $addr)"
  [[ $target != *@* ]] || user=${target%%@*}@
  host=$addr
  target=${user:-}$addr
fi
on_target test -d /sys/firmware/efi </dev/null ||
  die "$host didn't boot with UEFI, which grognix needs: turn legacy (CSM) boot off in its firmware settings."

# The disks, as "<stable path>\t<bytes>\t<device>\t<model>", leaving out the
# NixOS installer's own (the USB stick it booted from).
mapfile -t disks < <(on_target sh -s <<'EOF'
set -eu
boot=$(findmnt -nvo SOURCE /iso 2>/dev/null || true)
if [ -n "$boot" ]; then
  parent=$(lsblk -npo PKNAME "$boot" 2>/dev/null || true)
  boot=${parent:-$boot}
fi
lsblk -dbnpo NAME,TYPE,SIZE,MODEL | while read -r name type size model; do
  [ "$type" = disk ] && [ "$name" != "$boot" ] || continue
  case $name in /dev/zram* | /dev/loop* | /dev/ram*) continue ;; esac
  byid=""
  for link in /dev/disk/by-id/*; do
    case $link in *-part[0-9]* | */wwn-* | */nvme-eui.* | */nvme-nvme.*) continue ;; esac
    if [ -e "$link" ] && [ "$(readlink -f "$link")" = "$name" ]; then byid=$link; break; fi
  done
  printf '%s\t%s\t%s\t%s\n' "${byid:-$name}" "$size" "$name" "${model:-}"
done
EOF
)
describe() {
  local path size dev model
  IFS=$'\t' read -r path size dev model <<<"$1"
  printf '%s (%s, %s%s)' "$path" "$dev" "$(numfmt --to=iec "$size")" "${model:+, $model}"
}

chosen=""
if [[ -n $disk ]]; then
  for d in "${disks[@]}"; do
    IFS=$'\t' read -r path _ dev _ <<<"$d"
    if [[ $disk == "$path" || $disk == "$dev" ]]; then chosen=$d; fi
  done
  [[ -n $chosen ]] || die "$disk isn't one of $host's disks: $(printf '\n  %s' "${disks[@]%%$'\t'*}")"
elif [[ ${#disks[@]} -eq 0 ]]; then
  die "$host has no disk to install on."
elif [[ ${#disks[@]} -eq 1 ]]; then
  chosen=${disks[0]}
elif [[ -n $yes ]]; then
  die "$host has ${#disks[@]} disks: pick one with --disk."
else
  say "Which disk? It will be erased."
  for i in "${!disks[@]}"; do
    printf '  %d) %s\n' $((i + 1)) "$(describe "${disks[i]}")"
  done
  read -rp "> " n
  if ! [[ $n =~ ^[0-9]+$ ]] || ((n < 1 || n > ${#disks[@]})); then die "No disk $n."; fi
  chosen=${disks[n - 1]}
fi
disk=${chosen%%$'\t'*}

say "grognix goes on $host, on $(describe "$chosen")."
if [[ -z $yes ]]; then
  read -rp "Everything on that disk will be ERASED. Type \"yes\" to go on: " answer
  [[ $answer == yes ]] || die "Nothing done."
fi

# Who grog lets in: the keys you log in with.
if [[ -n $key_file ]]; then
  keys=$(<"$key_file")
elif ! keys=$(ssh-add -L 2>/dev/null); then
  keys=$(cat ~/.ssh/id_ed25519.pub ~/.ssh/id_ecdsa.pub ~/.ssh/id_rsa.pub 2>/dev/null || true)
fi
[[ $keys == *ssh-* || $keys == *ecdsa-* ]] || die "No SSH key to log in to grognix with: create one with ssh-keygen, or pass --ssh-key."

# Your time zone.
timezone=$(timedatectl show -p Timezone --value 2>/dev/null || true)
if [[ -z $timezone && -L /etc/localtime ]]; then
  timezone=$(readlink /etc/localtime)
  timezone=${timezone##*/zoneinfo/}
fi
timezone=${timezone:-UTC}

# What goes on its disk, as is (--extra-files): /etc/nixos, and secrets,
# which never go through the Nix store.
root=$tmp/root
etc=$root/etc/nixos
install -d -m 755 "$root" "$root/etc" "$etc"

if [[ -z $yes ]]; then
  read -rp "Wi-Fi network (empty: Ethernet, or set it up later): " ssid
  if [[ -n $ssid ]]; then
    read -rsp "Its password (empty: an open network): " psk
    echo
    install -d -m 755 "$root/etc/NetworkManager"
    install -d -m 700 "$root/etc/NetworkManager/system-connections"
    # NetworkManager ignores profiles others can read.
    (
      umask 077
      escape() { local s=${1//\\/\\\\}; printf '%s' "${s//$'\n'/\\n}"; }
      {
        printf '[connection]\nid=%s\ntype=wifi\n\n[wifi]\nmode=infrastructure\nssid=%s\n\n' \
          "$(escape "$ssid")" "$(escape "$ssid")"
        if [[ -n $psk ]]; then
          printf '[wifi-security]\nkey-mgmt=wpa-psk\npsk=%s\n\n' "$(escape "$psk")"
        fi
        printf '[ipv4]\nmethod=auto\n\n[ipv6]\nmethod=auto\n'
      } >"$root/etc/NetworkManager/system-connections/wifi.nmconnection"
    )
  fi

  read -rsp "OpenAI API key (empty: sign in to a ChatGPT subscription after): " api_key
  echo
  if [[ -n $api_key ]]; then
    install -d -m 755 "$root/etc/grog"
    (umask 077 && printf 'OPENAI_API_KEY=%s\n' "$api_key" >"$root/etc/grog/secrets.env")
  fi
fi

nix_string() {
  local s=${1//\\/\\\\}
  s=${s//\"/\\\"}
  printf '"%s"' "${s//\$\{/\\\$\{}"
}

flake() {
  cat <<EOF
# This grognix: grog's grognix (body/grognix in $follow), with
# what's particular to this machine in machine.nix. To update grog:
#   sudo nix flake update --flake /etc/nixos && sudo nixos-rebuild switch
{
  inputs.grog.url = $(nix_string "$1");

  outputs = { grog, ... }: {
    nixosConfigurations.grognix = grog.lib.grognix { modules = [ ./machine.nix ]; };
  };
}
EOF
}
flake "$grog" >"$etc/flake.nix"
flake "$follow" >"$tmp/flake.nix"

{
  cat <<EOF
# What's particular to this grognix; the rest is the same on every one.
# After changing it: sudo nixos-rebuild switch
{
  # Its hardware, as nixos-facter saw it when installing.
  hardware.facter.reportPath = ./facter.json;
  # The disk it's installed on.
  grognix.disk = $(nix_string "$disk");
  # Who can log in, as grog.
  users.users.grog.openssh.authorizedKeys.keys = [
EOF
  while IFS= read -r key; do
    [[ -z $key ]] || printf '    %s\n' "$(nix_string "$key")"
  done <<<"$keys"
  cat <<EOF
  ];
  time.timeZone = $(nix_string "$timezone");
  # The NixOS release it was installed with. Don't change it on upgrades.
  system.stateVersion = $(nix_string "$NIXOS_RELEASE");

  # More, see body/README.md in grog's repository:
  # grognix.desktop.enable = false; # headless
  # services.tailscale.enable = true; # then: sudo tailscale up
  # services.grog.settings.location = { city = "..."; region = "..."; country = "..."; };
}
EOF
} >"$etc/machine.nix"

if [[ $grog == "$follow" ]]; then
  say "Fetching $grog"
  nix flake lock "path:$etc"
fi

# From a checkout, the flake on the machine is fixed before it reboots.
phases=kexec,disko,install,reboot
[[ $grog == "$follow" ]] || phases=kexec,disko,install

say "Installing (this takes a while)"
anywhere_opts=(-i "$tmp/key")
# The same port after switching to its installer (kexec): a forwarded one, as
# in a VM, stays.
[[ -z $port ]] || anywhere_opts+=(-p "$port" --post-kexec-ssh-port "$port")
nixos-anywhere "${anywhere_opts[@]}" \
  --flake "path:$etc#grognix" \
  --generate-hardware-config nixos-facter "$etc/facter.json" \
  --extra-files "$root" \
  --copy-host-keys \
  --phases "$phases" \
  "$target"

if [[ $grog != "$follow" ]]; then
  root_ssh=(ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$tmp/key" "root@$host")
  "${root_ssh[@]}" 'cat > /mnt/etc/nixos/flake.nix && rm -f /mnt/etc/nixos/flake.lock' <"$tmp/flake.nix"
  "${root_ssh[@]}" "nohup sh -c 'sleep 3 && reboot' >/dev/null 2>&1 &" </dev/null
fi

say "grognix is installed, and starting."
login_ssh=(ssh "${ssh_opts[@]}")
[[ -z $key_file || ! -f ${key_file%.pub} ]] || login_ssh+=(-i "${key_file%.pub}")
printf 'Waiting for it'
for _ in $(seq 60); do
  if "${login_ssh[@]}" -o BatchMode=yes -o ConnectTimeout=5 "grog@$host" systemctl is-active -q grog </dev/null 2>/dev/null; then
    up=1
    break
  fi
  printf '.'
  sleep 5
done
echo

if [[ -z ${up:-} ]]; then
  say "It's taking long: try ssh grog@$host in a while."
elif [[ -z $yes && -z ${api_key:-} ]]; then
  read -rp "Sign grog in to your ChatGPT subscription now? [Y/n] " answer
  if [[ $answer != [nN]* ]]; then
    "${login_ssh[@]}" -t "grog@$host" grog login || true
  fi
fi

cat <<EOF

Then talk to it: "Grog, will it rain tomorrow?" It's at grognix.local
(or $host):
  ssh grog@grognix.local     grog status, grog chat, and its settings in
                             /etc/nixos/machine.nix
  Without an API key, its screen shows a code to sign in to a ChatGPT
  subscription with, on any phone or computer; so does grog login there.
EOF
