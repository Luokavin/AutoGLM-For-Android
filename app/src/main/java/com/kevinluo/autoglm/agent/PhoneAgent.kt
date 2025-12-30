package com.kevinluo.autoglm.agent

import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.action.ActionParseException
import com.kevinluo.autoglm.action.ActionParser
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.action.CoordinateOutOfRangeException
import com.kevinluo.autoglm.config.SystemPrompts
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelResult
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.util.ErrorHandler
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Configuration for the PhoneAgent.
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val language: String = "cn",
    val verbose: Boolean = true,
    val screenshotDelayMs: Long = 2000L
)

/**
 * Result of a single step execution.
 */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: AgentAction?,
    val thinking: String,
    val message: String?,
    val nextStepHint: String? = null,  // Hint to pass to the model in the next step
    val paused: Boolean = false  // Indicates step was interrupted by pause
)

/**
 * Result of a complete task execution.
 */
data class TaskResult(
    val success: Boolean,
    val message: String,
    val stepCount: Int
)

/**
 * Listener interface for PhoneAgent events.
 * Allows UI components to receive updates about task execution progress.
 */
interface PhoneAgentListener {
    fun onStepStarted(stepNumber: Int)
    fun onThinkingUpdate(thinking: String)
    fun onActionExecuted(action: AgentAction)
    fun onTaskCompleted(message: String)
    fun onTaskFailed(error: String)
    fun onScreenshotStarted()
    fun onScreenshotCompleted()
    fun onFloatingWindowRefreshNeeded()
    fun onTaskPaused(stepNumber: Int) {}  // Optional: called when task is paused
    fun onTaskResumed(stepNumber: Int) {} // Optional: called when task is resumed
}

/**
 * Represents the current state of the agent.
 */
enum class AgentState {
    IDLE,
    RUNNING,
    PAUSED,     // Task is paused, waiting to be resumed
    CANCELLED
}

/**
 * Core agent class responsible for coordinating the automation flow.
 * Manages task execution, model communication, and action handling.
 * 
 */
