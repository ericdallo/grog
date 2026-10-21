# shellcheck shell=bash
# Suspends this computer or turns it off ($1: suspend or poweroff), for
# grog's computer tool (grog.remote-hands), as its user: with sudo without a
# password, or as polkit allows. It happens a moment later, for grog to
# hear that it will first.

verb=$1
case $verb in
  suspend) can=CanSuspend ;;
  poweroff) can=CanPowerOff ;;
  *)
    echo "suspend or poweroff"
    exit 2
    ;;
esac
if sudo -n true 2>/dev/null; then
  exec sudo -n systemd-run --collect --quiet --on-active=1 systemctl "$verb"
fi
if busctl call org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager "$can" 2>/dev/null |
  grep -qx 's "yes"'; then
  exec systemd-run --user --collect --quiet --on-active=1 systemctl "$verb"
fi
echo "its user may not, from here: that takes sudo without a password, or polkit allowing it"
exit 1
