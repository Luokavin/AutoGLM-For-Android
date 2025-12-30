package com.kevinluo.autoglm.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.MainActivity
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务状态枚举
 * 用于跟踪和显示 Agent 任务的执行状态。
 */
enum class TaskStatus {
    /** 没有任务正在运行 */
    IDLE,
    /** 任务正在执行中 */
    RUNNING,
    /** 任务已被用户暂停 */
    PAUSED,
    /** 任务已成功完成 */
    COMPLETED,
    /** 任务因错误而失败 */
    FAILED,
    /** 等待用户确认以继续 */
    WAITING_CONFIRMATION,
    /** 等待用户接管控制 */
    WAITING_TAKEOVER
}

/**
 * 悬浮窗瀑布流显示的单步数据类
 *
 * @property stepNumber 任务执行中的步骤序号
 * @property thinking 模型对该步骤的思考/推理文本
 * @property action 该步骤执行的操作
 */
data class FloatingStep(
    val stepNumber: Int,
    val thinking: String,
    val action: String
)

/**
 * 管理任务执行悬浮窗的前台服务。
 *
 * 该服务提供了一个悬浮窗口界面，允许用户：
 * - 输入并启动新任务
 * - 以瀑布流方式查看实时任务执行进度
 * - 控制任务执行（暂停、继续、停止）
 * - 查看任务完成状态和结果
 *
 * 悬浮窗可以被拖动、最小化以及按需隐藏/显示。
 * 它实现了 [FloatingWindowController] 接口，允许其他组件控制窗口的可见性。
 */
class FloatingWindowService : Service(), FloatingWindowController, PhoneAgentListener {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isAttached = AtomicBoolean(false)
    private var isMinimized = false
    private var currentStepNumber = 0
    private var currentStatus = TaskStatus.IDLE
    private var latestActionPreview: String? = null
    private var thinkingAnimator: ObjectAnimator? = null
    private var thinkingDotsJob: Job? = null
    private var thinkingDotsAnimator: AnimatorSet? = null

    private var stopTaskCallback: (() -> Unit)? = null
    private var startTaskCallback: ((String) -> Unit)? = null
    private var resetAgentCallback: (() -> Unit)? = null
    private var pauseTaskCallback: (() -> Unit)? = null
    private var resumeTaskCallback: (() -> Unit)? = null
    
    // Coroutine scope for UI operations - uses SupervisorJob so child failures don't cancel siblings
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Steps list for waterfall display
    private val stepsList = mutableListOf<FloatingStep>()
    private var stepsAdapter: StepsAdapter? = null

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "floating_window"
        private const val NOTIFICATION_ID = 1001
        // Window size as percentage of screen
        private const val WIDTH_PERCENT = 0.80f
        private const val HEIGHT_PERCENT = 0.60f

        @Volatile
        private var instance: FloatingWindowService? = null

