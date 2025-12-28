package com.kevinluo.autoglm

import android.content.Context
import android.content.pm.PackageManager
import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.agent.AgentConfig
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.app.AppResolver
import com.kevinluo.autoglm.device.AccessibilityDeviceController
import com.kevinluo.autoglm.device.DeviceControlMode
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.device.IDeviceController
import com.kevinluo.autoglm.device.ShizukuDeviceController
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelConfig
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.settings.SettingsManager
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.util.HumanizedSwipeGenerator

/**
 * Centralized component manager for dependency injection and lifecycle management.
 * Provides a single point of access for all major components in the application.
 *
 * This class ensures:
 * - Proper dependency injection
 * - Lifecycle-aware component management
 * - Clean separation of concerns
 *
 * Requirements: All (integration)
 */
class ComponentManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ComponentManager"

        @Volatile
        private var instance: ComponentManager? = null

        /**
         * Gets the singleton instance of ComponentManager.
         *
         * @param context Application context
         * @return ComponentManager instance
         */
        fun getInstance(context: Context): ComponentManager {
            return instance ?: synchronized(this) {
                instance ?: ComponentManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Clears the singleton instance.
         * Should be called when the application is being destroyed.
         */
        fun clearInstance() {
            synchronized(this) {
                instance?.cleanup()
                instance = null
            }
        }
    }

    // Settings manager - always available
    val settingsManager: SettingsManager by lazy {
        SettingsManager(context)
    }

    // History manager - always available
    val historyManager: HistoryManager by lazy {
        HistoryManager.getInstance(context)
    }

    // User service reference - set when Shizuku connects
    private var userService: IUserService? = null

    // Device controller - initialized based on control mode
    private var _deviceController: IDeviceController? = null

    // Lazily initialized components that depend on UserService
    private var _deviceExecutor: DeviceExecutor? = null
    private var _textInputManager: TextInputManager? = null
    private var _screenshotService: ScreenshotService? = null
    private var _actionHandler: ActionHandler? = null
    private var _phoneAgent: PhoneAgent? = null

    // Components that don't depend on UserService
    private var _modelClient: ModelClient? = null
    private var _appResolver: AppResolver? = null
    private var _swipeGenerator: HumanizedSwipeGenerator? = null

    /**
     * Checks if the device controller is ready.
     * For Shizuku mode: checks if Shizuku service is connected
     * For Accessibility mode: checks if PhoneAgent is initialized
     */
    val isServiceConnected: Boolean
        get() {
            val controlMode = settingsManager.getDeviceControlMode()
            return when (controlMode) {
                DeviceControlMode.SHIZUKU -> userService != null
                DeviceControlMode.ACCESSIBILITY -> _phoneAgent != null
            }
        }

    /**
     * Gets the DeviceExecutor instance.
     * Requires UserService to be connected.
     */
    val deviceExecutor: DeviceExecutor?
        get() = _deviceExecutor

    /**
     * Gets the DeviceController instance.
     * Available in both Shizuku and Accessibility modes.
     */
    val deviceControllerInstance: IDeviceController?
        get() = _deviceController

    /**
     * Gets the ScreenshotService instance.
     * Requires UserService to be connected.
     */
    val screenshotService: ScreenshotService?
        get() = _screenshotService

    /**
     * Gets the ActionHandler instance.
     * Requires UserService to be connected.
     */
    val actionHandler: ActionHandler?
        get() = _actionHandler

    /**
     * Gets the PhoneAgent instance.
     * Requires UserService to be connected.
     */
    val phoneAgent: PhoneAgent?
        get() = _phoneAgent

    /**
     * Gets the ModelClient instance.
     * Creates a new instance if config has changed.
     */
    val modelClient: ModelClient
        get() {
            val config = settingsManager.getModelConfig()
            if (_modelClient == null || modelConfigChanged(config)) {
                _modelClient = ModelClient(config)
            }
            return _modelClient!!
        }

    /**
     * Gets the AppResolver instance.
     */
    val appResolver: AppResolver
        get() {
            if (_appResolver == null) {
                _appResolver = AppResolver(context.packageManager)
            }
            return _appResolver!!
        }

    /**
     * Gets the HumanizedSwipeGenerator instance.
     */
    val swipeGenerator: HumanizedSwipeGenerator
        get() {
            if (_swipeGenerator == null) {
                _swipeGenerator = HumanizedSwipeGenerator()
            }
            return _swipeGenerator!!
        }

    // Track current model config for change detection
    private var currentModelConfig: ModelConfig? = null

    /**
     * Called when UserService connects.
     * Initializes all service-dependent components for Shizuku mode.
     *
     * @param service The connected UserService
     */
    fun onServiceConnected(service: IUserService) {
        android.util.Log.i(TAG, "UserService connected, initializing components")
        userService = service

        // Only initialize Shizuku components if in Shizuku mode
        val controlMode = settingsManager.getDeviceControlMode()
        if (controlMode == DeviceControlMode.SHIZUKU) {
            initializeServiceDependentComponents()
        }
    }

    /**
     * Called when UserService disconnects.
     * Cleans up service-dependent components.
     */
    fun onServiceDisconnected() {
        android.util.Log.i(TAG, "UserService disconnected, cleaning up components")
        userService = null
        cleanupServiceDependentComponents()
    }

    /**
     * Initializes components that depend on UserService.
     */
    private fun initializeServiceDependentComponents() {
        val service = userService ?: return

        // Create DeviceExecutor
        _deviceExecutor = DeviceExecutor(service)

        // Create TextInputManager
        _textInputManager = TextInputManager(service)

        // Create ScreenshotService with floating window controller provider
        // Use a provider function so it can get the current instance dynamically
        _screenshotService = ScreenshotService(service) { FloatingWindowService.getInstance() }

        // Create ActionHandler with floating window provider to hide window during touch operations
        _actionHandler = ActionHandler(
            deviceExecutor = _deviceExecutor!!,
            appResolver = appResolver,
            swipeGenerator = swipeGenerator,
            textInputManager = _textInputManager!!,
            floatingWindowProvider = { FloatingWindowService.getInstance() }
        )

        // Create PhoneAgent
        val agentConfig = settingsManager.getAgentConfig()
        _phoneAgent = PhoneAgent(
            modelClient = modelClient,
            actionHandler = _actionHandler!!,
            screenshotService = _screenshotService!!,
            config = agentConfig,
            historyManager = historyManager
        )

        android.util.Log.i(TAG, "All service-dependent components initialized")
    }

    /**
     * Initializes components for accessibility mode.
     * Creates a stub device controller when Accessibility Service is available.
     */
    private fun initializeAccessibilityComponents() {
        // Create AccessibilityDeviceController
        _deviceController = AccessibilityDeviceController(context)

        // Create a stub UserService for accessibility mode
        val stubUserService = object : IUserService {
            override fun executeCommand(cmd: String): String = ""
            override fun destroy() {}
            override fun asBinder() = null
        }

        // Create DeviceExecutor with stub service (won't be actually used)
        _deviceExecutor = DeviceExecutor(stubUserService)

        // Create TextInputManager with stub service (won't be actually used)
        _textInputManager = TextInputManager(stubUserService)

        // Create ScreenshotService for accessibility mode
        _screenshotService = ScreenshotService(stubUserService) { FloatingWindowService.getInstance() }

        // Create ActionHandler for accessibility mode
        // The DeviceExecutor and TextInputManager instances exist but their actual methods
        // are not used in accessibility mode - all operations go through IDeviceController
        _actionHandler = ActionHandler(
            deviceExecutor = _deviceExecutor!!,
            appResolver = appResolver,
            swipeGenerator = swipeGenerator,
            textInputManager = _textInputManager!!,
            floatingWindowProvider = { FloatingWindowService.getInstance() }
        )

        // Create PhoneAgent
        val agentConfig = settingsManager.getAgentConfig()
        _phoneAgent = PhoneAgent(
            modelClient = modelClient,
            actionHandler = _actionHandler!!,
            screenshotService = _screenshotService!!,
            config = agentConfig,
            historyManager = historyManager
        )

        // Check permission status and log accordingly
        if (!_deviceController!!.checkPermission()) {
            android.util.Log.w(TAG, "Accessibility service not yet connected, but components initialized for deferred startup")
        } else {
            android.util.Log.i(TAG, "Accessibility mode components initialized with service connected")
        }
    }

    /**
     * Cleans up components that depend on UserService.
     */
    private fun cleanupServiceDependentComponents() {
        _phoneAgent?.cancel()
        _phoneAgent = null
        _actionHandler = null
        _screenshotService = null
        _textInputManager = null
        _deviceExecutor = null

        android.util.Log.i(TAG, "Service-dependent components cleaned up")
    }

    /**
     * Reinitializes the PhoneAgent with updated configuration.
     * Call this after settings have been changed.
     * Handles mode switching between Shizuku and Accessibility modes.
     */
    fun reinitializeAgent() {
        val controlMode = settingsManager.getDeviceControlMode()

        // Cancel any running task
        _phoneAgent?.cancel()

        // Recreate model client with new config
        _modelClient = null

        when (controlMode) {
            DeviceControlMode.SHIZUKU -> {
                if (userService == null) {
                    android.util.Log.w(TAG, "Cannot reinitialize agent: UserService not connected")
                    return
                }

                // Re-initialize Shizuku components
                cleanupServiceDependentComponents()
                initializeServiceDependentComponents()

                android.util.Log.i(TAG, "PhoneAgent reinitialized for Shizuku mode")
            }

            DeviceControlMode.ACCESSIBILITY -> {
                // Re-initialize accessibility components
                cleanupServiceDependentComponents()
                initializeAccessibilityComponents()

                android.util.Log.i(TAG, "PhoneAgent reinitialized for Accessibility mode")
            }
        }
    }

    /**
     * Sets the listener for PhoneAgent events.
     *
     * @param listener The listener to set
     */
    fun setPhoneAgentListener(listener: PhoneAgentListener?) {
        _phoneAgent?.setListener(listener)
    }

    /**
     * Sets the confirmation callback for ActionHandler.
     *
     * @param callback The callback to set
     */
    fun setConfirmationCallback(callback: ActionHandler.ConfirmationCallback?) {
        _actionHandler?.setConfirmationCallback(callback)
    }

    /**
     * Checks if the model config has changed.
     */
    private fun modelConfigChanged(newConfig: ModelConfig): Boolean {
        val changed = currentModelConfig != newConfig
        if (changed) {
            currentModelConfig = newConfig
        }
        return changed
    }

    /**
     * Cleans up all components.
     * Should be called when the application is being destroyed.
     */
    fun cleanup() {
        android.util.Log.i(TAG, "Cleaning up all components")
        cleanupServiceDependentComponents()
        _modelClient = null
        _appResolver = null
        _swipeGenerator = null
        currentModelConfig = null
    }

    /**
     * Gets the current state summary for debugging.
     */
    fun getStateSummary(): String {
        return buildString {
            appendLine("ComponentManager State:")
            appendLine("  - UserService connected: $isServiceConnected")
            appendLine("  - DeviceExecutor: ${if (_deviceExecutor != null) "initialized" else "null"}")
            appendLine("  - ScreenshotService: ${if (_screenshotService != null) "initialized" else "null"}")
            appendLine("  - ActionHandler: ${if (_actionHandler != null) "initialized" else "null"}")
            appendLine("  - PhoneAgent: ${if (_phoneAgent != null) "initialized" else "null"}")
            appendLine("  - ModelClient: ${if (_modelClient != null) "initialized" else "null"}")
            appendLine("  - AppResolver: ${if (_appResolver != null) "initialized" else "null"}")
            appendLine("  - SwipeGenerator: ${if (_swipeGenerator != null) "initialized" else "null"}")
        }
    }
}
