package com.alrasin.jarvis.debug

data class SmokeRequest(val id: String, val text: String, val readOnly: Boolean, val dryRun: Boolean = false) {
    companion object {
        const val ACTION = "com.alrasin.jarvis.TEST_COMMAND"
        fun parse(action: String?, extras: Map<String, Any?>): SmokeRequest {
            require(action == ACTION) { "Unexpected smoke action" }
            val id = extras["id"] as? String ?: error("Missing id")
            require(Regex("[A-Za-z0-9_.-]{1,80}").matches(id)) { "Invalid id" }
            val text = (extras["text"] as? String)?.trim() ?: error("Missing text")
            require(text.isNotBlank() && text.length <= 240) { "Text must contain 1–240 characters" }
            require(extras["read_only"] == null || extras["read_only"] is Boolean) { "read_only must be boolean" }
            require(extras["dry_run"] == null || extras["dry_run"] is Boolean) { "dry_run must be boolean" }
            return SmokeRequest(id, text, extras["read_only"] as? Boolean ?: true,
                extras["dry_run"] as? Boolean ?: false)
        }
    }
}
