package com.kevinluo.autoglm;

interface IUserService {
    void destroy() = 16777114;
    String executeCommand(String command) = 1;
    // Capture screenshot to internal buffer and return base64 length
    int captureScreenshotAndGetSize() = 2;
    // Read a base64 chunk from the internal screenshot buffer
    String readScreenshotChunk(int offset, int size) = 3;
    // Clear internal screenshot buffer to free memory
    void clearScreenshotBuffer() = 4;
}
