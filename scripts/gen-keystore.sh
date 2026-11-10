#!/usr/bin/env bash
# Creates the release signing key once and writes keystore.properties.
# Usage: scripts/gen-keystore.sh [keystore path]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
keystore="${1:-$HOME/.android/amazcgm-release.jks}"
alias_name="amazcgm"
props="$root/keystore.properties"

command -v keytool >/dev/null || { echo "error: keytool not found; install JDK 17" >&2; exit 1; }

if [[ -e "$keystore" ]]; then
    echo "error: $keystore already exists. Refusing to overwrite a release key." >&2
    exit 1
fi
if [[ -e "$props" ]]; then
    echo "error: $props already exists. Move it away first." >&2
    exit 1
fi

read -r -p "Name for the certificate (CN): " cn
[[ -n "$cn" ]] || { echo "error: name is required" >&2; exit 1; }
read -r -s -p "Keystore password (min 6 chars): " pass; echo
read -r -s -p "Repeat password: " pass2; echo
[[ "$pass" == "$pass2" ]] || { echo "error: passwords differ" >&2; exit 1; }
[[ ${#pass} -ge 6 ]] || { echo "error: password too short" >&2; exit 1; }

mkdir -p "$(dirname "$keystore")"
export AMAZCGM_GEN_PASS="$pass"
# PKCS12 keystores use one password for the store and the key.
keytool -genkeypair \
    -keystore "$keystore" -storetype PKCS12 \
    -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=$cn" \
    -storepass:env AMAZCGM_GEN_PASS -keypass:env AMAZCGM_GEN_PASS

umask 077
cat > "$props" <<PROPS
storeFile=$keystore
storePassword=$pass
keyAlias=$alias_name
keyPassword=$pass
PROPS

echo
echo "Keystore:   $keystore"
echo "Config:     $props (git-ignored, readable only by you)"
echo "Certificate SHA-256:"
keytool -list -v -keystore "$keystore" -alias "$alias_name" -storepass:env AMAZCGM_GEN_PASS \
    | awk '/SHA256:/ { print "  " $2 }'
unset AMAZCGM_GEN_PASS
cat <<'MSG'

Back up the keystore file and its password offline now.
If you lose either, installed copies can never be updated again:
users would have to uninstall and lose their stored unlock codes.
Put the SHA-256 above in README.md so users can verify downloads.
MSG
