# shellcheck shell=bash
# The machine's Wi-Fi, through NetworkManager, for grog's wifi action
# (grog.wifi): run by its hands as `set -- <what> <args...>` then this, so
# that a password goes to nmcli only, never into anything logged.
#
#   status [auto|yes|no]       whether Wi-Fi is on, the devices, the networks
#                              they see (rescanning as nmcli's --rescan says)
#                              and the SSIDs of the saved profiles
#   connect SSID DEVICE [PASSWORD]
#                              joins it on DEVICE, with a profile saved
#                              before, or made now: unbound from DEVICE once
#                              it works (any Wi-Fi device may use it then),
#                              gone when it doesn't
#   on                         turns Wi-Fi on

wifi_profiles() {
  nmcli -t -g UUID,TYPE connection show | awk -F: '$2 == "802-11-wireless" { print $1 }'
}

# The SSID of profile $1, unescaped as nmcli escapes them (-t).
ssid_of() {
  local ssid
  ssid=$(nmcli -t -g 802-11-wireless.ssid connection show "$1")
  ssid=${ssid//\\:/:}
  printf '%s\n' "${ssid//\\\\/\\}"
}

# The saved profiles for SSID $1.
profiles_of() {
  local uuid
  for uuid in $(wifi_profiles); do
    if [ "$(ssid_of "$uuid")" = "$1" ]; then
      echo "$uuid"
    fi
  done
}

status() {
  local profiles
  # Without NetworkManager, the one line saying so.
  if ! nmcli -t general status >/dev/null 2>&1; then
    nmcli general status 2>&1 | tail -n 1
    return 1
  fi
  echo "radio:$(nmcli radio wifi)"
  echo "--devices"
  nmcli -t -e yes -f DEVICE,TYPE,STATE,CONNECTION device status
  echo "--networks"
  nmcli -t -e yes -f IN-USE,SSID,SIGNAL,SECURITY,DEVICE device wifi list --rescan "${1:-auto}"
  echo "--saved"
  mapfile -t profiles < <(wifi_profiles)
  if [ "${#profiles[@]}" -gt 0 ]; then
    nmcli -t -e yes -g 802-11-wireless.ssid connection show "${profiles[@]}" | grep -v '^$'
  fi
  return 0
}

connect() {
  local ssid=$1 device=$2 before output result uuid
  before=" $(profiles_of "$ssid" | paste -sd ' ' -) "
  if [ $# -ge 3 ]; then
    output=$(nmcli --wait 40 device wifi connect "$ssid" password "$3" ifname "$device" 2>&1)
  else
    output=$(nmcli --wait 40 device wifi connect "$ssid" ifname "$device" 2>&1)
  fi
  result=$?
  for uuid in $(profiles_of "$ssid"); do
    case "$before" in
      *" $uuid "*) ;;
      *)
        if [ "$result" -eq 0 ]; then
          nmcli connection modify "$uuid" connection.interface-name "" >/dev/null 2>&1
        else
          nmcli connection delete "$uuid" >/dev/null 2>&1
        fi
        ;;
    esac
  done
  printf '%s\n' "$output"
  return "$result"
}

# Without nmcli, NetworkManager isn't there either: what nmcli says then.
if ! command -v nmcli >/dev/null 2>&1; then
  echo "Error: NetworkManager is not running."
  exit 1
fi

case "${1:-}" in
  status) status "${2:-auto}" ;;
  connect) shift; connect "$@" ;;
  on) nmcli radio wifi on ;;
  *)
    echo "wifi.sh: status, connect or on" >&2
    exit 2
    ;;
esac
