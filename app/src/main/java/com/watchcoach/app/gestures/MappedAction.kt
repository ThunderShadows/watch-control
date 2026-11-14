package com.watchcoach.app.gestures

enum class MappedAction(val label: String) {
    NONE("Do nothing"),
    OPEN_APP("Open an app"),
    INTENT_URI("Run an intent / Tasker URI"),
    DESKTOP_KEY("Laptop keystroke (companion)"),
    DESKTOP_CLICK("Laptop mouse click (companion)"),
    PHONE_MEDIA("Phone media (play/pause, next, volume)"),
    FIND_PHONE("Ring the phone"),
    OPEN_CAMERA("Open the camera"),
}
