package com.kevinluo.autoglm

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.kevinluo.autoglm.accessibility.AutoGLMAccessibilityService
import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.device.DeviceControlMode
import com.kevinluo.autoglm.settings.SettingsActivity
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.Logger
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Main activity for the AutoGLM Phone Agent application.
 *
 * This activity serves as the primary entry point for the application,
 * providing the main user interface for:
 * - Shizuku permission management and service binding
 * - Task input and execution control
 * - Task status display and step tracking
 * - Navigation to settings and history
 * - Floating window management
 *
 * The activity implements [PhoneAgentListener] to receive callbacks
 * during task execution for UI updates.
 *
 * Requirements: 1.1, 2.1, 2.2
 */
class MainActivity : AppCompatActivity(), PhoneAgentListener {

    // Shizuku status views
    private lateinit var shizukuCard: View
    private lateinit var statusText: TextView
    private lateinit var shizukuStatusIndicator: View
    private lateinit var shizukuButtonsRow: View
    private lateinit var requestPermissionBtn: Button
    private lateinit var openShizukuBtn: Button
    private lateinit var settingsBtn: ImageButton

    // Overlay permission views
    private lateinit var overlayPermissionCard: View
    private lateinit var overlayStatusIcon: android.widget.ImageView
    private lateinit var overlayStatusText: TextView
    private lateinit var requestOverlayBtn: Button

    // Keyboard views
    private lateinit var keyboardCard: View
    private lateinit var keyboardStatusIcon: android.widget.ImageView
    private lateinit var keyboardStatusText: TextView
    private lateinit var enableKeyboardBtn: Button

    // Accessibility permission views
    private lateinit var accessibilityCard: View
    private lateinit var accessibilityStatusIcon: android.widget.ImageView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var requestAccessibilityBtn: Button

    // Task input views
    private lateinit var taskInputLayout: TextInputLayout
    private lateinit var taskInput: TextInputEditText
    private lateinit var startTaskBtn: MaterialButton
    private lateinit var cancelTaskBtn: MaterialButton
    private lateinit var btnSelectTemplate: ImageButton

    // Task status views
    private lateinit var taskStatusIndicator: View
    private lateinit var taskStatusText: TextView
    private lateinit var stepCounterText: TextView
    private lateinit var runningSection: View

    // Component manager for dependency injection
    private lateinit var componentManager: ComponentManager

    // Current step tracking for floating window
    private var currentStepNumber = 0
    private var currentThinking = ""

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            BuildConfig.APPLICATION_ID,
            UserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val userService = IUserService.Stub.asInterface(service)
            Logger.i(TAG, "UserService connected")