        fun getInstance(): FloatingWindowService? = instance

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestOverlayPermission(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        Logger.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        // Only create the window view, don't show it automatically
        // Window will be shown when show() is called explicitly
        if (floatingView == null && canDrawOverlays(this)) {
            createWindowView()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.d(TAG, "Service destroying")
        stopThinkingAnimation()
        instance = null
        // Cancel all coroutines
        serviceScope.cancel()
        // Clear callbacks to prevent memory leaks
        stopTaskCallback = null
        startTaskCallback = null
        resetAgentCallback = null
        removeWindow()
        super.onDestroy()
    }

    // ==================== FloatingWindowController ====================

    private fun runOnMainSync(timeoutMs: Long = 500L, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Hides the floating window from the screen.
     *
     * Clears input focus and hides the keyboard before removing the window.
     *
     */
    override fun hide() {
        runOnMainSync {
            Logger.d(TAG, "hide() called, isAttached=${isAttached.get()}")
            if (isAttached.get()) {
                // Clear focus and hide keyboard before hiding window
                clearInputFocus()
                removeWindowInternal()
            }
        }
    }
    
    /**
     * Clears input focus and hides keyboard.
     *
     * After clearing focus, adds FLAG_NOT_FOCUSABLE so back key works in other apps.
     */
    private fun clearInputFocus() {
        val taskInput = floatingView?.findViewById<EditText>(R.id.task_input) ?: return
        
        if (!taskInput.hasFocus()) {
            return
        }
        
        Logger.d(TAG, "clearInputFocus: clearing focus and hiding keyboard")
        
        // Hide keyboard first
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(taskInput.windowToken, 0)
        
        // Clear focus
        taskInput.clearFocus()
        
        // Add FLAG_NOT_FOCUSABLE so back key works in other apps
        layoutParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (isAttached.get()) {
                try {
                    windowManager?.updateViewLayout(floatingView, params)
                    Logger.d(TAG, "clearInputFocus: added FLAG_NOT_FOCUSABLE")
                } catch (e: Exception) {
                    Logger.e(TAG, "Error updating layout after clearing focus", e)
                }
            }
        }
    }

    /**
     * Shows the floating window on the screen.
     *
     * Creates the window view if not already created, then adds it to the window manager.
     *
     */
    override fun show() {
        runOnMainSync {
            Logger.d(TAG, "show() called, isAttached=${isAttached.get()}, floatingView=${floatingView != null}")
            
            // Create window view if not created yet
            if (floatingView == null) {
                createWindowView()
            }
            
            if (!isAttached.get() && floatingView != null) {
                addWindowInternal()
            }
        }
    }

    /**
     * Shows the floating window and brings it to the front.
     *
     * If the window is already attached, removes and re-adds it to ensure it's on top.
     *
     */
    override fun showAndBringToFront() {
        runOnMainSync {
            val attached = isAttached.get()
            val hasView = floatingView != null
            Logger.d(TAG, "showAndBringToFront() called, isAttached=$attached, floatingView=$hasView")
            
            // Create window view if not created yet
            if (floatingView == null) {
                createWindowView()
            }
            
            if (floatingView != null) {
                if (isAttached.get()) {
                    removeWindowInternal()
                }
                addWindowInternal()
            }
        }
    }

    /**
     * Checks if the floating window is currently visible.
     *
     * @return true if the window is attached and visible, false otherwise
     *
     */
    override fun isVisible(): Boolean = isAttached.get()

    // ==================== Public Methods ====================

    /**
     * Sets the callback for starting a task from the floating window.
     *
     * @param callback Function to be called with the task description when user starts a task
     *
     */
    fun setStartTaskCallback(callback: (String) -> Unit) {
        startTaskCallback = callback
    }
    
    /**
     * Sets the callback for resetting the agent before starting a new task.
     *
     * @param callback Function to be called to reset the agent state
     *
     */
    fun setResetAgentCallback(callback: () -> Unit) {
        resetAgentCallback = callback
    }

    /**
     * Adds a new step to the waterfall display.
     *
     * @param stepNumber The sequential number of this step
     * @param thinking The model's reasoning text for this step
     * @param action The action being performed, or null if no action
     *
     */
    fun addStep(stepNumber: Int, thinking: String, action: AgentAction?) {
        serviceScope.launch {
            val step = FloatingStep(
                stepNumber = stepNumber,
                thinking = thinking,
                action = action?.formatForDisplay() ?: "无"
            )
            stepsList.add(step)
            stepsAdapter?.notifyItemInserted(stepsList.size - 1)

            if (action != null) {
                updateActionPreview(step.action)
            }

            // Scroll to bottom
            floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)?.let { rv ->
                rv.scrollToPosition(stepsList.size - 1)
            }

            // Update step counter
            currentStepNumber = stepNumber
            floatingView?.findViewById<TextView>(R.id.tv_step_counter)?.text =
                getString(R.string.step_counter_format, stepNumber)
        }
    }

    /**
     * Updates the thinking text for the current (last) step.
     *
     * @param thinking The new thinking text to display
     *
     */
    fun updateThinking(thinking: String) {
        serviceScope.launch {
            if (stepsList.isNotEmpty()) {
                val lastIndex = stepsList.size - 1
                stepsList[lastIndex] = stepsList[lastIndex].copy(thinking = thinking)
                stepsAdapter?.notifyItemChanged(lastIndex)
            }
        }
    }

    /**
     * Updates the action for the current (last) step.
     *
     * @param action The new action to display
     *
     */
    fun updateAction(action: AgentAction) {
        serviceScope.launch {
            if (stepsList.isNotEmpty()) {
                val lastIndex = stepsList.size - 1
                stepsList[lastIndex] = stepsList[lastIndex].copy(action = action.formatForDisplay())
                stepsAdapter?.notifyItemChanged(lastIndex)
                updateActionPreview(stepsList[lastIndex].action)
            }
        }
    }

    /**
     * Updates the task status and refreshes the UI accordingly.
     *
     * @param status The new task status to display
     *
     */
    fun updateStatus(status: TaskStatus) {
        Logger.d(TAG, "updateStatus called with status: $status")
        serviceScope.launch {
            Logger.d(TAG, "updateStatus serviceScope.launch executing, status: $status, floatingView: $floatingView")
            currentStatus = status
            
            // If switching to RUNNING, clear previous steps
            if (status == TaskStatus.RUNNING) {
                stepsList.clear()
                stepsAdapter?.notifyDataSetChanged()
                currentStepNumber = 0
                floatingView?.let { view ->
                    view.findViewById<TextView>(R.id.tv_result)?.visibility = View.GONE
                    view.findViewById<TextView>(R.id.tv_step_counter)?.text =
                        getString(R.string.step_counter_default)
                }
            }
            
            floatingView?.let { view ->
                val statusText = view.findViewById<TextView>(R.id.tv_status)
                val indicator = view.findViewById<View>(R.id.status_indicator)

                val (textRes, colorRes) = when (status) {
                    TaskStatus.IDLE -> R.string.task_status_idle to R.color.status_idle
                    TaskStatus.RUNNING -> R.string.task_status_running to R.color.status_running
                    TaskStatus.PAUSED -> R.string.task_status_paused to R.color.status_paused
                    TaskStatus.COMPLETED -> R.string.task_status_completed to R.color.status_completed
                    TaskStatus.FAILED -> R.string.task_status_failed to R.color.status_failed
                    TaskStatus.WAITING_CONFIRMATION -> R.string.floating_waiting_confirm to R.color.status_waiting
                    TaskStatus.WAITING_TAKEOVER -> R.string.takeover_title to R.color.status_waiting
                }

                statusText?.text = getString(textRes)
                indicator?.let {
                    val drawable = (it.background as? GradientDrawable)
                        ?: GradientDrawable().also { d -> it.background = d }
                    drawable.shape = GradientDrawable.OVAL
                    drawable.setColor(ContextCompat.getColor(this@FloatingWindowService, colorRes))
                }

                if (status == TaskStatus.RUNNING) {
                    startThinkingAnimation()
                } else {
                    stopThinkingAnimation()
                }

                // Update UI based on status
                Logger.d(TAG, "updateStatus calling updateUIForStatus with status: $status")
                updateUIForStatus(status)
            } ?: Logger.w(TAG, "updateStatus: floatingView is null!")
        }
    }

