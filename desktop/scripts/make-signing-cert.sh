#!/bin/bash
# Creates a self-signed code-signing certificate, "OnTask Local Signing", in your login keychain. Run once.
# Builds then sign OnTask with it automatically, so macOS keeps Accessibility and Screen Recording permissions across
# rebuilds (it remembers them by certificate instead of by exact build). Nothing leaves this Mac; to undo, delete
# "OnTask Local Signing" in Keychain Access.
set -euo pipefail
NAME="OnTask Local Signing"
if security find-identity -p codesigning | grep -q "\"$NAME\""; then echo "Already set up: $NAME"; exit 0; fi
DIR="$(mktemp -d)"; trap 'rm -rf "$DIR"' EXIT
cat > "$DIR/cert.cnf" <<CNF
[req]
distinguished_name = dn
prompt = no
x509_extensions = ext
[dn]
CN = $NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout "$DIR/key.pem" -out "$DIR/cert.pem" -config "$DIR/cert.cnf" 2>/dev/null
PASS="$(/usr/bin/openssl rand -hex 16)"
/usr/bin/openssl pkcs12 -export -inkey "$DIR/key.pem" -in "$DIR/cert.pem" -name "$NAME" -out "$DIR/cert.p12" -passout "pass:$PASS" 2>/dev/null
# -T lets codesign use the key without asking every build.
security import "$DIR/cert.p12" -k "$HOME/Library/Keychains/login.keychain-db" -P "$PASS" -T /usr/bin/codesign
echo "Created \"$NAME\". Rebuild OnTask, grant Accessibility once more, and it will stick from then on."
