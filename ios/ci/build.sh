#!/bin/bash
# Builds Couchpotato for the iOS simulator and runs the unit tests (no signing).
# Everything important goes to report.txt (published on the branch "ios-report").
cd "$(dirname "$0")/.."
REPORT="${REPORT:-report.txt}"
: > "$REPORT"
log() { echo "$@" | tee -a "$REPORT"; }

XCODE=$(ls -d /Applications/Xcode_2[7-9]* 2>/dev/null | grep -viE "beta|candidate|_RC" | sort -V | tail -1)
[ -n "$XCODE" ] && sudo xcode-select -s "$XCODE"
xcodebuild -version 2>&1 | tee -a "$REPORT"

brew list xcodegen >/dev/null 2>&1 || brew install xcodegen 2>&1 | tail -2 | tee -a "$REPORT"
xcodegen generate 2>&1 | tee -a "$REPORT"
[ -d Couchpotato.xcodeproj ] || { log "!! xcodegen hat kein Projekt erzeugt"; exit 12; }

SIM=$(xcrun simctl list devices available -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
best=[(k,x['udid']) for k,v in d.items() if 'iOS' in k for x in v if x['name'].startswith('iPhone')]
print(sorted(best)[-1][1] if best else '')")
log "Simulator: $SIM"

log ""
log "== Bauen und testen =="
xcodebuild test \
  -project Couchpotato.xcodeproj -scheme Couchpotato \
  -destination "id=$SIM" -derivedDataPath build/dd \
  CODE_SIGNING_ALLOWED=NO \
  CP_SUPABASE_URL="${SUPABASE_URL}" CP_SUPABASE_KEY="${SUPABASE_PUBLISHABLE_KEY}" > build.log 2>&1
RC=$?
log "xcodebuild: $RC"
grep -E "error:|Test Case .*(passed|failed)|Executed [0-9]+ test" build.log | sed 's#/Users/runner/work/[^/]*/[^/]*/##' | awk '!seen[$0]++' | head -120 | tee -a "$REPORT"
[ $RC -eq 0 ] || { log "== Letzte Zeilen =="; tail -25 build.log | tee -a "$REPORT"; }
log ""
log "== Warnungen (Auswahl) =="
grep -E "warning:" build.log | grep -v "/build/dd/" | sed 's#/Users/runner/work/[^/]*/[^/]*/##' | awk '!seen[$0]++' | head -30 >> "$REPORT"
exit $RC
