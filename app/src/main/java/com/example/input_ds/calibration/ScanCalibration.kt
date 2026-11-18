package com.example.input_ds.calibration

import com.example.input_ds.model.ScanSettings

enum class CalibrationMode { SIMULATION, HEADSET }
enum class CalibrationPhase { READY, RUNNING, FEEDBACK, PAUSED, COMPLETE }

data class ScanTrial(
    val targetIndex: Int,
    val selectedIndex: Int?,
    val intervalMs: Long,
    val elapsedMs: Long,
    val scanSteps: Int,
    val mode: CalibrationMode
) {
    val correct: Boolean get() = selectedIndex == targetIndex
}

data class ScanTrialGroup(val trials: List<ScanTrial>) {
    val intervalMs: Long get() = trials.first().intervalMs
    val correctCount: Int get() = trials.count { it.correct }
    val meanElapsedMs: Long get() = trials.sumOf { it.elapsedMs } / trials.size
}

data class ScanCalibrationState(
    val baselineMs: Long,
    val phase: CalibrationPhase = CalibrationPhase.READY,
    val mode: CalibrationMode = CalibrationMode.SIMULATION,
    val intervalMs: Long = baselineMs,
    val startedAtMs: Long = 0,
    val lastTickMs: Long = 0,
    val feedbackUntilMs: Long = 0,
    val highlightedIndex: Int = 0,
    val trials: List<ScanTrial> = emptyList(),
    val pausedPhase: CalibrationPhase = CalibrationPhase.RUNNING,
    val interruptions: Int = 0
) {
    val targetIndex: Int get() = ScanCalibration.TARGET_ORDER[trials.size % 4]
    val groups: List<ScanTrialGroup> get() = trials.chunked(4).filter { it.size == 4 }.map(::ScanTrialGroup)
    // Four observations are a practice recommendation, not a validated clinical estimate.
    val recommendation: ScanTrialGroup?
        get() = if (phase != CalibrationPhase.COMPLETE) null else groups
            .filter { it.correctCount >= 3 }
            .sortedWith(compareByDescending<ScanTrialGroup> { it.correctCount }
                .thenBy { it.meanElapsedMs }.thenByDescending { it.intervalMs })
            .firstOrNull()
}

/** Pure, replayable policy. Only trial boundaries may change the dwell interval. */
object ScanCalibration {
    val CHOICES = listOf("喝水", "吃饭", "休息", "帮助")
    internal val TARGET_ORDER = listOf(1, 3, 0, 2)
    const val TOTAL_TRIALS = 12
    const val FEEDBACK_MS = 1_200L
    private const val MAX_STEPS = 8 // Two complete cycles, including the initial item.

    fun ready(baselineMs: Long) = ScanCalibrationState(baselineMs.coerceIn(
        ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS
    ))

    fun start(baselineMs: Long, mode: CalibrationMode, nowMs: Long) = ready(baselineMs).copy(
        mode = mode, phase = CalibrationPhase.RUNNING, startedAtMs = nowMs, lastTickMs = nowMs
    )

    fun tick(state: ScanCalibrationState, nowMs: Long): ScanCalibrationState {
        if (state.phase == CalibrationPhase.FEEDBACK) {
            if (nowMs < state.feedbackUntilMs) return state
            if (state.trials.size == TOTAL_TRIALS) return state.copy(phase = CalibrationPhase.COMPLETE)
            val nextInterval = if (state.trials.size % 4 == 0) {
                val recent = state.trials.takeLast(4)
                val easy = recent.all { it.correct && it.scanSteps < CHOICES.size }
                (state.intervalMs + if (easy) -200L else 300L)
                    .coerceIn(ScanSettings.MIN_INTERVAL_MS, ScanSettings.MAX_INTERVAL_MS)
            } else state.intervalMs
            return state.copy(phase = CalibrationPhase.RUNNING, intervalMs = nextInterval,
                startedAtMs = nowMs, lastTickMs = nowMs, highlightedIndex = 0)
        }
        if (state.phase != CalibrationPhase.RUNNING) return state
        // A blocked UI must not grade choices the participant never saw.
        if (nowMs - state.lastTickMs > state.intervalMs) return pause(state)
        val elapsed = (nowMs - state.startedAtMs).coerceAtLeast(0)
        val steps = (elapsed / state.intervalMs).toInt()
        if (steps >= MAX_STEPS) return finishTrial(state, null, state.intervalMs * MAX_STEPS, MAX_STEPS, nowMs)
        return state.copy(highlightedIndex = steps % CHOICES.size, lastTickMs = nowMs)
    }

    fun confirm(state: ScanCalibrationState, mode: CalibrationMode, nowMs: Long): ScanCalibrationState {
        if (mode != state.mode || state.phase != CalibrationPhase.RUNNING) return state
        val current = tick(state, nowMs)
        if (current.phase != CalibrationPhase.RUNNING) return current
        val elapsed = (nowMs - current.startedAtMs).coerceAtLeast(0)
        return finishTrial(current, current.highlightedIndex, elapsed,
            (elapsed / current.intervalMs).toInt(), nowMs)
    }

    fun pause(state: ScanCalibrationState): ScanCalibrationState =
        if (state.phase in listOf(CalibrationPhase.RUNNING, CalibrationPhase.FEEDBACK))
            state.copy(phase = CalibrationPhase.PAUSED, pausedPhase = state.phase,
                interruptions = state.interruptions + 1)
        else state

    /** Restart an interrupted trial; time off-screen and disconnections are not errors. */
    fun resume(state: ScanCalibrationState, nowMs: Long): ScanCalibrationState {
        if (state.phase != CalibrationPhase.PAUSED) return state
        if (state.trials.size == TOTAL_TRIALS) return state.copy(phase = CalibrationPhase.COMPLETE)
        if (state.pausedPhase == CalibrationPhase.RUNNING) return state.copy(
            phase = CalibrationPhase.RUNNING, startedAtMs = nowMs, lastTickMs = nowMs, highlightedIndex = 0)
        // Preserve a completed group's policy update when interrupted during feedback.
        return tick(state.copy(phase = CalibrationPhase.FEEDBACK, feedbackUntilMs = nowMs), nowMs)
    }

    private fun finishTrial(state: ScanCalibrationState, selected: Int?, elapsed: Long, steps: Int, now: Long) =
        state.copy(phase = CalibrationPhase.FEEDBACK, feedbackUntilMs = now + FEEDBACK_MS,
            trials = state.trials + ScanTrial(state.targetIndex, selected, state.intervalMs, elapsed, steps, state.mode))
}
