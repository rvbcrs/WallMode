package io.github.rvbcrs.wallmode

object AppVisibility {
    @Volatile var settingsOpen = false
    @Volatile var userExited = false

    /**
     * True while [PresenceWakeService] holds the camera for motion detection.
     * The WebView checks this and declines a dashboard's camera request rather
     * than contending for a single-camera device (which throws "camera in use").
     */
    @Volatile var presenceCameraActive = false
}