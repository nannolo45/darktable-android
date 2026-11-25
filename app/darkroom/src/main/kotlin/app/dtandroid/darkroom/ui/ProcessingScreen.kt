// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.darkroom.speed.ProcessingResources
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief P4b: Settings › processing. What the screen shows; the app fills it. [gpuBusy]: the GPU is being switched on and
 * checked (the step); [gpuNote]: the last result (why it is off, or the check's numbers). [speedRunning]: the step of
 * "measure speed" in progress; [speedText]: its last result, which "copy" puts on the clipboard.
 */
data class ProcessingUi(
    val gpu: Boolean = false,
    val gpuBusy: String? = null,
    val gpuNote: String? = null,
    /** brief P4d: what "copy GPU details" copies (library tries, platforms, darktable's OpenCL log); null = none yet */
    val gpuDetails: String? = null,
    /** brief P4e: the GPU check found the GPU slower than the CPU (its text); the GPU may be forced on after a second confirmation */
    val gpuSlower: String? = null,
    val memory: ProcessingResources.Choice = ProcessingResources.Choice.NORMAL,
    /** the memory plan in force (from the engine's start) */
    val memoryNow: String? = null,
    /** the setting differs from the plan in force: it applies at the next start */
    val memoryPending: Boolean = false,
    val speedRunning: String? = null,
    val speedText: String? = null,
) {
    val busy: Boolean get() = gpuBusy != null || speedRunning != null
}

@Composable
fun ProcessingScreen(
    ui: ProcessingUi,
    onGpu: (Boolean) -> Unit,
    onMemory: (ProcessingResources.Choice) -> Unit,
    onMeasure: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    onCopyGpu: () -> Unit = {},
    onGpuForce: () -> Unit = {},
) {
    val c = DtTheme.colors
    var confirmForce by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.l, vertical = DtTheme.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
        ) {
            DtText("processing", DtTheme.type.title, c.textStrong, Modifier.weight(1f))
            DtTextButton("back", onBack, enabled = !ui.busy, compact = true)
        }
        Divider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Heading("use the GPU (OpenCL)")
            Row(Modifier.padding(horizontal = DtTheme.space.panel).semantics { contentDescription = "gpu ${if (ui.gpu) "on" else "off"}" },
                horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                DtTextButton("off", { onGpu(false) }, selected = !ui.gpu, enabled = !ui.busy)
                DtTextButton("on", { onGpu(true) }, selected = ui.gpu, enabled = !ui.busy)
            }
            Note("Off by default. Switching it on starts darktable's OpenCL and compares one test render on the GPU with " +
                "the CPU's; the GPU stays on only if the two pictures agree and the GPU is not slower (you can still force a slower one on). If the GPU fails during an edit, that render " +
                "is done on the CPU and the crash log says so.")
            ui.gpuBusy?.let { Line("$it…", Modifier.semantics { contentDescription = "gpu busy" }) }
            ui.gpuNote?.let { Line(it, Modifier.semantics { contentDescription = "gpu note" }) }
            // brief P4e: a GPU slower than the CPU stays off unless the user confirms twice
            if (ui.gpuSlower != null && !ui.gpu) {
                if (!confirmForce) Row(Modifier.padding(horizontal = DtTheme.space.panel)) {
                    DtTextButton("use the GPU anyway", { confirmForce = true }, enabled = !ui.busy)
                } else {
                    Line("The GPU was slower than the processor in the test, so editing will likely be slower with it. Use it anyway?",
                        Modifier.semantics { contentDescription = "gpu force confirm" })
                    Row(Modifier.padding(horizontal = DtTheme.space.panel), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                        DtTextButton("yes, use the GPU", { confirmForce = false; onGpuForce() }, enabled = !ui.busy)
                        DtTextButton("cancel", { confirmForce = false }, enabled = !ui.busy)
                    }
                }
            }
            Row(Modifier.padding(horizontal = DtTheme.space.panel)) {
                DtTextButton("copy GPU details", onCopyGpu, enabled = ui.gpuDetails != null && !ui.busy)
            }

            Heading("processing memory")
            Row(Modifier.padding(horizontal = DtTheme.space.panel), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                for (ch in listOf(ProcessingResources.Choice.LESS, ProcessingResources.Choice.NORMAL, ProcessingResources.Choice.MORE))
                    DtTextButton(ch.label, { onMemory(ch) }, selected = ch == ui.memory, enabled = !ui.busy)
            }
            Note("How much memory darktable plans with and how many decoded photos it keeps. \"more\" makes going back " +
                "and forth in a series and big exports faster but Android may stop the app sooner when other apps need " +
                "memory; \"less\" the other way round. Applies at the next start of the app.")
            ui.memoryNow?.let { Line("now: $it") }
            if (ui.memoryPending) Line("the new setting applies at the next start", Modifier.semantics { contentDescription = "memory pending" })

            Heading("speed")
            Row(Modifier.padding(horizontal = DtTheme.space.panel), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                DtTextButton("measure speed", onMeasure, enabled = !ui.busy)
                DtTextButton("copy", onCopy, enabled = ui.speedText != null && !ui.busy)
            }
            Note("Renders darktable's public test photo (fetched once from darktable's test set on GitHub, 16 MB) with the " +
                "default edit and a heavier one, whole photo and 100 %, three times each, on the CPU and (when on) the " +
                "GPU. Takes a few minutes. \"copy\" puts the times on the clipboard; nothing is sent.")
            ui.speedRunning?.let { Line("$it…", Modifier.semantics { contentDescription = "speed running" }) }
            ui.speedText?.let { t ->
                DtText(t.trimEnd(), DtTheme.type.small, c.text,
                    Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.s)
                        .semantics { contentDescription = "speed result" }, maxLines = 400)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    DtText(text, DtTheme.type.label, DtTheme.colors.textStrong,
        Modifier.fillMaxWidth().padding(start = DtTheme.space.panel, end = DtTheme.space.panel, top = DtTheme.space.l, bottom = DtTheme.space.s))
}

@Composable
private fun Note(text: String) {
    DtText(text, DtTheme.type.small, DtTheme.colors.textMuted,
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.s), maxLines = 8)
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier) {
    DtText(text, DtTheme.type.small, DtTheme.colors.text,
        modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xxs), maxLines = 8)
}
