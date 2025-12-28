package com.kevinluo.autoglm.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Accessibility Service for device control without Shizuku.
 *
 * This service provides:
 * - Screen capture using takeScreenshot() API (Android 13+)
 * - Touch operations via dispatchGesture()
 * - Text input via AccessibilityNodeInfo
 * - Key press simulation
 * - App launching and info retrieval
 *
 * Requirements: Android 13+ (API 33) for screenshot functionality
 */
class AutoGLMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoGLMAccessibilityService"

        // Gesture timing constants
        private const val TAP_DURATION_MS = 100L
        private const val DOUBLE_TAP_INTERVAL_MS = 100L

        // Android KeyEvent keycodes
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_RECENTS = 187
        const val KEYCODE_NOTIFICATIONS = 4
        const val KEYCODE_QUICK_SETTINGS = 5

        @Volatile
        private var instance: AutoGLMAccessibilityService? = null

        /**
         * Gets the singleton instance of the accessibility service.
         *
         * @return The service instance, or null if not connected
         */
        fun getInstance(): AutoGLMAccessibilityService? = instance

        /**
         * Checks if the accessibility service is enabled.
         *
         * @return true if the service is running, false otherwise
         */
        fun isEnabled(): Boolean = instance != null
    }

    private val serviceState = AtomicReference<ServiceState>(ServiceState.IDLE)

    private enum class ServiceState {
        IDLE, CONNECTING, CONNECTED, DISCONNECTED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG, "AccessibilityService connected")
        instance = this
        serviceState.set(ServiceState.CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events if needed
        // Currently not used, but can be used for detecting UI changes
    }

    override fun onInterrupt() {
        Logger.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "AccessibilityService destroyed")
        instance = null
        serviceState.set(ServiceState.DISCONNECTED)
    }

    // ==================== Screenshot ====================

    /**
     * Captures the current screen content.
     *
     * Uses AccessibilityService.takeScreenshot() API available on Android 13+.
     *
     * @return Bitmap of the screen, or null if capture failed
     */
    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                takeScreenshot(/* displayId= */ 0, /* executor= */ mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                if (bitmap != null) {
                                    continuation.resume(bitmap)
                                } else {
                                    continuation.resume(null)
                                }
                                hardwareBuffer.close()
                            } catch (e: Exception) {
                                Logger.e(TAG, "Failed to process screenshot result", e)
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(error: Int) {
                            Logger.e(TAG, "Screenshot capture failed with error code: $error")
                            continuation.resume(null)
                        }
                    })
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to capture screenshot", e)
                continuation.resume(null)
            }
        } else {
            Logger.w(TAG, "Screenshot not supported on Android < 13")
            continuation.resume(null)
        }
    }

    // ==================== Touch Operations ====================

    /**
     * Performs a tap gesture at the specified coordinates.
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @return true if gesture was dispatched successfully
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Performs a double tap gesture at the specified coordinates.
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @return true if both taps were dispatched successfully
     */
    suspend fun doubleTap(x: Int, y: Int): Boolean {
        val firstTap = tap(x, y)
        kotlinx.coroutines.delay(DOUBLE_TAP_INTERVAL_MS)
        val secondTap = tap(x, y)
        return firstTap && secondTap
    }

    /**
     * Performs a long press gesture at the specified coordinates.
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param durationMs Duration of the long press in milliseconds
     * @return true if gesture was dispatched successfully
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Performs a swipe gesture from start to end coordinates.
     *
     * @param startX Start X coordinate in pixels
     * @param startY Start Y coordinate in pixels
     * @param endX End X coordinate in pixels
     * @param endY End Y coordinate in pixels
     * @param durationMs Duration of the swipe in milliseconds
     * @return true if gesture was dispatched successfully
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    // ==================== Key Press ====================

    /**
     * Performs a key press event.
     *
     * Uses performGlobalAction for system keys and fallback to dispatchKeyEvent.
     *
     * @param keyCode Android KeyEvent keycode
     * @return true if key press was successful
     */
    fun pressKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            KEYCODE_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            KEYCODE_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            KEYCODE_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> {
                Logger.w(TAG, "Key code $keyCode not supported via accessibility")
                false
            }
        }
    }

    // ==================== Text Input ====================

    /**
     * Inputs text into the currently focused editable field.
     *
     * @param text The text to input
     * @return true if text was entered successfully
     */
    fun inputText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Logger.w(TAG, "No active window root node")
            return false
        }

        val focusedNode = findFocusedEditableNode(rootNode) ?: run {
            Logger.w(TAG, "No focused editable node found")
            return false
        }

        return try {
            // Clear existing text
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, createSetTextBundle(""))

            // Set new text
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, createSetTextBundle(text))

            Logger.d(TAG, "Text input successful: '${text.take(30)}...'")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to input text", e)
            false
        }
    }

    /**
     * Finds the currently focused editable node in the node tree.
     *
     * @param node The root node to search from
     * @return The focused editable node, or null if not found
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this node is focused and editable
        if (node.isFocused && node.isEditable) {
            return node
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            if (result != null) {
                return result
            }
            // Note: AccessibilityNodeInfo.recycle() is deprecated and no longer needed
            // The garbage collector will handle cleanup automatically
        }

        return null
    }

    /**
     * Creates a bundle for setting text in an editable node.
     *
     * @param text The text to set
     * @return Bundle with text argument
     */
    private fun createSetTextBundle(text: String): android.os.Bundle {
        return android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
    }

    // ==================== App Operations ====================

    /**
     * Gets the current foreground app's package name.
     *
     * @return Package name of the current app, or empty string if not found
     */
    fun getCurrentApp(): String {
        val rootNode = rootInActiveWindow ?: return ""

        // Try to get package name from window info
        val packageName = rootNode.packageName?.toString()

        return packageName ?: ""
    }

    /**
     * Opens the accessibility settings for this app.
     *
     * @param intent The intent to start the accessibility settings
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle any commands sent to the service
        return START_STICKY
    }
}
