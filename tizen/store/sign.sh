#!/usr/bin/env bash
# Signs the newest Samsung build with your Samsung certificate profile.
#   ./sign.sh                → couchpotato-signed.wgt (upload it in the Seller Office)
#   ./sign.sh 192.168.1.50   → also installs it on that TV (developer mode on, this computer's IP entered there)
# Needs Tizen Studio with the Samsung Certificate Extension and a certificate profile (default name: couchpotato).
set -euo pipefail
PROFILE="${CP_PROFILE:-couchpotato}"
TZ_HOME="${TIZEN_STUDIO:-$HOME/tizen-studio}"
TIZEN="$TZ_HOME/tools/ide/bin/tizen"
SDB="$TZ_HOME/tools/sdb"
[ -x "$TIZEN" ] || { echo "Tizen Studio nicht gefunden unter $TZ_HOME (TIZEN_STUDIO=… setzen)"; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
url="$(curl -fsSL "https://api.github.com/repos/GianlucaKo95/Couchpotato/releases?per_page=50" |
  python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin) if x["tag_name"].startswith("tizen-")]; print(r[0]["assets"][0]["browser_download_url"])')"
echo "Lade $url"
curl -fsSL -o "$work/couchpotato.wgt" "$url"

echo "Signiere mit Profil „$PROFILE“ …"
"$TIZEN" package -t wgt -s "$PROFILE" -- "$work/couchpotato.wgt"
cp "$work/couchpotato.wgt" ./couchpotato-signed.wgt
echo "Fertig: $(pwd)/couchpotato-signed.wgt"

if [ $# -ge 1 ]; then
  "$SDB" connect "$1"
  device="$("$SDB" devices | awk -v ip="$1" 'NR > 1 && index($1, ip) == 1 { print $3; exit }')"
  [ -n "$device" ] || { echo "Fernseher $1 nicht verbunden – Entwicklermodus und IP prüfen"; exit 1; }
  "$TIZEN" install -n couchpotato-signed.wgt -t "$device"
  echo "Installiert auf $device"
fi
