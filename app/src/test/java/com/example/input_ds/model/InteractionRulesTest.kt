package com.example.input_ds.model

import org.junit.Assert.*
import org.junit.Test

class InteractionRulesTest {
    @Test fun confirmationsRequireAnExplicitSideAndBiteDoesNotChangeTheChoice() {
        assertEquals(0, InteractionRules.confirmationIndex(ControlSignal.LEFT_LOOK, 1))
        assertEquals(1, InteractionRules.confirmationIndex(ControlSignal.RIGHT_LOOK, 0))
        assertEquals(0, InteractionRules.confirmationIndex(ControlSignal.BITE, 0))
        assertEquals(1, InteractionRules.confirmationIndex(ControlSignal.BITE, 1))
    }

    @Test fun backgroundAndCaregiverPagesCannotReceiveHeadsetCommands() {
        AppDestination.entries.forEach { page ->
            assertFalse(InteractionRules.acceptsHeadsetCommands(page, false, false))
        }
        listOf(AppDestination.SETTINGS, AppDestination.DEVICE_STATUS, AppDestination.COLLECTION).forEach {
            assertFalse(InteractionRules.acceptsHeadsetCommands(it, true, false))
        }
        assertTrue(InteractionRules.acceptsHeadsetCommands(AppDestination.CHINESE_CHESS, true, false))
    }

    @Test fun wizardInputBelongsToTheVisibleWizardActivity() {
        assertFalse(InteractionRules.acceptsHeadsetCommands(AppDestination.WIZARD_GAME, true, false))
        assertTrue(InteractionRules.acceptsHeadsetCommands(AppDestination.WIZARD_GAME, false, true))
        assertFalse(InteractionRules.acceptsHeadsetCommands(AppDestination.HOME, false, true))
    }

    @Test fun everyEntertainmentLeafReturnsToEntertainmentAndThenHome() {
        EntertainmentHubModule.entries.filter { it != EntertainmentHubModule.BACK }.forEach {
            assertEquals(AppDestination.ENTERTAINMENT, it.destination.parentDestination())
        }
        assertEquals(AppDestination.HOME, AppDestination.ENTERTAINMENT.parentDestination())
        assertNull(AppDestination.HOME.parentDestination())
    }
}
