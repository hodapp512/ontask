#!/bin/bash
# Publishes the Android app where its updater finds it: builds the release APK (signed with the key in
# ~/.ontask-android), puts it and version.json in the web app under /android/, and deploys hosting.
# Usage: android/release.sh "What's new (optional)"
set -euo pipefail
cd "$(dirname "$0")"
git diff --quiet HEAD -- . ../shared || { echo "Commit the Android changes first (the version number is the commit count)."; exit 1; }
./gradlew assembleRelease -q
CODE=$(git rev-list --count HEAD); NAME="0.1.$CODE"
mkdir -p ../web/public/android
cp app/build/outputs/apk/release/app-release.apk ../web/public/android/ontask.apk
python3 -c 'import json,sys; print(json.dumps({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "notes": sys.argv[3]}))' "$CODE" "$NAME" "${1:-}" > ../web/public/android/version.json
(cd ../web && npm run build >/dev/null)
(cd ../firebase && npx firebase deploy --only hosting --project ontask-ed2df >/dev/null)
echo "Published OnTask for Android $NAME"
