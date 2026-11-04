#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
umask 077

REPO="drvladcepeche-maker/contacts"
APP_DIR="$HOME/.mycontacts-signing"
KEYSTORE="$APP_DIR/mycontacts-release.p12"
B64_FILE="$APP_DIR/mycontacts-release.base64"
ALIAS="mycontacts-release"
LOG="$APP_DIR/setup.log"

mkdir -p "$APP_DIR"
chmod 700 "$APP_DIR"
exec > >(tee -a "$LOG") 2>&1

echo "=== MyContacts persistent Android signing setup ==="
echo "Private directory: $APP_DIR"
echo "The keystore will never be written into the Git repository."
echo

if ! command -v keytool >/dev/null 2>&1; then
  echo "Installing OpenJDK 17..."
  pkg update -y
  pkg install -y openjdk-17
fi
command -v keytool >/dev/null 2>&1 || { echo "ERROR: keytool unavailable."; exit 1; }

if ! command -v gh >/dev/null 2>&1; then
  echo "Installing GitHub CLI..."
  pkg install -y gh
fi
command -v gh >/dev/null 2>&1 || { echo "ERROR: GitHub CLI unavailable."; exit 1; }

if [ ! -f "$KEYSTORE" ]; then
  echo "Creating a new permanent PKCS12 signing key."
  echo "Choose a strong password and save it in a password manager."
  echo "This password will be entered invisibly and will not be printed or saved in this script."
  read -r -s -p "New keystore password (minimum 12 characters): " STOREPASS
  echo
  if [ "${#STOREPASS}" -lt 12 ]; then
    echo "ERROR: use at least 12 characters. No key was created."
    exit 1
  fi
  read -r -s -p "Repeat keystore password: " STOREPASS2
  echo
  if [ "$STOREPASS" != "$STOREPASS2" ]; then
    echo "ERROR: passwords do not match. No key was created."
    exit 1
  fi
  unset STOREPASS2
  export MYCONTACTS_STOREPASS="$STOREPASS"

  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -storepass:env MYCONTACTS_STOREPASS \
    -keypass:env MYCONTACTS_STOREPASS \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=MyContacts Release, OU=Android, O=MyContacts, L=Unknown, ST=Unknown, C=CA"
  chmod 600 "$KEYSTORE"
else
  echo "Existing keystore found. It will be reused; it will NOT be overwritten."
  read -r -s -p "Enter the existing keystore password: " STOREPASS
  echo
  export MYCONTACTS_STOREPASS="$STOREPASS"
fi

echo "Verifying the keystore..."
keytool -list -keystore "$KEYSTORE" -storetype PKCS12 \
  -storepass:env MYCONTACTS_STOREPASS -alias "$ALIAS" >/dev/null
echo "Keystore verification passed."

echo "Encoding the keystore for GitHub Actions..."
base64 -w 0 "$KEYSTORE" > "$B64_FILE"
chmod 600 "$B64_FILE"
test -s "$B64_FILE"

if ! gh auth status --hostname github.com >/dev/null 2>&1; then
  echo
  echo "GitHub authentication is required once."
  echo "GitHub CLI will display a one-time code and open or direct you to the browser."
  echo "Approve access for your GitHub account. Do not send the code to ChatGPT."
  gh auth login --hostname github.com --git-protocol https --web
fi

gh auth status --hostname github.com >/dev/null
echo "GitHub authentication verified."

echo "Uploading repository Actions secrets..."
gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO" < "$B64_FILE"
printf '%s' "$STOREPASS" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$STOREPASS" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"

unset STOREPASS MYCONTACTS_STOREPASS
echo "Checking that all four secret names exist (values remain hidden):"
gh secret list --repo "$REPO"

echo
echo "=== SIGNING SETUP COMPLETE ==="
echo "Keystore: $KEYSTORE"
echo "Base64 export: $B64_FILE"
echo "Log: $LOG"
echo "Repository secrets have been created/updated."
echo "Back up the .p12 file securely and keep its password separately."
echo "Never commit the keystore, Base64 file, or password."
echo "No installed app, contact, database row, or repository data was deleted or modified."
