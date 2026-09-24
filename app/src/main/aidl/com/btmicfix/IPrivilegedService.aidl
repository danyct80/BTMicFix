// IPrivilegedService.aidl
// AIDL interface for the Shizuku UserService that runs with shell privileges.
package com.btmicfix;

interface IPrivilegedService {
    // Required by Shizuku for service lifecycle management
    void destroy() = 16777114;

    // Execute an audio diagnostic/routing command as shell user
    String executeAudioCommand(String command) = 1;

    // Get audio system diagnostics
    String getAudioDump() = 2;

    // Legacy generic force-use entry point kept for compatibility
    boolean forceAudioStrategy(int strategy, int deviceType) = 3;

    // Force Android's communication + record policies to Bluetooth SCO.
    // Returns a detailed diagnostic string instead of only true/false.
    String forceBluetoothSco() = 4;

    // Clear the privileged SCO force and return policy routing to AUTO/NONE.
    String clearForcedBluetoothSco() = 5;

    // Report which privileged mechanisms are available on this ROM.
    String getRoutingCapabilities() = 6;
}
