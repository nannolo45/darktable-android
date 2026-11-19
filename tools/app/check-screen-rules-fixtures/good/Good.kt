// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture for tools/app/check-screen-rules.py --self-test: nothing here may be reported.
package fixture

/* a comment may say 16.dp or Color.Red or "#ffffff" */
fun good() {
    val a = Modifier.padding(DtTheme.space.panel) // comments may mention 4.dp
    val b = DtTheme.type.body.copy(color = DtTheme.colors.text)
    val c = DtIconImage(DtIcon.Power, "power", DtTheme.colors.textBright, DtTheme.sizes.icon)
    val d = "a string with 12.dp and Color.Red inside"
    val e = Modifier.weight(1f).alpha(DtTheme.alpha.dimmed)
    val sp = DtTheme.space
    val f = sp.xs
}
