#!@shell@
# shellcheck shell=bash
# `grog` entry point, installed by grog.nix.
set -euo pipefail

bb=(@babashka@ --config @out@/share/grog/bb.edn --main grog.main)

# Line editing and history for the interactive chat.
if [[ "${1:-}" == chat && -t 0 ]]; then
  exec @rlwrap@ -C grog "${bb[@]}" "$@"
fi

exec "${bb[@]}" "$@"
