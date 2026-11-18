package com.example.input_ds.calibration

import com.example.input_ds.model.ScanSettings
import org.junit.Assert.*
import org.junit.Test

class ScanCalibrationTest {
    private fun advance(state: ScanCalibrationState, until: Long): ScanCalibrationState {
        var current = state
        var time = state.lastTickMs
        while (time < until) {
            time = minOf(time + 50, until)
            current = ScanCalibration.tick(current, time)
        }
        return current
    }

    private fun choose(state: ScanCalibrationState, correct: Boolean = true, extraCycle: Boolean = false): ScanCalibrationState {
        val selected = if (correct) state.targetIndex else (state.targetIndex + 1) % 4
        val time = state.startedAtMs + (selected + if (extraCycle) 4 else 0) * state.intervalMs + 400
        return ScanCalibration.confirm(advance(state, time), state.mode, time)
    }

    private fun next(state: ScanCalibrationState) = ScanCalibration.tick(state, state.feedbackUntilMs)

    @Test fun perfectFirstPassesTryFasterSpeedsOnlyBetweenGroups() {
        var state = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 100)
        repeat(12) { index ->
            val expected = when (index / 4) { 0 -> 1500L; 1 -> 1300L; else -> 1100L }
            assertEquals(expected, state.intervalMs)
            state = choose(state)
            assertEquals(expected, state.intervalMs)
            assertEquals(CalibrationPhase.FEEDBACK, state.phase)
            assertEquals(state, ScanCalibration.confirm(state, state.mode, state.feedbackUntilMs - 1))
            state = next(state)
        }
        assertEquals(CalibrationPhase.COMPLETE, state.phase)
        assertEquals(1100L, state.recommendation?.intervalMs)
        assertEquals(12, state.trials.size)
        state.groups.forEach { assertEquals(setOf(0, 1, 2, 3), it.trials.map { t -> t.targetIndex }.toSet()) }
    }

    @Test fun errorsSlowDownAndAccuracyWinsOverSpeed() {
        var state = ScanCalibration.start(1500, CalibrationMode.HEADSET, 0)
        repeat(12) { index ->
            state = next(choose(state, correct = index < 4 || index % 4 != 0))
        }
        assertEquals(listOf(1500L, 1300L, 1600L), state.groups.map { it.intervalMs })
        assertEquals(listOf(4, 3, 3), state.groups.map { it.correctCount })
        assertEquals(1500L, state.recommendation?.intervalMs)
        assertTrue(state.trials.all { it.mode == CalibrationMode.HEADSET })
    }

    @Test fun noReliableGroupProducesNoRecommendation() {
        var state = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        repeat(12) { state = next(choose(state, correct = false)) }
        assertEquals(CalibrationPhase.COMPLETE, state.phase)
        assertNull(state.recommendation)
    }

    @Test fun timeoutIsRecordedAndCannotBecomeLateConfirmation() {
        val initial = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        val state = advance(initial, 12_000)
        assertEquals(CalibrationPhase.FEEDBACK, state.phase)
        assertNull(state.trials.single().selectedIndex)
        assertEquals(12_000L, state.trials.single().elapsedMs)
        assertEquals(8, state.trials.single().scanSteps)
        assertEquals(state, ScanCalibration.confirm(state, state.mode, 12_010))
    }

    @Test fun slowFirstPassesDoNotTriggerAcceleration() {
        var state = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        repeat(4) { state = next(choose(state, extraCycle = true)) }
        assertEquals(1800L, state.intervalMs)
    }

    @Test fun realAndSimulatedInputsCannotContaminateEachOther() {
        CalibrationMode.entries.forEach { mode ->
            val state = ScanCalibration.start(1500, mode, 0)
            val other = CalibrationMode.entries.first { it != mode }
            assertEquals(state, ScanCalibration.confirm(state, other, 100))
            assertEquals(1, ScanCalibration.confirm(state, mode, 100).trials.size)
        }
    }

    @Test fun pauseRestartsUnfinishedTrialWithoutCountingTimeAwayOrChangingSpeedAgain() {
        var state = ScanCalibration.start(1500, CalibrationMode.HEADSET, 0)
        repeat(4) { state = next(choose(state)) }
        val completed = state.trials
        state = ScanCalibration.resume(ScanCalibration.pause(state), 99_000)
        assertEquals(1300L, state.intervalMs)
        assertEquals(99_000L, state.startedAtMs)
        assertEquals(0, state.highlightedIndex)
        assertEquals(completed, state.trials)
        assertEquals(1, state.interruptions)
    }

    @Test fun pauseDuringGroupFeedbackPreservesPendingAdaptation() {
        var state = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        repeat(3) { state = next(choose(state)) }
        state = ScanCalibration.resume(ScanCalibration.pause(choose(state)), 99_000)
        assertEquals(4, state.trials.size)
        assertEquals(1300L, state.intervalMs)
    }

    @Test fun stalledDisplayPausesInsteadOfGradingUnseenChoices() {
        val state = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        val paused = ScanCalibration.tick(state, 5_000)
        assertEquals(CalibrationPhase.PAUSED, paused.phase)
        assertTrue(paused.trials.isEmpty())
    }

    @Test fun boundedSpeedsAndReplayAreDeterministic() {
        listOf(0L, 1100L, 1500L, 3000L, 10_000L).forEach { baseline ->
            fun replay(): ScanCalibrationState {
                var state = ScanCalibration.start(baseline, CalibrationMode.SIMULATION, 0)
                repeat(12) { state = next(choose(state, correct = it % 2 == 0)) }
                return state
            }
            val state = replay()
            assertEquals(state, replay())
            assertTrue(state.trials.all { it.intervalMs in ScanSettings.MIN_INTERVAL_MS..ScanSettings.MAX_INTERVAL_MS })
        }
    }

    @Test fun savingRequiresCompletedReliablePracticeAndExplicitAction() {
        val ready = ScanCalibrationUiState()
        assertFalse(CalibrationAction.SAVE in ready.actions)
        assertFalse(CalibrationAction.HEADSET in ready.actions)
        assertTrue(CalibrationAction.HEADSET in ready.copy(headsetReady = true).actions)
        var session = ScanCalibration.start(1500, CalibrationMode.SIMULATION, 0)
        repeat(12) { session = next(choose(session)) }
        val completed = ready.copy(session = session)
        assertTrue(CalibrationAction.SAVE in completed.actions)
        assertEquals(CalibrationAction.RETRY, completed.actions.first())
        assertNull(completed.savedIntervalMs)
        assertFalse(completed.exitRequested)
        assertFalse(CalibrationAction.SAVE in ready.copy(session = session.copy(
            trials = session.trials.map { it.copy(selectedIndex = null) })).actions)
    }

    @Test fun disconnectedHeadsetCannotResumePracticeWithSimulatedInput() {
        val session = ScanCalibration.pause(ScanCalibration.start(1500, CalibrationMode.HEADSET, 0))
        assertFalse(CalibrationAction.RESUME in ScanCalibrationUiState(session, headsetReady = false).actions)
        assertTrue(CalibrationAction.RESUME in ScanCalibrationUiState(session, headsetReady = true).actions)
    }
}
