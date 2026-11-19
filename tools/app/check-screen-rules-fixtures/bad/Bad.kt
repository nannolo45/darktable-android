// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture for tools/app/check-screen-rules.py --self-test: every line marked VIOLATION must be reported.
package fixture

fun bad() {
    val a = Modifier.padding(16.dp) // VIOLATION
    val b = TextStyle(fontSize = 13.sp) // VIOLATION
    val c = Color(0xFF262626) // VIOLATION
    val d = Color.Red // VIOLATION
    val e = background("#3b3b3b") // VIOLATION
    val f = Icons.Default.Close // VIOLATION
    val g = painterResource(R.drawable.power) // VIOLATION
    val h = drawPath(path, brush) // VIOLATION
    val i = Dp(4f) // VIOLATION
    val j = x.toFloat().dp // VIOLATION
    val k = 0xff262626 // VIOLATION
}
import androidx.compose.material3.Text // VIOLATION
