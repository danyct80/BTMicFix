#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
AIDL="$ROOT/app/src/main/aidl/com/btmicfix/IPrivilegedService.aidl"

fail() {
  echo "PASSIVE AUDIT FAIL: $1" >&2
  exit 1
}

# Forbidden mutating/capturing APIs must not exist anywhere in executable source.
for token in \
  'setCommunicationDevice(' \
  'clearCommunicationDevice(' \
  'startBluetoothSco(' \
  'stopBluetoothSco(' \
  'setPreferredDevice(' \
  'requestAudioFocus(' \
  'abandonAudioFocus(' \
  'AudioRecord(' \
  'AudioRecord.Builder' \
  'MediaRecorder(' \
  'setForceUse' \
  'setDevicesRoleForStrategy' \
  'clearDevicesRoleForStrategy'
do
  if grep -R -F -n "$token" "$SRC" >/tmp/btmicfix_audit_hits 2>/dev/null; then
    cat /tmp/btmicfix_audit_hits >&2
    fail "forbidden API token present: $token"
  fi
done

# Assignment/call patterns for AudioManager mode changes.
if grep -R -E -n 'audioManager[.]mode[[:space:]]*=|[.]setMode[[:space:]]*[(]' "$SRC" >/tmp/btmicfix_audit_hits 2>/dev/null; then
  cat /tmp/btmicfix_audit_hits >&2
  fail "audio mode mutation present"
fi

# Diagnostic-only build must not even request these permissions.
for permission in RECORD_AUDIO MODIFY_AUDIO_SETTINGS FOREGROUND_SERVICE REQUEST_COMPANION; do
  if grep -E "<uses-permission[^>]*android[.]permission[.]${permission}" "$MANIFEST" >/dev/null; then
    fail "forbidden manifest permission present: $permission"
  fi
done

# AIDL surface must stay read-only.
if grep -E 'force|clear|set|exclude|route|executeAudioCommand' "$AIDL" >/dev/null; then
  fail "AIDL exposes a mutating audio operation"
fi

# No Companion service or old routing classes may remain in this diagnostic branch.
if find "$SRC" -type f \( -name '*Routing*' -o -name '*Companion*' -o -name '*Exclusion*' \) | grep . >/dev/null; then
  find "$SRC" -type f \( -name '*Routing*' -o -name '*Companion*' -o -name '*Exclusion*' \) >&2
  fail "legacy active-routing source still present"
fi

echo "PASSIVE AUDIT PASS"