    /**
     * Updates the step counter display.
     *
     * @param step The current step number to display
     *
     */
    fun updateStepNumber(step: Int) {
        currentStepNumber = step
        serviceScope.launch {
            floatingView?.findViewById<TextView>(R.id.tv_step_counter)?.text =
                getString(R.string.step_counter_format, step)
        }
    }

    /**
     * Shows a result message in the floating window.
     *
     * @param message The result message to display
     * @param isSuccess Whether the result represents success or failure
     *
     */
    fun showResult(message: String, isSuccess: Boolean) {
        serviceScope.launch {
            floatingView?.let { view ->
                val resultView = view.findViewById<TextView>(R.id.tv_result)
                resultView?.visibility = View.VISIBLE
                resultView?.text = message
                resultView?.setTextColor(
                    ContextCompat.getColor(
                        this@FloatingWindowService,
                        if (isSuccess) R.color.status_completed else R.color.status_failed
                    )
                )
            }
        }
    }

    /**
     * Resets the floating window to its initial state.
     *
     * Clears all steps, resets the step counter, and returns to IDLE status.
     *
     */
    fun reset() {
        serviceScope.launch {
            Logger.d(TAG, "reset() called - clearing steps and resetting to IDLE")
            stepsList.clear()
            stepsAdapter?.notifyDataSetChanged()
            currentStepNumber = 0

            floatingView?.let { view ->
                view.findViewById<TextView>(R.id.tv_result)?.visibility = View.GONE
                view.findViewById<TextView>(R.id.tv_step_counter)?.text =
                    getString(R.string.step_counter_default)
                view.findViewById<EditText>(R.id.task_input)?.text?.clear()
                view.findViewById<TextView>(R.id.tv_action_preview)?.let { preview ->
                    preview.text = ""
                    preview.visibility = View.GONE
                }
            }

            currentStatus = TaskStatus.IDLE
            updateUIForStatus(TaskStatus.IDLE)
            
            // Update status indicator
            floatingView?.let { view ->
                val statusText = view.findViewById<TextView>(R.id.tv_status)
                val indicator = view.findViewById<View>(R.id.status_indicator)
                statusText?.text = getString(R.string.task_status_idle)
                indicator?.let {
                    val drawable = (it.background as? GradientDrawable)
                        ?: GradientDrawable().also { d -> it.background = d }
                    drawable.shape = GradientDrawable.OVAL
                    drawable.setColor(ContextCompat.getColor(this@FloatingWindowService, R.color.status_idle))
                }
            }
            
            Logger.d(TAG, "reset() complete")
        }
    }

    private fun startTaskInternal(taskDescription: String) {
        val agent = ComponentManager.getInstance(this).phoneAgent
        if (agent == null) {
            showResult("Agent not initialized", false)
            return
        }

        // Reset if needed
        if (agent.getState() != com.kevinluo.autoglm.agent.AgentState.IDLE) {
            agent.reset()
        }

        // Set this service as the listener
        agent.setListener(this)
        
        // Ensure callbacks are set for control
        setStopTaskCallback { agent.cancel() }
        setPauseTaskCallback { agent.pause() }
        setResumeTaskCallback { agent.resume() }
        setResetAgentCallback { agent.reset() }

        updateStatus(TaskStatus.RUNNING)
        
        serviceScope.launch(Dispatchers.Default) {
            try {
                agent.run(taskDescription)
            } catch (e: Exception) {
                Logger.e(TAG, "Error running task", e)
                withContext(Dispatchers.Main) {
                    updateStatus(TaskStatus.FAILED)
                    showResult("Error: ${e.message}", false)
                }
            }
        }
    }

    // ==================== PhoneAgentListener Implementation ====================

    override fun onStepStarted(stepNumber: Int) {
        addStep(stepNumber, "Thinking...", null)
    }

    override fun onThinkingUpdate(thinking: String) {
        updateThinking(thinking)
    }

    override fun onActionExecuted(action: AgentAction) {
        updateAction(action)
    }

    override fun onTaskCompleted(message: String) {
        updateStatus(TaskStatus.COMPLETED)
        showResult(message, true)
    }

    override fun onTaskFailed(error: String) {
        updateStatus(TaskStatus.FAILED)
        showResult(error, false)
    }

