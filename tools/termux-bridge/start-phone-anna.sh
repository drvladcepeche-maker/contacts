#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$HOME/contacts"
export RISH_APPLICATION_ID=com.termux
export RISH_BIN="$PREFIX/bin/rish"
exec node tools/termux-bridge/bridge.mjs
