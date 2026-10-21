#!/usr/bin/env bash
# grog on one of the user's computers (brain/README.md, Computers): it pairs
# the computer with grog, then is grog's hands there, what grog runs when it
# comes in over SSH, doing only what the computer lets it. There's nothing
# to install: grog serves it while someone at grog lets a computer pair
# (grog.pairing),
#
#   curl -fsS <grog's address>:8485 | bash
#
# and pairing keeps a copy in ~/.local/share/grog, which grog's key runs,
# only from grog's network. Like grog's own hands, it never logs what grog
# asks, or what commands print. It takes bash, coreutils, awk, curl and an
# SSH server; the actions, what they use (notify-send, playerctl, wpctl,
# loginctl, xdg-open).
#
#   computer.sh [pair] [ADDRESS[:PORT]] [--name NAME] [--ssh-port PORT]
#               [--host-key FILE] [--trust]
#   computer.sh hands
set -uo pipefail

# Grows when grog and its hands here would stop understanding each other
# (grog.computers/protocol-version).
protocol=2

# Where grog is, filled in when grog serves this script (grog.pairing/script).
grog_url=''

# Where grog listens for a computer to pair, unless said otherwise
# (computers.pairing.port).
pairing_port=8485

# What grog can ask of the computer, as its policy names them, and what each
# does. Besides, `hello` tells what the computer lets grog do, and `unpair`
# forgets grog.
actions=(status notify open media volume lock suspend power-off shell)
declare -A doing=(
  [status]="tell how it's doing"
  [notify]="show a notification"
  [open]="open something"
  [media]="control what plays"
  [volume]="change the volume"
  [lock]="lock the screen"
  [suspend]="suspend the computer"
  [power-off]="turn the computer off"
  [shell]="run a command"
)

# What the computer lets grog do unless its user says otherwise: what can't
# hurt, without asking; suspending it, turning it off and commands, asking
# whoever is at it.
default_allow="status notify open media volume lock"
default_ask="suspend power-off shell"

# How long whoever is at the computer has to allow what grog asks, in
# seconds.
ask_s=30
# What an action takes at most, but a command, in seconds.
action_s=15
# How long a command may take, in milliseconds.
default_timeout_ms=30000
max_timeout_ms=600000
# How much of what a command printed goes back, in bytes: grog keeps 8000
# characters of it.
max_output_bytes=32000

policy_file=${XDG_CONFIG_HOME:-$HOME/.config}/grog/hands.json
hands_file=${XDG_DATA_HOME:-$HOME/.local/share}/grog/computer.sh
authorized_keys=$HOME/.ssh/authorized_keys
host_key_files=(/etc/ssh/ssh_host_ed25519_key.pub /etc/ssh/ssh_host_ecdsa_key.pub /etc/ssh/ssh_host_rsa_key.pub)
# An SSH public key: its type, its base64 (the third group), what it says.
key_pattern='^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) ([[:alnum:]+/]+={0,3})( .*)?$'

declare -A request=() reply=()
action='' from=grog host='' allow='' ask='' deny='' blob='' output='' timeout_ms=$default_timeout_ms
url='' grog_host='' work='' why='' status='' body=''

