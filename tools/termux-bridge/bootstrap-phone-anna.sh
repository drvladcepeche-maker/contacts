#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

echo "=== телефон Анна: Termux bootstrap ==="
echo "This installs prebuilt Termux packages and copies the Shizuku-exported files."
echo "It does not register a device or print/store a Supabase token."

if ! command -v pkg >/dev/null 2>&1; then
  echo "ERROR: Run this script inside Termux."
  exit 1
fi

pkg update -y
pkg install -y nodejs-lts git curl

echo
echo "=== Runtime ==="
node --version
npm --version
git --version
curl --version | head -n 1

echo
echo "=== Shared storage ==="
if [ ! -d "$HOME/storage/shared" ]; then
  termux-setup-storage
fi
if [ ! -d "$HOME/storage/shared" ]; then
  echo "ERROR: Termux has no shared-storage access yet."
  echo "Grant the Android storage permission and run this script again."
  exit 2
fi

echo
echo "=== Locate Shizuku export ==="
RISH_SOURCE=""
while IFS= read -r candidate; do
  dir="$(dirname "$candidate")"
  if [ -f "$dir/rish_shizuku.dex" ]; then
    RISH_SOURCE="$dir"
    break
  fi
done < <(find "$HOME/storage/shared" -maxdepth 6 -type f -name rish -print 2>/dev/null)

if [ -z "$RISH_SOURCE" ]; then
  echo "ERROR: rish and rish_shizuku.dex were not found."
  echo "In Shizuku, open 'Use Shizuku in terminal apps' -> 'Export files'."
  echo "Save both files together under Download (or a folder inside shared storage)."
  exit 3
fi

install -m 700 "$RISH_SOURCE/rish" "$PREFIX/bin/rish"
install -m 400 "$RISH_SOURCE/rish_shizuku.dex" "$PREFIX/bin/rish_shizuku.dex"

if ! grep -q '^export RISH_APPLICATION_ID=com.termux$' "$HOME/.bashrc" 2>/dev/null; then
  printf '\nexport RISH_APPLICATION_ID=com.termux\n' >> "$HOME/.bashrc"
fi
export RISH_APPLICATION_ID=com.termux

echo "Installed:"
ls -l "$PREFIX/bin/rish" "$PREFIX/bin/rish_shizuku.dex"

echo
echo "=== Shizuku authorization test ==="
echo "If Android asks whether Termux may use Shizuku, grant access."
set +e
"$PREFIX/bin/rish" -c 'id'
status=$?
set -e
if [ "$status" -ne 0 ]; then
  echo "Shizuku test failed (exit $status). Check that Shizuku is running and Termux is authorized."
  exit "$status"
fi

echo
echo "=== Clone/update Osiris profile ==="
if [ -d "$HOME/contacts/.git" ]; then
  git -C "$HOME/contacts" fetch origin feature/osiris-phone-anna
  git -C "$HOME/contacts" checkout feature/osiris-phone-anna
  git -C "$HOME/contacts" pull --ff-only origin feature/osiris-phone-anna
else
  git clone --single-branch --branch feature/osiris-phone-anna https://github.com/drvladcepeche-maker/contacts.git "$HOME/contacts"
fi

echo
echo "BOOTSTRAP COMPLETE."
echo "Next, inspect the device profile and register it once:"
echo "  cd ~/contacts"
echo "  node tools/termux-bridge/setup.mjs 'телефон Анна'"
echo "Do not run setup.mjs twice. Keep the token file private."
