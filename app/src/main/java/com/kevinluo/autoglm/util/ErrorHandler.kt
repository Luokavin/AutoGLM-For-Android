package com.kevinluo.autoglm.util

import com.kevinluo.autoglm.config.I18n
import com.kevinluo.autoglm.model.NetworkError

/**
 * Centralized error handling utility for the AutoGLM Phone Agent application.
 *
 * Provides consistent error categorization, logging, and user-friendly messages
 * with multi-language support (Chinese and English).
 * All error handling should go through this utility to ensure consistent
 * error formatting and logging across the application.
 */
object ErrorHandler {
    /**
     * Error categories for classification.
     *
     * Used to categorize errors for appropriate handling and display.
     */
    enum class ErrorCategory {
        /** Network-related errors (connection, timeout, server errors). */
        NETWORK,

        /** Permission-related errors (missing permissions, Shizuku issues). */
        PERMISSION,

        /** Action execution errors (tap, swipe, type failures). */
        ACTION,

        /** Screenshot capture errors. */
        SCREENSHOT,

        /** Parsing errors (JSON, model response parsing). */
        PARSING,

        /** Configuration errors (invalid settings). */
        CONFIGURATION,

        /** Unknown or unexpected errors. */
        UNKNOWN,
    }

    /**
     * Represents a handled error with user-friendly message.
     *
     * @property category The category of the error for classification
     * @property userMessage User-friendly message suitable for display
     * @property technicalMessage Technical message for logging and debugging
     * @property isRetryable Whether the operation can be retried
     * @property originalException The original exception that caused the error, if any
     */
    data class HandledError(
        val category: ErrorCategory,
        val userMessage: String,
        val technicalMessage: String,
        val isRetryable: Boolean,
        val originalException: Throwable? = null,
    )

