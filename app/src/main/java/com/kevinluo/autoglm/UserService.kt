package com.kevinluo.autoglm

import com.kevinluo.autoglm.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * User service for executing shell commands with elevated privileges.
 * This service runs in a separate process with Shizuku permissions.
 */
class UserService : IUserService.Stub() {
    // Internal buffer to store base64-encoded screenshot data
    private var screenshotBase64: String? = null

    /**
     * Destroys the service and exits the process.
     */
    override fun destroy() {
        Logger.i(TAG, "destroy")
        System.exit(0)
    }

    /**
     * Executes a shell command and returns the output.
     *
     * @param command The shell command to execute
     * @return The command output including stdout, stderr, and exit code
     */
    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            reader.close()
            errorReader.close()

            if (errorOutput.isNotEmpty()) {
                output.append("\n[stderr]\n").append(errorOutput)
            }
            output.append("\n[exit code: $exitCode]")

            output.toString()
        } catch (e: Exception) {
            "Error: ${e.message}\n${e.stackTraceToString()}"
        }
    }

    /**
     * Captures a screenshot and stores base64 data in memory.
     * Returns the base64 string length, or -1 on failure.
     */
    override fun captureScreenshotAndGetSize(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p | base64"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val sb = StringBuilder()
            var line: String?
            // Read without preserving newlines to avoid wrapping issues
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }

            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append('\n')
            }

            val exitCode = process.waitFor()
            reader.close()
            errorReader.close()

            if (exitCode != 0 || errorOutput.isNotEmpty()) {
                // Failure, clear buffer
                screenshotBase64 = null
                return -1
            }

            screenshotBase64 = sb.toString()
            screenshotBase64?.length ?: -1
        } catch (e: Exception) {
            screenshotBase64 = null
            -1
        }
    }

    /**
     * Reads a chunk from the in-memory base64 screenshot.
     */
    override fun readScreenshotChunk(offset: Int, size: Int): String {
        val data = screenshotBase64 ?: return ""
        if (offset < 0 || size <= 0 || offset >= data.length) return ""
        val end = kotlin.math.min(offset + size, data.length)
        return data.substring(offset, end)
    }

    /**
     * Clears the in-memory screenshot buffer.
     */
    override fun clearScreenshotBuffer() {
        screenshotBase64 = null
    }

    companion object {
        private const val TAG = "UserService"
    }
}
