#!/data/data/com.termux/files/usr/bin/bash
HOME=/data/data/com.termux/files/home
PREFIX=/data/data/com.termux/files/usr
BOOT_DIR="$HOME/.termux"
LOG="$BOOT_DIR/logs/remote-watchdog.log"
LOCK_DIR="$BOOT_DIR/remote-watchdog.lock"
SCRIPT="$BOOT_DIR/remote-watchdog.sh"
WORKER="$HOME/osiris-bridge/worker_v3.py"
WORKER_PID_FILE="$HOME/osiris-bridge/worker_v3.pid"
WORKER_HEALTH="$BOOT_DIR/worker-v3.heartbeat"
WORKER_STATE="$HOME/.config/osiris/bridge-state.json"
HEARTBEAT="$BOOT_DIR/wifi-heartbeat.sh"
HEARTBEAT_HEALTH="$HOME/osiris-network-diagnostics/wifi-heartbeat.heartbeat"
DC_MAIN="$HOME/.npm/_npx/4b4c857f6efdfb61/node_modules/@wonderwhy-er/desktop-commander/dist/index.js"
DC_BIN="$HOME/.npm/_npx/4b4c857f6efdfb61/node_modules/.bin/desktop-commander"
DC_LOG="$HOME/.desktop-commander-device/boot.log"
BASE_URL='https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge'
mkdir -p "$BOOT_DIR/logs" "$HOME/.desktop-commander-device" "$HOME/osiris-bridge"
exec >>"$LOG" 2>&1
chmod 600 "$LOG" 2>/dev/null || true
log(){ printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

# Atomic singleton lock; remove only a stale lock.
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  oldpid=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    oldcmd=$(tr '\0' ' ' < "/proc/$oldpid/cmdline" 2>/dev/null || true)
    case "$oldcmd" in *"$SCRIPT"*) log "Watchdog already active PID=$oldpid; exiting duplicate"; exit 0;; esac
  fi
  rm -rf "$LOCK_DIR"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then log 'Cannot acquire watchdog lock'; exit 1; fi
fi
echo $$ > "$LOCK_DIR/pid"
trap 'rm -rf "$LOCK_DIR"' EXIT
trap 'rm -rf "$LOCK_DIR"; exit 0' INT TERM
log "Watchdog v3 started PID=$$; check interval=10s; recovery wait=15s"

find_pid(){
  KIND="$1" "$PREFIX/bin/python3" - <<'PY'
import glob, os
kind=os.environ['KIND']
home='/data/data/com.termux/files/home'
prefix='/data/data/com.termux/files/usr'
worker=home+'/osiris-bridge/worker_v3.py'
dc=home+'/.npm/_npx/4b4c857f6efdfb61/node_modules/@wonderwhy-er/desktop-commander/dist/index.js'
dcbin=home+'/.npm/_npx/4b4c857f6efdfb61/node_modules/.bin/desktop-commander'
watch=home+'/.termux/remote-watchdog.sh'
for f in glob.glob('/proc/[0-9]*/cmdline'):
    try:
        pid=int(f.split('/')[2]); a=open(f,'rb').read().decode(errors='ignore').split('\0')
    except Exception: continue
    if not a or not a[0]: continue
    ok=False
    if kind=='worker': ok=(len(a)>=3 and a[0]==prefix+'/bin/python3' and a[1]=='-u' and a[2]==worker)
    elif kind=='dc': ok=(len(a)>=3 and a[1]==dcbin and a[2]=='remote')
    elif kind=='heartbeat': ok=(len(a)>=2 and a[1]==home+'/.termux/wifi-heartbeat.sh')
    elif kind=='dc_child':
        try: ppid=int(open('/proc/%d/status'%pid).read().split('PPid:',1)[1].splitlines()[0].strip())
        except Exception: ppid=-1
        ok=(len(a)>=2 and a[1]==dc and ppid==int(os.environ.get('DC_PARENT','-1')))
    elif kind=='watchdog': ok=(len(a)>=2 and a[1]==watch)
    if ok:
        print(pid); break
PY
}

