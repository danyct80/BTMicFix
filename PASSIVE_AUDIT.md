# Passive architecture audit — 0.9.0

The 0.9 branch is intentionally a diagnostic-only build.

## Enforced invariants

- No microphone capture object is created by BTMicFix.
- No communication device is selected or cleared.
- No Bluetooth SCO session is started/stopped by BTMicFix.
- No audio focus is requested/abandoned.
- No AudioManager mode is modified.
- No AudioPolicy / force-use / device-role mutation is exposed through Shizuku.
- No CompanionDeviceService or automatic routing service is present.
- Manifest requests neither microphone capture nor audio-routing permissions.
- Shizuku surface is read-only and only returns filtered dumpsys snapshots.

`tools/audit_passive.sh` checks these invariants and CI runs it before unit tests and APK assembly.

## Observation timing logic

- BASELINE: 0–3 seconds.
- GEMINI: 3–15 seconds.
- RECOVERY: 15–20 seconds.
- COMPLETE: >=20 seconds.

The public API sampler runs every ~100 ms, logs state changes immediately and adds a heartbeat every ~1 second. Read-only privileged snapshots are scheduled at baseline, Gemini onset, Gemini mid-window and recovery.
