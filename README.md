# BTMicFix

**Fix Bluetooth earbuds microphone routing for AI voice apps on Android.**

AI voice apps like ChatGPT, Claude, and Gemini often fail to switch your Bluetooth earbuds from music mode (A2DP) to hands-free mode (SCO/HFP), causing them to use your phone's built-in microphone instead of your earbuds' mic. BTMicFix forces this switch automatically.

## How It Works

BTMicFix uses Android's `setCommunicationDevice()` API (Android 12+) to force the system to route communication audio through your Bluetooth earbuds. This triggers the A2DP → SCO/HFP profile switch that AI apps fail to perform on their own.

### Three Layers

| Layer | Purpose | Requires |
|---|---|---|
| **Audio Routing** (primary) | Forces BT mic via `setCommunicationDevice()` | Nothing — public API |
| **Background Service** | Auto-activates when earbuds connect via Companion Device Manager | One-time pairing in app |
| **Shizuku Fallback** (optional) | Privileged shell commands for stubborn devices | [Shizuku](https://shizuku.rikka.app/) installed |

## Requirements

- Android 12+ (API 31+)
- Bluetooth earbuds paired with your device
- No root required

## Building

```bash
# Clone the repo
git clone https://github.com/Endda/btmicfix.git
cd btmicfix

# Build the debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Install** the APK on your Android device
2. **Grant Bluetooth permission** when prompted
3. **Pair your earbuds** in the Setup wizard (for automatic background mode)
4. **Tap "Enable Routing"** on the home screen
5. **Open your AI app** — your earbuds' microphone should now work

For automatic background mode, complete the pairing step. BTMicFix will then activate whenever your earbuds connect, even without opening the app.

## Shizuku (Optional)

Some devices or Android versions may not respond to the standard `setCommunicationDevice()` API. For these cases, BTMicFix can optionally use [Shizuku](https://shizuku.rikka.app/) to execute privileged audio routing commands. This is **not required** for most users.

## Project Structure

```
app/src/main/java/com/btmicfix/
├── BTMicFixApp.kt              # Application class
├── MainActivity.kt             # Single activity entry point
├── audio/
│   ├── AudioRoutingManager.kt  # Core routing logic (setCommunicationDevice)
│   └── BluetoothStateReceiver.kt # BT connect/disconnect listener
├── companion/
│   ├── DeviceCompanionManager.kt # CDM association management
│   └── BTCompanionService.kt    # Background auto-routing service
├── shizuku/
│   ├── ShizukuManager.kt       # Shizuku lifecycle & permissions
│   └── PrivilegedServiceImpl.kt # Privileged command execution
├── ui/
│   ├── components/             # Reusable Compose components
│   ├── screens/                # HomeScreen, SetupScreen
│   └── theme/                  # Material 3 dark theme
└── util/
    ├── Preferences.kt          # SharedPreferences wrapper
    └── Logger.kt               # Centralized logging
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Audio:** AudioManager (API 31+)
- **Background:** CompanionDeviceManager + CompanionDeviceService
- **Optional:** Shizuku API 13.1.5

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

## License

```
Copyright 2026 BTMicFix Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Android Auto / W622 experimental Shizuku fallback (0.2.0-aa)

This fork adds an explicit privileged fallback for the motorcycle Android Auto case where
`setCommunicationDevice()` reports success but Gemini/Assistant still loses the Bluetooth mic.

### What changed

- Shizuku now binds a real UserService and reports whether that service is actually connected.
- Added **FORZA CARDO ORA (SHIZUKU)**:
  1. re-asserts the selected Bluetooth SCO communication device;
  2. attempts to force Android audio policy `FOR_COMMUNICATION` and `FOR_RECORD` to `FORCE_BT_SCO`.
- Added **LOCK ROUTING PER 30 SECONDI** to re-assert both routes every 500 ms while Android Auto
  may be stealing the route during Assistant activation.
- Privileged routing reports exactly which mechanism succeeded or failed instead of showing a
  misleading "fallback active" state.
- The privileged service first tries hidden `AudioSystem.setForceUse()` from the Shizuku shell
  process, then falls back to `cmd audio set-force-use` only when the ROM exposes that command.
- Added policy verification from `dumpsys audio` / `dumpsys media.audio_policy`.

### Recommended test sequence

1. Start Shizuku and grant BTMicFix permission.
2. Connect Android Auto to the W622.
3. Connect the Cardo to the phone.
4. In BTMicFix select/route to the Cardo (BT SCO).
5. Confirm the Shizuku card says **servizio privilegiato connesso**.
6. Tap **LOCK ROUTING PER 30 SECONDI**.
7. During those 30 seconds invoke Gemini/Assistant from the Cardo and speak into the Cardo mic.
8. Read the diagnostic result shown in BTMicFix. If it says the ROM blocks both reflection and
   `cmd audio set-force-use`, the limitation is below the normal app/Shizuku routing layer.

This is experimental and intentionally does not use root.


### Multi-source microphone diagnostics
The W622/Android Auto diagnostic fork can test the Bluetooth microphone independently with `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, and `MIC`. Each test reports the requested Bluetooth input, the actual `AudioRecord.routedDevice`, and PCM activity without saving audio.
