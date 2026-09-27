package com.btmicfix

import android.app.Application
import com.btmicfix.diagnostics.PassiveDiagnosticsManager

/** Process owner for the diagnostic-only passive observer. */
class BTMicFixApp : Application() {
    lateinit var passiveDiagnosticsManager: PassiveDiagnosticsManager
        private set

    override fun onCreate() {
        super.onCreate()
        passiveDiagnosticsManager = PassiveDiagnosticsManager(this)
    }
}
