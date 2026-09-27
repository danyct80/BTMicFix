package com.btmicfix;

interface IPrivilegedService {
    void destroy() = 16777114;
    String executeAudioCommand(String command) = 1;
    String getAudioDump() = 2;
    boolean forceAudioStrategy(int strategy, int deviceType) = 3;
    String forceBluetoothSco() = 4;
    String clearForcedBluetoothSco() = 5;
    String getRoutingCapabilities() = 6;
}
