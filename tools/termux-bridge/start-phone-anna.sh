#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$HOME/contacts"

# A detached Node worker does not inherit Android's runtime classpath
# variables from Termux's interactive shell. rish/app_process silently exits
# when these are missing. Copy only the Android runtime variables needed by
# app_process from the invoking Termux shell; do not copy credentials or the
# full interactive environment.
parent_env="/proc/$PPID/environ"
if [ -r "$parent_env" ]; then
  while IFS= read -r -d '' entry; do
    case "$entry" in
      ANDROID_ART_ROOT=*|ANDROID_I18N_ROOT=*|ANDROID_TZDATA_ROOT=*|ANDROID_STORAGE=*|ANDROID_ASSETS=*|ANDROID__BUILD_VERSION_SDK=*|ASEC_MOUNTPOINT=*|BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*)
        export "$entry"
        ;;
    esac
  done < "$parent_env"
fi

required_vars=(ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT ANDROID_STORAGE ANDROID_ASSETS ANDROID__BUILD_VERSION_SDK ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH SYSTEMSERVERCLASSPATH)
missing=()
for name in "${required_vars[@]}"; do
  if [ -z "${!name:-}" ]; then missing+=("$name"); fi
done
if (("${#missing[@]}" > 0)); then
  printf 'Missing Android runtime environment variables for Shizuku: %s\n' "${missing[*]}" >&2
  printf 'Start this script directly from an open Termux terminal session.\n' >&2
  exit 2
fi

export RISH_APPLICATION_ID=com.termux
export RISH_BIN="$PREFIX/bin/rish"
exec node tools/termux-bridge/bridge.mjs
