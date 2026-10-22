package com.alrasin.jarvis.ui

object SetupPolicy {
    fun runtimePermissions(sdk: Int): List<Pair<String, String>> = buildList {
        add("RECORD_AUDIO" to "Microphone — wake word and spoken commands")
        if (sdk >= 33) add("POST_NOTIFICATIONS" to "Notifications — listening status and reminders")
        add("READ_CONTACTS" to "Contacts — find people by name")
        add("CALL_PHONE" to "Phone — place calls")
        add("READ_PHONE_STATE" to "Phone state — detect calls")
        add("SEND_SMS" to "Send SMS — text messages")
        add("READ_SMS" to "Read SMS — message summaries")
        add("CAMERA" to "Camera — flashlight access")
        add("ACCESS_COARSE_LOCATION" to "Approximate location — local weather")
        add("ACCESS_FINE_LOCATION" to "Precise location — optional weather accuracy")
        if (sdk >= 31) add("BLUETOOTH_CONNECT" to "Bluetooth — nearby audio devices")
        if (sdk <= 32) add("READ_EXTERNAL_STORAGE" to "Media storage — optional music access")
    }.map { (permission, label) -> "android.permission.$permission" to label }

    fun wakeReady(microphone: Boolean, models: Map<String, Boolean>): Boolean =
        microphone && listOf("wake_features", "wake_jimi", "stt").all { models[it] == true }

    fun newWakeDetected(testStartedMs: Long?, wakeTimesMs: List<Long>): Boolean =
        testStartedMs != null && wakeTimesMs.any { it >= testStartedMs }
}
