# shellcheck shell=bash
# How the machine is doing, for grog's system_status tool: run by its hands
# (grog.hands) as the speaker's user. Read-only. Each line is a fact; what
# can't be found is left out.

awk '{ printf "up: %d days, %d hours, %d minutes\n", $1 / 86400, $1 % 86400 / 3600, $1 % 3600 / 60 }' /proc/uptime
echo "load: $(cut -d ' ' -f 1-3 /proc/loadavg)"
free -m 2>/dev/null | awk '/^Mem:/ { print "memory: " $3 " of " $2 " MB used" }'
df -h / 2>/dev/null | awk 'NR == 2 { print "disk: " $3 " of " $2 " used" }'
# Some zones can't be read: cat goes on, where sort would give up.
temperature=$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -n 1)
if [ -n "$temperature" ]; then
  echo "temperature: $((temperature / 1000)) °C"
fi
echo "addresses: $(ip -brief -4 address show scope global 2>/dev/null | awk '{ print $1 " " $3 }' | paste -sd ',' -)"
# What NetworkManager knows: a rescan takes seconds.
nmcli -t -f active,ssid,signal device wifi list --rescan no 2>/dev/null | awk -F: '$1 == "yes" { print "wifi: " $2 ", signal " $3 "%" }'
description() {
  wpctl inspect "$1" 2>/dev/null | sed -n 's/.*node\.description = "\(.*\)"/\1/p' | head -n 1
}
echo "speaker: $(description @DEFAULT_AUDIO_SINK@)"
echo "mic: $(description @DEFAULT_AUDIO_SOURCE@)"
if command -v bluetoothctl >/dev/null; then
  # Without bluetoothd, bluetoothctl waits for it forever.
  connected=$(timeout 3 bluetoothctl devices Connected 2>/dev/null | cut -d ' ' -f 3- | paste -sd ',' -)
  echo "bluetooth connected: ${connected:-none}"
fi
echo "grog: $(systemctl is-active grog 2>/dev/null), voice: $(systemctl --user is-active grog-voice 2>/dev/null)"
echo "failed services: $(systemctl --failed --no-legend --plain 2>/dev/null | awk '{ print $1 }' | paste -sd ' ' -)"
if version=$(nixos-version 2>/dev/null); then
  echo "nixos: $version"
fi
# The system's generation: the deploy that made it made its link.
if generation=$(readlink /nix/var/nix/profiles/system 2>/dev/null); then
  made=$(stat -c %Y "/nix/var/nix/profiles/$generation")
  echo "system generation: ${generation//[^0-9]/}, made $(date -d "@$made" '+%Y-%m-%d %H:%M')"
fi
