# BTMicFix — W622 / Bluetooth microphone diagnostic build

BTMicFix is an Android utility for selecting one explicit Bluetooth communication device, requesting microphone routing through it, and verifying the **real input used by Android**.

## This build

Version **0.6.5-final-audit**.

The app is designed around one user-selected priority device. It never falls back to the first Bluetooth device it finds. The Bluetooth list on the home screen is informational only.

### Unified diagnostic

The **Diagnostica completa** flow performs, in one run:

- confirmation of the real `AudioManager.communicationDevice`;
- `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, and `MIC` captures using the active Android route;
- the same three sources with `AudioRecord.setPreferredDevice()` when the target input can be identified safely;
- verification of `AudioRecord.routedDevice` while recording is active;
- real PCM level measurement with silence calibration, RMS/peak thresholds, and explicit PASS/NO_AUDIO/WRONG_DEVICE/INDETERMINATE results;
- optional Shizuku force-use verification, one real forced `VOICE_RECOGNITION` capture, and restoration of the original force-use policy.

Shizuku is optional. Core routing uses public Android audio APIs.

## Requirements

- Android 13 or newer (**API 33+**)
- Bluetooth communication device paired with the phone
- `BLUETOOTH_CONNECT` permission
- `RECORD_AUDIO` permission for microphone diagnostics
- Shizuku only for the optional privileged fallback/diagnostic

## Build

The repository includes a GitHub Actions workflow that builds the debug APK with JDK 17.

Local command:

```bash
./gradlew assembleDebug
```

The app module writes build output under `app/build_tmp/`.

## Safety / routing rules

- Priority identity uses Companion Device Manager association ID and Bluetooth address; friendly names are secondary hints only.
- No hardcoded headset/head-unit names are used.
- Microphone test functions do not call `AudioManager.setCommunicationDevice()` and do not invoke Shizuku themselves.
- If Android cannot prove which Bluetooth input was used, the test reports `INDETERMINATE` instead of a false PASS.
- Shizuku force-use state is snapshotted and restored; partial force operations attempt immediate rollback.
