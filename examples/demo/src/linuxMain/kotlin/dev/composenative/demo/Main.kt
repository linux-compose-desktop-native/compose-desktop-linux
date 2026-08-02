package dev.composenative.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import dev.composenative.composeDesktopApplication

fun main() = composeDesktopApplication(title = "Compose Desktop Demo") {
    DemoContent()
}

@Composable
private fun DemoContent() {
    Layout(
        content = {},
        modifier = Modifier.drawBehind {
            drawRect(
                color = Color(0xFF5F87FF),
                topLeft = Offset(48f, 48f),
                size = Size(320f, 96f),
            )
        },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}
