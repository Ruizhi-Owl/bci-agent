package com.example.input_ds.calibration

import android.app.Application
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CalibrationAction(val label: String) {
    SIMULATION("模拟练习"), HEADSET("耳机练习"), RESUME("继续练习"),
    RETRY("重新练习"), SAVE("使用推荐速度"), EXIT("放弃并返回")
}

data class ScanCalibrationUiState(
    val session: ScanCalibrationState = ScanCalibration.ready(ScanSettings.DEFAULT_INTERVAL_MS),
    val headsetReady: Boolean = false,
    val menuIndex: Int = 0,
    val menuDirection: Int = 1,
    val exitRequested: Boolean = false,
    val savedIntervalMs: Long? = null,
    val logError: Boolean = false
) {
    val actions: List<CalibrationAction> get() = when (session.phase) {
        CalibrationPhase.READY -> listOfNotNull(CalibrationAction.SIMULATION,
            CalibrationAction.HEADSET.takeIf { headsetReady }, CalibrationAction.EXIT)
        CalibrationPhase.PAUSED -> listOfNotNull(CalibrationAction.RESUME.takeIf {
            session.mode == CalibrationMode.SIMULATION || headsetReady
        }, CalibrationAction.RETRY, CalibrationAction.EXIT)
        CalibrationPhase.COMPLETE -> listOfNotNull(CalibrationAction.RETRY,
            CalibrationAction.SAVE.takeIf { session.recommendation != null }, CalibrationAction.EXIT)
        else -> emptyList()
    }
}

class ScanCalibrationViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanCalibrationUiState())
    val state: StateFlow<ScanCalibrationUiState> = _state
    private var ticker: Job? = null
    private var nextMenuMs = 0L
    private var sessionId = UUID.randomUUID().toString()
    private val logMutex = Mutex()
    private fun now() = SystemClock.elapsedRealtime()

    fun open(baselineMs: Long, headsetReady: Boolean) {
        ticker?.cancel()
        _state.value = ScanCalibrationUiState(ScanCalibration.ready(baselineMs), headsetReady)
        sessionId = UUID.randomUUID().toString()
        activate()
    }

    fun activate() {
        if (ticker?.isActive == true || _state.value.exitRequested) return
        nextMenuMs = now() + _state.value.session.baselineMs
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(50)
                val time = now()
                updateSession(ScanCalibration.tick(_state.value.session, time))
                val ui = _state.value
                if (ui.actions.isNotEmpty() && time >= nextMenuMs) {
                    _state.value = ui.copy(menuIndex = Math.floorMod(ui.menuIndex + ui.menuDirection, ui.actions.size))
                    nextMenuMs = time + ui.session.baselineMs
                }
            }
        }
    }

    fun suspendPractice() {
        updateSession(ScanCalibration.pause(_state.value.session))
        ticker?.cancel()
    }

    fun setHeadsetReady(ready: Boolean) {
        if (_state.value.headsetReady == ready) return
        _state.value = _state.value.copy(headsetReady = ready, menuIndex = 0)
        if (!ready && _state.value.session.mode == CalibrationMode.HEADSET) pause()
    }

    fun pause() = updateSession(ScanCalibration.pause(_state.value.session))

    fun handleSignal(signal: ControlSignal, source: CalibrationMode) {
        if (_state.value.exitRequested || ticker?.isActive != true) return
        val session = _state.value.session
        if (session.phase in listOf(CalibrationPhase.RUNNING, CalibrationPhase.FEEDBACK)) {
            if (source != session.mode) return
            when (signal) {
                ControlSignal.LEFT_LOOK -> pause()
                ControlSignal.BITE -> updateSession(ScanCalibration.confirm(session, source, now()))
                else -> Unit
            }
        } else if (_state.value.actions.isNotEmpty()) {
            when (signal) {
                ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(menuDirection = -1)
                ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(menuDirection = 1)
                ControlSignal.BITE -> _state.value.actions.getOrNull(_state.value.menuIndex)?.let(::select)
                else -> Unit
            }
        }
    }

    fun select(action: CalibrationAction) {
        val ui = _state.value
        if (ui.exitRequested || action !in ui.actions) return
        when (action) {
            CalibrationAction.SIMULATION, CalibrationAction.HEADSET -> {
                sessionId = UUID.randomUUID().toString()
                updateSession(ScanCalibration.start(ui.session.baselineMs,
                    if (action == CalibrationAction.HEADSET) CalibrationMode.HEADSET else CalibrationMode.SIMULATION, now()))
            }
            CalibrationAction.RESUME -> updateSession(ScanCalibration.resume(ui.session, now()))
            CalibrationAction.RETRY -> {
                record("retry")
                _state.value = ui.copy(session = ScanCalibration.ready(ui.session.baselineMs), menuIndex = 0)
                nextMenuMs = now() + ui.session.baselineMs
            }
            CalibrationAction.SAVE -> {
                val interval = ui.session.recommendation?.intervalMs ?: return
                record("accepted")
                _state.value = ui.copy(savedIntervalMs = interval, exitRequested = true)
            }
            CalibrationAction.EXIT -> discard()
        }
    }

    fun discard() {
        if (_state.value.exitRequested) return
        record("discarded")
        _state.value = _state.value.copy(exitRequested = true)
        ticker?.cancel()
    }

    private fun updateSession(session: ScanCalibrationState) {
        val previous = _state.value.session
        if (session == previous) return
        val changedPhase = session.phase != previous.phase
        _state.value = _state.value.copy(session = session,
            menuIndex = if (changedPhase) 0 else _state.value.menuIndex)
        if (changedPhase) nextMenuMs = now() + session.baselineMs
        if (session.phase == CalibrationPhase.COMPLETE && previous.phase != CalibrationPhase.COMPLETE) record("pending")
    }

    /** Small local summary; never includes EEG, typed text, or server credentials. */
    private fun record(outcome: String) {
        val session = _state.value.session
        if (session.trials.isEmpty()) return
        val id = sessionId
        viewModelScope.launch {
            runCatching {
                logMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val directory = File(getApplication<Application>().filesDir, "scan_calibration").apply { mkdirs() }
                        val json = JSONObject().apply {
                            put("policy", "scan-speed-practice-v1")
                            put("session_id", id)
                            put("timestamp_ms", System.currentTimeMillis())
                            put("outcome", outcome)
                            put("mode", session.mode.name)
                            put("baseline_ms", session.baselineMs)
                            put("recommended_ms", session.recommendation?.intervalMs ?: JSONObject.NULL)
                            put("interruptions", session.interruptions)
                            put("trials", JSONArray().apply {
                                session.trials.forEach { trial -> put(JSONObject().apply {
                                    put("target", trial.targetIndex)
                                    put("selected", trial.selectedIndex ?: JSONObject.NULL)
                                    put("interval_ms", trial.intervalMs)
                                    put("elapsed_ms", trial.elapsedMs)
                                    put("scan_steps", trial.scanSteps)
                                    put("correct", trial.correct)
                                }) }
                            })
                        }
                        val file = AtomicFile(File(directory, "$id.json"))
                        val stream = file.startWrite()
                        try {
                            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
                            file.finishWrite(stream)
                        } catch (error: Exception) {
                            file.failWrite(stream)
                            throw error
                        }
                    }
                }
            }.onFailure { _state.value = _state.value.copy(logError = true) }
        }
    }
}
