#!/system/bin/sh
# Tells ledctl when the screen turns on or off (started by service.sh, one instance). The "while on" LED mode runs
# only with the screen on; the TV service reprograms the LED ~1 s after screen-on, so "on" is sent a few times.
# logcat blocks while idle, so this costs nothing between events.
MODDIR=${0%/*}
P=/sys/module/ledctl/parameters
echo $$ > "$MODDIR/watch.pid"

# restart logcat if logd goes away; without the loop screen_on would stay at its last value for good
while :; do
  RP=
  logcat -b events -T 1 -s screen_toggled 2>/dev/null | while read -r l; do
    case "$l" in
      *"screen_toggled: 1"*)
        [ -n "$RP" ] && kill "$RP" 2>/dev/null
        # retries run in the background so a quick screen-off is handled at once, not after the last sleep
        ( for d in 0 0.5 0.5 1 1 2 3; do
            sleep $d
            [ -w "$P/screen_on" ] || exit
            echo 1 > "$P/screen_on"
          done ) &
        RP=$! ;;
      *"screen_toggled: 0"*)
        [ -n "$RP" ] && kill "$RP" 2>/dev/null
        RP=
        [ -w "$P/screen_on" ] && echo 0 > "$P/screen_on" ;;
    esac
  done
  sleep 5
done
