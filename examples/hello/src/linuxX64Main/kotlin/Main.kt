import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import dev.composenative.composeDesktopApplication

/**
 * The smallest thing that proves the published contract works: name the plugin,
 * call one function, get a native window.
 *
 * Drawing is deliberately primitive. Until compose-foundation is published for
 * linuxX64 there is no Text, Box or Column to reach for.
 */
fun main() = composeDesktopApplication(title = "Hello") {
    Layout(
        modifier = Modifier.drawBehind {
            drawRect(Color(0xFFE0533B), Offset(60f, 60f), Size(200f, 120f))
            drawRect(Color(0xFF3A6EA5), Offset(300f, 60f), Size(200f, 120f))
        },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}
