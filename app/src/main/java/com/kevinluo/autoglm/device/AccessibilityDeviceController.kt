package com.kevinluo.autoglm.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import com.kevinluo.autoglm.accessibility.AutoGLMAccessibilityService
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.util.Base64

/**
 * Device controller implementation using Android Accessibility Service.
 *
 * This controller uses the AccessibilityService API for device control.
 * It requires the accessibility service to be enabled in system settings.
 *
 * @param context Android context
 *
 * Requirements: Android 13+ (API 33) for screenshot functionality
 */
class AccessibilityDeviceController(
    private val context: Context
) : IDeviceController {

    override fun getMode(): DeviceControlMode = DeviceControlMode.ACCESSIBILITY

    override fun checkPermission(): Boolean {
        return AutoGLMAccessibilityService.isEnabled()
    }

    override fun requestPermission(context: Context): Boolean {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open accessibility settings", e)
            return false
        }
    }

    override suspend fun tap(x: Int, y: Int): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        val result = service.tap(x, y)
        if (result) {
            "Tap at ($x, $y) succeeded"
        } else {
            "Tap at ($x, $y) failed"
        }
    }

    override suspend fun doubleTap(x: Int, y: Int): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        val result = service.doubleTap(x, y)
        if (result) {
            "Double tap at ($x, $y) succeeded"
        } else {
            "Double tap at ($x, $y) failed"
        }
    }

    override suspend fun longPress(x: Int, y: Int, durationMs: Int): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        val result = service.longPress(x, y, durationMs)
        if (result) {
            "Long press at ($x, $y) for ${durationMs}ms succeeded"
        } else {
            "Long press at ($x, $y) failed"
        }
    }

    override suspend fun swipe(points: List<Point>, durationMs: Int): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        if (points.size < 2) {
            return@withContext "Error: Swipe requires at least 2 points"
        }

        // Perform swipe between each consecutive point
        var allSuccess = true
        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            val segmentDuration = durationMs / (points.size - 1)

            val result = service.swipe(start.x, start.y, end.x, end.y, segmentDuration)
            if (!result) {
                allSuccess = false
            }

            // Small delay between segments for smooth multi-point swipe
            if (i < points.size - 2) {
                kotlinx.coroutines.delay(20)
            }
        }

        if (allSuccess) {
            "Swipe with ${points.size} points succeeded"
        } else {
            "Swipe completed with some segments failed"
        }
    }

    override suspend fun pressKey(keyCode: Int): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        val result = service.pressKey(keyCode)
        if (result) {
            "Key press $keyCode succeeded"
        } else {
            "Key press $keyCode failed"
        }
    }

    override suspend fun launchApp(packageName: String): String = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val service = AutoGLMAccessibilityService.getInstance()
            if (service == null) {
                return@withContext "Error: Accessibility service not connected"
            }

            // For accessibility mode, we can't directly launch apps via shell
            // We need to use a different approach or return an error
            // One option is to use PackageManager to get the launch intent
            try {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Launched app: $packageName"
                } else {
                    "Error: No launch intent found for $packageName"
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to launch app: $packageName", e)
                "Error: Failed to launch $packageName: ${e.message}"
            }
        } else {
            "Error: App launch not supported on Android < 5.0"
        }
    }

    override suspend fun getCurrentApp(): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext ""
        }

        service.getCurrentApp()
    }

    override suspend fun inputText(text: String): String = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            return@withContext "Error: Accessibility service not connected"
        }

        val result = service.inputText(text)
        if (result) {
            "Text input successful: '${text.take(30)}...'"
        } else {
            "Text input failed: No focused editable field"
        }
    }

    override suspend fun captureScreen(): Screenshot = withContext(Dispatchers.Main) {
        val service = AutoGLMAccessibilityService.getInstance()
        if (service == null) {
            Logger.e(TAG, "Accessibility service not connected")
            return@withContext createFallbackScreenshot()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Logger.w(TAG, "Screenshot not supported on Android < 13 (API 33)")
            return@withContext createFallbackScreenshot()
        }

        try {
            Logger.d(TAG, "Attempting to capture screenshot via AccessibilityService")
            val bitmap = service.captureScreen()
            if (bitmap != null) {
                val width = bitmap.width
                val height = bitmap.height
                Logger.d(TAG, "Screenshot captured successfully: ${width}x${height}")

                // Convert bitmap to Screenshot
                val base64Data = encodeBitmapToBase64(bitmap)
                bitmap.recycle()

                Screenshot(
                    base64Data = base64Data,
                    width = width,
                    height = height,
                    originalWidth = width,
                    originalHeight = height,
                    isSensitive = false
                )
            } else {
                Logger.w(TAG, "Screenshot capture returned null bitmap")
                createFallbackScreenshot()
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "Security exception when capturing screenshot - service may not have screenshot capability", e)
            createFallbackScreenshot()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to capture screenshot via accessibility service", e)
            createFallbackScreenshot()
        }
    }

    /**
     * Creates a fallback black screenshot when capture fails.
     */
    private fun createFallbackScreenshot(): Screenshot {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)

        val base64Data = encodeBitmapToBase64(bitmap)
        bitmap.recycle()

        return Screenshot(
            base64Data = base64Data,
            width = 1080,
            height = 1920,
            isSensitive = true
        )
    }

    /**
     * Encodes a Bitmap to a base64 string.
     *
     * @param bitmap The bitmap to encode
     * @param format Compression format (default: WEBP_LOSSY)
     * @param quality Compression quality 0-100 (default: 80)
     * @return Base64-encoded string of the compressed image
     */
    private fun encodeBitmapToBase64(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat? = null,
        quality: Int = 80
    ): String {
        val compressFormat = format ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "AccessibilityDeviceController"
    }
}