start_worker(){
  nohup "$PREFIX/bin/python3" -u "$WORKER" >/dev/null 2>>"$HOME/osiris-bridge/worker.stderr.log" < /dev/null &
  echo $! > "$WORKER_PID_FILE"
  log "Worker launched PID=$(cat "$WORKER_PID_FILE")"
}
start_dc(){
  if [ -x "$DC_BIN" ]; then
    nohup "$PREFIX/bin/node" "$DC_BIN" remote >>"$DC_LOG" 2>&1 < /dev/null &
    log "Desktop Commander remote launcher started PID=$!"
  elif [ -f "$DC_MAIN" ]; then
    nohup "$PREFIX/bin/node" "$DC_MAIN" remote >>"$DC_LOG" 2>&1 < /dev/null &
    log "Desktop Commander fallback launcher started PID=$!"
  else
    log 'Desktop Commander entrypoint missing; no repeated npm cache scan'
  fi
}
start_heartbeat(){
  nohup "$PREFIX/bin/bash" "$HEARTBEAT" >/dev/null 2>&1 < /dev/null &
  log "Wi-Fi heartbeat launched PID=$!"
}
dc_process_state(){
  "$PREFIX/bin/python3" - "$1" <<'PYDC'
import sys
pid=sys.argv[1]
try:
    s=open('/proc/'+pid+'/stat').read()
    tail=s[s.rfind(')')+2:].split()
    print(tail[0] if tail else 'UNKNOWN')
except Exception:
    print('UNKNOWN')
PYDC
}

worker_active_job(){
  "$PREFIX/bin/python3" - "$WORKER_STATE" <<'PYJOB'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        state=json.load(f)
    value=state.get("active_job_id")
    if isinstance(value, str) and value:
        print(value)
except Exception:
    pass
PYJOB
}
vpn_iface(){
  local d
  for d in /sys/class/net/tun*; do
    [ -e "$d" ] && { basename "$d"; return 0; }
  done
  return 1
}

restart_lxbox(){
  log 'VPN tunnel down; launching LxBox and requesting connection'
  RISH_APPLICATION_ID=com.termux timeout 5 "$PREFIX/bin/rish" -c '/system/bin/pm enable --user 0 com.leadaxe.lxbox/.vpn.LxBoxIntentReceiver' >>"$LOG" 2>&1
  log "LxBox VPN action receiver enable rc=$?"
  RISH_APPLICATION_ID=com.termux timeout 8 "$PREFIX/bin/rish" -c '/system/bin/am start --user 0 -n com.leadaxe.lxbox/.MainActivity' >>"$LOG" 2>&1
  sleep 2
  RISH_APPLICATION_ID=com.termux timeout 5 "$PREFIX/bin/rish" -c '/system/bin/am broadcast --user 0 -a com.leadaxe.lxbox.START_VPN -n com.leadaxe.lxbox/.vpn.LxBoxIntentReceiver' >>"$LOG" 2>&1
  log "LxBox START_VPN broadcast rc=$?; recovery window=15s"
  sleep 15
  local iface
  iface=$(vpn_iface || true)
  if [ -n "$iface" ]; then
    log "LxBox VPN recovery verified; interface=$iface"
  else
    log 'LxBox VPN recovery NOT verified; no tun interface'
  fi
}

