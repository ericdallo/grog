# shellcheck shell=bash
# Locks the screens of this computer's user, for grog's computer tool
# (grog.remote-hands): logind tells the screen lockers of their graphical
# sessions, which lock if they listen.

user=$(id -un)
locked=0
for session in $(loginctl list-sessions --no-legend | awk -v user="$user" '$3 == user { print $1 }'); do
  case $(loginctl show-session "$session" -p Type --value) in
    wayland | x11) loginctl lock-session "$session" && locked=$((locked + 1)) ;;
  esac
done
if [ "$locked" -eq 0 ]; then
  echo "no screen of $user to lock"
  exit 1
fi
