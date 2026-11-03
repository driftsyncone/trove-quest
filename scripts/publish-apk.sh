#!/bin/bash
# Publishes a Trove build for the in-app updater: the APK + latest.json go to the Trove
# server's data dir on the server (DATA_DIR/apps/quest/), where GET /app/quest/latest.json offers it.
#
#   scripts/publish-apk.sh --dry-run        build, check and stage only (nothing leaves this PC)
#   scripts/publish-apk.sh                  build with versionCode + 1 and publish
#   scripts/publish-apk.sh --apk FILE       publish an existing APK (its versionCode must be higher)
#   scripts/publish-apk.sh --release        build the release variant (needs the release keystore)
#   scripts/publish-apk.sh --debug          build the old debuggable debug variant instead
#
# By default it builds the `publish` variant (assemblePublish -> app/build/outputs/apk/publish/
# app-publish.apk): not debuggable, no R8, signed with the same debug key as the earlier published
# debug builds, so it updates them in place.
#
# Versioning (README "Updates"): version.properties holds the last published versionCode and the
# versionName to publish. This script builds with versionCode + 1 and writes that number back after
# a successful publish. CHANGELOG.md must have a "## [<versionName>]" section: its text becomes the
# notes shown in the headset. No entry, no publish.
set -euo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=0
APK_IN=""
VARIANT=publish
KEEP=3 # APKs kept on the server (the new one included)
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --apk) APK_IN="$2"; shift ;;
    --release) VARIANT=release ;;
    --debug) VARIANT=debug ;;
    --keep) KEEP="$2"; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

SSH_KEY="${TROVE_SERVER_SSH_KEY:-$HOME/.ssh/id_ed25519}"
SERVER_HOST="${TROVE_SERVER_HOST:-root@your-server}"
TARGET="${TROVE_SERVER_APPS_DIR:-/srv/trove/data/apps/quest}"
SSH=(ssh -i "$SSH_KEY" -o IdentitiesOnly=yes "$SERVER_HOST")
SCP=(scp -q -i "$SSH_KEY" -o IdentitiesOnly=yes)
PACKAGE=app.trove.quest

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -x "$BUILD_TOOLS/aapt2" ] || { echo "aapt2 not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is needed" >&2; exit 1; }

die() { echo "error: $*" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" version.properties | tr -d '[:space:]'; }

LAST_CODE="$(prop versionCode)"
NAME="$(prop versionName)"
[[ "$LAST_CODE" =~ ^[0-9]+$ ]] || die "version.properties: versionCode is not a number"
[[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || die "version.properties: versionName '$NAME' is not semver (x.y.z)"

# ---- the APK -----------------------------------------------------------------------------------
if [ -n "$APK_IN" ]; then
  [ -f "$APK_IN" ] || die "no such file: $APK_IN"
  APK="$APK_IN"
else
  CODE=$((LAST_CODE + 1))
  if [ "$VARIANT" = release ] && [ -z "${TROVE_KEYSTORE:-}" ] && [ ! -f keystore.properties ]; then
    die "--release needs the release keystore (TROVE_KEYSTORE* env or keystore.properties, README \"Signing\")"
  fi
  # Check the notes before spending a minute on the build.
  grep -qE "^## \[?${NAME//./\\.}\]?([[:space:]]|$)" CHANGELOG.md || die "CHANGELOG.md has no '## [$NAME]' section"
  TASK="assemble${VARIANT^}"
  echo "Building $NAME (versionCode $CODE, $VARIANT)…"
  ./gradlew -q "$TASK" "-Ptrove.versionCode=$CODE" "-Ptrove.versionName=$NAME"
  APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
  [ -f "$APK" ] || die "build produced no $APK (unsigned release?)"
fi

BADGING_ALL="$("$BUILD_TOOLS/aapt2" dump badging "$APK")"
BADGING="$(sed -n "/^package:/p" <<<"$BADGING_ALL")"
APK_PACKAGE="$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")"
CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")"
APK_NAME_V="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")"
[ "$APK_PACKAGE" = "$PACKAGE" ] || die "APK package is '$APK_PACKAGE', expected $PACKAGE"
[ -n "$CODE" ] || die "could not read versionCode from the APK"
[ "$CODE" -gt "$LAST_CODE" ] || die "APK versionCode $CODE is not higher than the last published ($LAST_CODE in version.properties)"
NAME="$APK_NAME_V"

# Signer: updates only install over the same key.
SIGNER="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>/dev/null || true)"
# "Signer #1 certificate DN: …" or "V2 Signer: certificate DN: …" depending on build-tools.
SIGNER_DN="$(sed -n 's/^.*[Ss]igner.* certificate DN: //p' <<<"$SIGNER" | sed -n 1p)"
SIGNER_SHA="$(sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' <<<"$SIGNER" | sed -n 1p)"
[ -n "$SIGNER_SHA" ] || die "APK is not signed (or apksigner failed)"

# ---- notes from CHANGELOG.md ---------------------------------------------------------------------
NOTES="$(awk -v ver="$NAME" '
  /^## / {
    if (found) exit
    h = $0; sub(/^## \[?/, "", h); sub(/[] ].*$/, "", h)
    if (h == ver) { found = 1; next }
  }
  found { print }
' CHANGELOG.md | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"
[ -n "$(tr -d '[:space:]' <<<"$NOTES")" ] || die "CHANGELOG.md has no notes under '## [$NAME]' (every release needs user-facing notes)"

# ---- stage -------------------------------------------------------------------------------------
FILE="trove-$NAME-$CODE.apk"
STAGE="build/publish"
rm -rf "$STAGE" && mkdir -p "$STAGE"
cp "$APK" "$STAGE/$FILE"
SIZE="$(stat -c %s "$STAGE/$FILE")"
SHA="$(sha256sum "$STAGE/$FILE" | cut -d' ' -f1)"
jq -n --argjson code "$CODE" --arg name "$NAME" --argjson size "$SIZE" --arg sha "$SHA" --arg notes "$NOTES" \
  --arg file "$FILE" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg signer "$SIGNER_SHA" \
  '{versionCode: $code, versionName: $name, sizeBytes: $size, sha256: $sha, notes: $notes, file: $file,
    publishedAt: $at, signerSha256: $signer}' >"$STAGE/latest.json"