            // Notify ComponentManager
            componentManager.onServiceConnected(userService)

            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.toast_user_service_connected, Toast.LENGTH_SHORT).show()
                updateDeviceServiceStatus()
                initializePhoneAgent()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.i(TAG, "UserService disconnected")

            // Notify ComponentManager
            componentManager.onServiceDisconnected()

            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.toast_user_service_disconnected, Toast.LENGTH_SHORT).show()
                updateDeviceServiceStatus()
                updateTaskButtonStates()
            }
        }
    }

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    updateDeviceServiceStatus()
                    bindUserService()
                    Toast.makeText(this, R.string.toast_shizuku_permission_granted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.toast_shizuku_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateDeviceServiceStatus()
        if (hasShizukuPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.w(TAG, "Shizuku binder died")
        componentManager.onServiceDisconnected()
        updateDeviceServiceStatus()
        updateTaskButtonStates()
    }

    // Accessibility state change listener
    private val accessibilityStateChangeListener =
        AccessibilityManager.AccessibilityStateChangeListener { enabled ->
            Logger.d(TAG, "Accessibility state changed: enabled=$enabled")
            runOnUiThread {
                updateAccessibilityStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize ComponentManager
        componentManager = ComponentManager.getInstance(this)
        Logger.i(TAG, "ComponentManager initialized")

        // Log all launchable apps at startup
        logAllLaunchableApps()

        initViews()
        setupListeners()
        setupShizukuListeners()
        setupAccessibilityListener()

        updateDeviceServiceStatus()
        updateOverlayPermissionStatus()
        updateKeyboardStatus()
        updateAccessibilityStatus()
        updateTaskStatus(TaskStatus.IDLE)
    }

    /**
     * Logs all launchable apps for debugging purposes.
     *
     * Queries the package manager for all apps with launcher activities
     * and logs them for debugging. Only logs the first 20 apps to avoid
     * excessive log output.
     */
    private fun logAllLaunchableApps() {
        // Query apps without loading icons to avoid excessive logging
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList = packageManager.queryIntentActivities(intent, 0)

        val apps = resolveInfoList.mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val displayName = resolveInfo.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
            val packageName = activityInfo.packageName ?: return@mapNotNull null
            displayName to packageName
        }.distinctBy { it.second }

        Logger.i(TAG, "=== All Launchable Apps: ${apps.size} total ===")
        // Only log first 20 apps to avoid log quota
        apps.take(20).forEach { (name, pkg) ->
            Logger.i(TAG, "  $name -> $pkg")
        }
        if (apps.size > 20) {
            Logger.i(TAG, "  ... and ${apps.size - 20} more apps")
        }
        Logger.i(TAG, "=== End of App List ===")
    }

    /**
     * Updates the overlay permission status display.
     *
     * Checks if the app has overlay permission and updates the UI
     * to show the current status and appropriate action button.
     */
    private fun updateOverlayPermissionStatus() {
        val hasPermission = FloatingWindowService.canDrawOverlays(this)

        if (hasPermission) {
            overlayStatusText.text = getString(R.string.overlay_permission_granted)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
            requestOverlayBtn.visibility = View.GONE
        } else {
            overlayStatusText.text = getString(R.string.overlay_permission_denied)
            overlayStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
            requestOverlayBtn.visibility = View.VISIBLE
        }
    }

    /**
     * Updates the keyboard status display.
     *
     * Checks if AutoGLM Keyboard is enabled and updates the UI accordingly.
     */
    private fun updateKeyboardStatus() {
        val status = com.kevinluo.autoglm.input.KeyboardHelper.getAutoGLMKeyboardStatus(this)

        when (status) {
            com.kevinluo.autoglm.input.KeyboardHelper.KeyboardStatus.ENABLED -> {
                keyboardStatusText.text = getString(R.string.keyboard_settings_subtitle)
                keyboardStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
                enableKeyboardBtn.visibility = View.GONE
            }
            com.kevinluo.autoglm.input.KeyboardHelper.KeyboardStatus.NOT_ENABLED -> {
                keyboardStatusText.text = getString(R.string.keyboard_not_enabled)
                keyboardStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
                enableKeyboardBtn.visibility = View.VISIBLE
                enableKeyboardBtn.text = getString(R.string.enable_keyboard)
            }
        }
    }

    /**
     * Updates the accessibility service status display.
     *
     * Checks if AutoGLM Accessibility Service is enabled and updates the UI accordingly.
     * Also shows/hides relevant permission cards based on the device control mode.
     */
    private fun updateAccessibilityStatus() {
        val controlMode = componentManager.settingsManager.getDeviceControlMode()

        // Show/hide relevant cards based on control mode
        when (controlMode) {
            DeviceControlMode.SHIZUKU -> {
                // Shizuku mode: show Shizuku card and keyboard card, hide accessibility card
                shizukuCard.visibility = View.VISIBLE
                keyboardCard.visibility = View.VISIBLE
                accessibilityCard.visibility = View.GONE
            }
            DeviceControlMode.ACCESSIBILITY -> {
                // Accessibility mode: hide Shizuku card and keyboard card, show accessibility card
                shizukuCard.visibility = View.GONE
                keyboardCard.visibility = View.GONE
                accessibilityCard.visibility = View.VISIBLE

                // Update accessibility status
                val isAccessibilityEnabled = AutoGLMAccessibilityService.isEnabled()

                if (isAccessibilityEnabled) {
                    accessibilityStatusText.text = getString(R.string.accessibility_permission_granted)
                    accessibilityStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running))
                    requestAccessibilityBtn.visibility = View.GONE
                } else {
                    accessibilityStatusText.text = getString(R.string.accessibility_permission_denied)
                    accessibilityStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_waiting))
                    requestAccessibilityBtn.visibility = View.VISIBLE
                    requestAccessibilityBtn.text = getString(R.string.request_accessibility_permission)
                }

                // Check Android version for accessibility support
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    accessibilityStatusText.text = getString(R.string.accessibility_not_supported)
                    requestAccessibilityBtn.visibility = View.GONE
                }
            }
        }

        // Also update the main status display to reflect the current mode
        updateDeviceServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        Logger.d(TAG, "onResume - checking for settings changes")

        // Update overlay permission status (user may have granted it)
        updateOverlayPermissionStatus()

        // Update keyboard status (user may have enabled it)
        updateKeyboardStatus()

        // Update accessibility status (user may have enabled it or mode changed)
        updateAccessibilityStatus()

        // Re-setup floating window callbacks if service is running
        FloatingWindowService.getInstance()?.let { service ->
            service.setStopTaskCallback {
                Logger.d(TAG, "stopTaskCallback invoked from floating window (onResume)")
                cancelTask()
            }
            service.setStartTaskCallback { task ->
                startTaskFromFloatingWindow(task)
            }
            service.setResetAgentCallback {
                Logger.d(TAG, "resetAgentCallback invoked from floating window (onResume)")
                componentManager.phoneAgent?.reset()
            }
            service.setPauseTaskCallback {
                Logger.d(TAG, "pauseTaskCallback invoked from floating window (onResume)")
                pauseTask()
            }
            service.setResumeTaskCallback {
                Logger.d(TAG, "resumeTaskCallback invoked from floating window (onResume)")
                resumeTask()
            }
        }

        // Only reinitialize if service is connected and we need to refresh
        if (componentManager.isServiceConnected) {
            // Check if settings actually changed before reinitializing
            if (componentManager.settingsManager.hasConfigChanged()) {
                componentManager.reinitializeAgent()
            }
            componentManager.setPhoneAgentListener(this)
            setupConfirmationCallback()
            updateTaskButtonStates()
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy - cleaning up")
        super.onDestroy()

        // Remove Shizuku listeners
        Shizuku.removeRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        // Remove accessibility state change listener
        (getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.removeAccessibilityStateChangeListener(
            accessibilityStateChangeListener
        )

        // Cancel any running task
        componentManager.phoneAgent?.cancel()

        // Unbind user service
        if (componentManager.isServiceConnected) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (e: Exception) {
                Logger.e(TAG, "Error unbinding user service", e)
            }
        }

        // Note: Don't stop FloatingWindowService here - it should run independently
        // The service will be stopped when user explicitly closes it or the app process is killed
    }

    /**
     * Initializes all view references from the layout.
     *
     * Binds all UI components to their corresponding view IDs.
     */
    private fun initViews() {
        // Shizuku status views
        shizukuCard = findViewById(R.id.shizukuCard)
        statusText = findViewById(R.id.statusText)
        shizukuStatusIndicator = findViewById(R.id.shizukuStatusIndicator)
        shizukuButtonsRow = findViewById(R.id.shizukuButtonsRow)
        requestPermissionBtn = findViewById(R.id.requestPermissionBtn)
        openShizukuBtn = findViewById(R.id.openShizukuBtn)
        settingsBtn = findViewById(R.id.settingsBtn)

        // Overlay permission views
        overlayPermissionCard = findViewById(R.id.overlayPermissionCard)
        overlayStatusIcon = findViewById(R.id.overlayStatusIcon)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        requestOverlayBtn = findViewById(R.id.requestOverlayBtn)

        // Keyboard views
        keyboardCard = findViewById(R.id.keyboardCard)
        keyboardStatusIcon = findViewById(R.id.keyboardStatusIcon)
        keyboardStatusText = findViewById(R.id.keyboardStatusText)
        enableKeyboardBtn = findViewById(R.id.enableKeyboardBtn)

        // Accessibility permission views
        accessibilityCard = findViewById(R.id.accessibilityCard)
        accessibilityStatusIcon = findViewById(R.id.accessibilityStatusIcon)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        requestAccessibilityBtn = findViewById(R.id.requestAccessibilityBtn)

        // Task input views
        taskInputLayout = findViewById(R.id.taskInputLayout)
        taskInput = findViewById(R.id.taskInput)
        startTaskBtn = findViewById(R.id.startTaskBtn)
        cancelTaskBtn = findViewById(R.id.cancelTaskBtn)
        btnSelectTemplate = findViewById(R.id.btnSelectTemplate)

        // Task status views
        taskStatusIndicator = findViewById(R.id.taskStatusIndicator)
        taskStatusText = findViewById(R.id.taskStatusText)
        stepCounterText = findViewById(R.id.stepCounterText)
        runningSection = findViewById(R.id.runningSection)
    }

    /**
     * Sets up click listeners and text watchers for all interactive views.
     *
     * Configures button click handlers, navigation actions, and input monitoring.
     */
    private fun setupListeners() {
        // Shizuku permission button
        requestPermissionBtn.setOnClickListener {
            requestShizukuPermission()
        }

        // Open Shizuku button
        openShizukuBtn.setOnClickListener {
            openShizukuApp()
        }

        // Settings button - Requirements: 6.1
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // History button
        findViewById<View>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, com.kevinluo.autoglm.history.HistoryActivity::class.java))
        }

        // Floating window button - open floating window
        findViewById<ImageButton>(R.id.floatingWindowBtn).setOnClickListener {
            openFloatingWindow()
        }

        // Overlay permission button
        requestOverlayBtn.setOnClickListener {
            FloatingWindowService.requestOverlayPermission(this)
        }

        // Keyboard enable button
        enableKeyboardBtn.setOnClickListener {
            // Open input method settings to enable AutoGLM Keyboard
            com.kevinluo.autoglm.input.KeyboardHelper.openInputMethodSettings(this)
        }

        // Accessibility permission button
        requestAccessibilityBtn.setOnClickListener {
            // Open accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Start task button - Requirements: 1.1
        startTaskBtn.setOnClickListener {
            startTask()
        }

        // Cancel task button - Requirements: 1.4
        cancelTaskBtn.setOnClickListener {
            cancelTask()
        }

        // Select template button
        btnSelectTemplate.setOnClickListener {
            showTemplateSelectionDialog()
        }

        // Enable start button when task input has text
        taskInput.setOnFocusChangeListener { _, _ ->
            updateTaskButtonStates()
        }

        // Watch for text changes to enable/disable start button
        taskInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateTaskButtonStates()
            }
        })
    }

    /**
     * Opens the floating window for task input and control.
     *
     * Checks for overlay permission before starting the floating window service.
     * Sets up all necessary callbacks for task control and minimizes the app.
     */
    private fun openFloatingWindow() {
        if (!FloatingWindowService.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_permission_required, Toast.LENGTH_LONG).show()
            FloatingWindowService.requestOverlayPermission(this)
            return
        }

        // Start floating window service
        startService(Intent(this, FloatingWindowService::class.java))

        // Setup callbacks after service starts
        android.os.Handler(mainLooper).postDelayed({
            FloatingWindowService.getInstance()?.let { service ->
                service.setStopTaskCallback {
                    Logger.d(TAG, "stopTaskCallback invoked from floating window (openFloatingWindow)")
                    cancelTask()
                }
                service.setStartTaskCallback { task ->
                    startTaskFromFloatingWindow(task)
                }
                service.setResetAgentCallback {
                    Logger.d(TAG, "resetAgentCallback invoked from floating window (openFloatingWindow)")
                    componentManager.phoneAgent?.reset()
                }
                service.setPauseTaskCallback {
                    Logger.d(TAG, "pauseTaskCallback invoked from floating window (openFloatingWindow)")
                    pauseTask()
                }
                service.setResumeTaskCallback {
                    Logger.d(TAG, "resumeTaskCallback invoked from floating window (openFloatingWindow)")
                    resumeTask()
                }
                service.show()
            }
        }, 100)

        // Minimize app to background
        moveTaskToBack(true)
    }

    /**
     * Starts a task from the floating window input.
     *
     * Validates the agent state, resets if necessary, and launches
     * the task execution in a coroutine.
     *
     * @param taskDescription The task description entered in the floating window
     */
    private fun startTaskFromFloatingWindow(taskDescription: String) {
        val agent = componentManager.phoneAgent
        if (agent == null) {
            Logger.e(TAG, "PhoneAgent not initialized")
            FloatingWindowService.getInstance()?.showResult("Agent 未初始化", false)
            return
        }

        // Reset agent if it was previously cancelled or in a bad state
        if (agent.getState() != com.kevinluo.autoglm.agent.AgentState.IDLE) {
            Logger.d(TAG, "Agent not in IDLE state (${agent.getState()}), resetting...")
            agent.reset()
        }

        if (agent.isRunning()) {
            Logger.w(TAG, "Task already running")
            return
        }

        // Ensure stopTaskCallback is set
        FloatingWindowService.getInstance()?.setStopTaskCallback {
            Logger.d(TAG, "stopTaskCallback invoked from floating window (startTaskFromFloatingWindow)")
            cancelTask()
        }

        // Update floating window status
        FloatingWindowService.getInstance()?.updateStatus(TaskStatus.RUNNING)

        // Update main activity UI
        runOnUiThread {
            taskInput.setText(taskDescription)
            updateTaskStatus(TaskStatus.RUNNING)
            updateTaskButtonStates()
        }

        Logger.i(TAG, "Starting task from floating window: $taskDescription")

        // Run task in coroutine
        lifecycleScope.launch {
            try {
                agent.setListener(this@MainActivity)
                val result = agent.run(taskDescription)

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        updateTaskStatus(TaskStatus.COMPLETED)
                    } else {
                        updateTaskStatus(TaskStatus.FAILED)
                    }
                    updateTaskButtonStates()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Task execution error", e)
                withContext(Dispatchers.Main) {
                    updateTaskStatus(TaskStatus.FAILED)
                    updateTaskButtonStates()
                    FloatingWindowService.getInstance()?.showResult("错误: ${e.message}", false)
                }
            }
        }
    }

    /**
     * Sets up Shizuku event listeners.
     *
     * Registers listeners for permission results, binder received, and binder dead events.
     */
    private fun setupShizukuListeners() {
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    /**
     * Sets up accessibility state change listener.
     *
     * Registers listener to detect when accessibility service is enabled/disabled.
     */
    private fun setupAccessibilityListener() {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        accessibilityManager?.addAccessibilityStateChangeListener(accessibilityStateChangeListener)
    }

    /**
     * Opens the Shizuku app or Play Store if not installed.
     *
     * Attempts to launch Shizuku directly, or opens the Play Store
     * listing if Shizuku is not installed.
     */
    private fun openShizukuApp() {
        val shizukuPackage = "moe.shizuku.privileged.api"
        val launchIntent = packageManager.getLaunchIntentForPackage(shizukuPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            // Shizuku not installed, open Play Store
            try {
                val marketUri = android.net.Uri.parse("market://details?id=$shizukuPackage")
                startActivity(Intent(Intent.ACTION_VIEW, marketUri))
            } catch (e: Exception) {
                val webUri = android.net.Uri.parse(
                    "https://play.google.com/store/apps/details?id=$shizukuPackage"
                )
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        }
    }

    /**
     * Initializes the PhoneAgent with all required dependencies.
     *
     * Called after UserService is connected. Sets up the agent listener
     * and confirmation callback for sensitive operations.
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    private fun initializePhoneAgent() {
        if (!componentManager.isServiceConnected) {
            Logger.w(TAG, "Cannot initialize PhoneAgent: service not connected")
            return
        }

        // Set up listener
        componentManager.setPhoneAgentListener(this)

        // Setup confirmation callback
        setupConfirmationCallback()

        updateTaskButtonStates()
        Logger.i(TAG, "PhoneAgent initialized successfully")
    }

    /**
     * Sets up the confirmation callback for sensitive operations.
     *
     * Configures the ActionHandler to use the floating window for
     * user confirmations, takeover requests, and interaction options.
     */
    private fun setupConfirmationCallback() {
        componentManager.setConfirmationCallback(object : ActionHandler.ConfirmationCallback {
            override suspend fun onConfirmationRequired(message: String): Boolean {
                return withContext(Dispatchers.Main) {
                    // Use floating window for confirmation
                    var result = true
                    FloatingWindowService.getInstance()?.showConfirmation(message) { confirmed ->
                        result = confirmed
                    }
                    result
                }
            }

            override suspend fun onTakeOverRequested(message: String) {
                withContext(Dispatchers.Main) {
                    FloatingWindowService.getInstance()?.showTakeOver(message) {}
                }
            }

            override suspend fun onInteractionRequired(options: List<String>?): Int {
                return withContext(Dispatchers.Main) {
                    var selectedIndex = -1
                    options?.let {
                        FloatingWindowService.getInstance()?.showInteract(it) { index ->
                            selectedIndex = index
                        }
                    }
                    selectedIndex
                }
            }
        })
    }

    /**
     * Starts the task execution from the main activity input.
     *
     * Validates the task description, checks agent state, starts the
     * floating window service, and launches the task in a coroutine.
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    private fun startTask() {
        val taskDescription = taskInput.text?.toString()?.trim() ?: ""

        // Validate task description
        if (taskDescription.isBlank()) {
            Toast.makeText(this, R.string.toast_task_empty, Toast.LENGTH_SHORT).show()
            taskInputLayout.error = getString(R.string.toast_task_empty)
            return
        }

        taskInputLayout.error = null

        val agent = componentManager.phoneAgent
        if (agent == null) {
            Logger.e(TAG, "PhoneAgent not initialized")
            Toast.makeText(this, "Device controller not initialized. Please check your settings.", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if already running
        if (agent.isRunning()) {
            Logger.w(TAG, "Task already running")
            return
        }

        // Check device controller permissions
        val deviceController = componentManager.deviceControllerInstance
        if (deviceController != null && !deviceController.checkPermission()) {
            val mode = deviceController.getMode()
            val message = when (mode) {
                com.kevinluo.autoglm.device.DeviceControlMode.ACCESSIBILITY -> {
                    "Accessibility service not enabled. Please enable it in system settings."
                }
                com.kevinluo.autoglm.device.DeviceControlMode.SHIZUKU -> {
                    "Shizuku service not connected. Please ensure Shizuku is running."
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            deviceController.requestPermission(this)
            return
        }

        // Start floating window service if overlay permission granted
        if (FloatingWindowService.canDrawOverlays(this)) {
            Logger.d(TAG, "startTask: Starting floating window service")
            startService(Intent(this, FloatingWindowService::class.java))
        } else {
            Toast.makeText(this, R.string.toast_overlay_permission_required, Toast.LENGTH_LONG).show()
            FloatingWindowService.requestOverlayPermission(this)
            return
        }

        // Update UI state - manually set running state since agent.run() hasn't started yet
        updateTaskStatus(TaskStatus.RUNNING)
        // Manually update UI for running state
        startTaskBtn.visibility = View.GONE
        runningSection.visibility = View.VISIBLE
        cancelTaskBtn.isEnabled = true
        taskInput.isEnabled = false

        Logger.i(TAG, "Starting task: $taskDescription")

        // Run task in coroutine
        lifecycleScope.launch {
            // Set up callbacks immediately after service starts
            withContext(Dispatchers.Main) {
                // Wait a short time for service to initialize
                delay(100)
                val floatingWindow = FloatingWindowService.getInstance()
                Logger.d(TAG, "startTask: FloatingWindowService instance = $floatingWindow")
                floatingWindow?.setStopTaskCallback {
                    Logger.d(TAG, "stopTaskCallback invoked from floating window")
                    cancelTask()
                }
                floatingWindow?.setStartTaskCallback { task ->
                    startTaskFromFloatingWindow(task)
                }
                floatingWindow?.setResetAgentCallback {
                    Logger.d(TAG, "resetAgentCallback invoked from floating window (startTask)")
                    componentManager.phoneAgent?.reset()
                }
                floatingWindow?.setPauseTaskCallback {
                    Logger.d(TAG, "pauseTaskCallback invoked from floating window (startTask)")
                    pauseTask()
                }
                floatingWindow?.setResumeTaskCallback {
                    Logger.d(TAG, "resumeTaskCallback invoked from floating window (startTask)")
                    resumeTask()
                }
                Logger.d(TAG, "startTask: Calling updateStatus(RUNNING) on floating window")
                floatingWindow?.updateStatus(TaskStatus.RUNNING)
                floatingWindow?.show()
            }

            // Minimize app to let agent work
            withContext(Dispatchers.Main) {
                moveTaskToBack(true)
            }

            try {
                val result = agent.run(taskDescription)

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        Logger.i(TAG, "Task completed: ${result.message}")
                        updateTaskStatus(TaskStatus.COMPLETED)
                    } else {
                        Logger.w(TAG, "Task failed: ${result.message}")
                        updateTaskStatus(TaskStatus.FAILED)
                    }
                    updateTaskButtonStates()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Task error", e)
                withContext(Dispatchers.Main) {
                    updateTaskStatus(TaskStatus.FAILED)
                    updateTaskButtonStates()
                }
            }
        }
    }

    /**
     * Cancels the currently running task.
     *
     * Cancels the agent, resets its state, and updates the UI
     * to reflect the cancelled status.
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    private fun cancelTask() {
        Logger.i(TAG, "Cancelling task")

        // Cancel the agent - this will cancel any ongoing network requests
        componentManager.phoneAgent?.cancel()

        // Reset the agent state so it can accept new tasks
        componentManager.phoneAgent?.reset()

        Toast.makeText(this, R.string.toast_task_cancelled, Toast.LENGTH_SHORT).show()
        updateTaskStatus(TaskStatus.FAILED)
        updateTaskButtonStates()

        // Update floating window to show cancelled state
        // Use the same message as PhoneAgent for consistency
        val cancellationMessage = PhoneAgent.CANCELLATION_MESSAGE
        FloatingWindowService.getInstance()?.showResult(cancellationMessage, false)
    }

    /**
     * Pauses the currently running task.
     *
     * Requests the agent to pause and updates the UI status accordingly.
     */
    private fun pauseTask() {
        Logger.i(TAG, "Pausing task")

        val paused = componentManager.phoneAgent?.pause() == true
        if (paused) {
            updateTaskStatus(TaskStatus.PAUSED)
            FloatingWindowService.getInstance()?.updateStatus(TaskStatus.PAUSED)
        }
    }

    /**
     * Resumes a paused task.
     *
     * Requests the agent to resume and updates the UI status accordingly.
     */
    private fun resumeTask() {
        Logger.i(TAG, "Resuming task")

        val resumed = componentManager.phoneAgent?.resume() == true
        if (resumed) {
            updateTaskStatus(TaskStatus.RUNNING)
            FloatingWindowService.getInstance()?.updateStatus(TaskStatus.RUNNING)
        }
    }

    /**
     * Updates the task button states based on current conditions.
     *
     * Enables or disables buttons based on service connection status,
     * agent availability, task input, and running state.
     */
    private fun updateTaskButtonStates() {
        val hasService = componentManager.isServiceConnected
        val hasAgent = componentManager.phoneAgent != null
        val hasTaskText = !taskInput.text.isNullOrBlank()
        val isRunning = componentManager.phoneAgent?.isRunning() == true

        // Show/hide sections based on running state
        startTaskBtn.visibility = if (isRunning) View.GONE else View.VISIBLE
        runningSection.visibility = if (isRunning) View.VISIBLE else View.GONE

        startTaskBtn.isEnabled = hasService && hasAgent && hasTaskText && !isRunning
        cancelTaskBtn.isEnabled = isRunning
        taskInput.isEnabled = !isRunning

        Logger.d(
            TAG,
            "Button states updated: service=$hasService, agent=$hasAgent, " +
                "text=$hasTaskText, running=$isRunning"
        )
    }

    /**
     * Updates the task status display.
     *
     * Updates the status text, indicator color, and floating window
     * to reflect the current task status.
     *
     * @param status The new task status to display
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    private fun updateTaskStatus(status: TaskStatus) {
        val (text, colorRes) = when (status) {
            TaskStatus.IDLE -> getString(R.string.task_status_idle) to R.color.status_idle
            TaskStatus.RUNNING -> getString(R.string.task_status_running) to R.color.status_running
            TaskStatus.PAUSED -> getString(R.string.task_status_paused) to R.color.status_paused
            TaskStatus.COMPLETED -> getString(R.string.task_status_completed) to R.color.status_completed
            TaskStatus.FAILED -> getString(R.string.task_status_failed) to R.color.status_failed
            TaskStatus.WAITING_CONFIRMATION -> "等待确认" to R.color.status_waiting
            TaskStatus.WAITING_TAKEOVER -> "等待接管" to R.color.status_waiting
        }

        taskStatusText.text = text

        val drawable = taskStatusIndicator.background as? GradientDrawable
            ?: GradientDrawable().also { taskStatusIndicator.background = it }
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(ContextCompat.getColor(this, colorRes))

        // Also update floating window
        FloatingWindowService.getInstance()?.updateStatus(status)
    }

    // region PhoneAgentListener Implementation

    /**
     * Called when a step starts.
     *
     * Updates the step counter and floating window with the new step number.
     *
     * @param stepNumber The current step number
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onStepStarted(stepNumber: Int) {
        runOnUiThread {
            currentStepNumber = stepNumber
            currentThinking = ""
            stepCounterText.text = getString(R.string.step_counter_format, stepNumber)
            FloatingWindowService.getInstance()?.updateStepNumber(stepNumber)
        }
    }

    /**
     * Called when thinking is updated.
     *
     * Stores the current thinking text for display in the floating window.
     *
     * @param thinking The model's thinking text
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onThinkingUpdate(thinking: String) {
        runOnUiThread {
            currentThinking = thinking
        }
    }

    /**
     * Called when an action is executed.
     *
     * Adds the step to the floating window waterfall display.
     *
     * @param action The action that was executed
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onActionExecuted(action: AgentAction) {
        runOnUiThread {
            // Add step to floating window waterfall
            FloatingWindowService.getInstance()?.addStep(currentStepNumber, currentThinking, action)
        }
    }

    /**
     * Called when task completes successfully.
     *
     * Updates the UI to show completion status and displays the result
     * in the floating window.
     *
     * @param message The completion message
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onTaskCompleted(message: String) {
        runOnUiThread {
            updateTaskStatus(TaskStatus.COMPLETED)
            FloatingWindowService.getInstance()?.showResult(message, true)
            updateTaskButtonStates()
            // Keep floating window open for user to see the result
        }
    }

    /**
     * Called when task fails.
     *
     * Updates the UI to show failure status and displays the error
     * in the floating window.
     *
     * @param error The error message
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onTaskFailed(error: String) {
        runOnUiThread {
            updateTaskStatus(TaskStatus.FAILED)
            FloatingWindowService.getInstance()?.showResult(error, false)
            updateTaskButtonStates()
            // Keep floating window open for user to see the error
        }
    }

    /**
     * Called when screenshot capture starts.
     *
     * Note: Floating window hide is handled by ScreenshotService.
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onScreenshotStarted() {
        // Floating window hide is handled by ScreenshotService
    }

    /**
     * Called when screenshot capture completes.
     *
     * Note: Floating window show is handled by ScreenshotService.
     *
     * Requirements: 1.1, 2.1, 2.2
     */
    override fun onScreenshotCompleted() {
        // Floating window show is handled by ScreenshotService
    }

    /**
     * Called when floating window needs to be refreshed.
     *
     * This happens after launching another app to ensure the overlay stays visible.
     */
    override fun onFloatingWindowRefreshNeeded() {
        Logger.d(TAG, "onFloatingWindowRefreshNeeded called")
        runOnUiThread {
            FloatingWindowService.getInstance()?.bringToFront()
        }
    }

    // endregion

    // region Device Service Status Methods

    /**
     * Updates the device service status display based on current control mode.
     *
     * For Shizuku mode: shows Shizuku connection status.
     * For Accessibility mode: shows Accessibility service status.
     */
    private fun updateDeviceServiceStatus() {
        val controlMode = componentManager.settingsManager.getDeviceControlMode()

        when (controlMode) {
            DeviceControlMode.SHIZUKU -> updateShizukuStatusDisplay()
            DeviceControlMode.ACCESSIBILITY -> updateAccessibilityStatusDisplay()
        }
    }

    /**
     * Updates the Shizuku connection status display.
     *
     * Checks Shizuku binder status, permission, and service connection,
     * then updates the UI to reflect the current state.
     */
    private fun updateShizukuStatusDisplay() {
        val isBinderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

        val hasPermission = hasShizukuPermission()
        val serviceConnected = componentManager.isServiceConnected

        val statusMessage = when {
            !isBinderAlive -> getString(R.string.shizuku_status_not_running)
            !hasPermission -> getString(R.string.shizuku_status_no_permission)
            !serviceConnected -> getString(R.string.shizuku_status_connecting)
            else -> getString(R.string.shizuku_status_connected)
        }

        val statusColor = when {
            !isBinderAlive -> R.color.status_failed
            !hasPermission -> R.color.status_waiting
            !serviceConnected -> R.color.status_waiting
            else -> R.color.status_running
        }

        runOnUiThread {
            statusText.text = statusMessage
            shizukuStatusIndicator.background.setTint(getColor(statusColor))

            // Show buttons based on Shizuku state
            if (serviceConnected) {
                // Connected - hide buttons row
                shizukuButtonsRow.visibility = View.GONE
            } else {
                // Not connected - show buttons row
                shizukuButtonsRow.visibility = View.VISIBLE
                // Open Shizuku button - always visible when not connected
                openShizukuBtn.visibility = View.VISIBLE
                // Permission button - always visible, but disabled when Shizuku not running
                requestPermissionBtn.visibility = View.VISIBLE
                requestPermissionBtn.isEnabled = isBinderAlive
            }

            updateTaskButtonStates()
        }
    }

    /**
     * Updates the Accessibility service status display.
     *
     * Shows the accessibility service connection status in the same location
     * where Shizuku status is normally displayed.
     */
    private fun updateAccessibilityStatusDisplay() {
        val isAccessibilityEnabled = AutoGLMAccessibilityService.isEnabled()
        val isAndroidSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val statusMessage = when {
            !isAndroidSupported -> getString(R.string.accessibility_not_supported)
            !isAccessibilityEnabled -> getString(R.string.accessibility_permission_denied)
            else -> getString(R.string.accessibility_permission_granted)
        }

        val statusColor = when {
            !isAndroidSupported -> R.color.status_failed
            !isAccessibilityEnabled -> R.color.status_waiting
            else -> R.color.status_running
        }

        runOnUiThread {
            statusText.text = statusMessage
            shizukuStatusIndicator.background.setTint(getColor(statusColor))

            // Hide Shizuku buttons in accessibility mode
            shizukuButtonsRow.visibility = View.GONE

            updateTaskButtonStates()
        }
    }

    /**
     * Checks if Shizuku permission is granted.
     *
     * @return true if Shizuku is running and permission is granted, false otherwise
     */
    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Requests Shizuku permission from the user.
     *
     * Validates Shizuku state before requesting permission and handles
     * various edge cases like Shizuku not running or old version.
     */
    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.toast_shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }

        if (Shizuku.isPreV11()) {
            Toast.makeText(this, R.string.toast_shizuku_version_low, Toast.LENGTH_LONG).show()
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_shizuku_already_granted, Toast.LENGTH_SHORT).show()
            bindUserService()
            return
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(this, R.string.toast_shizuku_grant_in_app, Toast.LENGTH_LONG).show()
            return
        }

        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    /**
     * Binds the Shizuku user service.
     *
     * Attempts to bind the user service if Shizuku permission is granted.
     */
    private fun bindUserService() {
        if (!hasShizukuPermission()) return
        try {
            Logger.i(TAG, "Binding user service")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to bind service", e)
        }
    }

    // endregion

    // region Helper Methods

    /**
     * Formats an agent action for display.
     *
     * @param action The agent action to format
     * @return A human-readable string representation of the action
     */
    private fun formatAction(action: AgentAction): String = action.formatForDisplay()

    // endregion

    // region Task Templates

    /**
     * Shows a dialog to select a task template.
     *
     * Displays available task templates from settings and allows
     * the user to select one to populate the task input field.
     */
    private fun showTemplateSelectionDialog() {
        val templates = componentManager.settingsManager.getTaskTemplates()

        if (templates.isEmpty()) {
            Toast.makeText(this, R.string.settings_no_templates, Toast.LENGTH_SHORT).show()
            // Offer to go to settings to add templates
            AlertDialog.Builder(this)
                .setTitle(R.string.task_select_template)
                .setMessage(R.string.settings_no_templates)
                .setPositiveButton(R.string.settings_title) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }

        val templateNames = templates.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.task_select_template)
            .setItems(templateNames) { _, which ->
                val selectedTemplate = templates[which]
                taskInput.setText(selectedTemplate.description)
                updateTaskButtonStates()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
    }
}
