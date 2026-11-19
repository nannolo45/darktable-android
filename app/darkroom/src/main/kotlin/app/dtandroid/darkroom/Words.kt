// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

/** Brief P2f: messages correct in number ("1 photo", "3 photos"), never "photo(s)". */
object Words {
    /** "1 photo", "2 photos", "no photos"; [plural] for words whose plural is not [one] + "s". */
    fun count(n: Int, one: String, plural: String = one + "s"): String = when (n) {
        0 -> "no $plural"
        1 -> "1 $one"
        else -> "$n $plural"
    }
}
