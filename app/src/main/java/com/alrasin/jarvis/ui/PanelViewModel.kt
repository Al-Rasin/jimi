package com.alrasin.jarvis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.data.TurnDao
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.Notifications
import com.alrasin.jarvis.service.WakeFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PanelViewModel @Inject constructor(
    private val app: Application,
    private val turns: TurnDao,
    private val wakes: WakeEventDao,
) : AndroidViewModel(app) {
    val phase = JarvisCoreService.phase
    val heard = JarvisCoreService.heard
    val reply = JarvisCoreService.reply
    val turnId = JarvisCoreService.turnId
    val wakeId = JarvisCoreService.wakeId

    /** ✓: the heard text was right. */
    fun right() = rate(true, null)

    /** ✗ + fixed text: saves the correction (training data) and runs the fixed command. */
    fun fix(text: String) {
        rate(false, text)
        JarvisCoreService.ask(app, text, source = "fix")
    }

    fun ask(text: String) = JarvisCoreService.ask(app, text)

    fun listen() = JarvisCoreService.listen(app)

    fun shutUp() = JarvisCoreService.shutUp(app)

    /** The wake was false: same as ✗ on the wake notification (D-023). */
    fun notCalled() {
        shutUp()
        val id = wakeId.value ?: return
        Notifications.cancelWakeCheck(app, id)
        viewModelScope.launch(Dispatchers.IO) { WakeFeedback.setVerdict(app, wakes, id, WakeEvent.VERDICT_NO) }
    }

    private fun rate(ok: Boolean, correction: String?) {
        val id = turnId.value.takeIf { it > 0 } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            turns.rate(id, ok, correction)
            // a right command also means the wake was right
            val wake = wakeId.value
            if (ok && wake != null) {
                Notifications.cancelWakeCheck(app, wake)
                WakeFeedback.setVerdict(app, wakes, wake, WakeEvent.VERDICT_YES)
            }
        }
    }
}
