package com.example.input_ds.ui.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.input_ds.calibration.*
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.ui.theme.*
import com.example.input_ds.ui.components.InputContextBoundary
import java.util.Locale

@Composable
fun ScanCalibrationScreen(
    viewModel: ScanCalibrationViewModel,
    headsetReady: Boolean,
    onSave: (Long) -> Unit,
    onExit: () -> Unit
) {
    val ui by viewModel.state.collectAsState()
    val session = ui.session
    InputContextBoundary(if (session.phase == CalibrationPhase.RUNNING || session.phase == CalibrationPhase.FEEDBACK)
        "practice" else session.phase)
    LaunchedEffect(headsetReady) { viewModel.setHeadsetReady(headsetReady) }
    LaunchedEffect(ui.exitRequested) {
        if (ui.exitRequested) {
            ui.savedIntervalMs?.let(onSave)
            onExit()
        }
    }
    DisposableEffect(viewModel) {
        viewModel.activate()
        onDispose { viewModel.suspendPractice() }
    }
    AuroraBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("扫描速度校准", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = viewModel::discard) { Text("退出") }
            }
            when (session.phase) {
                CalibrationPhase.READY -> GlassPanel(Modifier.fillMaxWidth()) {
                    Text("12 次目标选择", style = MaterialTheme.typography.headlineMedium)
                    Text("等待目标高亮后咬牙确认。每 4 次调整一次速度。")
                    Text("当前速度 ${session.baselineMs} 毫秒", color = TextGray)
                    if (!headsetReady) Text("耳机练习需先在设置中启用耳机控制。", color = TextGray)
                }
                CalibrationPhase.RUNNING, CalibrationPhase.FEEDBACK -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val number = if (session.phase == CalibrationPhase.FEEDBACK) session.trials.size else session.trials.size + 1
                        Text("第 $number / 12 次", style = MaterialTheme.typography.titleMedium)
                        Text("${if (session.mode == CalibrationMode.HEADSET) "耳机" else "模拟"}  ${session.intervalMs} 毫秒")
                    }
                    GlassPanel(Modifier.fillMaxWidth()) {
                        val target = if (session.phase == CalibrationPhase.FEEDBACK) session.trials.last().targetIndex else session.targetIndex
                        Text("请选择：${ScanCalibration.CHOICES[target]}", style = MaterialTheme.typography.headlineMedium)
                        ScanCalibration.CHOICES.chunked(2).forEachIndexed { row, choices ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                choices.forEachIndexed { column, choice ->
                                    val highlighted = session.highlightedIndex == row * 2 + column
                                    GlassPanel(
                                        Modifier.weight(1f).semantics { selected = highlighted },
                                        selected = highlighted
                                    ) {
                                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                                            Text(choice, style = MaterialTheme.typography.headlineMedium,
                                                color = if (highlighted) AuroraVioletBright else TextWhite)
                                        }
                                    }
                                }
                            }
                        }
                        if (session.phase == CalibrationPhase.FEEDBACK) {
                            val trial = session.trials.last()
                            Text(when {
                                trial.selectedIndex == null -> "两轮内未确认"
                                trial.correct -> "选择正确"
                                else -> "选中了${ScanCalibration.CHOICES[trial.selectedIndex]}"
                            }, color = if (trial.correct) AuroraOk else AuroraWarn)
                        } else Text("咬牙确认，左看暂停", color = TextGray)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (session.mode == CalibrationMode.SIMULATION) {
                            AuroraButton("确认当前项", {
                                viewModel.handleSignal(ControlSignal.BITE, CalibrationMode.SIMULATION)
                            }, Modifier.weight(1f), enabled = session.phase == CalibrationPhase.RUNNING)
                        }
                        AuroraButton("暂停", viewModel::pause, Modifier.weight(1f))
                    }
                }
                CalibrationPhase.PAUSED -> GlassPanel(Modifier.fillMaxWidth()) {
                    Text("练习已暂停", style = MaterialTheme.typography.headlineMedium)
                    Text("继续后重新开始当前题，已完成的记录保留。")
                    if (session.mode == CalibrationMode.HEADSET && !headsetReady) {
                        Text("耳机控制已停用，请返回设置重新启用。", color = AuroraWarn)
                    }
                }
                CalibrationPhase.COMPLETE -> GlassPanel(Modifier.fillMaxWidth()) {
                    val recommendation = session.recommendation
                    Text(if (recommendation == null) "暂不推荐调整" else "推荐 ${recommendation.intervalMs} 毫秒",
                        style = MaterialTheme.typography.headlineMedium)
                    Text("原速度 ${session.baselineMs} 毫秒", color = TextGray)
                    Row(Modifier.fillMaxWidth()) {
                        Text("速度", Modifier.weight(1f))
                        Text("正确", Modifier.weight(1f))
                        Text("平均用时", Modifier.weight(1f))
                    }
                    session.groups.forEach { group ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${group.intervalMs} 毫秒", Modifier.weight(1f))
                            Text("${group.correctCount} / 4", Modifier.weight(1f))
                            Text(String.format(Locale.ROOT, "%.1f 秒", group.meanElapsedMs / 1000.0), Modifier.weight(1f))
                        }
                    }
                    Text(if (recommendation == null) "每组正确不足 3 次，请重新练习。"
                        else "优先选择正确率最高的一组，再比较平均用时。", color = TextGray)
                    if (recommendation != null) {
                        Text("接受后更新全局扫描速度，首页和娱乐模块也会使用。", color = TextGray)
                    }
                }
            }
            if (ui.actions.isNotEmpty()) {
                Text("左看或右看改变轮转方向，咬牙选择。", color = TextGray)
                ui.actions.forEachIndexed { index, action ->
                    SelectableGlassPanel(ui.menuIndex == index, { viewModel.select(action) },
                        Modifier.fillMaxWidth(), PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(action.label, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (ui.logError) Text("练习记录保存失败。", color = AuroraWarn)
        }
    }
}
