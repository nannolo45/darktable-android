// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/** Test tags of the native screens (instrumented tests). */
object ShellTags {
    const val SETUP = "lpl.setup"
    const val ADDRESS = "lpl.address"
    const val STATUS = "lpl.status"
    const val UNREACHABLE = "lpl.unreachable"
    const val MESSAGE = "lpl.message"
}

/** The native screen covering the web view, if any. */
@Composable
fun ShellOverlay(ui: ShellUi, actions: ShellActions) {
    when (val s = ui.screen) {
        is Screen.Setup -> SetupScreen(ui, s, actions)
        is Screen.Unreachable -> UnreachableScreen(s, actions)
        Screen.Web -> Unit
    }
}

@Composable
private fun Page(tag: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DtTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(DtTheme.space.xxl)
            .testTag(tag),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = DtTheme.sizes.formMaxWidth).fillMaxWidth()) {
            Spacer(Modifier.height(DtTheme.space.xxxl))
            content()
        }
    }
}

@Composable
private fun SetupScreen(ui: ShellUi, s: Screen.Setup, a: ShellActions) {
    val c = DtTheme.colors
    val focus = remember { FocusRequester() }
    val status = ui.setupStatus
    val busy = status == SetupStatus.Checking
    Page(ShellTags.SETUP) {
        ShellText(ShellTexts.SETUP_TITLE, DtTheme.type.title, c.textStrong)
        Spacer(Modifier.height(DtTheme.space.xxl))
        ShellText(ShellTexts.SETUP_LABEL, DtTheme.type.small, c.textMuted)
        Spacer(Modifier.height(DtTheme.space.xs))
        Box(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (ui.setupText.isEmpty()) ShellText(ShellTexts.SETUP_HINT, DtTheme.type.body, c.textDisabled)
            BasicTextField(
                value = ui.setupText,
                onValueChange = {
                    ui.setupText = it
                    if (!busy) ui.setupStatus = SetupStatus.Idle
                },
                enabled = !busy,
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { a.connect() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ShellTags.ADDRESS),
            )
        }
        Spacer(Modifier.height(DtTheme.space.s))
        val (line, lineColor) = when (status) {
            SetupStatus.Idle -> "" to c.textMuted
            SetupStatus.Checking -> ShellTexts.CHECKING to c.textMuted
            is SetupStatus.Problem -> status.text to c.textStrong
            is SetupStatus.NotLpl -> ShellTexts.NOT_LPL to c.textStrong
        }
        ShellText(line, DtTheme.type.small, lineColor, Modifier.heightIn(min = DtTheme.sizes.infoLine).testTag(ShellTags.STATUS), maxLines = 4)
        Spacer(Modifier.height(DtTheme.space.m))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m, Alignment.End)) {
            if (status is SetupStatus.NotLpl) {
                ShellButton(ShellTexts.CHANGE, onClick = { a.backToAddress() })
                ShellButton(ShellTexts.USE_ANYWAY, onClick = { a.useAnyway(status.origin) })
            } else {
                if (s.cancelable || busy) ShellButton(ShellTexts.CANCEL, onClick = { a.cancelSetup() })
                ShellButton(ShellTexts.CONNECT, onClick = { a.connect() }, enabled = !busy)
            }
        }
    }
    LaunchedEffect(s) { runCatching { focus.requestFocus() } }
}

@Composable
private fun UnreachableScreen(s: Screen.Unreachable, a: ShellActions) {
    val c = DtTheme.colors
    Page(ShellTags.UNREACHABLE) {
        ShellText(ShellTexts.UNREACHABLE_TITLE, DtTheme.type.title, c.textStrong)
        Spacer(Modifier.height(DtTheme.space.m))
        ShellText(s.address, DtTheme.type.body, c.textBright, maxLines = 3)
        Spacer(Modifier.height(DtTheme.space.m))
        ShellText(if (s.untrusted) ShellTexts.UNTRUSTED_HELP else ShellTexts.UNREACHABLE_HELP, DtTheme.type.small, c.textMuted, maxLines = 6)
        Spacer(Modifier.height(DtTheme.space.xxl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m, Alignment.End)) {
            ShellButton(ShellTexts.CHANGE_ADDRESS, onClick = { a.changeAddress() })
            ShellButton(if (s.retrying) ShellTexts.TRYING else ShellTexts.TRY_AGAIN, onClick = { a.tryAgain() }, enabled = !s.retrying)
        }
    }
}

/** The line at the bottom: "darktable is not installed", "downloading ...". */
@Composable
fun MessageBar(ui: ShellUi, a: ShellActions) {
    val m = ui.message ?: return
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().testTag(ShellTags.MESSAGE)) {
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(c.border))
        Row(
            Modifier.fillMaxWidth().background(c.header).padding(start = DtTheme.space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellText(m.text, DtTheme.type.label, c.textStrong, Modifier.weight(1f).padding(vertical = DtTheme.space.m), maxLines = 3)
            if (m.action != null && m.onAction != null) {
                ShellButton(m.action, onClick = m.onAction, modifier = Modifier.padding(start = DtTheme.space.m))
            }
            Box(
                Modifier
                    .size(DtTheme.sizes.touch)
                    .clickable(role = Role.Button) { a.closeMessage() }
                    .semantics { contentDescription = ShellTexts.CLOSE },
                contentAlignment = Alignment.Center,
            ) {
                DtIconImage(DtIcon.Close, null, c.text, DtTheme.sizes.icon)
            }
        }
    }
}

@Composable
private fun ShellText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** darktable's flat text button: header grey, small radius, `size.touch` high. */
@Composable
private fun ShellButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DtTheme.colors
    Box(
        modifier
            .height(DtTheme.sizes.touch)
            .clip(RoundedCornerShape(DtTheme.sizes.radiusControl))
            .background(c.header)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DtTheme.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        ShellText(label, DtTheme.type.label, if (enabled) c.textStrong else c.textDisabled)
    }
}