# trim TEXT: TEXT without the blanks around it.
trim() {
  local s=$1
  s=${s#"${s%%[![:space:]]*}"}
  printf '%s' "${s%"${s##*[![:space:]]}"}"
}

# b64 TEXT: TEXT in base64, on one line.
b64() {
  printf '%s' "$1" | base64 -w0
}

# joined SEPARATOR WORD...: the WORDs, with SEPARATOR between them.
joined() {
  local separator=$1 out='' word
  shift
  for word; do out+=${out:+$separator}$word; done
  printf '%s' "$out"
}

# This computer's name.
host_name() {
  local name=''
  if [[ -r /etc/hostname ]]; then name=$(trim "$(</etc/hostname)"); fi
  printf '%s' "${name:-${HOSTNAME:-this computer}}"
}

# rewrite_keys BLOB [LINE]: authorized_keys without the lines letting the
# key BLOB in, then with LINE, written whole or not at all.
rewrite_keys() {
  local dir=${authorized_keys%/*} tmp
  if [[ ! -d $dir ]] && ! { mkdir -p "$dir" && chmod 700 "$dir"; }; then
    return 1
  fi
  tmp=$(mktemp "$authorized_keys.XXXXXX") || return 1
  if [[ -e $authorized_keys ]] &&
    ! awk -v blob="$1" '{ for (i = 1; i <= NF; i++) if ($i == blob) next; print }' "$authorized_keys" >"$tmp"; then
    rm -f "$tmp"
    return 1
  fi
  if { [[ -z ${2-} ]] || printf '%s\n' "$2" >>"$tmp"; } && chmod 600 "$tmp" && mv -f "$tmp" "$authorized_keys"; then
    return 0
  fi
  rm -f "$tmp"
  return 1
}

# The hands: what grog's key runs, `computer.sh hands`.

# Reads grog's request on stdin, a line of key=value fields apart by spaces,
# each value in base64 (grog.computers/request-line): in `request`.
read_request() {
  local line field key value
  local -a fields=()
  IFS= read -r line || [[ -n $line ]] || return 1
  read -r -a fields <<<"$line"
  ((${#fields[@]})) || return 1
  for field in "${fields[@]}"; do
    key=${field%%=*}
    [[ $field == *=* && $key =~ ^[[:alpha:]]+$ ]] || return 1
    value=$(printf '%s' "${field#*=}" | base64 -d 2>/dev/null) || return 1
    request[$key]=$value
  done
}

# answer KEY VALUE...: grog's answer, the line it reads
# (grog.computers/answer-in), each value in base64. And that's all.
answer() {
  local line=grog-hands
  while (($# >= 2)); do
    line+=" $1=$(b64 "$2")"
    shift 2
  done
  printf '%s\n' "$line"
  exit 0
}

# fail MESSAGE: what grog asked can't be done, saying why.
fail() {
  answer error "$1"
}

check_version() {
  local v=${request[v]-}
  if ! [[ $v =~ ^[[:digit:]]{1,4}$ ]]; then
    fail "send a request: a line of key=value fields, each value in base64"
  elif ((10#$v > protocol)); then
    fail "this computer's hands are older than grog: pair it again"
  elif ((10#$v < protocol)); then
    fail "this computer's hands are newer than grog: update grog"
  fi
}

# grog's name, as it says: `from`.
check_from() {
  from=${request[from]-grog}
  if [[ -z $from || ${#from} -gt 160 || $from == *[[:cntrl:]]* ]]; then
    fail "from: a short name, on one line"
  fi
}

# check_text KEY MAX [lines]: fails unless the request's KEY is text of up
# to MAX characters, on one line unless `lines`.
check_text() {
  local v=${request[$1]-} one_line=", on one line"
  if [[ -n ${3-} ]]; then one_line=''; fi
  if [[ -z ${v//[[:space:]]/} || ${#v} -gt $2 ]] || [[ -n $one_line && $v == *[$'\r\n']* ]]; then
    fail "$1: text of up to $2 characters$one_line"
  fi
}

# What `open` opens: a web or mail link, or a file's path.
check_target() {
  local link='^([Hh][Tt][Tt][Pp][Ss]?://|[Mm][Aa][Ii][Ll][Tt][Oo]:|/|~/)[^[:space:]]'
  check_text target 2000
  if ! [[ ${request[target]} =~ $link ]]; then
    fail "target: a link (https://..., mailto:...) or a file's path (/... or ~/...)"
  fi
}

check_media() {
  case ${request[media]-} in
    play-pause | play | pause | next | previous | stop) ;;
    *) fail "media: one of next, pause, play, play-pause, previous, stop" ;;
  esac
}

# check_number KEY LO HI: fails unless the request's KEY, when it says it,
# is a whole number from LO to HI.
check_number() {
  local v=${request[$1]-}
  [[ -n ${request[$1]+set} ]] || return 0
  if ! [[ $v =~ ^(0|-?[1-9][[:digit:]]{0,2})$ ]] || ((v < $2 || v > $3)); then
    fail "$1 must be a number from $2 to $3"
  fi
}

check_mute() {
  if [[ -n ${request[mute]+set} && ${request[mute]} != true && ${request[mute]} != false ]]; then
    fail "mute must be true or false"
  fi
}

# How long the command may take: `timeout_ms`.
check_timeout() {
  timeout_ms=${request[timeoutMs]-$default_timeout_ms}
  if ! [[ $timeout_ms =~ ^[1-9][[:digit:]]{0,8}$ ]] || ((timeout_ms > max_timeout_ms)); then
    fail "timeoutMs: a whole number of milliseconds, from 1 to $max_timeout_ms"
  fi
}

# The key `unpair` forgets: its base64, `blob`.
check_key() {
  check_text key 1000
  if ! [[ ${request[key]} =~ $key_pattern ]]; then
    fail "key: an SSH public key"
  fi
  blob=${BASH_REMATCH[3]}
}

# Reads what the computer lets grog do (`policy_file`), the default without
# one: `allow`, `ask` and `deny`, names apart by spaces. Fails saying why
# when it makes no sense: grog may do nothing then.
read_policy() {
  local json name='"[[:alnum:]_.-]*"'
  local list="\\[($name(,$name)*)?\\]"
  local entry="\"(allow|ask|deny)\":$list"
  allow=$default_allow ask=$default_ask deny=''
  [[ -e $policy_file ]] || return 0
  if ! json=$(tr -d ' \t\r\n' <"$policy_file") || ! [[ $json =~ ^\{($entry(,$entry)*)?\}$ ]]; then
    fail "its policy, $policy_file, should be {\"allow\": [...], \"ask\": [...], \"deny\": [...]}"
  fi
  allow=$(names_in "$json" allow)
  ask=$(names_in "$json" ask)
  deny=$(names_in "$json" deny)
}

# names_in JSON KEY: the names in the policy's list KEY, apart by spaces.
names_in() {
  local list="\"$2\":\\[([^]]*)\\]" names
  [[ $1 =~ $list ]] || return 0
  names=${BASH_REMATCH[1]//\"/}
  printf '%s' "${names//,/ }"
}

# has NAME LIST: whether LIST, names apart by spaces, has NAME.
has() {
  [[ " $2 " == *" $1 "* ]]
}

# mode ACTION: how the policy lets grog take ACTION: allow, ask or deny.
# Denying wins, and what it doesn't name is denied.
mode() {
  if has "$1" "$deny"; then
    echo deny
  elif has "$1" "$allow"; then
    echo allow
  elif has "$1" "$ask"; then
    echo ask
  else
    echo deny
  fi
}

# Asks whoever is at the computer to allow what grog asks, on a notification
# they have `ask_s` to answer: returns once they allow it, fails saying why
# otherwise. Its default action (clicking it, with most notification
# daemons; dunst's middle click) and its Allow button (where there are
# buttons) allow it; ignoring or closing it denies it.
allowed() {
  local title="$from wants to ${doing[$action]}" body="Allow it?" line id='' yes=false notified
  case $action in
    shell)
      body=${request[command]}
      if ((${#body} > 300)); then body="${body:0:300}…"; fi
      ;;
    open) body=${request[target]} ;;
    notify) body=${request[text]} ;;
  esac
  output=$(timeout $((ask_s + 2)) notify-send --app-name="$from" --urgency=critical --expire-time=$((ask_s * 1000)) \
    --print-id --wait --action=default=Allow --action=allow=Allow -- "$title" "$body" 2>&1 </dev/null)
  notified=$?
  while read -r line; do
    if [[ -z $id && $line =~ ^[[:digit:]]+$ ]]; then id=$line; fi
    if [[ $line == default || $line == allow ]]; then yes=true; fi
  done <<<"$output"
  [[ $yes == true ]] && return 0
  if ((notified == 124)) && [[ -n $id ]]; then
    timeout 5 busctl --user call org.freedesktop.Notifications /org/freedesktop/Notifications \
      org.freedesktop.Notifications CloseNotification u "$id" >/dev/null 2>&1
  fi
  if ((notified != 0 && notified != 124)); then
    fail "nobody is there to allow it on $host: it can't ask anyone (no session, or no notify-send)"
  fi
  fail "nobody allowed it on $host (someone at it has $ask_s s to)"
}

# run COMMAND...: runs COMMAND for an action, without input, for `action_s`
# at most: what it printed in `output`, its exit status returned.
run() {
  output=$(timeout "$action_s" "$@" 2>&1 </dev/null)
}

# ok STATUS WHAT: done when STATUS (a command's) is 0; WHAT failed
# otherwise, saying what it printed (`output`).
ok() {
  local said
  if (($1 == 0)); then answer "done" true; fi
  said=$(trim "$output")
  fail "$2: ${said:-exit $1}"
}

# expanded PATH: PATH, ~/ being the user's home.
expanded() {
  # shellcheck disable=SC2088 # a ~ as grog says it, not this shell's
  if [[ $1 == "~/"* ]]; then
    printf '%s/%s' "$HOME" "${1#"~/"}"
  else
    printf '%s' "$1"
  fi
}

# sessions USER: USER's login sessions.
sessions() {
  timeout 5 loginctl list-sessions --no-legend 2>/dev/null | awk -v user="$1" '$3 == user { print $1 }'
}

# How the computer is doing, for grog's computer tool: a fact a line, what
# can't be found left out. Read-only.
computer_status() {
  local user battery session type state version
  echo "name: $(host_name)"
  awk '{ printf "up: %d days, %d hours, %d minutes\n", $1 / 86400, $1 % 86400 / 3600, $1 % 3600 / 60 }' /proc/uptime
  echo "load: $(cut -d ' ' -f 1-3 /proc/loadavg), $(nproc 2>/dev/null) CPUs"
  free -m 2>/dev/null | awk '/^Mem:/ { print "memory: " $3 " of " $2 " MB used" }'
  for battery in /sys/class/power_supply/BAT*; do
    if [[ -r $battery/capacity ]]; then
      echo "battery: $(<"$battery/capacity")%, $(tr '[:upper:]' '[:lower:]' <"$battery/status")"
    fi
  done
  user=$(id -un)
  for session in $(sessions "$user"); do
    type=$(timeout 5 loginctl show-session "$session" -p Type --value 2>/dev/null)
    case $type in
      wayland | x11)
        state=unlocked
        if [[ $(timeout 5 loginctl show-session "$session" -p LockedHint --value 2>/dev/null) == yes ]]; then
          state=locked
        fi
        if [[ $(timeout 5 loginctl show-session "$session" -p IdleHint --value 2>/dev/null) == yes ]]; then
          state="$state, idle"
        fi
        echo "screen: $user's ($type), $state"
        ;;
    esac
  done
  if command -v playerctl >/dev/null; then
    timeout 5 playerctl metadata --format 'playing: {{lc(status)}}, {{artist}} - {{title}} ({{playerName}})' 2>/dev/null
  fi
  if command -v wpctl >/dev/null; then
    # volume: 0.40 [muted]
    LC_ALL=C timeout 5 wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null | tr '[:upper:]' '[:lower:]'
  fi
  if version=$(nixos-version 2>/dev/null); then
    echo "nixos: $version"
  fi
}

# Locks the screens of the computer's user: logind tells the screen lockers
# of their graphical sessions, which lock if they listen.
lock_screens() {
  local user session locked=0
  user=$(id -un)
  for session in $(sessions "$user"); do
    case $(timeout 5 loginctl show-session "$session" -p Type --value 2>/dev/null) in
      wayland | x11) timeout 5 loginctl lock-session "$session" && locked=$((locked + 1)) ;;
    esac
  done
  if ((locked == 0)); then
    echo "no screen of $user to lock"
    return 1
  fi
}

# power suspend|poweroff: suspends the computer or turns it off, as its
# user: with sudo without a password, or as polkit allows. It happens a
# moment later, for grog to hear that it will first.
power() {
  local can=CanSuspend
  if [[ $1 == poweroff ]]; then can=CanPowerOff; fi
  if timeout 5 sudo -n true 2>/dev/null; then
    timeout 5 sudo -n systemd-run --collect --quiet --on-active=1 systemctl "$1"
    return
  fi
  if timeout 5 busctl call org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager "$can" \
    2>/dev/null | grep -qx 's "yes"'; then
    timeout 5 systemd-run --user --collect --quiet --on-active=1 systemctl "$1"
    return
  fi
  echo "its user may not, from here: that takes sudo without a password, or polkit allowing it"
  return 1
}

# volume ARGUMENT...: wpctl, failing saying why unless it works: what it
# printed in `output`.
volume() {
  if ! output=$(LC_ALL=C timeout "$action_s" wpctl "$@" 2>&1 </dev/null); then
    fail "the volume couldn't be changed: $(trim "$output")"
  fi
}

# Changes the volume as grog asks, then tells it.
set_volume() {
  local sink=@DEFAULT_AUDIO_SINK@ change sign=+ muted=false fraction
  local said='Volume: ([[:digit:]]+)\.([[:digit:]]*)( \[MUTED\])?'
  if [[ -n ${request[mute]-} ]]; then
    if [[ ${request[mute]} == true ]]; then volume set-mute "$sink" 1; else volume set-mute "$sink" 0; fi
  fi
  if [[ -n ${request[level]-} ]]; then
    volume set-volume -l 1 "$sink" "${request[level]}%"
  fi
  if [[ -n ${request[change]-} ]]; then
    change=${request[change]}
    if ((change < 0)); then
      sign=-
      change=$((-change))
    fi
    volume set-volume -l 1 "$sink" "$change%$sign"
  fi
  volume get-volume "$sink"
  if ! [[ $output =~ $said ]]; then
    fail "the volume couldn't be changed: $(trim "$output")"
  fi
  fraction=${BASH_REMATCH[2]}000
  if [[ -n ${BASH_REMATCH[3]} ]]; then muted=true; fi
  answer level $((10#${BASH_REMATCH[1]} * 100 + 10#${fraction:0:2} + (${fraction:2:1} >= 5))) muted "$muted"
}

# seconds MS: MS milliseconds, in seconds.
seconds() {
  printf '%d.%03d' $(($1 / 1000)) $(($1 % 1000))
}

# Runs grog's command with bash, without input, as the computer's user:
# what it leaves running in the background goes on without the hands, and
# past its time it's killed, with whatever it started.
shell() {
  local dir=${XDG_RUNTIME_DIR-} out notice exited timed_out=false truncated=false
  if [[ -z $dir || ! -d $dir || ! -w $dir ]]; then dir=${TMPDIR:-/tmp}; fi
  if ! out=$(mktemp "$dir/grog-hands.XXXXXX") || ! notice=$(mktemp "$dir/grog-hands.XXXXXX"); then
    fail "it can't run a command: there's nowhere to keep what it prints"
  fi
  # shellcheck disable=SC2016
  timeout -v -k 5 "$(seconds "$timeout_ms")" "$BASH" -c 'exec >"$1" 2>&1 </dev/null; exec "$0" -c "$2"' \
    "$BASH" "$out" "${request[command]}" 2>"$notice"
  exited=$?
  # timeout only says something when it kills it.
  if [[ -s $notice ]]; then timed_out=true; fi
  if (($(wc -c <"$out") > max_output_bytes)); then truncated=true; fi
  printf 'grog-hands exit=%s output=%s timedOut=%s truncated=%s\n' "$(b64 "$exited")" \
    "$(head -c "$max_output_bytes" "$out" | base64 -w0)" "$(b64 "$timed_out")" "$(b64 "$truncated")"
  rm -f "$out" "$notice"
  exit 0
}

# Forgets grog's key: it can't come in anymore.
unpair() {
  if [[ -e $authorized_keys ]] && ! rewrite_keys "$blob"; then
    fail "grog's key couldn't be taken out of $authorized_keys"
  fi
  answer "done" true
}

# Takes the action grog asks for, now that the policy lets it: what it did.
take() {
  case $action in
    status) answer status "$(trim "$(computer_status 2>/dev/null)")" ;;
    notify)
      run notify-send --app-name="$from" -- "$from" "${request[text]}"
      ok $? "the notification failed"
      ;;
    open)
      run systemd-run --user --collect --quiet -- xdg-open "$(expanded "${request[target]}")"
      ok $? "it couldn't be opened"
      ;;
    media)
      run playerctl "${request[media]}"
      ok $? "playerctl failed"
      ;;
    volume) set_volume ;;
    lock)
      output=$(lock_screens 2>&1 </dev/null)
      ok $? "it couldn't be locked"
      ;;
    suspend)
      output=$(power suspend 2>&1 </dev/null)
      ok $? "it can't be suspended"
      ;;
    power-off)
      output=$(power poweroff 2>&1 </dev/null)
      ok $? "it can't be turned off"
      ;;
    shell) shell ;;
  esac
}

# What grog's key runs when grog comes in: answers the request on stdin, on
# stdout, once the policy lets grog take it.
hands() {
  local user
  # The session of the computer's user, which SSH may not say: its D-Bus
  # (notifications, players), its audio.
  XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
  DBUS_SESSION_BUS_ADDRESS=${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}
  export XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS
  host=$(host_name)
  if ! read_request; then
    fail "send a request: a line of key=value fields, each value in base64"
  fi
  exec </dev/null
  check_version
  check_from
  action=${request[action]-}
  case $action in
    hello)
      read_policy
      user=$(id -un)
      answer v "$protocol" host "$host" user "$user" allow "$allow" ask "$ask" deny "$deny"
      ;;
    unpair)
      check_key
      unpair
      ;;
    status | lock | suspend | power-off) ;;
    notify) check_text text 1000 lines ;;
    open) check_target ;;
    media) check_media ;;
    volume)
      check_number level 0 100
      check_number change -100 100
      check_mute
      ;;
    shell)
      check_text command 20000 lines
      check_timeout
      ;;
    *) fail "action: one of hello, unpair, $(joined ", " "${actions[@]}")" ;;
  esac
  read_policy
  case $(mode "$action") in
    deny) fail "$host doesn't let $from ${doing[$action]} (its policy: $policy_file)" ;;
    ask) allowed ;;
  esac
  take
}

# Pairing: `computer.sh pair`.

usage() {
  cat <<'EOF'
Usage: computer.sh [pair] [ADDRESS[:PORT]] [--name NAME] [--ssh-port PORT]
                   [--host-key FILE] [--trust]
         pairs this computer with grog at ADDRESS (where grog served this
         script, unless said), for grog to act on it as it allows
         (~/.config/grog/hands.json). --name: what grog calls it (its host
         name); --ssh-port: where its SSH server listens (22); --host-key:
         its SSH host key, if it isn't in /etc/ssh; --trust: grog may do
         anything here, without asking
       computer.sh hands
         what grog runs here once paired, when it comes in over SSH
EOF
}

say() {
  printf '%s\n' "$@"
}

# error MESSAGE: pairing failed, saying why.
error() {
  printf '[error] %s\n' "$1" >&2
  exit 1
}

usage_error() {
  usage >&2
  exit 2
}

# locate ADDRESS: where grog is, from ADDRESS (HOST, HOST:PORT or
# [IPV6]:PORT, with http:// or not): its `url`, http://HOST:PORT, and its
# `grog_host`.
locate() {
  local a=${1#http://} port
  a=${a%%/*}
  if [[ $a =~ ^\[([[:xdigit:]:.]+)\](:([[:digit:]]{1,5}))?$ ]]; then
    grog_host=${BASH_REMATCH[1]} port=${BASH_REMATCH[3]:-$pairing_port}
    url="http://[$grog_host]:$port"
  elif [[ $a == *:*:* && $a =~ ^[[:xdigit:]:.]+$ ]]; then
    grog_host=$a
    url="http://[$a]:$pairing_port"
  elif [[ $a =~ ^([[:alnum:].-]+)(:([[:digit:]]{1,5}))?$ ]]; then
    grog_host=${BASH_REMATCH[1]} port=${BASH_REMATCH[3]:-$pairing_port}
    url="http://$grog_host:$port"
  else
    return 1
  fi
}

# find_host_key FILE...: this computer's SSH host key, from the first of the
# FILEs there is: its type and base64.
find_host_key() {
  local file type blob
  for file; do
    [[ -r $file ]] || continue
    read -r type blob _ <"$file" || [[ -n ${blob-} ]] || continue
    if [[ "$type $blob" =~ $key_pattern ]]; then
      printf '%s %s' "$type" "$blob"
      return 0
    fi
  done
  return 1
}

# listening PORT: whether something takes connections on PORT here.
listening() {
  # shellcheck disable=SC2016
  timeout 2 "$BASH" -c 'exec 3<>"/dev/tcp/127.0.0.1/$1"' "$BASH" "$1" 2>/dev/null
}

# computer_name HOSTNAME: what grog calls a computer whose host name is
# HOSTNAME.
computer_name() {
  local name
  name=$(printf '%s' "${1%%.*}" | LC_ALL=C tr '[:upper:]' '[:lower:]' | LC_ALL=C tr -cs '[:lower:][:digit:]-' '[-*]')
  while [[ $name == -* ]]; do name=${name#-}; done
  while [[ $name == *- ]]; do name=${name%-}; done
  printf '%s' "${name:0:32}"
}

# 16 random bytes, in hex.
random_hex() {
  od -An -N16 -tx1 /dev/urandom | tr -d ' \n'
}

# sha256 TEXT: TEXT's SHA-256, in hex.
sha256() {
  local digest
  digest=$(printf '%s' "$1" | sha256sum) || return 1
  printf '%s' "${digest%% *}"
}

# pairing_code KEY NONCE HOST_KEY HOST_NONCE: the 6 digits both screens
# show, from grog's key and nonce, and this computer's host key and nonce
# (grog.pairing/code).
pairing_code() {
  local digest
  digest=$(sha256 "$1"$'\n'"$2"$'\n'"$3"$'\n'"$4") || return 1
  printf '%06d' $(((16#${digest:0:8} & 0x7fffffff) % 1000000))
}

# network ADDRESS: where grog's key may come from, around grog's ADDRESS as
# this computer sees it: its /24 for IPv4 (a new address on the same
# network still works), only itself otherwise.
network() {
  if [[ $1 =~ ^([[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+)\.[[:digit:]]+$ ]]; then
    printf '%s.0/24' "${BASH_REMATCH[1]}"
  else
    printf '%s' "$1"
  fi
}

# resolve HOST: HOST's IP address, or HOST when it can't be told.
resolve() {
  local ip=''
  if [[ $1 =~ ^[[:digit:].]+$ || $1 == *:* ]]; then
    printf '%s' "$1"
    return
  fi
  if command -v getent >/dev/null; then
    ip=$(getent ahostsv4 "$1" 2>/dev/null | awk 'NR == 1 { print $1 }')
    if [[ -z $ip ]]; then ip=$(getent hosts "$1" 2>/dev/null | awk 'NR == 1 { print $1 }'); fi
  fi
  printf '%s' "${ip:-$1}"
}

# mac_toward ADDRESS: the address of the network card on the way to
# ADDRESS, what grog wakes this computer with, or nothing.
mac_toward() {
  local route link
  command -v ip >/dev/null || return 0
  route=$(ip route get "$1" 2>/dev/null) || return 0
  [[ $route =~ [[:space:]]dev[[:space:]]+([^[:space:]]+) ]] || return 0
  link=$(ip -o link show dev "${BASH_REMATCH[1]}" 2>/dev/null) || return 0
  if [[ $link =~ link/ether[[:space:]]+([[:xdigit:]]{2}(:[[:xdigit:]]{2}){5}) && ${BASH_REMATCH[1]} != 00:00:00:00:00:00 ]]; then
    printf '%s' "${BASH_REMATCH[1],,}"
  fi
}

# http METHOD PATH [JSON]: asks grog's pairing (at `url`): the HTTP `status`
# and `body` of its answer. Fails saying why (`why`) when it can't be
# reached.
http() {
  local response said
  local -a args=(-sS --max-time 15 -X "$1" -w '\n%{http_code}')
  if [[ -n ${3-} ]]; then args+=(-H 'Content-Type: application/json' --data-binary "$3"); fi
  if ! response=$(curl "${args[@]}" "$url$2" 2>"$work/curl"); then
    said=$(trim "$(<"$work/curl")")
    if [[ $said =~ ^curl:\ \([[:digit:]]+\)\ (.*)$ ]]; then said=${BASH_REMATCH[1]}; fi
    why="grog doesn't let a computer pair at $url ($said): let one pair on grog first (its menu, or grog computers add)"
    return 1
  fi
  status=${response##*$'\n'}
  body=${response%$'\n'*}
}

# call METHOD PATH [JSON]: `http`, then the fields of grog's answer (lines
# of key=value) in `reply`. Fails saying why (`why`) unless it's a 200.
call() {
  local line
  http "$@" || return 1
  reply=()
  while IFS= read -r line; do
    line=${line//[[:cntrl:]]/}
    if [[ $line == ?*=* ]]; then reply[${line%%=*}]=${line#*=}; fi
  done <<<"$body"
  [[ $status == 200 ]] && return 0
  why=${reply[error]:-grog answered with HTTP $status}
  return 1
}

# Keeps the copy of this script grog served (in `work`) where grog's key
# runs it.
keep_hands() {
  mkdir -p "${hands_file%/*}" && cp "$work/computer.sh" "$hands_file.new" && chmod 700 "$hands_file.new" &&
    mv -f "$hands_file.new" "$hands_file"
}

# policy_json ALLOW ASK DENY: a policy, its names apart by spaces, as JSON.
policy_json() {
  printf '{\n  "allow": %s,\n  "ask": %s,\n  "deny": %s\n}\n' "$(json_names "$1")" "$(json_names "$2")" \
    "$(json_names "$3")"
}

# json_names NAMES: NAMES, apart by spaces, as a JSON list.
json_names() {
  local list='' name
  for name in $1; do list+=${list:+, }\"$name\"; done
  printf '[%s]' "$list"
}

# write_policy TRUST: says what grog may do here, the default, unless the
# user already did; or everything, without asking, when TRUST is true.
write_policy() {
  if [[ $1 == true ]]; then
    mkdir -p "${policy_file%/*}" && policy_json "${actions[*]}" "" "" >"$policy_file"
  elif [[ ! -e $policy_file ]]; then
    mkdir -p "${policy_file%/*}" && policy_json "$default_allow" "$default_ask" "" >"$policy_file"
  fi
}

# Pairs this computer with grog: see `usage`. Both screens show a code made
# from both keys, and someone at grog allows it when they're the same; then
# this computer lets grog's key in, only to run its hands here (a copy of
# this script, as grog served it), and only from grog's network. Someone in
# between swapping keys can't make the codes match (but once in a million):
# this computer commits to its nonce (sends its hash) before it gets grog's,
# and only reveals it then.
pair() {
  local address=$grog_url name='' ssh_port=22 trust=false key_file='' host_key user nonce grog_ip mac
  local id key grog_nonce grog_name expires_in code deadline from line
  while (($#)); do
    case $1 in
      --name | --ssh-port | --host-key)
        (($# >= 2)) || usage_error
        case $1 in
          --name) name=$2 ;;
          --ssh-port) ssh_port=$2 ;;
          *) key_file=$2 ;;
        esac
        shift 2
        ;;
      --trust)
        trust=true
        shift
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      -*) usage_error ;;
      *)
        address=$1
        shift
        ;;
    esac
  done
  [[ -n $address ]] || error "Say where grog is: computer.sh pair <grog's address>"
  locate "$address" || error "grog's address makes no sense: say it like 192.168.15.10, or grognix.local:8485."
  if [[ -n $key_file ]]; then
    host_key=$(find_host_key "$key_file") || error "There's no SSH host key in $key_file."
  elif ! host_key=$(find_host_key "${host_key_files[@]}"); then
    error "There's no SSH server here: grog reaches computers over SSH. Install one and start it (on NixOS: services.openssh.enable = true)."
  fi
  if ! [[ $ssh_port =~ ^[1-9][[:digit:]]{0,4}$ ]] || ((ssh_port > 65535)); then
    error "--ssh-port: a port number."
  fi
  listening "$ssh_port" ||
    error "Nothing takes SSH connections here on port $ssh_port: start the SSH server (on NixOS: services.openssh.enable = true), or say its port with --ssh-port."
  command -v curl >/dev/null || error "Install curl first: this computer talks to grog with it."
  name=${name:-$(computer_name "$(host_name)")}
  [[ $name =~ ^[[:alnum:]-]{1,32}$ ]] ||
    error "Say what grog calls this computer with --name: lowercase letters, digits and dashes, up to 32."
  user=$(id -un)
  [[ $user =~ ^[[:alnum:]_.-]+$ ]] || error "grog can't act as $user: its name is too odd."
  [[ $hands_file =~ ^/[[:alnum:]/._+@-]+$ ]] ||
    error "grog's hands can't be kept at $hands_file: grog's key can only run them from a plain path (letters, digits, / . _ + @ -)."

  work=$(mktemp -d) || error "There's nowhere to keep grog's hands for now (mktemp failed)."
  trap 'rm -rf "$work"' EXIT
  http GET /computer.sh || error "$why"
  if [[ $status != 200 || $body != "#!/usr/bin/env bash"* ]] || ! printf '%s' "$body" >"$work/computer.sh"; then
    error "grog's answer makes no sense: is it grog there?"
  fi

  nonce=$(random_hex)
  grog_ip=$(resolve "$grog_host")
  mac=$(mac_toward "$grog_ip")
  call POST /v1/pair "{\"v\":$protocol,\"name\":\"$name\",\"user\":\"$user\",\"hostKey\":\"$host_key\",\"sshPort\":$ssh_port,\"commitment\":\"$(sha256 "$nonce")\"${mac:+,\"mac\":\"$mac\"}}" ||
    error "$why"
  id=${reply[id]-} key=${reply[key]-} grog_nonce=${reply[nonce]-} grog_name=${reply[name]:-grog}
  expires_in=${reply[expiresIn]-}
  if ! [[ $id =~ ^[[:xdigit:]]{32}$ && $grog_nonce =~ ^[[:xdigit:]]{32}$ && $expires_in =~ ^[[:digit:]]{1,7}$ &&
    $key =~ $key_pattern ]]; then
    error "grog's answer makes no sense: is it grog there?"
  fi
  blob=${BASH_REMATCH[3]}
  call POST "/v1/pair/$id/nonce" "{\"nonce\":\"$nonce\"}" || error "$why"
  code=$(pairing_code "$key" "$grog_nonce" "$host_key" "$nonce")
  say "Pairing $name with $grog_name. On $grog_name's screen, check the code is" "" "  ${code:0:3} ${code:3}" "" \
    "and allow it there. Waiting (Ctrl-C to stop)..."

  deadline=$((SECONDS + 10#$expires_in + 60))
  while :; do
    call GET "/v1/pair/$id" || error "$why"
    case ${reply[status]-} in
      started | asking) ;;
      *) break ;;
    esac
    if ((SECONDS > deadline)); then
      reply[status]=expired
      break
    fi
    sleep 1
  done
  case ${reply[status]-} in
    allowed) ;;
    denied) error "It wasn't allowed on $grog_name." ;;
    expired | closed) error "Pairing closed on $grog_name before it was allowed." ;;
    *) error "${reply[error]:-Pairing ${reply[status]:-failed}}" ;;
  esac

  from=${reply[from]-}
  [[ $from =~ ^[[:alnum:].:-]+$ ]] || from=$grog_ip
  line="restrict,from=\"$(network "$from")\",command=\"$hands_file hands\" $key"
  keep_hands || error "grog's hands couldn't be kept at $hands_file."
  if [[ -L $authorized_keys ]]; then
    grep -qxF -- "$line" "$authorized_keys" ||
      error "$authorized_keys is a link (home-manager?): put this line where it comes from, then pair again: $line"
  elif ! rewrite_keys "$blob" "$line"; then
    error "grog can't be let in: $authorized_keys can't be written."
  fi
  write_policy "$trust" || error "What grog may do here can't be written to $policy_file."
  if ! call POST "/v1/pair/$id/check" '{}'; then
    [[ -L $authorized_keys ]] || rewrite_keys "$blob"
    error "$why"
  fi
  say "" "Paired: grog can act on this computer now. What it may do here, and what it asks first: $policy_file"
}

main() {
  case ${1-} in
    hands) hands ;;
    pair)
      shift
      pair "$@"
      ;;
    help | -h | --help) usage ;;
    *) pair "$@" ;;
  esac
}

main "$@"