    override fun onScreenshotStarted() {
        // Optional: show progress
    }

    override fun onScreenshotCompleted() {
        // Optional: hide progress
    }

    override fun onFloatingWindowRefreshNeeded() {
        updateUIForStatus(currentStatus)
    }
    
    override fun onTaskPaused(stepNumber: Int) {
        updateStatus(TaskStatus.PAUSED)
    }
    
    override fun onTaskResumed(stepNumber: Int) {
        updateStatus(TaskStatus.RUNNING)
    }

    /**
     * Sets the callback for stopping a task from the floating window.
     *
     * @param callback Function to be called when user clicks the stop button
     *
     */
    fun setStopTaskCallback(callback: () -> Unit) {
        Logger.d(TAG, "setStopTaskCallback called")
        stopTaskCallback = callback
    }
    
    /**
     * Sets the callback for pausing a task from the floating window.
     *
     * @param callback Function to be called when user clicks the pause button
     *
     */
    fun setPauseTaskCallback(callback: () -> Unit) {
        Logger.d(TAG, "setPauseTaskCallback called")
        pauseTaskCallback = callback
    }
    
    /**
     * Sets the callback for resuming a task from the floating window.
     *
     * @param callback Function to be called when user clicks the resume button
     *
     */
    fun setResumeTaskCallback(callback: () -> Unit) {
        Logger.d(TAG, "setResumeTaskCallback called")
        resumeTaskCallback = callback
    }

    /**
     * Brings the floating window to the front of other windows.
     *
     */
    fun bringToFront() {
        showAndBringToFront()
    }

    /**
     * Shows a confirmation dialog and waits for user response.
     *
     * @param message The confirmation message to display
     * @param callback Function to be called with the user's response (true for confirm, false for cancel)
     *
     */
    fun showConfirmation(message: String, callback: (Boolean) -> Unit) {
        serviceScope.launch {
            updateStatus(TaskStatus.WAITING_CONFIRMATION)
            Logger.d(TAG, "Confirmation requested: $message")
            delay(100)
            callback(true)
            updateStatus(TaskStatus.RUNNING)
        }
    }

    /**
     * Shows a takeover request and waits for user to take control.
     *
     * @param message The takeover message to display
     * @param callback Function to be called when user acknowledges the takeover
     *
     */
    fun showTakeOver(message: String, callback: () -> Unit) {
        serviceScope.launch {
            updateStatus(TaskStatus.WAITING_TAKEOVER)
            Logger.d(TAG, "Takeover requested: $message")
            delay(100)
            callback()
            updateStatus(TaskStatus.RUNNING)
        }
    }

    /**
     * Shows an interaction dialog with multiple options.
     *
     * @param options List of option strings to display
     * @param callback Function to be called with the selected option index (-1 if no options)
     *
     */
    fun showInteract(options: List<String>, callback: (Int) -> Unit) {
        serviceScope.launch {
            updateStatus(TaskStatus.WAITING_CONFIRMATION)
            Logger.d(TAG, "Interact requested with options: $options")
            delay(100)
            callback(if (options.isNotEmpty()) 0 else -1)
            updateStatus(TaskStatus.RUNNING)
        }
    }

    // ==================== Private Methods ====================

