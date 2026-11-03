#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
umask 077

APP_DIR="$HOME/.mycontacts-signing"
KEYSTORE="$APP_DIR/mycontacts-release.p12"
B64_FILE="$APP_DIR/mycontacts-release.base64"
ALIAS="mycontacts-release"
LOG="$APP_DIR/setup.log"

mkdir -p "$APP_DIR"
chmod 700 "$APP_DIR"
exec > >(tee -a "$LOG") 2>&1

echo "=== MyContacts persistent Android signing setup ==="
echo "Private files directory: $APP_DIR"
echo "The keystore will NOT be placed in the Git repository."
echo

if ! command -v keytool >/dev/null 2>&1; then
  echo "Installing Java keytool (OpenJDK 17) in Termux..."
  pkg update -y
  pkg install -y openjdk-17
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool is still unavailable. Install a Termux package that provides keytool and rerun."
  exit 1
fi

if [ -e "$KEYSTORE" ]; then
  echo "STOP: A keystore already exists at:"
  echo "$KEYSTORE"
  echo "This script will not overwrite or replace it."
  echo "If this is the key created for MyContacts, continue with the separate export procedure."
  exit 2
fi

echo "You will now create a permanent PKCS12 signing key."
echo "When keytool asks for passwords:"
echo "  - choose a strong password and store it safely;"
echo "  - use the SAME password for the key password when prompted;"
echo "  - do not send the password to ChatGPT or put it in the repository."
echo
echo "When asked for identity details, you may use a neutral name such as MyContacts Release."
echo

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000

chmod 600 "$KEYSTORE"

echo
echo "Verifying the newly created keystore. Enter the same password when prompted."
keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS"

echo
echo "Creating Base64 export for GitHub Actions..."
base64 -w 0 "$KEYSTORE" > "$B64_FILE"
chmod 600 "$B64_FILE"
test -s "$B64_FILE"

if command -v sha256sum >/dev/null 2>&1; then
  echo "Keystore SHA-256:"
  sha256sum "$KEYSTORE"
fi

if command -v termux-clipboard-set >/dev/null 2>&1; then
  termux-clipboard-set < "$B64_FILE"
  echo
  echo "Base64 value copied to Android clipboard."
else
  echo
  echo "Clipboard helper is unavailable. Install Termux:API and run: termux-clipboard-set < $B64_FILE"
fi

echo
echo "=== GitHub secrets to add manually ==="
echo "Repository: https://github.com/drvladcepeche-maker/contacts/settings/secrets/actions"
echo
echo "ANDROID_KEYSTORE_BASE64"
echo "  Value: contents of $B64_FILE (already copied to clipboard if Termux:API is installed)"
echo "ANDROID_KEYSTORE_PASSWORD"
echo "  Value: the password you entered for the keystore"
echo "ANDROID_KEY_ALIAS"
echo "  Value: $ALIAS"
echo "ANDROID_KEY_PASSWORD"
echo "  Value: the SAME password you entered for the keystore"
echo
echo "Do not paste the password or Base64 value into this chat."
echo "Back up this file securely before deleting or changing anything:"
echo "  $KEYSTORE"
echo "Keep the backup and password separately. Losing this key prevents future in-place updates of the new .fullfields app."
echo
echo "Setup complete. No app was installed, uninstalled, or modified."