VPN_FAILS=0
API_FAILS=0
LAST_LX_RESTART=0
LAST_WORKER_RESTART=0
LAST_WORKER_DEFER_LOG=0
LAST_DC_START=0
LAST_HEARTBEAT_START=0
while true; do
  now=$(date +%s)
  heartbeat_pid=$(find_pid heartbeat)
  if [ -n "$heartbeat_pid" ]; then
    if [ -f "$HEARTBEAT_HEALTH" ]; then heartbeat_age=$((now - $(stat -c %Y "$HEARTBEAT_HEALTH" 2>/dev/null || echo 0))); else heartbeat_age=9999; fi
    if [ "$heartbeat_age" -gt 90 ]; then
      log "Wi-Fi heartbeat stale (${heartbeat_age}s); restarting PID=$heartbeat_pid"
      kill -TERM "$heartbeat_pid" 2>/dev/null || true
      sleep 1
      heartbeat_pid=''
    fi
  fi
  if [ -z "$heartbeat_pid" ] && [ $((now - LAST_HEARTBEAT_START)) -ge 10 ]; then
    LAST_HEARTBEAT_START=$now
    start_heartbeat
  fi
  tun_iface=$(vpn_iface || true)
  tun_ok=0
  [ -n "$tun_iface" ] && tun_ok=1
  http_code=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 4 --max-time 6 "$BASE_URL" 2>/dev/null || true)
  net_ok=0
  if [ "$tun_ok" -eq 1 ] && [ -n "$http_code" ] && [ "$http_code" != '000' ]; then net_ok=1; fi

  if [ "$tun_ok" -eq 1 ]; then
    # A live TUN interface and a failed API probe are different fault domains.
    # Count API failures independently; never restart LxBox while its tunnel exists.
    VPN_FAILS=0
    if [ "$http_code" != '000' ] && [ -n "$http_code" ]; then
      if [ "$API_FAILS" -gt 0 ]; then
        log "API path recovered after $API_FAILS failed probe(s); HTTP=$http_code"
      fi
      API_FAILS=0
    else
      API_FAILS=$((API_FAILS + 1))
      if [ "$API_FAILS" -eq 1 ] || [ $((API_FAILS % 6)) -eq 0 ]; then
        log "VPN tunnel $tun_iface is UP; API path probe failed ($API_FAILS consecutive); LxBox left untouched"
      fi
    fi
  else
    API_FAILS=0
    VPN_FAILS=$((VPN_FAILS + 1))
    log "VPN tunnel DOWN ($VPN_FAILS/3); HTTP=${http_code:-000}"
    if [ "$VPN_FAILS" -ge 3 ] && [ $((now - LAST_LX_RESTART)) -ge 180 ]; then
      LAST_LX_RESTART=$now
      restart_lxbox
      VPN_FAILS=0
    fi
  fi

  # Keep the poller alive even when VPN/API is temporarily unavailable.
  # It retries network failures internally; restart a stale heartbeat only
  # while the API path is reachable to avoid process churn during outages.
  worker_pid=$(find_pid worker)
  if [ -n "$worker_pid" ]; then
    if [ "$net_ok" -eq 1 ]; then
      if [ -f "$WORKER_HEALTH" ]; then
        health_age=$((now - $(stat -c %Y "$WORKER_HEALTH" 2>/dev/null || echo 0)))
      else
        health_age=9999
      fi
      if [ "$health_age" -gt 45 ] && [ $((now - LAST_WORKER_RESTART)) -ge 30 ]; then
        active_job=$(worker_active_job || true)
        if [ -n "$active_job" ]; then
          state_age=$((now - $(stat -c %Y "$WORKER_STATE" 2>/dev/null || echo 0)))
        else
          state_age=9999
        fi
        # terminal_run permits up to 300s. Do not kill a healthy long-running
        # command just because it cannot poll while executing; allow 360s total.
        if [ -n "$active_job" ] && [ "$state_age" -lt 360 ]; then
          if [ $((now - LAST_WORKER_DEFER_LOG)) -ge 60 ]; then
            log "Worker API heartbeat stale (${health_age}s) but job $active_job is active (${state_age}s); deferring restart"
            LAST_WORKER_DEFER_LOG=$now
          fi
        else
          log "Worker heartbeat stale (${health_age}s); active_job=${active_job:-none} state_age=${state_age}s; restarting PID=$worker_pid"
          kill -TERM "$worker_pid" 2>/dev/null || true
          sleep 2
          LAST_WORKER_RESTART=$now
          start_worker
        fi
      fi
    fi
  else
    if [ $((now - LAST_WORKER_RESTART)) -ge 10 ]; then
      LAST_WORKER_RESTART=$now
      start_worker
    fi
  fi

  # Desktop Commander and user-facing apps start only after the VPN/API path is reachable.
  if [ "$net_ok" -eq 1 ]; then
    dc_pid=$(find_pid dc)
    if [ -z "$dc_pid" ]; then
      if [ $((now - LAST_DC_START)) -ge 15 ]; then
        LAST_DC_START=$now
        log 'VPN verified; Desktop Commander remote parent absent; starting canonical launcher'
        start_dc
      fi
    else
    dc_state=$(dc_process_state "$dc_pid")
    case "$dc_state" in
      T|t|Z)
        log "Desktop Commander remote parent PID=$dc_pid stopped/zombie (state=$dc_state); terminating and relaunching"
        kill -KILL "$dc_pid" 2>/dev/null || true
        sleep 1
        LAST_DC_START=$now
        start_dc
        ;;
      *)
        dc_child=$(DC_PARENT="$dc_pid" find_pid dc_child)
        if [ -n "$dc_child" ]; then
          child_state=$(dc_process_state "$dc_child")
          case "$child_state" in
            T|t|Z)
              log "Desktop Commander command worker PID=$dc_child stopped (state=$child_state); terminating worker for parent recovery"
              kill -KILL "$dc_child" 2>/dev/null || true
              ;;
          esac
        fi
        ;;
    esac
    fi
    # Launch Termux UI and AnyDesk after Desktop Commander has a live process.
    if [ -z "${UI_APPS_STARTED:-}" ] && [ -n "$(find_pid dc)" ]; then
      RISH_APPLICATION_ID=com.termux timeout 8 "$PREFIX/bin/rish" -c '/system/bin/am start --user 0 -n com.termux/.app.TermuxActivity' >>"$LOG" 2>&1 || true
      RISH_APPLICATION_ID=com.termux timeout 8 "$PREFIX/bin/rish" -c '/system/bin/am start --user 0 -n com.anydesk.anydeskandroid/.gui.activity.HubActivity' >>"$LOG" 2>&1 || true
      UI_APPS_STARTED=1
      log 'Termux UI and AnyDesk launch requests sent after VPN and Desktop Commander'
    fi
  fi
  sleep 10
done
