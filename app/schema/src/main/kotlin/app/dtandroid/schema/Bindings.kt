// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

/** The binding of an editable control, or null for containers and labels. */
fun Control.binding(): Binding? = when (this) {
    is Control.Slider -> binding
    is Control.ComboBox -> binding
    is Control.Toggle -> binding
    else -> null
}
