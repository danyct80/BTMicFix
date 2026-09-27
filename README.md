# BTMicFix 0.7.0 — Logic Core

BTMicFix is an Android utility for selecting one explicit Bluetooth communication device and verifying the real microphone input used by Android.

## Routing architecture in 0.7.0

The 0.7 branch changes the routing model completely:

- one process-wide `AudioRoutingManager` is shared by the Activity and CompanionDeviceService;
- BTMicFix places **one** `AudioManager.setCommunicationDevice()` request;
- BTMicFix **never calls `AudioManager.setMode()`**;
- there is **no route-hold loop**, timer reassertion, or routing reaction to audio-mode changes;
- phone calls, VoIP apps and voice assistants are allowed to take temporary priority;
- when another audio owner takes the communication route, BTMicFix reports `Yielded` and does nothing;
- when Android gives the route back, BTMicFix observes it and returns to `Active` without another request;
- explicit disable/configuration changes are the only normal operations that call `clearCommunicationDevice()`;
- a physical target disconnect clears only BTMicFix's logical request state after a debounced Companion Device callback.

This follows Android's communication-device arbitration model: simultaneous requests are prioritized by the application controlling the audio mode. BTMicFix intentionally does not try to become that owner.

## Logic tests

`RoutingPolicy` is a pure Kotlin policy layer with unit tests for:

- one-shot user activation;
- auto-route only on device appearance/app resume;
- no reassertion after communication-device changes;
- no reassertion after audio-mode changes;
- no timer-based route hold;
- assistant/phone takeover represented as `Yielded`;
- route return represented as `Active` without issuing another request;
- auto-route disabled behavior;
- disconnected-target behavior.

GitHub Actions runs `testDebugUnitTest` before building the APK.

## Unified microphone diagnostic

The complete diagnostic performs:

- one-shot route request if no request already exists;
- real `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, and `MIC` captures;
- each source once using the active Android route and once with `AudioRecord.setPreferredDevice()`;
- verification of `AudioRecord.routedDevice` while recording;
- real PCM RMS/peak analysis with silence calibration and conservative identity checks;
- optional Shizuku force-use diagnostic isolated from normal routing;
- guaranteed Shizuku cleanup;
- restoration of the pre-diagnostic BTMicFix request state.

Shizuku is **never used by normal routing**.

## Requirements

- Android 13+ (API 33+)
- Bluetooth communication device paired with the phone
- `BLUETOOTH_CONNECT`
- `RECORD_AUDIO` for diagnostics
- Shizuku only for optional privileged diagnostics

## Build

GitHub Actions uses JDK 17 and runs:

```bash
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

Build output is under `app/build_tmp/`.


## 0.8.0 - Inverse Exclusion Probe

Adds a temporary Shizuku diagnostic that does the opposite of positive routing: it attempts to mark a selected head unit DEVICE_ROLE_DISABLED only for AudioProductStrategy entries matching VOICE_COMMUNICATION and ASSISTANT. The test never targets media strategies, snapshots any pre-existing disabled-role lists, runs for 20 seconds, and restores the exact previous policy in finally.
