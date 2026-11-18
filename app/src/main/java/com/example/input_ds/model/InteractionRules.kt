package com.example.input_ds.model

/** Shared input contract. Game movement and the first T9 stage are direct-control modes. */
object InteractionRules {
    const val SCAN_HINT = "左看向左扫描，右看向右扫描，咬牙确认"
    const val CONFIRM_HINT = "左看选择取消，右看选择确定，咬牙执行"

    fun scanDirection(signal: ControlSignal, current: Int): Int = when (signal) {
        ControlSignal.LEFT_LOOK -> -1
        ControlSignal.RIGHT_LOOK -> 1
        else -> current
    }

    fun confirmationIndex(signal: ControlSignal, current: Int): Int = when (signal) {
        ControlSignal.LEFT_LOOK -> 0
        ControlSignal.RIGHT_LOOK -> 1
        else -> current
    }

    fun acceptsHeadsetCommands(destination: AppDestination, mainForeground: Boolean, wizardForeground: Boolean): Boolean =
        if (destination == AppDestination.WIZARD_GAME) wizardForeground
        else mainForeground && destination !in setOf(
            AppDestination.SETTINGS, AppDestination.DEVICE_STATUS, AppDestination.COLLECTION
        )
}

fun AppDestination.parentDestination(): AppDestination? = when (this) {
    AppDestination.HOME -> null
    AppDestination.ASYNC_MAZE, AppDestination.SNAKE_CLIMB, AppDestination.WIZARD_GAME,
    AppDestination.CHINESE_CHESS, AppDestination.DOUDIZHU, AppDestination.MAHJONG,
    AppDestination.TV, AppDestination.MUSIC -> AppDestination.ENTERTAINMENT
    AppDestination.COLLECTION -> AppDestination.DEVICE_STATUS
    else -> AppDestination.HOME
}
