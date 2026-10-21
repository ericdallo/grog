# shellcheck shell=bash
# How this computer is doing, for grog's computer tool: run by grog's hands
# here (grog.remote-hands) as its user, when grog asks. Read-only. Each line
# is a fact; what can't be found is left out.

echo "name: $(cat /etc/hostname 2>/dev/null || uname -n)"
awk '{ printf "up: %d days, %d hours, %d minutes\n", $1 / 86400, $1 % 86400 / 3600, $1 % 3600 / 60 }' /proc/uptime
echo "load: $(cut -d ' ' -f 1-3 /proc/loadavg), $(nproc 2>/dev/null) CPUs"
free -m 2>/dev/null | awk '/^Mem:/ { print "memory: " $3 " of " $2 " MB used" }'
for battery in /sys/class/power_supply/BAT*; do
  if [ -r "$battery/capacity" ]; then
    echo "battery: $(cat "$battery/capacity")%, $(tr '[:upper:]' '[:lower:]' <"$battery/status")"
  fi
done
user=$(id -un)
for session in $(loginctl list-sessions --no-legend 2>/dev/null | awk -v user="$user" '$3 == user { print $1 }'); do
  type=$(loginctl show-session "$session" -p Type --value 2>/dev/null)
  case $type in
    wayland | x11)
      state=unlocked
      [ "$(loginctl show-session "$session" -p LockedHint --value 2>/dev/null)" = yes ] && state=locked
      [ "$(loginctl show-session "$session" -p IdleHint --value 2>/dev/null)" = yes ] && state="$state, idle"
      echo "screen: $user's ($type), $state"
      ;;
  esac
done
if command -v playerctl >/dev/null; then
  playerctl metadata --format 'playing: {{lc(status)}}, {{artist}} - {{title}} ({{playerName}})' 2>/dev/null
fi
if command -v wpctl >/dev/null; then
  # volume: 0.40 [muted]
  wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null | tr '[:upper:]' '[:lower:]'
fi
if version=$(nixos-version 2>/dev/null); then
  echo "nixos: $version"
fi