echo
echo "Trove $NAME (versionCode $CODE)"
echo "  APK     $STAGE/$FILE ($((SIZE / 1000000)) MB)"
echo "  sha256  $SHA"
echo "  signer  $SIGNER_DN"
echo "          $SIGNER_SHA"
[[ "$BADGING_ALL" == *application-debuggable* ]] && echo "  (debuggable build: slower; the publish variant is not debuggable)"
[[ "$SIGNER_DN" == *"Android Debug"* ]] && echo "  (debug key: only devices with a build from this PC's ~/.android/debug.keystore can update)"
echo "  notes:"
sed 's/^/    /' <<<"$NOTES"
echo

if [ "$DRY_RUN" = 1 ]; then
  echo "Dry run: would copy $FILE and latest.json to $SERVER_HOST:$TARGET/ (chown 1000:1000),"
  echo "keep the newest $KEEP APKs there and set versionCode=$CODE in version.properties."
  echo "latest.json:"
  sed 's/^/  /' "$STAGE/latest.json"
  exit 0
fi

# ---- publish -----------------------------------------------------------------------------------
REMOTE_CODE="$("${SSH[@]}" "cat '$TARGET/latest.json' 2>/dev/null" | jq -r '.versionCode // 0' 2>/dev/null || echo 0)"
[ "$CODE" -gt "${REMOTE_CODE:-0}" ] || die "the server already has versionCode $REMOTE_CODE; this build is $CODE"

echo "Copying to $SERVER_HOST:$TARGET/ …"
"${SSH[@]}" "mkdir -p '$TARGET'"
# APK first, then latest.json, each via a temp name + mv, so the app never sees a half-written file
# or a latest.json pointing at an APK that isn't there yet.
"${SCP[@]}" "$STAGE/$FILE" "$SERVER_HOST:$TARGET/.$FILE.tmp"
"${SCP[@]}" "$STAGE/latest.json" "$SERVER_HOST:$TARGET/.latest.json.tmp"
"${SSH[@]}" bash -s -- "$TARGET" "$FILE" "$KEEP" <<'REMOTE'
set -euo pipefail
dir=$1 file=$2 keep=$3
cd "$dir"
mv -f ".$file.tmp" "$file"
mv -f .latest.json.tmp latest.json
chown 1000:1000 . "$file" latest.json
# Keep the newest $keep APKs (by modification time); never the one just published.
# (grep finds nothing on the first publish, and ls fails once no trove-*.apk is left; with
# pipefail neither may abort the script: 0.4.3 published but stopped before version.properties.)
{ ls -t trove-*.apk 2>/dev/null || true; } | { grep -vxF "$file" || true; } | tail -n +"$keep" | xargs -r rm -f
ls -l "$dir"
REMOTE

sed -i "s/^versionCode=.*/versionCode=$CODE/" version.properties
echo
echo "Published $NAME ($CODE). version.properties now has versionCode=$CODE: commit it, e.g."
echo "  git commit -m \"Release $NAME\" version.properties CHANGELOG.md && git tag v$NAME"
