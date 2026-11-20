// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.ModuleInstance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brief P3b: style and preset files made by the tests, in darktable's file layout (`<darktable_style>`,
 * `<darktable_preset>`). For the stand-in engine the parameters are its JSON in `<op_params>` (FakeEngine reads
 * them so); nothing here comes from a person's files.
 */
object P3bFiles {
    data class Item(val op: String, val version: Int, val params: JsonObject?, val enabled: Boolean = true, val instanceName: String = "")

    fun item(m: ModuleInstance, change: Map<String, JsonPrimitive> = emptyMap()) =
        Item(m.op, m.version, JsonObject(m.params + change))

    fun style(name: String, description: String, items: List<Item>): ByteArray {
        val plugins = items.withIndex().joinToString("") { (i, it) ->
            "<plugin><num>$i</num><module>${it.version}</module><operation>${it.op}</operation>" +
                "<op_params>${it.params?.toString() ?: "00"}</op_params><enabled>${if (it.enabled) 1 else 0}</enabled>" +
                "<blendop_params>00</blendop_params><blendop_version>1</blendop_version><multi_priority>0</multi_priority>" +
                "<multi_name>${it.instanceName}</multi_name><multi_name_hand_edited>0</multi_name_hand_edited></plugin>"
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<darktable_style version=\"1.0\"><info><name>${esc(name)}</name>" +
            "<description>${esc(description)}</description></info><style>$plugins</style></darktable_style>\n").toByteArray()
    }

    fun preset(name: String, op: String, version: Int, params: JsonObject?, description: String = ""): ByteArray =
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<darktable_preset version=\"2.0\"><preset><name>${esc(name)}</name>" +
            "<description>${esc(description)}</description><operation>$op</operation><op_params>${params?.toString() ?: "00"}</op_params>" +
            "<op_version>$version</op_version><enabled>1</enabled><blendop_version>1</blendop_version></preset></darktable_preset>\n").toByteArray()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
