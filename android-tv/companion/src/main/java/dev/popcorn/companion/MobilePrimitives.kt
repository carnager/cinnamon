package dev.popcorn.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CinnamonBrand(
    modifier: Modifier = Modifier,
    showName: Boolean = true,
    markSize: Int = 34,
    fontSize: Int = 19,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Canvas(Modifier.size(markSize.dp)) {
            val stroke = (size.minDimension * .075f).coerceAtLeast(2f)
            listOf(.43f, .31f, .19f).forEach { radiusFraction ->
                val radius = size.minDimension * radiusFraction
                drawArc(
                    color = Accent,
                    startAngle = 42f,
                    sweepAngle = 276f,
                    useCenter = false,
                    topLeft = center - Offset(radius, radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (showName) {
            Text(
                "Cinnamon",
                color = TextColor,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.25).sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun StatusMessage(message: String, success: Boolean, modifier: Modifier = Modifier) {
    if (message.isBlank()) return
    val color = if (success) Teal else ErrorRed
    Box(
        modifier
            .background(color.copy(alpha = .10f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = .30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(message, color = color, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }
}
