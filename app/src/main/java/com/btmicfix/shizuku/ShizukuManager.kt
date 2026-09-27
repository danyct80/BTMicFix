package com.btmicfix.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.btmicfix.BuildConfig
import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Optional Shizuku integration. Core routing does not depend on it. */
class ShizukuManager {

    enum class ShizukuStatus {
        UNKNOWN,
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_NEEDED,
        READY,
    }

    enum class UserServiceState {
        DISCONNECTED,
        CONNECTING,
        READY,
        ERROR,
    }

    private val _status = MutableStateFlow(ShizukuStatus.UNKNOWN)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _serviceState = MutableStateFlow(UserServiceState.DISCONNECTED)
    val serviceState: StateFlow<UserServiceState> = _serviceState.asStateFlow()

    private val _lastForceResult = MutableStateFlow<String?>(null)
    val lastForceResult: StateFlow<String?> = _lastForceResult.asStateFlow()

    private var privilegedService: IPrivilegedService? = null
    private var bindingRequested = false
    @Volatile private var forcedScoApplied = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (_status.value == ShizukuStatus.READY) bindPrivilegedService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        privilegedService = null
        bindingRequested = false
        _serviceState.value = UserServiceState.DISCONNECTED
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.READY
                bindPrivilegedService()
            } else {
                _status.value = ShizukuStatus.PERMISSION_NEEDED
            }
        }

    fun initialize() {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshStatus()
            if (_status.value == ShizukuStatus.READY) bindPrivilegedService()
        } catch (e: Exception) {
            Logger.e("Failed to initialize Shizuku", e)
            _status.value = ShizukuStatus.NOT_INSTALLED
        }
    }

    fun cleanup() {
        clearForcedBluetoothScoIfApplied()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Logger.e("Error removing Shizuku listeners", e)
        }
        unbindPrivilegedService()
    }

    fun refreshStatus() {
        _status.value = try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.READY
            } else {
                ShizukuStatus.PERMISSION_NEEDED
            }
        } catch (e: Exception) {
            Logger.e("Error checking Shizuku", e)
            ShizukuStatus.NOT_INSTALLED
        }

        if (_status.value == ShizukuStatus.READY && privilegedService == null) {
            bindPrivilegedService()
        }
    }

    fun requestPermission() {
        if (_status.value == ShizukuStatus.NOT_RUNNING ||
            _status.value == ShizukuStatus.NOT_INSTALLED
        ) return
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Logger.e("Shizuku permission request failed", e)
        }
    }

    fun isAvailable(): Boolean = _status.value == ShizukuStatus.READY
    fun isServiceReady(): Boolean = _serviceState.value == UserServiceState.READY

    /** Wait for the privileged UserService so diagnostics never start on an unknown force-use state. */
    suspend fun awaitServiceReady(timeoutMs: Long = 5_000L): Boolean {
        refreshStatus()
        if (!isAvailable()) return false
        if (isServiceReady() && privilegedService?.asBinder()?.pingBinder() == true) return true

        bindPrivilegedService()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(250L)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isServiceReady() && privilegedService?.asBinder()?.pingBinder() == true) return true
            if (_serviceState.value == UserServiceState.ERROR || !isAvailable()) return false
            delay(100L)
        }
        return false
    }

    fun bindPrivilegedService() {
        if (!isAvailable() || privilegedService != null || bindingRequested) return
        val args = buildUserServiceArgs() ?: run {
            _serviceState.value = UserServiceState.ERROR
            return
        }
        try {
            bindingRequested = true
            _serviceState.value = UserServiceState.CONNECTING
            Shizuku.bindUserService(args, userServiceConnection)
        } catch (e: Exception) {
            bindingRequested = false
            _serviceState.value = UserServiceState.ERROR
            Logger.e("Failed to bind Shizuku UserService", e)
        }
    }

    fun unbindPrivilegedService() {
        if (!bindingRequested && privilegedService == null) return
        try {
            buildUserServiceArgs()?.let {
                Shizuku.unbindUserService(it, userServiceConnection, true)
            }
        } catch (_: Exception) {
        } finally {
            privilegedService = null
            bindingRequested = false
            _serviceState.value = UserServiceState.DISCONNECTED
        }
    }

    fun forceBluetoothSco(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            bindPrivilegedService()
            return "RESULT=FAILED\nServizio privilegiato non connesso"
        }
        return try {
            val result = service.forceBluetoothSco() ?: "RESULT=FAILED\nNessuna risposta"
            forcedScoApplied = result.contains("RESULT=OK", ignoreCase = true) ||
                result.contains("RESULT=FAILED_DIRTY", ignoreCase = true)
            _lastForceResult.value = result
            result
        } catch (e: Exception) {
            _serviceState.value = UserServiceState.ERROR
            val result = "RESULT=FAILED\n${e.javaClass.simpleName}: ${e.message}"
            _lastForceResult.value = result
            result
        }
    }

    fun clearForcedBluetoothSco(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            return "RESULT=CLEAR_FAILED\nServizio privilegiato non connesso"
        }
        return try {
            val result = service.clearForcedBluetoothSco() ?: "RESULT=CLEAR_FAILED\nNessuna risposta"
            if (result.contains("RESULT=CLEARED", ignoreCase = true)) {
                forcedScoApplied = false
            }
            _lastForceResult.value = result
            result
        } catch (e: Exception) {
            _serviceState.value = UserServiceState.ERROR
            val result = "RESULT=CLEAR_FAILED\n${e.javaClass.simpleName}: ${e.message}"
            _lastForceResult.value = result
            result
        }
    }

    fun clearForcedBluetoothScoIfApplied(): String {
        if (!forcedScoApplied) return "Nessuna forzatura SCO attiva"
        return clearForcedBluetoothSco()
    }

    fun isForcedBluetoothScoApplied(): Boolean = forcedScoApplied

    fun getRoutingCapabilities(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            bindPrivilegedService()
            return "Servizio privilegiato non ancora connesso"
        }
        return try {
            service.routingCapabilities ?: "Nessuna risposta"
        } catch (e: Exception) {
            "ERRORE: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun getAudioDiagnostics(): String? {
        val service = privilegedService ?: return null
        return try { service.audioDump } catch (_: Exception) { null }
    }

    fun clearLastForceResult() {
        _lastForceResult.value = null
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingRequested = false
            if (binder != null && binder.pingBinder()) {
                privilegedService = IPrivilegedService.Stub.asInterface(binder)
                _serviceState.value = UserServiceState.READY
            } else {
                privilegedService = null
                _serviceState.value = UserServiceState.ERROR
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            bindingRequested = false
            _serviceState.value = UserServiceState.DISCONNECTED
        }
    }

    private fun buildUserServiceArgs(): Shizuku.UserServiceArgs? = try {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivilegedServiceImpl::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    } catch (e: Exception) {
        Logger.e("Could not build Shizuku UserService args", e)
        null
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1337
    }
}
