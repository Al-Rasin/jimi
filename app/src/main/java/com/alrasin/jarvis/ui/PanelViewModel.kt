package com.alrasin.jarvis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.core.WrongAnswers
import com.alrasin.jarvis.data.TurnDao
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.service.CoreState
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.Notifications
import com.alrasin.jarvis.service.Turn
import com.alrasin.jarvis.service.WakeFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PanelViewModel @Inject constructor(
    private val app: Application,
    private val turns: TurnDao,
    private val wakes: WakeEventDao,
) : AndroidViewModel(app) {
    val phase = CoreState.phase
    val heard = CoreState.heard
    val reply = CoreState.reply
    val micLevel = CoreState.micLevel
    val turnId = CoreState.turnId
    val wakeId = CoreState.wakeId

    private val _exchanges = MutableStateFlow<List<Turn>>(emptyList())
    /** Finished turns since this panel opened (chat bubbles). */
    val exchanges: StateFlow<List<Turn>> = _exchanges
    private val _live = MutableStateFlow(false)
    /** True while a command is being heard or answered; false once it is in [exchanges]. */
    val live: StateFlow<Boolean> = _live
    private val _reported = MutableStateFlow(0L)
    /** Turn id of the last "wrong answer" report (to show "Saved"). */
    val reported: StateFlow<Long> = _reported

    init {
        val opened = CoreState.lastTurn.value?.id
        viewModelScope.launch {
            CoreState.lastTurn.collect { t ->
                if (t == null || t.id == opened || _exchanges.value.any { it.id == t.id }) return@collect
                _exchanges.value = _exchanges.value + t
                _live.value = false
            }
        }
        viewModelScope.launch { heard.collect { _live.value = it.isNotBlank() && it != CoreState.lastTurn.value?.heard } }
        viewModelScope.launch {
            phase.collect {
                if (it == CoreState.PHASE_THINKING || it == CoreState.PHASE_SPEAKING)
                    _live.value = true
            }
        }
    }

    /** ✗ on the answer: saves a report for diagnosis (WrongAnswers). */
    fun wrongAnswer(note: String) {
        val t = _exchanges.value.lastOrNull() ?: CoreState.lastTurn.value ?: return
        _reported.value = t.id
        viewModelScope.launch(Dispatchers.IO) { WrongAnswers.save(app, t.id, t.heard, t.reply, t.tool, note) }
    }

    /** ✓: the heard text was right. */
    fun right() = rate(true, null)

    /** ✗ + fixed text: saves the correction (training data) and runs the fixed command. */
    fun fix(text: String) {
        rate(false, text)
        viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.ask(app, text, source = "fix") }
    }

    fun ask(text: String) = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.ask(app, text) }

    fun listen() = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.listen(app) }

    fun shutUp() = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.shutUp(app) }

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
