#!/bin/bash
# Builds Couchpotato as Release, signs it with the App Store Connect API key and uploads it to TestFlight.
# Secrets: APPLE_TEAM_ID, ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 (content of the .p8 file),
#          SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY (same as for the TV app). Adapted from Meeterz.
cd "$(dirname "$0")/.."
REPORT="${REPORT:-report.txt}"
: > "$REPORT"
log() { echo "$@" | tee -a "$REPORT"; }

for v in APPLE_TEAM_ID ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_P8; do
  [ -n "${!v}" ] || { log "!! Secret $v fehlt"; exit 20; }
done
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_PUBLISHABLE_KEY" ] || log "!! Supabase-Secrets fehlen – der Haushalts-Abgleich ist in diesem Build aus"

# Apple refuses builds made with a beta Xcode: those have a lower-case letter at the end of the build number
XCODE=""
for dir in $(ls -d /Applications/Xcode_2[7-9]* 2>/dev/null | grep -viE "beta|candidate|_RC" | sort -Vr); do
  build=$(DEVELOPER_DIR="$dir" xcodebuild -version 2>/dev/null | awk '/Build version/ {print $3}')
  if [ -n "$build" ] && ! echo "$build" | grep -q '[a-z]$'; then XCODE="$dir"; break; fi
done
[ -n "$XCODE" ] || { log "!! Keine Release-Xcode gefunden"; exit 10; }
sudo xcode-select -s "$XCODE"
xcodebuild -version 2>&1 | tee -a "$REPORT"
brew list xcodegen >/dev/null 2>&1 || brew install xcodegen 2>&1 | tail -2 | tee -a "$REPORT"

# build number = running number of the workflow (TestFlight wants a new one for every upload)
sed -i '' "s#CURRENT_PROJECT_VERSION: \"1\"#CURRENT_PROJECT_VERSION: \"${GITHUB_RUN_NUMBER:-1}\"#" project.yml

KEYDIR="$HOME/.appstoreconnect/private_keys"
mkdir -p "$KEYDIR"
printf "%s\n" "$ASC_KEY_P8" > "$KEYDIR/AuthKey_${ASC_KEY_ID}.p8"
export ASC_KEY_PATH="$KEYDIR/AuthKey_${ASC_KEY_ID}.p8"

mkdir -p build
WORK="$PWD/build/signing"
PROFILE_NAME="Couchpotato CI ${GITHUB_RUN_NUMBER:-0}-$(date +%s)"
KC="$PWD/build/ci.keychain-db"
trap 'security delete-keychain "$KC" >/dev/null 2>&1' EXIT

log "== Zertifikat und Profil =="
python3 ci/asc.py purge 2>&1 | tee -a "$REPORT"
python3 ci/asc.py create "$WORK" de.koeven.couchpotato "$PROFILE_NAME" 2>&1 | tee -a "$REPORT"
if [ ! -f "$WORK/ids.json" ]; then
  log "!! Zertifikat/Profil konnten nicht erzeugt werden (App-ID de.koeven.couchpotato angelegt?)"
  log "== Vorhandene Distributionszertifikate =="
  python3 ci/asc.py certs 2>&1 | tee -a "$REPORT"
  exit 33
fi
PROFILE_UUID=$(python3 -c "import json;print(json.load(open('$WORK/ids.json'))['uuid'])")

openssl x509 -inform DER -in "$WORK/cert.der" -out "$WORK/cert.pem"
openssl pkcs12 -export -inkey "$WORK/key.pem" -in "$WORK/cert.pem" -out "$WORK/sign.p12" -passout pass:ci -legacy >/dev/null 2>&1 \
  || openssl pkcs12 -export -inkey "$WORK/key.pem" -in "$WORK/cert.pem" -out "$WORK/sign.p12" -passout pass:ci
security create-keychain -p ci "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p ci "$KC"
for url in https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer https://www.apple.com/certificateauthority/AppleWWDRCAG6.cer; do
  curl -sfL "$url" -o "$WORK/ca.cer" && security import "$WORK/ca.cer" -k "$KC" -T /usr/bin/codesign >/dev/null 2>&1
done
security import "$WORK/sign.p12" -k "$KC" -P ci -T /usr/bin/codesign -T /usr/bin/security 2>&1 | tee -a "$REPORT"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k ci "$KC" >/dev/null 2>&1
security list-keychains -d user -s "$KC" $(security list-keychains -d user | tr -d '"')
for d in "$HOME/Library/MobileDevice/Provisioning Profiles" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
  mkdir -p "$d" && cp "$WORK/profile.mobileprovision" "$d/$PROFILE_UUID.mobileprovision"
done

python3 - "$PROFILE_NAME" <<'PY'
import sys
s = open("project.yml").read()
s = s.replace("CODE_SIGN_STYLE: Automatic",
              f'CODE_SIGN_STYLE: Manual\n        CODE_SIGN_IDENTITY: "Apple Distribution"\n        PROVISIONING_PROFILE_SPECIFIER: "{sys.argv[1]}"', 1)
open("project.yml", "w").write(s)
PY
xcodegen generate 2>&1 | tee -a "$REPORT"

log "== Archivieren =="
xcodebuild archive \
  -project Couchpotato.xcodeproj -scheme Couchpotato -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/Couchpotato.xcarchive \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
  CP_SUPABASE_URL="${SUPABASE_URL}" CP_SUPABASE_KEY="${SUPABASE_PUBLISHABLE_KEY}" > archive.log 2>&1
RES=$?
grep -E "error:|ARCHIVE (SUCCEEDED|FAILED)" archive.log | sort -u | head -30 | tee -a "$REPORT"
[ $RES -eq 0 ] || { tail -40 archive.log | tee -a "$REPORT"; log "!! Archivieren fehlgeschlagen ($RES)"; exit 30; }

cat > ExportOptions.plist <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>export</string>
  <key>teamID</key><string>${APPLE_TEAM_ID}</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key><dict><key>de.koeven.couchpotato</key><string>${PROFILE_NAME}</string></dict>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
PL

log "== Exportieren =="
xcodebuild -exportArchive -archivePath build/Couchpotato.xcarchive -exportOptionsPlist ExportOptions.plist -exportPath build/export > export.log 2>&1
RES=$?
grep -iE "error|EXPORT" export.log | sort -u | head -20 | tee -a "$REPORT"
[ $RES -eq 0 ] || { log "!! Export fehlgeschlagen ($RES)"; exit 31; }
IPA=$(ls build/export/*.ipa | head -1)

log "== Hochladen zu TestFlight =="
xcrun altool --upload-app -f "$IPA" -t ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" > upload.log 2>&1
RES=$?
tail -20 upload.log | tee -a "$REPORT"
[ $RES -eq 0 ] || { log "!! Upload fehlgeschlagen ($RES)"; exit 32; }
log "Hochgeladen: Version $(grep MARKETING_VERSION project.yml | head -1 | sed 's/.*: *//; s/\"//g') Build ${GITHUB_RUN_NUMBER:-1}"