    /**
     * Updates the UI elements based on the current task status.
     *
     * @param status The current task status
     */
    private fun updateUIForStatus(status: TaskStatus) {
        Logger.d(TAG, "updateUIForStatus called with status: $status")
        floatingView?.let { view ->
            val inputArea = view.findViewById<LinearLayout>(R.id.input_area)
            val stepsRecycler = view.findViewById<RecyclerView>(R.id.steps_recycler_view)
            val controlButtonsContainer = view.findViewById<LinearLayout>(R.id.control_buttons_container)
            val stopBtn = view.findViewById<MaterialButton>(R.id.btn_stop)
            val pauseBtn = view.findViewById<MaterialButton>(R.id.btn_pause)
            val resumeBtn = view.findViewById<MaterialButton>(R.id.btn_resume)
            val newTaskBtn = view.findViewById<MaterialButton>(R.id.btn_new_task)
            val actionPreview = view.findViewById<TextView>(R.id.tv_action_preview)

            Logger.d(TAG, "updateUIForStatus: inputArea=$inputArea, stepsRecycler=$stepsRecycler, stopBtn=$stopBtn")

            when (status) {
                TaskStatus.IDLE -> {
                    Logger.d(TAG, "updateUIForStatus: Setting IDLE state - show input, hide steps")
                    inputArea?.visibility = View.VISIBLE
                    stepsRecycler?.visibility = View.GONE
                    controlButtonsContainer?.visibility = View.GONE
                    newTaskBtn?.visibility = View.GONE
                    actionPreview?.visibility = View.GONE
                    latestActionPreview = null
                }
                TaskStatus.RUNNING -> {
                    Logger.d(TAG, "updateUIForStatus: Setting RUNNING state - hide input, show steps")
                    inputArea?.visibility = View.GONE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.VISIBLE
                    resumeBtn?.visibility = View.GONE
                    stopBtn?.visibility = View.VISIBLE
                    newTaskBtn?.visibility = View.GONE
                    
                    // Hide action preview during RUNNING to avoid squeezing "Thinking..."
                    actionPreview?.visibility = View.GONE
                }
                TaskStatus.WAITING_CONFIRMATION, TaskStatus.WAITING_TAKEOVER -> {
                    Logger.d(TAG, "updateUIForStatus: Setting WAITING state")
                    inputArea?.visibility = View.GONE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.VISIBLE
                    resumeBtn?.visibility = View.GONE
                    stopBtn?.visibility = View.VISIBLE
                    newTaskBtn?.visibility = View.GONE
                    
                    if (!latestActionPreview.isNullOrBlank()) {
                        actionPreview?.text = latestActionPreview
                        actionPreview?.visibility = View.VISIBLE
                    } else {
                        actionPreview?.visibility = View.GONE
                    }
                }
                TaskStatus.PAUSED -> {
                    Logger.d(TAG, "updateUIForStatus: Setting PAUSED state - show steps and resume button")
                    inputArea?.visibility = View.GONE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.VISIBLE
                    pauseBtn?.visibility = View.GONE
                    resumeBtn?.visibility = View.VISIBLE
                    stopBtn?.visibility = View.VISIBLE
                    newTaskBtn?.visibility = View.GONE
                    if (!latestActionPreview.isNullOrBlank()) {
                        actionPreview?.text = latestActionPreview
                        actionPreview?.visibility = View.VISIBLE
                    } else {
                        actionPreview?.visibility = View.GONE
                    }
                }
                TaskStatus.COMPLETED, TaskStatus.FAILED -> {
                    Logger.d(TAG, "updateUIForStatus: Setting COMPLETED/FAILED state - show steps and new task button")
                    inputArea?.visibility = View.GONE
                    stepsRecycler?.visibility = View.VISIBLE
                    controlButtonsContainer?.visibility = View.GONE
                    newTaskBtn?.visibility = View.VISIBLE
                    if (!latestActionPreview.isNullOrBlank()) {
                        actionPreview?.text = latestActionPreview
                        actionPreview?.visibility = View.VISIBLE
                    } else {
                        actionPreview?.visibility = View.GONE
                    }

                    if (isMinimized) {
                        // If minimized, we should expand to show the result
                        toggleMinimize()
                    }
                }
            }
            
            // If minimized, ensure body content is hidden regardless of status update
            if (isMinimized) {
                inputArea?.visibility = View.GONE
                stepsRecycler?.visibility = View.GONE
                controlButtonsContainer?.visibility = View.GONE
                stopBtn?.visibility = View.GONE
                pauseBtn?.visibility = View.GONE
                resumeBtn?.visibility = View.GONE
                newTaskBtn?.visibility = View.GONE
                view.findViewById<TextView>(R.id.tv_result)?.visibility = View.GONE
            }

            val inputVis = inputArea?.visibility

            val stepsVis = stepsRecycler?.visibility
            Logger.d(TAG, "updateUIForStatus: After update - inputArea.vis=$inputVis, stepsRecycler.vis=$stepsVis")
        } ?: Logger.w(TAG, "updateUIForStatus: floatingView is null!")
    }

    /**
     * Creates and shows the floating window.
     */
    private fun createAndShowWindow() {
        Logger.d(TAG, "Creating and showing floating window")
        createWindowView()
        addWindowInternal()
        updateStatus(TaskStatus.IDLE)
        Logger.d(TAG, "Floating window created and shown")
    }
    
