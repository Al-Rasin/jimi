package com.alrasin.jarvis.data

/** Tables added in DB v2 (copied from app/schemas/…/2.json; wake_event from v1 is untouched). */
internal val MIGRATION_SQL_1_2 = listOf(
    "CREATE TABLE IF NOT EXISTS `conversation_turn` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, `source` TEXT NOT NULL, `heard` TEXT NOT NULL, `rawText` TEXT NOT NULL, `audioPath` TEXT, `reply` TEXT NOT NULL, `tool` TEXT, `wakeId` INTEGER, `textOk` INTEGER, `correction` TEXT)",
    "CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, `text` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `reminder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `dueMs` INTEGER NOT NULL, `repeat` TEXT NOT NULL, `done` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `notification_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `appName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `important` INTEGER NOT NULL, `reason` TEXT NOT NULL, `status` TEXT NOT NULL, `label` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_notification_log_timeMs` ON `notification_log` (`timeMs`)",
    "CREATE TABLE IF NOT EXISTS `app_rule` (`pkg` TEXT NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`pkg`))",
    "CREATE TABLE IF NOT EXISTS `memory_fact` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, `text` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `tool_call_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, `heard` TEXT NOT NULL, `tool` TEXT NOT NULL, `argsJson` TEXT NOT NULL, `source` TEXT NOT NULL, `ok` INTEGER NOT NULL, `result` TEXT NOT NULL, `corrected` TEXT)",
    "CREATE TABLE IF NOT EXISTS `routine` (`name` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`name`))",
)

/** Tables added in DB v3: app map (ROADMAP J.1). */
internal val MIGRATION_SQL_2_3 = listOf(
    "CREATE TABLE IF NOT EXISTS `app_info` (`pkg` TEXT NOT NULL, `versionCode` INTEGER NOT NULL, `label` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, `lastLearned` INTEGER NOT NULL, PRIMARY KEY(`pkg`))",
    "CREATE TABLE IF NOT EXISTS `app_screen` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pkg` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `name` TEXT NOT NULL, `lastSeen` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_app_screen_pkg` ON `app_screen` (`pkg`)",
    "CREATE TABLE IF NOT EXISTS `app_element` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `screen` INTEGER NOT NULL, `selectorsJson` TEXT NOT NULL, `role` TEXT NOT NULL, `label` TEXT NOT NULL, `risk` TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_app_element_screen` ON `app_element` (`screen`)",
    "CREATE TABLE IF NOT EXISTS `app_edge` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromScreen` INTEGER NOT NULL, `element` INTEGER NOT NULL, `toScreen` INTEGER NOT NULL, `timesSeen` INTEGER NOT NULL, `lastOk` INTEGER NOT NULL, `failedCount` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_app_edge_fromScreen` ON `app_edge` (`fromScreen`)",
)

/** Table added in DB v4: saved recipes (ROADMAP §H.5). */
internal val MIGRATION_SQL_3_4 = listOf(
    "CREATE TABLE IF NOT EXISTS `recipes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pkg` TEXT NOT NULL, `name` TEXT NOT NULL, `say` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `risky` INTEGER NOT NULL, `source` TEXT NOT NULL, `minVersion` INTEGER NOT NULL, `maxVersion` INTEGER NOT NULL, `timeMs` INTEGER NOT NULL)",
)
