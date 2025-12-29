package com.kevinluo.autoglm.device

import android.content.Context
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Point

/**
 * Device control mode enumeration.
 *
 * Defines the available methods for controlling the device.
 *
 * @property SHIZUKU Uses Shizuku shell commands (requires Shizuku app)
 * @property ACCESSIBILITY Uses Android Accessibility Service
 */
enum class DeviceControlMode(val displayName: String) {
    SHIZUKU("Shizuku"),
    ACCESSIBILITY("无障碍服务")
}

/**
 * Abstract interface for device control operations.
 *
 * This interface defines the contract for device manipulation regardless of the
 * underlying implementation (Shizuku shell commands or Accessibility Service).
 *
 * Implementations must handle:
 * - Touch operations (tap, swipe, long press, double tap)
 * - Key press events
 * - App launching and info retrieval
 * - Text input (method varies by implementation)
 * - Screen capture
 * - Permission management
 *
 * Requirements: Integration with both Shizuku and Accessibility modes
 */
interface IDeviceController {

    /**
     * Gets the control mode of this device controller.
     *
     * @return The device control mode (SHIZUKU or ACCESSIBILITY)
     */
    fun getMode(): DeviceControlMode

    /**
     * Checks if the required permissions are granted.
     *
     * For Shizuku mode: Checks if Shizuku service is connected
     * For Accessibility mode: Checks if accessibility service is enabled
     *
     * @return true if all required permissions are granted, false otherwise
     */
    fun checkPermission(): Boolean

    /**
     * Requests the required permissions from the user.
     *
     * For Shizuku mode: Launches Shizuku app or shows setup instructions
     * For Accessibility mode: Opens system accessibility settings
     *
     * @param context Android context for starting intents
     * @return true if the request was initiated successfully, false otherwise
     */
    fun requestPermission(context: Context): Boolean

    // ==================== Touch Operations ====================

    /**
     * Performs a tap at the specified absolute coordinates.
     *
     * @param x Absolute X coordinate in pixels
     * @param y Absolute Y coordinate in pixels
     * @return Result of the operation
     */
    suspend fun tap(x: Int, y: Int): String

    /**
     * Performs a double tap at the specified absolute coordinates.
     *
     * @param x Absolute X coordinate in pixels
     * @param y Absolute Y coordinate in pixels
     * @return Result of the operation
     */
    suspend fun doubleTap(x: Int, y: Int): String

    /**
     * Performs a long press at the specified absolute coordinates.
     *
     * @param x Absolute X coordinate in pixels
     * @param y Absolute Y coordinate in pixels
     * @param durationMs Duration of the long press in milliseconds
     * @return Result of the operation
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Int = 3000): String

    /**
     * Performs a swipe gesture using a list of points.
     *
     * @param points List of points defining the swipe path, must contain at least 2 points
     * @param durationMs Total duration of the swipe in milliseconds
     * @return Result of the operation
     */
    suspend fun swipe(points: List<Point>, durationMs: Int): String

    // ==================== Key Press Operations ====================

    /**
     * Presses a key by its keycode.
     *
     * @param keyCode The Android KeyEvent keycode (e.g., KEYCODE_BACK, KEYCODE_HOME)
     * @return Result of the operation
     */
    suspend fun pressKey(keyCode: Int): String

    // ==================== App Operations ====================

    /**
     * Launches an app by its package name.
     *
     * @param packageName The package name of the app to launch
     * @return Result of the operation
     */
    suspend fun launchApp(packageName: String): String

    /**
     * Gets the current foreground app's package name.
     *
     * @return The package name of the current foreground app, or empty string if not found
     */
    suspend fun getCurrentApp(): String

    // ==================== Text Input ====================

    /**
     * Inputs text into the currently focused input field.
     *
     * Implementation varies by mode:
     * - Shizuku: Uses keyboard switching via TextInputManager
     * - Accessibility: Directly injects text via AccessibilityNodeInfo
     *
     * @param text The text to input
     * @return Result of the operation
     */
    suspend fun inputText(text: String): String

    // ==================== Screenshot ====================

    /**
     * Captures the current screen content.
     *
     * Implementation varies by mode:
     * - Shizuku: Uses screencap shell command
     * - Accessibility: Uses AccessibilityService.takeScreenshot() (Android 13+)
     *
     * @return Screenshot object containing the captured image data and metadata
     */
    suspend fun captureScreen(): Screenshot

    // ==================== Constants ====================

    companion object {
        // Android KeyEvent keycodes
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
    }
}
