package com.kevinluo.autoglm.device

import android.content.Context
import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.util.Logger

/**
 * Device controller implementation using Shizuku shell commands.
 *
 * This controller uses the Shizuku framework to execute shell commands for device control.
 * It requires the Shizuku app to be installed and the user service to be connected.
 *
 * @param userService Shizuku user service for executing shell commands
 * @param textInputManager Manager for text input operations (keyboard switching)
 * @param screenshotProvider Provider function for screenshot service
 *
 * Requirements: Shizuku-based device control
 */
class ShizukuDeviceController(
    private val userService: IUserService,
    private val textInputManager: TextInputManager,
    private val screenshotProvider: () -> ScreenshotService
) : IDeviceController {

    private val executor = DeviceExecutor(userService)

    override fun getMode(): DeviceControlMode = DeviceControlMode.SHIZUKU

    override fun checkPermission(): Boolean {
        return try {
            // Try to execute a simple command to check if Shizuku is connected
            val result = userService.executeCommand("echo test")
            result.contains("test")
        } catch (e: Exception) {
            Logger.w(TAG, "Shizuku permission check failed: ${e.message}")
            false
        }
    }

    override fun requestPermission(context: Context): Boolean {
        try {
            // Launch Shizuku app
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to launch Shizuku app", e)
        }
        return false
    }

    override suspend fun tap(x: Int, y: Int): String {
        return executor.tap(x, y)
    }

    override suspend fun doubleTap(x: Int, y: Int): String {
        return executor.doubleTap(x, y)
    }

    override suspend fun longPress(x: Int, y: Int, durationMs: Int): String {
        return executor.longPress(x, y, durationMs)
    }

    override suspend fun swipe(points: List<com.kevinluo.autoglm.util.Point>, durationMs: Int): String {
        return executor.swipe(points, durationMs)
    }

    override suspend fun pressKey(keyCode: Int): String {
        return executor.pressKey(keyCode)
    }

    override suspend fun launchApp(packageName: String): String {
        return executor.launchApp(packageName)
    }

    override suspend fun getCurrentApp(): String {
        return executor.getCurrentApp()
    }

    override suspend fun inputText(text: String): String {
        val result = textInputManager.typeText(text)
        return if (result.success) {
            "Text input successful: ${text.take(30)}..."
        } else {
            "Text input failed: ${result.message}"
        }
    }

    override suspend fun captureScreen(): Screenshot {
        return screenshotProvider().capture()
    }

    companion object {
        private const val TAG = "ShizukuDeviceController"
    }
}
