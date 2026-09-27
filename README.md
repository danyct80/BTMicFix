# BTMicFix 0.9.0 — Passive Observer

This branch is intentionally diagnostic-only. It does **not** route, capture, force or exclude audio.

## What the 20-second probe does

- 0–3 s: baseline, do nothing.
- 3–15 s: press the Cardo voice button and speak to Gemini normally while Android Auto is active.
- 15–20 s: release everything and wait.

During the window the app only reads/listens to:

- `AudioManager.mode`
- current and available communication devices
- all visible audio input/output devices
- active recording configurations (`source`, effective source, device, silenced state, formats)
- audio-device, recording, communication-device and mode callbacks
- current Wi-Fi/cellular/network transports
- Bluetooth profile connection states (when BLUETOOTH_CONNECT is granted)
- optional **read-only** Shizuku snapshots from `dumpsys audio`, `dumpsys media.audio_policy` and `dumpsys bluetooth_manager`

It never opens `AudioRecord`, never requests `RECORD_AUDIO`, never calls `setCommunicationDevice`, never changes `AudioManager.mode`, never requests audio focus and never changes AudioPolicy.

## Safety / logic gate

`tools/audit_passive.sh` fails CI if a mutating/capturing audio API or the old routing/companion architecture reappears. GitHub Actions runs this audit before unit tests and APK compilation.

After the run, use **COPIA REPORT COMPLETO** and paste the report into the ChatGPT conversation.