    /**
     * Creates the window view without showing it.
     */
    private fun createWindowView() {
        if (floatingView != null) {
            Logger.d(TAG, "Window view already created")
            return
        }
        
        Logger.d(TAG, "Creating floating window view")

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_AutoGLM)
        floatingView =
            LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_window, null)

        layoutParams = createLayoutParams()

        setupRecyclerView()
        setupDragBehavior()
        setupButtons()
        setupTaskInput()
        
        updateStatus(TaskStatus.IDLE)
        
        // Setup touch listener to clear focus when tapping outside input
        setupTouchToClearFocus()
        
        Logger.d(TAG, "Floating window view created")
    }
    
    /**
     * Sets up touch listener on the floating view to clear input focus
     * when user taps outside the input field or outside the window.
     */
    private fun setupTouchToClearFocus() {
        floatingView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) {
                val taskInput = floatingView?.findViewById<EditText>(R.id.task_input)
                if (taskInput?.hasFocus() == true) {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        // Touch outside window, clear focus
                        Logger.d(TAG, "Touch outside window, clearing focus")
                        clearInputFocus()
                    } else {
                        // Check if touch is outside the input field
                        val inputLocation = IntArray(2)
                        taskInput.getLocationOnScreen(inputLocation)
                        val inputRect = android.graphics.Rect(
                            inputLocation[0],
                            inputLocation[1],
                            inputLocation[0] + taskInput.width,
                            inputLocation[1] + taskInput.height
                        )
                        
                        // Get touch position relative to screen
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()
                        
                        if (!inputRect.contains(touchX, touchY)) {
                            // Touch is outside input, clear focus
                            Logger.d(TAG, "Touch outside input, clearing focus")
                            clearInputFocus()
                        }
                    }
                }
            }
            false // Don't consume the event, let it propagate
        }
    }

    /**
     * Creates the window layout parameters.
     *
     * @return WindowManager.LayoutParams configured for the floating window
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val widthPx = (screenWidth * WIDTH_PERCENT).toInt()
        val heightPx = (screenHeight * HEIGHT_PERCENT).toInt()

        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            // FLAG_NOT_TOUCH_MODAL: allow touches outside window to pass through
            // FLAG_WATCH_OUTSIDE_TOUCH: receive ACTION_OUTSIDE events to clear focus
            // FLAG_NOT_FOCUSABLE: initially not focusable so back key works in other apps
            // When user clicks input, FLAG_NOT_FOCUSABLE will be removed to allow keyboard
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Allow keyboard input
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    /**
     * Sets up the RecyclerView for displaying steps.
     */
    private fun setupRecyclerView() {
        val recyclerView = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
        recyclerView?.let { rv ->
            rv.layoutManager = LinearLayoutManager(this)
            stepsAdapter = StepsAdapter(stepsList)
            rv.adapter = stepsAdapter
        }
    }

    /**
     * Sets up the task input field and start button.
     */
    private fun setupTaskInput() {
        val taskInput = floatingView?.findViewById<EditText>(R.id.task_input)
        val startBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_start)
        val selectTemplateBtn = floatingView?.findViewById<ImageButton>(R.id.btn_select_template)

        // Use OnTouchListener to handle focus BEFORE the click event
        // This ensures FLAG_NOT_FOCUSABLE is removed before system tries to show keyboard
        taskInput?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                Logger.d(TAG, "taskInput touched (ACTION_DOWN)")
                layoutParams?.let { params ->
                    val wasNotFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                    if (wasNotFocusable) {
                        // Remove FLAG_NOT_FOCUSABLE to allow focus BEFORE the touch is processed
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        if (isAttached.get()) {
                            try {
                                windowManager?.updateViewLayout(floatingView, params)
                                Logger.d(TAG, "setupTaskInput: removed FLAG_NOT_FOCUSABLE on touch")
                                
                                // Use ViewTreeObserver to wait for window focus, then show keyboard
                                floatingView?.viewTreeObserver?.addOnWindowFocusChangeListener(
                                    object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                                            Logger.d(TAG, "Window focus changed: hasFocus=$hasFocus")
                                            if (hasFocus) {
                                                floatingView?.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                                                taskInput.requestFocus()
                                                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                                imm.showSoftInput(taskInput, InputMethodManager.SHOW_IMPLICIT)
                                            }
                                        }
                                    }
                                )
                            } catch (e: Exception) {
                                Logger.e(TAG, "Error updating layout for focus", e)
                            }
                        }
                    }
                }
            }
            false // Don't consume the event, let it propagate for normal EditText behavior
        }
        
        // Template selection button
        selectTemplateBtn?.setOnClickListener {
            showTemplateSelectionPopup(it, taskInput)
        }

        startBtn?.setOnClickListener {
            val task = taskInput?.text?.toString()?.trim() ?: ""
            if (task.isBlank()) {
                android.widget.Toast.makeText(
                    this,
                    R.string.toast_task_empty,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            clearInputFocus()

            Logger.d(TAG, "Resetting agent before starting new task")
            resetAgentCallback?.invoke()

            stepsList.clear()
            stepsAdapter?.notifyDataSetChanged()
            currentStepNumber = 0
            currentStatus = TaskStatus.RUNNING
            latestActionPreview = null
            updateUIForStatus(TaskStatus.RUNNING)
            
            floatingView?.let { view ->
                val statusText = view.findViewById<TextView>(R.id.tv_status)
                val indicator = view.findViewById<View>(R.id.status_indicator)
                statusText?.text = getString(R.string.task_status_running)
                indicator?.let {
                    val drawable = (it.background as? GradientDrawable)
                        ?: GradientDrawable().also { d -> it.background = d }
                    drawable.shape = GradientDrawable.OVAL
                    drawable.setColor(ContextCompat.getColor(this, R.color.status_running))
                }
                view.findViewById<TextView>(R.id.tv_step_counter)?.text =
                    getString(R.string.step_counter_default)
                view.findViewById<TextView>(R.id.tv_result)?.visibility = View.GONE
                view.findViewById<TextView>(R.id.tv_action_preview)?.let { preview ->
                    preview.text = ""
                    preview.visibility = View.GONE
                }
            }

            Logger.d(TAG, "Starting task: $task")
            startTaskInternal(task)

            android.widget.Toast.makeText(
                this,
                R.string.toast_task_started,
                android.widget.Toast.LENGTH_SHORT
            ).show()

            if (!isMinimized) {
                toggleMinimize()
            }
        }
    }

    private fun updateActionPreview(actionText: String) {
        latestActionPreview = actionText
        floatingView?.findViewById<TextView>(R.id.tv_action_preview)?.let { preview ->
            if (actionText.isBlank() || actionText == getString(R.string.floating_action_none)) {
                preview.text = ""
                preview.visibility = View.GONE
            } else {
                preview.text = actionText
                preview.visibility = View.VISIBLE
            }
        }
    }

    private fun startThinkingAnimation() {
        // Start breathing light animation
        val indicator = floatingView?.findViewById<View>(R.id.status_indicator)
        if (indicator != null && thinkingAnimator == null) {
            val alpha = PropertyValuesHolder.ofFloat("alpha", 0.3f, 1.0f, 0.3f)
            val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f, 1.0f)
            val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f, 1.0f)
            val animator = ObjectAnimator.ofPropertyValuesHolder(indicator, alpha, scaleX, scaleY).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            thinkingAnimator = animator
            animator.start()
        }

        // Start text dots animation
        startThinkingDotsAnimation()
    }

    private fun startThinkingDotsAnimation() {
        stopThinkingDotsAnimation()
        
        val statusText = floatingView?.findViewById<TextView>(R.id.tv_status)
        if (statusText == null) {
            Logger.w(TAG, "startThinkingDotsAnimation: statusText is null")
            return
        }
        
        val rawText = getString(R.string.task_status_running)
        // Remove trailing dots or ellipsis to get clean base text
        val baseText = rawText.trim().trimEnd('.', '…')
        
        // Dynamic dots pattern as requested: . -> .. -> ... -> [space]. -> [space].. -> [space]...
        val dots = listOf(".", "..", "...", " .", " ..", " ...")
        
        thinkingDotsJob = serviceScope.launch {
            var index = 0
            while (isActive) {
                val dot = dots[index % dots.size]
                statusText.text = "$baseText$dot"
                index++
                delay(400)
            }
        }
    }

    private fun stopThinkingDotsAnimation() {
        thinkingDotsJob?.cancel()
        thinkingDotsJob = null
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        floatingView?.findViewById<View>(R.id.status_indicator)?.let { indicator ->
            indicator.scaleX = 1f
            indicator.scaleY = 1f
            indicator.alpha = 1f
        }
        stopThinkingDotsAnimation()
    }
    
    /**
     * Shows a popup menu for template selection.
     *
     * @param anchor The view to anchor the popup to
     * @param taskInput The EditText to populate with the selected template
     */
    private fun showTemplateSelectionPopup(anchor: View, taskInput: EditText?) {
        val settingsManager = com.kevinluo.autoglm.settings.SettingsManager(this)
        val templates = settingsManager.getTaskTemplates()
        
        if (templates.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                R.string.settings_no_templates,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        val popup = android.widget.PopupMenu(this, anchor)
        templates.forEachIndexed { index, template ->
            popup.menu.add(0, index, index, template.name)
        }
        
        popup.setOnMenuItemClickListener { item ->
            val selectedTemplate = templates[item.itemId]
            taskInput?.setText(selectedTemplate.description)
            true
        }
        
        popup.show()
    }

    /**
     * Adds the floating window to the window manager.
     */
    private fun addWindowInternal() {
        if (isAttached.get() || floatingView == null || layoutParams == null) {
            val attached = isAttached.get()
            val hasView = floatingView != null
            val hasParams = layoutParams != null
            Logger.w(TAG, "Cannot add window: isAttached=$attached, view=$hasView, params=$hasParams")
            return
        }

        try {
            Logger.d(TAG, "Adding window with params: x=${layoutParams?.x}, y=${layoutParams?.y}")
            windowManager?.addView(floatingView, layoutParams)
            isAttached.set(true)
            Logger.d(TAG, "Window added successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add window", e)
        }
    }

    /**
     * Removes the floating window from the window manager.
     */
    private fun removeWindowInternal() {
        if (!isAttached.get() || floatingView == null) {
            Logger.w(TAG, "Cannot remove window: isAttached=${isAttached.get()}, view=${floatingView != null}")
            return
        }

        try {
            windowManager?.removeView(floatingView)
            isAttached.set(false)
            Logger.d(TAG, "Window removed successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to remove window", e)
        }
    }

    /**
     * Removes the window and cleans up resources.
     */
    private fun removeWindow() {
        if (isAttached.get() && floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Logger.e(TAG, "Error removing window", e)
            }
        }
        floatingView = null
        layoutParams = null
        isAttached.set(false)
    }

    /**
     * Sets up drag behavior for the floating window header.
     */
    private fun setupDragBehavior() {
        val header = floatingView?.findViewById<View>(R.id.header) ?: return
        val params = layoutParams ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isAttached.get()) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {
                            Logger.e(TAG, "Error updating layout", e)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Sets up click listeners for the floating window buttons.
     */
    private fun setupButtons() {
        floatingView?.findViewById<ImageButton>(R.id.btn_open_app)?.setOnClickListener {
            // Open MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        floatingView?.findViewById<ImageButton>(R.id.btn_minimize)?.setOnClickListener {
            toggleMinimize()
        }

        floatingView?.findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            stopSelf()
        }

        floatingView?.findViewById<MaterialButton>(R.id.btn_stop)?.setOnClickListener {
            Logger.d(TAG, "Stop button clicked, stopTaskCallback = $stopTaskCallback")
            stopTaskCallback?.invoke()
            if (stopTaskCallback == null) {
                Logger.w(TAG, "stopTaskCallback is null!")
            }
        }
        
        floatingView?.findViewById<MaterialButton>(R.id.btn_pause)?.setOnClickListener {
            Logger.d(TAG, "Pause button clicked, pauseTaskCallback = $pauseTaskCallback")
            pauseTaskCallback?.invoke()
            if (pauseTaskCallback == null) {
                Logger.w(TAG, "pauseTaskCallback is null!")
            }
        }
        
        floatingView?.findViewById<MaterialButton>(R.id.btn_resume)?.setOnClickListener {
            Logger.d(TAG, "Resume button clicked, resumeTaskCallback = $resumeTaskCallback")
            resumeTaskCallback?.invoke()
            if (resumeTaskCallback == null) {
                Logger.w(TAG, "resumeTaskCallback is null!")
            }
        }
        
        floatingView?.findViewById<MaterialButton>(R.id.btn_new_task)?.setOnClickListener {
            Logger.d(TAG, "New task button clicked")
            // Reset to IDLE state to show input area
            reset()
        }
    }

    /**
     * Toggles the minimized state of the floating window.
     */
    private fun toggleMinimize() {
        val inputArea = floatingView?.findViewById<LinearLayout>(R.id.input_area)
        val recyclerView = floatingView?.findViewById<RecyclerView>(R.id.steps_recycler_view)
        val resultView = floatingView?.findViewById<TextView>(R.id.tv_result)
        val controlButtonsContainer = floatingView?.findViewById<LinearLayout>(R.id.control_buttons_container)
        val stopBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_stop)
        val pauseBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_pause)
        val resumeBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_resume)
        val newTaskBtn = floatingView?.findViewById<MaterialButton>(R.id.btn_new_task)
        val minimizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_minimize)
        val container = floatingView?.findViewById<View>(R.id.floating_window_container)
        val statusText = floatingView?.findViewById<TextView>(R.id.tv_status)
        val stepCounter = floatingView?.findViewById<TextView>(R.id.tv_step_counter)
        val actionPreview = floatingView?.findViewById<TextView>(R.id.tv_action_preview)

        isMinimized = !isMinimized

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        if (isMinimized) {
            // Hide all content except header
            inputArea?.visibility = View.GONE
            recyclerView?.visibility = View.GONE
            resultView?.visibility = View.GONE
            controlButtonsContainer?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
            pauseBtn?.visibility = View.GONE
            resumeBtn?.visibility = View.GONE
            newTaskBtn?.visibility = View.GONE

            // Show status and action, hide step counter
            statusText?.visibility = View.VISIBLE
            stepCounter?.visibility = View.GONE

            if (!latestActionPreview.isNullOrBlank()) {
                actionPreview?.visibility = View.VISIBLE
            } else {
                actionPreview?.visibility = View.GONE
            }

            // Change icon to + (expand)
            minimizeBtn?.setImageResource(R.drawable.ic_plus)

            // Keep width same as expanded state to avoid squeezing content
            layoutParams?.width = (screenWidth * WIDTH_PERCENT).toInt()
            layoutParams?.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            // Restore based on current status
            updateUIForStatus(currentStatus)
            statusText?.visibility = View.VISIBLE
            stepCounter?.visibility = View.VISIBLE

            if (resultView?.text?.isNotEmpty() == true) {
                resultView.visibility = View.VISIBLE
            }
            // Change icon to - (minimize)
            minimizeBtn?.setImageResource(R.drawable.ic_minus)

            // Restore full window size
            layoutParams?.width = (screenWidth * WIDTH_PERCENT).toInt()
            layoutParams?.height = (screenHeight * HEIGHT_PERCENT).toInt()
        }

        if (isAttached.get()) {
            try {
                windowManager?.updateViewLayout(floatingView, layoutParams)
            } catch (e: Exception) {
                Logger.e(TAG, "Error updating layout after minimize", e)
            }
        }
    }

    /**
     * Creates the notification channel for the foreground service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoGLM Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent execution status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /**
     * Creates the notification for the foreground service.
     *
     * @return The notification to display
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoGLM Agent")
            .setContentText("Agent is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ==================== Steps Adapter ====================

    /**
     * RecyclerView adapter for displaying steps in the waterfall view.
     *
     * @property steps The list of steps to display
     *
     */
    private inner class StepsAdapter(
        private val steps: List<FloatingStep>
    ) : RecyclerView.Adapter<StepsAdapter.ViewHolder>() {

        /**
         * ViewHolder for step items.
         */
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val stepNumber: TextView = itemView.findViewById(R.id.step_number)
            val thinkingText: TextView = itemView.findViewById(R.id.thinking_text)
            val actionText: TextView = itemView.findViewById(R.id.action_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_floating_step, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val step = steps[position]
            holder.stepNumber.text = step.stepNumber.toString()
            
            // Hide thinking if empty
            if (step.thinking.isBlank()) {
                holder.thinkingText.visibility = View.GONE
            } else {
                holder.thinkingText.visibility = View.VISIBLE
                holder.thinkingText.text = step.thinking
            }
            
            holder.actionText.text = step.action
        }

        override fun getItemCount(): Int = steps.size
    }
}
