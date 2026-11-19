package com.alrasin.jimi.actions

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.core.ToolCall
import com.alrasin.jimi.core.ToolContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** "do not disturb for 1 hour", "flashlight for 2 minutes": switches the feature back off when the time is up. */
class ToggleOffWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun registry(): ToolRegistry
    }

    override suspend fun doWork(): Result {
        val target = inputData.getString(TARGET) ?: return Result.success()
        val registry = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).registry()
        val r = registry.get("toggle")?.run(
            ToolCall("toggle", mapOf("target" to target, "state" to "off")),
            ToolContext(applicationContext, "timed $target off", false),
        )
        JimiLog.i("toggle: timed off $target ok=${r?.ok}")
        return Result.success()
    }

    companion object {
        private const val TARGET = "target"
        private fun name(target: String) = "toggle_off_$target"

        fun schedule(context: Context, target: String, minutes: Long) {
            val work = OneTimeWorkRequestBuilder<ToggleOffWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TARGET to target))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(target), ExistingWorkPolicy.REPLACE, work)
        }

        /** The user switched it themselves, so the timed switch-off is dropped. */
        fun cancel(context: Context, target: String) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(name(target)) }
        }
    }
}
