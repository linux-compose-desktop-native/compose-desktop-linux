package dev.composenative.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import dev.composenative.ExternalTexture
import dev.composenative.composeDesktopApplication

fun main() = composeDesktopApplication(title = "Compose Desktop Demo") {
    DemoContent()
}

/**
 * Two external textures side by side with Compose content drawn over them.
 *
 * The point is that the textures are ordinary layout nodes: they are placed and
 * sized by the layout, not blitted across the window, and the Compose rectangle
 * composites on top of both.
 */
@Composable
private fun DemoContent() {
    val left = remember { CheckerTexture(0xFF3A6EA5) }
    val right = remember { CheckerTexture(0xFFB5651D) }

    Layout(
        contents = listOf(
            { ExternalTexture(left) },
            { ExternalTexture(right) },
            { Overlay() },
        ),
    ) { (leftMeasurables, rightMeasurables, overlayMeasurables), constraints ->
        val inset = 48
        val panelWidth = (constraints.maxWidth - inset * 3) / 2
        val panelHeight = constraints.maxHeight - inset * 2
        val panel = constraints.copy(
            minWidth = panelWidth, maxWidth = panelWidth,
            minHeight = panelHeight, maxHeight = panelHeight,
        )
        val leftPlaceables = leftMeasurables.map { it.measure(panel) }
        val rightPlaceables = rightMeasurables.map { it.measure(panel) }
        val overlayPlaceables = overlayMeasurables.map { it.measure(constraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            leftPlaceables.forEach { it.place(inset, inset) }
            rightPlaceables.forEach { it.place(inset * 2 + panelWidth, inset) }
            overlayPlaceables.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun Overlay() {
    Layout(
        modifier = Modifier.drawBehind {
            drawRect(
                color = Color(0xFF5F87FF),
                topLeft = Offset(size.width / 2 - 120f, size.height / 2 - 40f),
                size = Size(240f, 80f),
            )
        },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}