    /**
     * Handles a network error and returns a user-friendly error.
     *
     * @param error The network error to handle
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleNetworkError(error: NetworkError, language: String = "cn"): HandledError {
        Logger.logNetworkError(error.message ?: "Unknown network error")

        return when (error) {
            is NetworkError.ConnectionFailed -> {
                HandledError(
                    category = ErrorCategory.NETWORK,
                    userMessage = I18n.getMessage("connect_failed", language),
                    technicalMessage = error.message,
                    isRetryable = true,
                    originalException = error,
                )
            }

            is NetworkError.Timeout -> {
                HandledError(
                    category = ErrorCategory.NETWORK,
                    userMessage = I18n.getMessage("request_timeout", language),
                    technicalMessage = "Request timed out after ${error.timeoutMs}ms",
                    isRetryable = true,
                    originalException = error,
                )
            }

            is NetworkError.ServerError -> {
                val userMsg = when (error.statusCode) {
                    401 -> I18n.getMessage("auth_failed_401", language)
                    403 -> I18n.getMessage("forbidden_403", language)
                    404 -> I18n.getMessage("not_found_404", language)
                    429 -> I18n.getMessage("rate_limit_429", language)
                    else -> I18n.getFormattedMessage("server_error_format", language, error.statusCode)
                }
                HandledError(
                    category = ErrorCategory.NETWORK,
                    userMessage = userMsg,
                    technicalMessage = "Server error ${error.statusCode}: ${error.message}",
                    isRetryable = error.statusCode >= 500,
                    originalException = error,
                )
            }

            is NetworkError.ParseError -> {
                HandledError(
                    category = ErrorCategory.PARSING,
                    userMessage = I18n.getMessage("parse_response_failed", language),
                    technicalMessage = "Parse error: ${error.rawResponse.take(MAX_RAW_RESPONSE_LENGTH)}",
                    isRetryable = false,
                    originalException = error,
                )
            }
        }
    }

    /**
     * Handles an action execution error.
     *
     * @param actionType Type of action that failed (e.g., "tap", "swipe", "type")
     * @param error Error message describing what went wrong
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleActionError(
        actionType: String,
        error: String,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Action error [$actionType]: $error", exception ?: Exception(error))

        return HandledError(
            category = ErrorCategory.ACTION,
            userMessage = I18n.getFormattedMessage("action_failed_format", language, actionType),
            technicalMessage = error,
            isRetryable = true,
            originalException = exception,
        )
    }

    /**
     * Handles a screenshot capture error.
     *
     * @param error Error message describing what went wrong
     * @param isSensitive Whether the error is due to sensitive screen detection
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleScreenshotError(
        error: String,
        isSensitive: Boolean = false,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Screenshot error: $error", exception ?: Exception(error))

        return if (isSensitive) {
            HandledError(
                category = ErrorCategory.SCREENSHOT,
                userMessage = I18n.getMessage("screen_protected", language),
                technicalMessage = "Sensitive screen detected",
                isRetryable = false,
                originalException = exception,
            )
        } else {
            HandledError(
                category = ErrorCategory.SCREENSHOT,
                userMessage = I18n.getMessage("screenshot_failed", language),
                technicalMessage = error,
                isRetryable = true,
                originalException = exception,
            )
        }
    }

    /**
     * Handles a permission error.
     *
     * @param permission Permission that is missing or denied
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handlePermissionError(
        permission: String,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Permission error: $permission", exception ?: Exception("Missing permission: $permission"))

        return HandledError(
            category = ErrorCategory.PERMISSION,
            userMessage = I18n.getFormattedMessage("missing_permission_format", language, permission),
            technicalMessage = "Missing permission: $permission",
            isRetryable = false,
            originalException = exception,
        )
    }

    /**
     * Handles a Shizuku-related error.
     *
     * @param error Error message describing the Shizuku issue
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleShizukuError(
        error: String,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Shizuku error: $error", exception ?: Exception(error))

        return HandledError(
            category = ErrorCategory.PERMISSION,
            userMessage = I18n.getMessage("shizuku_unavailable", language),
            technicalMessage = error,
            isRetryable = true,
            originalException = exception,
        )
    }

    /**
     * Handles a parsing error.
     *
     * @param input Input that failed to parse (will be truncated in logs)
     * @param error Error message describing the parsing failure
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleParsingError(
        input: String,
        error: String,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(
            TAG,
            "Parsing error: $error, input: ${input.take(MAX_INPUT_LOG_LENGTH)}",
            exception ?: Exception(error),
        )

        return HandledError(
            category = ErrorCategory.PARSING,
            userMessage = I18n.getMessage("parse_model_failed", language),
            technicalMessage = "Parse error: $error",
            isRetryable = false,
            originalException = exception,
        )
    }

    /**
     * Handles a configuration error.
     *
     * @param setting Setting name that is invalid or missing
     * @param error Error message describing the configuration issue
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleConfigurationError(
        setting: String,
        error: String,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Configuration error [$setting]: $error")

        return HandledError(
            category = ErrorCategory.CONFIGURATION,
            userMessage = I18n.getFormattedMessage("config_error_format", language, setting),
            technicalMessage = error,
            isRetryable = false,
        )
    }

    /**
     * Handles an unknown/unexpected error.
     *
     * @param error Error message describing what went wrong
     * @param exception Optional exception that caused the error
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleUnknownError(
        error: String,
        exception: Throwable? = null,
        language: String = "cn",
    ): HandledError {
        Logger.e(TAG, "Unknown error: $error", exception ?: Exception(error))

        return HandledError(
            category = ErrorCategory.UNKNOWN,
            userMessage = I18n.getMessage("unknown_error_retry", language),
            technicalMessage = error,
            isRetryable = true,
            originalException = exception,
        )
    }

    /**
     * Handles an app not found error.
     *
     * @param appName Name of the app that wasn't found
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return HandledError with appropriate user and technical messages
     */
    fun handleAppNotFoundError(appName: String, language: String = "cn"): HandledError {
        Logger.w(TAG, "App not found: $appName")

        return HandledError(
            category = ErrorCategory.ACTION,
            userMessage = I18n.getFormattedMessage("app_not_found_format", language, appName),
            technicalMessage = "App not found: $appName",
            isRetryable = false,
        )
    }

    /**
     * Formats an error for user display.
     *
     * @param error The handled error to format
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return Formatted error message suitable for UI display
     */
    fun formatErrorForDisplay(error: HandledError, language: String = "cn"): String = buildString {
        append(error.userMessage)
        if (error.isRetryable) {
            append(I18n.getMessage("retryable_suffix", language))
        }
    }

    /**
     * Formats an error for logging.
     *
     * @param error The handled error to format
     * @return Formatted error message suitable for log output
     */
    fun formatErrorForLog(error: HandledError): String = buildString {
        append("[${error.category}] ")
        append(error.technicalMessage)
        error.originalException?.let {
            append("\nStack trace: ${it.stackTraceToString().take(MAX_STACK_TRACE_LENGTH)}")
        }
    }

    // Constants - placed at the bottom following code style guidelines
    private const val TAG = "ErrorHandler"
    private const val MAX_RAW_RESPONSE_LENGTH = 200
    private const val MAX_INPUT_LOG_LENGTH = 100
    private const val MAX_STACK_TRACE_LENGTH = 500
}
