package dev.gaiusred.tuiosmobile.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign

enum class Destination { Sessions, Mail, Terminal, Settings }

@Composable
fun BottomDestinations(selected: Destination, onSelect: (Destination) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp) {
        Row(Modifier.selectableGroup().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { destination ->
                val enabled = destination != Destination.Terminal
                val active = selected == destination
                val ink = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(27.dp),
                    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else androidx.compose.ui.graphics.Color.Transparent) {
                    Column(Modifier.testTag("tab-${destination.name}")
                        .selectable(active, enabled = enabled, role = Role.Tab) { onSelect(destination) }
                        .semantics {
                            if (!enabled) contentDescription = "Terminal, coming later"
                        }.defaultMinSize(minHeight = 58.dp).padding(vertical = 7.dp, horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        DestinationIcon(destination, Modifier.size(24.dp), ink)
                        Spacer(Modifier.height(3.dp))
                        Text(destination.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = ink,
                            softWrap = true, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Small original line icons, drawn from simple geometry. No platform asset copies. */
@Composable
private fun DestinationIcon(destination: Destination, modifier: Modifier, ink: androidx.compose.ui.graphics.Color) {
    Canvas(modifier) {
        val unit = size.width / 24
        val stroke = Stroke(1.9f * unit)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (destination) {
            Destination.Sessions -> {
                drawRoundRect(ink, p(3f, 4f), Size(18 * unit, 14 * unit), CornerRadius(5 * unit), style = stroke)
                drawPath(Path().apply { moveTo(7 * unit, 17 * unit); lineTo(5 * unit, 21 * unit)
                    lineTo(12 * unit, 18 * unit) }, ink, style = stroke)
                for (x in listOf(8f, 12f, 16f)) drawCircle(ink, unit, p(x, 11f))
            }
            Destination.Mail -> {
                drawRoundRect(ink, p(3f, 5f), Size(18 * unit, 14 * unit), CornerRadius(3 * unit), style = stroke)
                drawLine(ink, p(4f, 7f), p(12f, 13f), 1.9f * unit)
                drawLine(ink, p(12f, 13f), p(20f, 7f), 1.9f * unit)
            }
            Destination.Terminal -> {
                drawRoundRect(ink, p(3f, 4f), Size(18 * unit, 16 * unit), CornerRadius(3 * unit), style = stroke)
                drawLine(ink, p(7f, 8f), p(10f, 11f), 1.9f * unit)
                drawLine(ink, p(10f, 11f), p(7f, 14f), 1.9f * unit)
                drawLine(ink, p(12f, 15f), p(17f, 15f), 1.9f * unit)
            }
            Destination.Settings -> {
                drawCircle(ink, 7.5f * unit, p(12f, 12f), style = stroke)
                drawCircle(ink, 2.7f * unit, p(12f, 12f), style = stroke)
                for (i in 0..7) {
                    val angle = i * kotlin.math.PI / 4
                    drawLine(ink, p(12f + 7.5f * kotlin.math.cos(angle).toFloat(), 12f + 7.5f * kotlin.math.sin(angle).toFloat()),
                        p(12f + 10f * kotlin.math.cos(angle).toFloat(), 12f + 10f * kotlin.math.sin(angle).toFloat()), 1.9f * unit)
                }
            }
        }
    }
}

