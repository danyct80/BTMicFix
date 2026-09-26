package com.btmicfix.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.btmicfix.BuildConfig
import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Manages Shizuku availability, permission and the privileged UserService.
 *
 * The previous implementation could show "Shizuku ready" while the UserService was not
 * actually bound, and the normal routing path never invoked the privileged fallback.
 * This version exposes the real service state and provides explicit force/clear methods.
 */
class ShizukuManager {

    private val _status = MutableStateFlow(ShizukuStatus.UNKNOWN)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _serviceState = MutableStateFlow(UserServiceState.DISCONNECTED)
    val serviceState: StateFlow<UserServiceState> = _serviceState.asStateFlow()

    private val _lastForceResult = MutableStateFlow<String?>(null)
    val lastForceResult: StateFlow<String?> = _lastForceResult.asStateFlow()

    private var privilegedService: IPrivilegedService? = null
    private var bindingRequested = false

    enum class ShizukuStatus {
        UNKNOWN,
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_NEEDED,
        READY,
    }

    enum class UserServiceState {
        DISCONNECTED,
        BINDING,
        READY,
        ERROR,
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Logger.i("Shizuku binder received")
        refreshStatus()
        ensurePrivilegedServiceBound()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.w("Shizuku binder died")
        privilegedService = null
        bindingRequested = false
        _serviceState.value = UserServiceState.DISCONNECTED
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Logger.i("Shizuku permission granted")
                _status.value = ShizukuStatus.READY
                ensurePrivilegedServiceBound()
            } else {
                Logger.w("Shizuku permission denied")
                _status.value = ShizukuStatus.PERMISSION_NEEDED
            }
        }

    fun initialize() {
        Logger.i("Initializing ShizukuManager")
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshStatus()
            ensurePrivilegedServiceBound()
        } catch (e: Exception) {
            Logger.e("Failed to initialize Shizuku listeners", e)
            _status.value = ShizukuStatus.NOT_INSTALLED
            _serviceState.value = UserServiceState.ERROR
        }
    }

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Logger.e("Error cleaning up Shizuku listeners", e)
        }
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
            Logger.e("Error checking Shizuku status", e)
            ShizukuStatus.NOT_INSTALLED
        }

        if (_status.value == ShizukuStatus.READY) ensurePrivilegedServiceBound()
    }

    fun requestPermission() {
        if (_status.value == ShizukuStatus.NOT_INSTALLED ||
            _status.value == ShizukuStatus.NOT_RUNNING
        ) {
            Logger.w("Cannot request permission — Shizuku not available")
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Logger.e("Error requesting Shizuku permission", e)
        }
    }

    fun isAvailable(): Boolean = _status.value == ShizukuStatus.READY
    fun isPrivilegedServiceReady(): Boolean = privilegedService?.asBinder()?.pingBinder() == true

    /**
     * Bind proactively. Safe to call repeatedly.
     */
    fun ensurePrivilegedServiceBound() {
        if (!isAvailable()) return
        if (isPrivilegedServiceReady()) {
            _serviceState.value = UserServiceState.READY
            return
        }
        if (bindingRequested) return

        try {
            val args = buildUserServiceArgs() ?: run {
                _serviceState.value = UserServiceState.ERROR
                return
            }
            bindingRequested = true
            _serviceState.value = UserServiceState.BINDING
            Shizuku.bindUserService(args, userServiceConnection)
            Logger.i("Binding to PrivilegedService via Shizuku")
        } catch (e: Exception) {
            bindingRequested = false
            _serviceState.value = UserServiceState.ERROR
            Logger.e("Failed to bind PrivilegedService", e)
        }
    }

    fun bindPrivilegedService() = ensurePrivilegedServiceBound()

    fun unbindPrivilegedService() {
        try {
            val args = buildUserServiceArgs() ?: return
            Shizuku.unbindUserService(args, userServiceConnection, true)
        } catch (_: Exception) {
            // May not be bound.
        }
        privilegedService = null
        bindingRequested = false
        _serviceState.value = UserServiceState.DISCONNECTED
    }

    /**
     * ACTUAL privileged Android Auto fallback.
     * This is intentionally explicit instead of being silently advertised as active.
     */
    fun forceBluetoothSco(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            ensurePrivilegedServiceBound()
            val msg = "Shizuku pronto, ma il servizio privilegiato non è ancora connesso. Riprova tra un secondo."
            _lastForceResult.value = msg
            return msg
        }

        return try {
            val result = service.forceBluetoothSco() ?: "Nessuna risposta dal servizio privilegiato"
            _lastForceResult.value = result
            Logger.i("Shizuku SCO force result:\n$result")
            result
        } catch (e: Exception) {
            _serviceState.value = UserServiceState.ERROR
            val result = "ERRORE Shizuku: ${e.javaClass.simpleName}: ${e.message}"
            _lastForceResult.value = result
            Logger.e("forceBluetoothSco failed", e)
            result
        }
    }

    fun clearLastForceResult() {
        _lastForceResult.value = null
    }

    fun clearForcedBluetoothSco(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            return "Servizio privilegiato non connesso"
        }
        return try {
            val result = service.clearForcedBluetoothSco() ?: "Nessuna risposta"
            _lastForceResult.value = result
            result
        } catch (e: Exception) {
            "ERRORE clear Shizuku: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun getRoutingCapabilities(): String {
        val service = privilegedService
        if (service == null || !service.asBinder().pingBinder()) {
            ensurePrivilegedServiceBound()
            return "Servizio privilegiato non ancora connesso"
        }
        return try {
            service.routingCapabilities ?: "Nessuna risposta"
        } catch (e: Exception) {
            "ERRORE: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun executeShellCommand(command: String): String? {
        val service = privilegedService ?: run {
            ensurePrivilegedServiceBound()
            return null
        }
        return try {
            service.executeAudioCommand(command)
        } catch (e: Exception) {
            Logger.e("Shell command exception: $command", e)
            null
        }
    }

    fun getAudioDiagnostics(): String? {
        val service = privilegedService ?: return null
        return try {
            service.audioDump
        } catch (e: Exception) {
            Logger.e("Error getting audio diagnostics", e)
            null
        }
    }

    private fun buildUserServiceArgs(): Shizuku.UserServiceArgs? {
        return try {
            Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    PrivilegedServiceImpl::class.java.name,
                )
            )
                .daemon(true)
                .processNameSuffix("privileged")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        } catch (e: Exception) {
            Logger.e("Could not build Shizuku UserService args", e)
            null
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingRequested = false
            if (binder != null && binder.pingBinder()) {
                privilegedService = IPrivilegedService.Stub.asInterface(binder)
                _serviceState.value = UserServiceState.READY
                Logger.i("PrivilegedService connected")
            } else {
                privilegedService = null
                _serviceState.value = UserServiceState.ERROR
                Logger.e("PrivilegedService connection returned invalid binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            bindingRequested = false
            _serviceState.value = UserServiceState.DISCONNECTED
            Logger.w("PrivilegedService disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            privilegedService = null
            bindingRequested = false
            _serviceState.value = UserServiceState.ERROR
            Logger.w("PrivilegedService binding died")
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1337
    }
}
