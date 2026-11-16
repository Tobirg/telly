#!/system/bin/sh
# ledctl Magisk service: load ledctl.ko with the saved settings and keep the "while on" LED mode after
# screen-on (the TV service reprograms the LED ~1 s after every screen-on, so the mode is written again).
# Settings: ledctl.conf next to this file, lines key=value (sleep_led, sleep_level, on_led, on_level).
MODDIR=${0%/*}
P=/sys/module/ledctl/parameters

ARGS=""
if [ -f "$MODDIR/ledctl.conf" ]; then
  while IFS='=' read -r k v || [ -n "$k" ]; do
    case "$k" in
      sleep_led|sleep_level|on_led|on_level)
        case "$k$v" in sleep_led2|on_led2) v= ;; esac  # blink mode was removed
        n=${v#-}                                       # optional minus sign, then digits only
        case "$n" in ''|*[!0-9]*) ;; *) ARGS="$ARGS $k=$v" ;; esac ;;
    esac
  done < "$MODDIR/ledctl.conf"
fi

if [ ! -d "$P" ]; then
  insmod "$MODDIR/ledctl.ko" $ARGS 2> "$MODDIR/insmod.log"
fi
dmesg | grep 'ledctl:' | tail -4 > "$MODDIR/last_load.log"

# one screen-on watcher (own session, so a caller like `su -c sh service.sh` returns at once).
# pgrep sees every instance, also one whose watch.pid was lost or overwritten.
[ -n "$(pgrep -f "$MODDIR/watch.sh")" ] && exit 0
setsid sh "$MODDIR/watch.sh" </dev/null >/dev/null 2>&1 &
echo $! > "$MODDIR/watch.pid"   # at once, not only from watch.sh: a second service.sh run must find it