class PhoneAgent(
    private val modelClient: ModelClient,
    private val actionHandler: ActionHandler,
    private val screenshotService: ScreenshotService,
    private val config: AgentConfig = AgentConfig(),
    private val historyManager: HistoryManager? = null
) {
    // Private properties
    private val context: AtomicReference<AgentContext?> = AtomicReference(null)
    private val state: AtomicReference<AgentState> = AtomicReference(AgentState.IDLE)
    private val cancelled: AtomicBoolean = AtomicBoolean(false)
    private val paused: AtomicBoolean = AtomicBoolean(false)
    private val actionExecuted: AtomicBoolean = AtomicBoolean(false)  // Track if action has been executed in current step
    private var listener: PhoneAgentListener? = null
    private var currentStepNumber: Int = 0
    
    /**
     * Gets the cancellation message based on language setting.
     *
     * @return Localized cancellation message
     */
    private fun getCancellationMessage(): String {
        return if (config.language.lowercase() == "en" || config.language.lowercase() == "english") {
            CANCELLATION_MESSAGE_EN
        } else {
            CANCELLATION_MESSAGE
        }
    }
    
    /**
     * Sets the listener for agent events.
     *
     * @param listener The listener to receive agent events, or null to remove
     */
    fun setListener(listener: PhoneAgentListener?) {
        this.listener = listener
    }
    
    /**
     * Gets the current state of the agent.
     *
     * @return Current AgentState
     */
    fun getState(): AgentState = state.get()
    
    /**
     * Checks if a task is currently running.
     * 
     * @return true if a task is running, false otherwise
     */
    fun isRunning(): Boolean = state.get() == AgentState.RUNNING
    
    /**
     * Checks if a task is currently paused.
     * 
     * @return true if a task is paused, false otherwise
     */
    fun isPaused(): Boolean = state.get() == AgentState.PAUSED
    
    /**
     * Pauses the currently running task.
     * The task will pause immediately and cancel any ongoing network request.
     * When resumed, the current step will be retried (unless action was already executed).
     * 
     * @return true if pause was initiated, false if task is not running
     */
    fun pause(): Boolean {
        Logger.i(TAG, "Pause requested, current state: ${state.get()}")
        
        if (state.compareAndSet(AgentState.RUNNING, AgentState.PAUSED)) {
            paused.set(true)
            
            // Cancel any ongoing model request immediately
            modelClient.cancelCurrentRequest()
            
            // If action hasn't been executed yet, we'll retry this step when resumed
            // The step number will be decremented in executeStep when it detects pause
            val willRetry = !actionExecuted.get()
            Logger.i(TAG, "Task paused at step $currentStepNumber, willRetryStep=$willRetry")
            listener?.onTaskPaused(currentStepNumber)
            return true
        }
        
        Logger.w(TAG, "Cannot pause: task is not running (state: ${state.get()})")
        return false
    }
    
    /**
     * Resumes a paused task.
     * 
     * @return true if resume was successful, false if task is not paused
     */
    fun resume(): Boolean {
        Logger.i(TAG, "Resume requested, current state: ${state.get()}")
        
        if (state.compareAndSet(AgentState.PAUSED, AgentState.RUNNING)) {
            paused.set(false)
            Logger.i(TAG, "Task resumed from step $currentStepNumber")
            listener?.onTaskResumed(currentStepNumber)
            return true
        }
        
        Logger.w(TAG, "Cannot resume: task is not paused (state: ${state.get()})")
        return false
    }
    
    /**
     * Waits while the task is paused.
     * Returns immediately if not paused or if cancelled.
     * Uses a polling approach with configurable interval.
     */
    private suspend fun waitWhilePaused() {
        while (paused.get() && !cancelled.get()) {
            kotlinx.coroutines.delay(PAUSE_CHECK_INTERVAL_MS)
        }
    }

    
    /**
     * Validates a task description.
     * 
     * @param task The task description to validate
     * @return true if valid, false if empty or whitespace only
     * 
     */
    fun isValidTask(task: String): Boolean {
        return task.isNotBlank()
    }
    
    /**
     * Runs a complete task from start to finish.
     * Executes steps until the task is completed, cancelled, or max steps reached.
     * 
     * @param task The natural language task description
     * @return TaskResult containing success status, message, and step count
     * 
     */
    suspend fun run(task: String): TaskResult = coroutineScope {
        // Validate task description (Requirement 1.2)
        if (!isValidTask(task)) {
            Logger.w(TAG, "Task validation failed: empty or whitespace only")
            return@coroutineScope TaskResult(
                success = false,
                message = "Task description cannot be empty or whitespace only",
                stepCount = 0
            )
        }
        
        // Check if already running (Requirement 1.3)
        if (!state.compareAndSet(AgentState.IDLE, AgentState.RUNNING)) {
            Logger.w(TAG, "Task rejected: another task is already running")
            return@coroutineScope TaskResult(
                success = false,
                message = "A task is already running. Please wait or cancel it first.",
                stepCount = 0
            )
        }
        
        cancelled.set(false)
        paused.set(false)
        currentStepNumber = 0
        
        // Initialize context with system prompt based on language setting
        val systemPrompt = SystemPrompts.getPrompt(config.language)
        context.set(AgentContext(systemPrompt))
        
        // Start history recording
        historyManager?.startTask(task)
        
        Logger.logTaskStart(task)
        
        // Track if we've already completed history to avoid duplicates
        var historyCompleted = false
        val cancellationMsg = getCancellationMessage()
        
        try {
            var stepCount = 0
            var lastMessage = ""
            var success = true
            var nextStepHint: String? = null
            
            // Execute steps until finished or max steps reached
            // When maxSteps is 0, run indefinitely (no limit)
            while (config.maxSteps == 0 || stepCount < config.maxSteps) {
                ensureActive()
                
                if (cancelled.get()) {
                    state.set(AgentState.CANCELLED)
                    Logger.i(TAG, "Task cancelled by user at step $stepCount")
                    if (!historyCompleted) {
                        historyManager?.completeTask(false, cancellationMsg)
                        historyCompleted = true
                    }
                    // Don't call listener here - let the caller handle UI updates
                    return@coroutineScope TaskResult(
                        success = false,
                        message = cancellationMsg,
                        stepCount = stepCount
                    )
                }
                
                // Execute single step, passing hint from previous step if any
                val stepResult = executeStep(
                    task = if (stepCount == 0) task else null,
                    hint = nextStepHint
                )
                
                // Handle pause - wait for resume and retry the step
                if (stepResult.paused) {
                    Logger.i(TAG, "Step returned paused, waiting for resume...")
                    waitWhilePaused()
                    
                    // Check if cancelled while paused
                    if (cancelled.get()) {
                        state.set(AgentState.CANCELLED)
                        Logger.i(TAG, "Task cancelled while paused")
                        if (!historyCompleted) {
                            historyManager?.completeTask(false, cancellationMsg)
                            historyCompleted = true
                        }
                        return@coroutineScope TaskResult(
                            success = false,
                            message = cancellationMsg,
                            stepCount = stepCount
                        )
                    }
                    
                    // Resume - continue loop to retry the step (stepCount not incremented)
                    Logger.i(TAG, "Resumed, retrying step...")
                    continue
                }
                
                stepCount++
                
                // Store hint for next step
                nextStepHint = stepResult.nextStepHint
                
                if (!stepResult.success) {
                    // Check if this failure is due to cancellation
                    if (cancelled.get()) {
                        state.set(AgentState.CANCELLED)
                        Logger.i(TAG, "Task cancelled at step $stepCount")
                        if (!historyCompleted) {
                            historyManager?.completeTask(false, cancellationMsg)
                            historyCompleted = true
                        }
                        return@coroutineScope TaskResult(
                            success = false,
                            message = cancellationMsg,
                            stepCount = stepCount
                        )
                    }
                    
                    success = false
                    lastMessage = stepResult.message ?: "Step execution failed"
                    Logger.w(TAG, "Step $stepCount failed: $lastMessage")
                    // Don't call listener here - let the caller handle UI updates
                    break
                }
                
                if (stepResult.finished) {
                    lastMessage = stepResult.message ?: "Task completed"
                    Logger.i(TAG, "Task finished at step $stepCount: $lastMessage")
                    // Don't call listener here - let the caller handle UI updates
                    break
                }
                
                lastMessage = stepResult.message ?: ""
            }
            
            // Check if max steps reached (only when maxSteps > 0)
            if (config.maxSteps > 0 && stepCount >= config.maxSteps) {
                lastMessage = "Maximum steps (${config.maxSteps}) reached"
                Logger.w(TAG, lastMessage)
                success = false
            }
            
            // Complete history recording (only if not already done)
            if (!historyCompleted) {
                historyManager?.completeTask(success, lastMessage)
                historyCompleted = true
            }
            
            Logger.logTaskComplete(success, lastMessage, stepCount)
            
            TaskResult(
                success = success,
                message = lastMessage,
                stepCount = stepCount
            )
        } catch (e: CancellationException) {
            state.set(AgentState.CANCELLED)
            Logger.i(TAG, "Task cancelled via coroutine cancellation")
            if (!historyCompleted) {
                historyManager?.completeTask(false, cancellationMsg)
            }
            TaskResult(
                success = false,
                message = cancellationMsg,
                stepCount = currentStepNumber
            )
        } catch (e: Exception) {
            // Always check if cancelled first - user cancellation takes priority over any other error
            if (cancelled.get()) {
                state.set(AgentState.CANCELLED)
                Logger.i(TAG, "Task cancelled, ignoring exception: ${e.message}")
                if (!historyCompleted) {
                    historyManager?.completeTask(false, cancellationMsg)
                }
                return@coroutineScope TaskResult(
                    success = false,
                    message = cancellationMsg,
                    stepCount = currentStepNumber
                )
            }
            
            val handledError = ErrorHandler.handleUnknownError("Task execution error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            if (!historyCompleted) {
                historyManager?.completeTask(false, handledError.userMessage)
            }
            TaskResult(
                success = false,
                message = handledError.userMessage,
                stepCount = currentStepNumber
            )
        } finally {
            state.set(AgentState.IDLE)
        }
    }

    
    /**
     * Executes a single step of the task.
     * 
     * @param task Optional task description (only needed for first step)
     * @param hint Optional hint from previous step (e.g., app not found, need to search on screen)
     * @return StepResult containing step outcome
     */
    private suspend fun executeStep(task: String?, hint: String? = null): StepResult {
        // Wait if paused (check at the beginning of each step)
        waitWhilePaused()
        
        // Reset action executed flag at the start of each step
        actionExecuted.set(false)
        
        currentStepNumber++
        Logger.logStep(currentStepNumber, task ?: "continue")
        listener?.onStepStarted(currentStepNumber)
        
        val ctx = context.get() ?: run {
            Logger.e(TAG, "Agent context not initialized")
            return StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = "",
                message = "Agent context not initialized"
            )
        }
        
        val cancellationMsg = getCancellationMessage()
        
        try {
            // Check cancellation before any operation
            if (cancelled.get()) {
                Logger.i(TAG, "Task cancelled at start of step")
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = cancellationMsg
                )
            }
            
            // 截图前等待（可配置的延迟）
            // 如果是第一步，且用户没有特别设置超长延迟，我们可以缩短等待时间以提升响应速度
            val currentDelay = if (currentStepNumber == 1 && config.screenshotDelayMs > 500) {
                500L // 第一步只等待 500ms
            } else {
                config.screenshotDelayMs
            }
            
            if (currentDelay > 0) {
                Logger.d(TAG, "截图前等待 ${currentDelay}ms...")
                kotlinx.coroutines.delay(currentDelay)
            }
            
            // Check cancellation after delay
            if (cancelled.get()) {
                Logger.i(TAG, "Task cancelled after screenshot delay")
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = cancellationMsg
                )
            }
            
            // Check pause after delay - if paused, return to retry this step
            if (paused.get()) {
                Logger.i(TAG, "Task paused before screenshot, will retry step")
                currentStepNumber--  // Decrement so we retry this step
                return StepResult(
                    success = true,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = PAUSE_MESSAGE,
                    paused = true
                )
            }
            
            // Capture screenshot
            Logger.d(TAG, "Capturing screenshot...")
            listener?.onScreenshotStarted()
            val screenshot = screenshotService.capture()
            listener?.onScreenshotCompleted()
            Logger.logScreenshot(screenshot.width, screenshot.height, screenshot.isSensitive)
            
            // Check cancellation after screenshot
            if (cancelled.get()) {
                Logger.i(TAG, "Task cancelled after screenshot capture")
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = cancellationMsg
                )
            }
            
            // Check pause after screenshot - if paused, discard screenshot and retry
            if (paused.get()) {
                Logger.i(TAG, "Task paused after screenshot, will retry step with fresh screenshot")
                currentStepNumber--  // Decrement so we retry this step
                return StepResult(
                    success = true,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = PAUSE_MESSAGE,
                    paused = true
                )
            }
            
            // Store screenshot for history recording
            historyManager?.setCurrentScreenshot(screenshot.base64Data, screenshot.width, screenshot.height)
            
            // Build user message
            val userText = when {
                task != null -> "任务: $task\n当前屏幕截图如下:"
                hint != null -> "上一步执行结果: $hint\n继续执行任务，当前屏幕截图如下:"
                else -> "继续执行任务，当前屏幕截图如下:"
            }
            
            // Add user message with screenshot to context
            ctx.addUserMessage(userText, screenshot.base64Data)
            
            // Request model response
            Logger.d(TAG, "Requesting model response...")
            
            val modelResult = modelClient.request(ctx.getMessages())
            
            // Check if cancelled after request (request might have been interrupted)
            if (cancelled.get()) {
                Logger.i(TAG, "Task cancelled during/after model request")
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = cancellationMsg
                )
            }
            
            // Check pause after model request - if paused, discard response and retry
            if (paused.get()) {
                Logger.i(TAG, "Task paused during/after model request, will retry step")
                currentStepNumber--  // Decrement so we retry this step
                // Remove the user message we just added since we'll retry
                ctx.removeLastUserMessage()
                return StepResult(
                    success = true,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = PAUSE_MESSAGE,
                    paused = true
                )
            }
            
            when (modelResult) {
                is ModelResult.Success -> {
                    val response = modelResult.response
                    Logger.logThinking(response.thinking)
                    Logger.logModelAction(response.action)
                    
                    // Update listener with thinking
                    listener?.onThinkingUpdate(response.thinking)
                    
                    // Add assistant response to context
                    ctx.addAssistantMessage(response.rawContent)
                    
                    // Check again if cancelled before processing action
                    if (cancelled.get()) {
                        Logger.i(TAG, "Task cancelled before action execution")
                        return StepResult(
                            success = false,
                            finished = true,
                            action = null,
                            thinking = response.thinking,
                            message = cancellationMsg
                        )
                    }
                    
                    // Check pause before action execution - if paused, discard and retry
                    if (paused.get()) {
                        Logger.i(TAG, "Task paused before action execution, will retry step")
                        currentStepNumber--  // Decrement so we retry this step
                        // Remove the messages we just added since we'll retry
                        ctx.removeLastAssistantMessage()
                        ctx.removeLastUserMessage()
                        return StepResult(
                            success = true,
                            finished = false,
                            action = null,
                            thinking = response.thinking,
                            message = PAUSE_MESSAGE,
                            paused = true
                        )
                    }
                    
                    // Parse and execute action - retry if empty
                    if (response.action.isBlank()) {
                        Logger.w(TAG, "No action in model response, attempting retry...")
                        
                        // Remove the assistant response that had no action
                        ctx.removeLastAssistantMessage()
                        
                        // Retry loop - resend the same request
                        var retryCount = 0
                        var lastThinking = response.thinking
                        var finalAction = response.action
                        
                        while (finalAction.isBlank() && retryCount < MAX_EMPTY_ACTION_RETRIES) {
                            retryCount++
                            Logger.i(TAG, "Empty action retry $retryCount/$MAX_EMPTY_ACTION_RETRIES")
                            
                            // Check cancellation/pause before retry
                            if (cancelled.get() || paused.get()) break
                            
                            // Resend the same request (context still has the user message with screenshot)
                            val retryResult = modelClient.request(ctx.getMessages())
                            
                            if (cancelled.get() || paused.get()) break
                            
                            when (retryResult) {
                                is ModelResult.Success -> {
                                    val retryResponse = retryResult.response
                                    lastThinking = retryResponse.thinking
                                    finalAction = retryResponse.action
                                    Logger.d(TAG, "Retry $retryCount response action: ${finalAction.take(50)}")
                                    
                                    // Update listener with new thinking
                                    listener?.onThinkingUpdate(retryResponse.thinking)
                                    
                                    // If got action, add assistant response to context
                                    if (finalAction.isNotBlank()) {
                                        ctx.addAssistantMessage(retryResponse.rawContent)
                                    }
                                }
                                is ModelResult.Error -> {
                                    Logger.w(TAG, "Retry $retryCount failed: ${retryResult.error.message}")
                                    break
                                }
                            }
                        }
                        
                        // If still no action after retries, fail
                        if (finalAction.isBlank()) {
                            Logger.w(TAG, "No action after $retryCount retries")
                            historyManager?.recordStep(
                                stepNumber = currentStepNumber,
                                thinking = lastThinking,
                                action = null,
                                actionDescription = "无操作",
                                success = false,
                                message = "模型响应中没有操作（已重试${retryCount}次）"
                            )
                            return StepResult(
                                success = false,
                                finished = false,
                                action = null,
                                thinking = lastThinking,
                                message = "模型响应中没有操作（已重试${retryCount}次）"
                            )
                        }
                        
                        // Got action after retry, continue with execution
                        Logger.i(TAG, "Got action after $retryCount retries")
                        return executeAction(finalAction, lastThinking, screenshot.originalWidth, screenshot.originalHeight)
                    }
                    
                    return executeAction(response.action, response.thinking, screenshot.originalWidth, screenshot.originalHeight)
                }
                
                is ModelResult.Error -> {
                    // Check if this error is due to cancellation
                    if (cancelled.get()) {
                        Logger.i(TAG, "Task cancelled, ignoring model error")
                        return StepResult(
                            success = false,
                            finished = true,
                            action = null,
                            thinking = "",
                            message = cancellationMsg
                        )
                    }
                    
                    // Check if this error is due to pause (request was cancelled)
                    if (paused.get()) {
                        Logger.i(TAG, "Model request cancelled due to pause, will retry step")
                        currentStepNumber--  // Decrement so we retry this step
                        // Remove the user message we just added since we'll retry
                        ctx.removeLastUserMessage()
                        return StepResult(
                            success = true,
                            finished = false,
                            action = null,
                            thinking = "",
                            message = PAUSE_MESSAGE,
                            paused = true
                        )
                    }
                    
                    val handledError = ErrorHandler.handleNetworkError(modelResult.error)
                    Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError))
                    // Record failed step
                    historyManager?.recordStep(
                        stepNumber = currentStepNumber,
                        thinking = "",
                        action = null,
                        actionDescription = "模型错误",
                        success = false,
                        message = handledError.userMessage
                    )
                    return StepResult(
                        success = false,
                        finished = false,
                        action = null,
                        thinking = "",
                        message = handledError.userMessage
                    )
                }
            }
        } catch (e: CancellationException) {
            // Coroutine cancellation - always treat as user cancellation
            Logger.i(TAG, "Step cancelled via coroutine cancellation")
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = cancellationMsg
            )
        } catch (e: Exception) {
            // Check if exception is due to cancellation
            if (cancelled.get()) {
                Logger.i(TAG, "Task cancelled, exception ignored: ${e.message}")
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = cancellationMsg
                )
            }
            
            // Check if exception is due to pause
            if (paused.get()) {
                Logger.i(TAG, "Exception during paused state, will retry step: ${e.message}")
                currentStepNumber--
                return StepResult(
                    success = true,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = PAUSE_MESSAGE,
                    paused = true
                )
            }
            
            val handledError = ErrorHandler.handleUnknownError("Step execution error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            return StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = "",
                message = handledError.userMessage
            )
        }
    }
    
    /**
     * Parses and executes an action from the model response.
     *
     * @param actionStr The action string from model response
     * @param thinking The thinking text from model response
     * @param screenWidth Current screen width in pixels
     * @param screenHeight Current screen height in pixels
     * @return StepResult containing action execution outcome
     */
    private suspend fun executeAction(
        actionStr: String,
        thinking: String,
        screenWidth: Int,
        screenHeight: Int
    ): StepResult {
        return try {
            Logger.d(TAG, "Parsing action: $actionStr")
            val action = ActionParser.parse(actionStr)
            Logger.logAction(action::class.simpleName ?: "Unknown", actionStr)
            
            // Notify listener
            listener?.onActionExecuted(action)
            
            // Mark that we're about to execute the action - after this point, no retry on pause
            actionExecuted.set(true)
            
            // Execute the action
            Logger.d(TAG, "Executing action...")
            val result = actionHandler.execute(action, screenWidth, screenHeight)
            Logger.d(TAG, "Action result: success=${result.success}, finish=${result.shouldFinish}")
            
            // Record step in history
            historyManager?.recordStep(
                stepNumber = currentStepNumber,
                thinking = thinking,
                action = action,
                actionDescription = action.formatForDisplay(),
                success = result.success,
                message = result.message
            )
            
            // Refresh floating window if needed (e.g., after launching another app)
            if (result.refreshFloatingWindow) {
                Logger.d(TAG, "Refreshing floating window after action")
                listener?.onFloatingWindowRefreshNeeded()
            }
            
            // Determine if we need to pass a hint to the next step
            // This is used when an action succeeds but needs follow-up (e.g., app not found by package name)
            val nextHint = if (result.success && !result.shouldFinish && result.message != null 
                && result.message.contains("请在主屏幕或应用列表中查找")) {
                result.message
            } else {
                null
            }
            
            StepResult(
                success = result.success,
                finished = result.shouldFinish,
                action = action,
                thinking = thinking,
                message = result.message,
                nextStepHint = nextHint
            )
        } catch (e: CoordinateOutOfRangeException) {
            // Handle coordinate out of range - return hint for retry
            Logger.w(TAG, "Coordinate out of range: ${e.message}")
            val correctionHint = buildCoordinateCorrectionHint(e, config.language)
            
            // Record failed step
            historyManager?.recordStep(
                stepNumber = currentStepNumber,
                thinking = thinking,
                action = null,
                actionDescription = "坐标越界: ${e.originalAction}",
                success = false,
                message = correctionHint
            )
            
            StepResult(
                success = true,  // Mark as success to continue, but provide hint
                finished = false,
                action = null,
                thinking = thinking,
                message = correctionHint,
                nextStepHint = correctionHint
            )
        } catch (e: ActionParseException) {
            val handledError = ErrorHandler.handleParsingError(actionStr, e.message ?: "Unknown parse error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            // Record failed step
            historyManager?.recordStep(
                stepNumber = currentStepNumber,
                thinking = thinking,
                action = null,
                actionDescription = "解析错误: $actionStr",
                success = false,
                message = handledError.userMessage
            )
            StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = thinking,
                message = handledError.userMessage
            )
        }
    }
    
    /**
     * Builds a correction hint message for coordinate out of range errors.
     * This hint will be passed to the model in the next step to help it correct the coordinates.
     *
     * @param e The coordinate out of range exception
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return Localized correction hint message
     */
    private fun buildCoordinateCorrectionHint(e: CoordinateOutOfRangeException, language: String): String {
        val invalidDetails = e.invalidCoordinates.joinToString(if (language == "en") ", " else "、") { 
            "${it.name}=${it.value}" 
        }
        
        return if (language.lowercase() == "en" || language.lowercase() == "english") {
            """【Coordinate Error】The coordinates in your previous action are out of valid range!
Invalid coordinates: $invalidDetails
Original action: ${e.originalAction}

【Important Reminder】The coordinate system starts from top-left (0,0) to bottom-right (999,999).
All coordinate values MUST be between 0 and 999.
Please re-analyze the current screenshot and output correct coordinates (within 0-999 range)."""
        } else {
            """【坐标错误】你上一步输出的操作坐标超出了有效范围！
错误的坐标: $invalidDetails
原始操作: ${e.originalAction}

【重要提醒】坐标系统从左上角 (0,0) 开始到右下角 (999,999) 结束，所有坐标值必须在 0-999 范围内。
请重新分析当前屏幕截图，输出正确的坐标（0-999范围内）。"""
        }
    }

    
    /**
     * Executes a single step for manual step-by-step control.
     * 
     * @param task Optional task description (required for first step)
     * @return StepResult containing step outcome
     * 
     */
    suspend fun step(task: String? = null): StepResult {
        // For first step, validate task
        if (context.get() == null) {
            if (task == null || !isValidTask(task)) {
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "Task description required for first step"
                )
            }
            
            // Check if already running
            if (!state.compareAndSet(AgentState.IDLE, AgentState.RUNNING)) {
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "A task is already running"
                )
            }
            
            // Initialize context with system prompt based on language setting
            val systemPrompt = SystemPrompts.getPrompt(config.language)
            context.set(AgentContext(systemPrompt))
            currentStepNumber = 0
            // Reset cancelled flag only when starting a new task (first step)
            cancelled.set(false)
        }
        
        if (cancelled.get()) {
            state.set(AgentState.IDLE)
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = getCancellationMessage()
            )
        }
        
        try {
            val result = executeStep(task)
            
            // If finished or failed, reset state
            if (result.finished || !result.success) {
                state.set(AgentState.IDLE)
            }
            
            return result
        } catch (e: Exception) {
            state.set(AgentState.IDLE)
            
            // Check if cancelled
            if (cancelled.get()) {
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = getCancellationMessage()
                )
            }
            
            return StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = "",
                message = "Step execution error: ${e.message}"
            )
        }
    }
    
    /**
     * Cancels the currently running task.
     * Uses compareAndSet to ensure state transition only happens when task is RUNNING or PAUSED.
     * 
     */
    fun cancel() {
        Logger.i(TAG, "Cancel requested, current state: ${state.get()}")
        cancelled.set(true)
        paused.set(false)  // Clear pause flag to unblock waitWhilePaused()
        
        // Try to transition from RUNNING or PAUSED to CANCELLED
        var transitioned = state.compareAndSet(AgentState.RUNNING, AgentState.CANCELLED)
        if (!transitioned) {
            transitioned = state.compareAndSet(AgentState.PAUSED, AgentState.CANCELLED)
        }
        
        // Cancel any ongoing model request
        modelClient.cancelCurrentRequest()
        
        Logger.i(TAG, "Task cancelled, state transitioned: $transitioned, current state: ${state.get()}")
    }
    
    /**
     * Resets the agent to initial state.
     * Clears context and prepares for a new task.
     * Note: Does NOT reset the cancelled flag - that's handled at the start of run()
     * to ensure cancellation is properly detected even after reset.
     * 
     */
    fun reset() {
        Logger.i(TAG, "Resetting agent, current state: ${state.get()}")
        
        // Cancel any ongoing model request
        modelClient.cancelCurrentRequest()
        
        // Clear state (but NOT the cancelled flag - it's reset at start of run())
        context.get()?.reset()
        context.set(null)
        currentStepNumber = 0
        paused.set(false)  // Reset pause flag
        // Note: cancelled flag is intentionally NOT reset here
        // It will be reset at the start of run() to ensure proper cancellation detection
        state.set(AgentState.IDLE)
        
        Logger.i(TAG, "Agent reset complete, state: ${state.get()}")
    }
    
    /**
     * Gets the current step number.
     *
     * @return Current step number in the task execution
     */
    fun getCurrentStepNumber(): Int = currentStepNumber
    
    /**
     * Gets the current context for inspection.
     *
     * @return Current AgentContext or null if not initialized
     */
    fun getContext(): AgentContext? = context.get()
    
    /**
     * Sets a custom system prompt.
     * Must be called before starting a task.
     *
     * @param prompt The custom system prompt to use
     */
    fun setSystemPrompt(prompt: String) {
        if (state.get() == AgentState.IDLE) {
            context.set(AgentContext(prompt))
        }
    }
    
    // Companion object placed at the bottom following code style guidelines (Requirement 3.1)
    companion object {
        private const val TAG = "PhoneAgent"
        
        /** Cancellation message in Chinese. */
        const val CANCELLATION_MESSAGE = "任务已被用户取消"
        
        /** Cancellation message in English. */
        const val CANCELLATION_MESSAGE_EN = "Task cancelled by user"
        
        /** Pause message in Chinese. */
        const val PAUSE_MESSAGE = "任务已暂停"
        
        /** Pause message in English. */
        const val PAUSE_MESSAGE_EN = "Task paused"
        
        /** Interval in milliseconds for checking pause state during wait loops. */
        private const val PAUSE_CHECK_INTERVAL_MS = 100L
        
        /** Maximum retries when model returns empty action. */
        private const val MAX_EMPTY_ACTION_RETRIES = 3
    }
}
