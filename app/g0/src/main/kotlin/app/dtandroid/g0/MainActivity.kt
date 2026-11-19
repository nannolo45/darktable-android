// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.g0

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The G0 diagnostic screen (brief G0). Default Material theme on purpose:
 * this is a test tool, not the product (the brief exempts it from rule 5).
 */
class MainActivity : ComponentActivity() {
    private val vm: DiagViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        vm.screenW = minOf(dm.widthPixels, dm.heightPixels)
        vm.screenH = maxOf(dm.widthPixels, dm.heightPixels)
        setContent { MaterialTheme { Scaffold { pad -> Screen(vm, Modifier.padding(pad)) } } }
    }
}

private fun share(ctx: Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, subject))
}

private fun ms(v: Double?) = v?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "-"

@Composable
private fun Screen(vm: DiagViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val pickRaw = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickRaw) }
    val pickXmp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickXmp) }
    val saveXmp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(vm::saveXmp)
    }
    var showLog by remember { mutableStateOf(false) }
    val rep = vm.report
    val ready = rep != null && rep.initCode == 0

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("dt-android G0 test app", style = MaterialTheme.typography.titleLarge)
        Text("Unofficial test build of a darktable port. Not a darktable release.", style = MaterialTheme.typography.bodySmall)

        // 1. engine status (experiment S4)
        val failed = vm.startError != null || (rep != null && !rep.ok)
        Card(
            colors = if (failed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(vm.startStatus, style = MaterialTheme.typography.titleMedium)
                if (rep == null && vm.startError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (rep != null) {
                    Text(
                        if (rep.ok) "All ${rep.pluginsLoaded} of ${rep.pluginsExpected} module plugins loaded"
                        else "NOT ALL PLUGINS LOADED: ${rep.pluginsLoaded} of ${rep.pluginsExpected}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(rep.summary(), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                vm.startError?.let { Text(it) }
            }
        }

        vm.busy?.let {
            Text(it)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        vm.message?.let { Text(it) }

        // 2. open a RAW (copied into app storage) and optionally its XMP
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready && vm.busy == null, onClick = { pickRaw.launch(arrayOf("*/*")) }) { Text("Open RAW") }
            OutlinedButton(enabled = ready && vm.rawFile != null, onClick = { pickXmp.launch(arrayOf("*/*")) }) { Text("Pick XMP") }
            Button(enabled = ready && vm.rawFile != null && vm.busy == null, onClick = { vm.openImage() }) { Text("Open") }
        }
        vm.rawFile?.let { Text("RAW: ${it.name}" + (vm.xmpFile?.let { x -> "\nXMP: ${x.name}" } ?: "\nno XMP picked")) }
        vm.xmpCheckText?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

        if (vm.sessionOpen) {
            if (vm.readOnly) Text("READ-ONLY: this XMP failed the version check, it will not be rewritten.", color = MaterialTheme.colorScheme.error)

            // 3. preview, exposure, region
            vm.preview?.let {
                Image(it.asImageBitmap(), "preview", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text("preview (whole image, ${EngineOps.PREVIEW_SIZE} px): ${ms(vm.previewMs)}")
            Text(String.format(Locale.ROOT, "exposure: %+.2f EV", vm.exposure))
            Slider(
                value = vm.exposure,
                onValueChange = vm::onExposure,
                onValueChangeFinished = { vm.onExposureDone() },
                valueRange = (vm.exposureBase - 3f)..(vm.exposureBase + 3f),
            )
            vm.region?.let {
                Text("screen-sized region: ${ms(vm.regionMs)}")
                Text(vm.regionDesc, style = MaterialTheme.typography.bodySmall)
                Image(it.asImageBitmap(), "region at 100 %", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }

            // 4. modules in the history
            Text("modules in the history", style = MaterialTheme.typography.titleMedium)
            vm.modules.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, Modifier.weight(1f))
                    Switch(checked = m.enabled, onCheckedChange = { on -> vm.toggle(m, on) })
                }
            }

            // 5. output
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = vm.busy == null, onClick = { vm.exportJpeg() }) { Text("Export JPEG") }
                Button(enabled = !vm.readOnly && vm.busy == null, onClick = { saveXmp.launch(vm.suggestedXmpName()) }) { Text("Save XMP") }
            }
            vm.exportText?.let { Text(it) }

            // 6. benchmark
            HorizontalDivider()
            Button(enabled = vm.busy == null, onClick = { vm.runBenchmark() }) { Text("Run benchmark") }
            vm.benchmarkText?.let { t ->
                Text(t, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Button(onClick = { share(ctx, "dt-android G0 benchmark", t) }) { Text("Share results") }
            }
        }

        // 7. engine log
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide engine log" else "Show engine log (${vm.logLines.size} lines)") }
            TextButton(onClick = { share(ctx, "dt-android G0 engine log", vm.logText()) }) { Text("Share log") }
        }
        if (showLog) {
            Text(
                vm.logLines.takeLast(400).joinToString("\n"),
                Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}
