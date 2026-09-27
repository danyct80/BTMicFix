package com.btmicfix;

interface IPrivilegedService {
    void destroy();
    String collectPassiveSnapshot(String label);
}
