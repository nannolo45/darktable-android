// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.dtandroid.darkroom.ui.Divider
import app.dtandroid.darkroom.ui.DtText
import app.dtandroid.darkroom.ui.DtTextButton
import app.dtandroid.darkroom.ui.PanelHeading
import app.dtandroid.ui.theme.DtTheme

/** A labelled one-line text field in the darkroom's slider-row look. */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false, enabled: Boolean = true) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs)) {
        DtText(label, DtTheme.type.label, c.text)
        Box(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
            if (value.isEmpty()) DtText(hint, DtTheme.type.body, c.textDim)
        }
    }
}

/**
 * Add the LightPhotoLibrary server, or change it (brief P2e item 2): its address (as in the shell),
 * its password if it has one, and the name LPL shows for this device. Saved only after the server
 * answered as LPL and accepted the password.
 */
@Composable
fun ServerScreen(vm: AppViewModel) = Page {
    val f by vm.serverForm.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(if (f.editing != null) "LightPhotoLibrary server" else "add LightPhotoLibrary") {
            DtTextButton("back", { vm.show(Screen.LIBRARY) })
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = DtTheme.sizes.formMaxWidth)) {
            f.then?.let {
                Paragraph("The link you opened comes from ${it.server.url}. The app only talks to servers you add here. " +
                    "Add this one if it is your LightPhotoLibrary; the photo opens afterwards.")
            }
            Field("server address", f.address, { vm.editServerForm(f.copy(address = it)) }, "for example 192.0.2.10:8080",
                enabled = f.editing == null && f.busy == null)
            Field("password", f.password, { vm.editServerForm(f.copy(password = it)) },
                if (f.editing != null) "unchanged (type to change)" else "leave empty if LightPhotoLibrary has none", password = true, enabled = f.busy == null)
            Field("name of this device in LightPhotoLibrary", f.deviceName, { vm.editServerForm(f.copy(deviceName = it)) }, "for example Phone",
                enabled = f.busy == null)
            Paragraph("LightPhotoLibrary shows this name next to the photos you edit here (\"on Phone: waiting to sync\"). " +
                "The password is kept encrypted on this phone and sent only to this server.", muted = true)
            f.busy?.let { Paragraph(it, muted = true) }
            f.error?.let { Paragraph(it) }
            Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                DtTextButton(if (f.editing != null) "check and save" else "check and add", { vm.saveServer() }, enabled = f.busy == null)
                f.editing?.let { o -> DtTextButton("remove server", { vm.removeServer(o) }, enabled = f.busy == null) }
            }
            if (f.editing != null) Paragraph("this install's id: ${vm.servers.installId}", muted = true)
        }
    }
}

/** A link from LightPhotoLibrary is being opened (brief P2e item 4). */
@Composable
fun HandOffScreen(vm: AppViewModel) = Page {
    val u by vm.handOff.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("from LightPhotoLibrary") { DtTextButton("back", { vm.cancelHandOff() }) }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (val s = u) {
                null -> Paragraph("…", muted = true)
                is HandOffUi.Invalid -> {
                    PanelHeading("this link cannot be opened")
                    Paragraph("The app checked the link and did not use it: ${s.reason}.")
                    Paragraph("Open the photo again from LightPhotoLibrary. If this keeps happening, send a screenshot.", muted = true)
                }
                is HandOffUi.UnknownServer -> {
                    PanelHeading("a server the app does not know")
                    Paragraph("The link asks to open ${app.dtandroid.darkroom.Words.count(s.handOff.ids.size, "photo")} from ${s.handOff.server.url}, which is not in the app's list. " +
                        "The app contacts only servers you have added.")
                    Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                        DtTextButton("add this server", { vm.showServerForm(s.handOff) })
                        DtTextButton("not now", { vm.cancelHandOff() })
                    }
                }
                is HandOffUi.Fetching -> {
                    PanelHeading(s.handOff.title ?: "LightPhotoLibrary")
                    Paragraph(s.text)
                    if (s.handOff.ids.size > 1) Paragraph("${s.handOff.ids.size} photos; the others appear in the filmstrip and are fetched when you open them.", muted = true)
                }
                is HandOffUi.Failed -> {
                    PanelHeading("the photo could not be opened")
                    Paragraph(s.text)
                    Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                        DtTextButton("try again", { vm.startHandOff(s.handOff) })
                        DtTextButton("back to the list", { vm.cancelHandOff() })
                    }
                }
            }
        }
    }
}
