package com.watchcoach.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchcoach.app.data.health.DaySteps
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun StepBarChart(
    days: List<DaySteps>,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    barColor: Color = MaterialTheme.colorScheme.primary,
    todayBarColor: Color = MaterialTheme.colorScheme.tertiary,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    val valueStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    val description = if (days.isEmpty()) {
        "No step history"
    } else {
        "Steps for the last 7 days. " + days.joinToString { "${it.date.dayOfWeek} ${it.steps}" }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = description },
    ) {
        if (days.isEmpty()) return@Canvas
        val maxSteps = max(days.maxOf { it.steps }, 1L).toFloat()
        val labelBand = 22.dp.toPx()
        val topBand = 18.dp.toPx()
        val chartHeight = size.height - labelBand - topBand
        val slotWidth = size.width / days.size
        val barWidth = slotWidth * 0.55f
        val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

        days.forEachIndexed { index, day ->
            val ratio = (day.steps / maxSteps).coerceIn(0f, 1f)
            val barHeight = max(ratio * chartHeight, if (day.steps > 0) 4.dp.toPx() else 0f)
            val left = slotWidth * index + (slotWidth - barWidth) / 2f
            val top = topBand + (chartHeight - barHeight)
            val color = if (day.date == today) todayBarColor else barColor
            drawRoundRect(
                color = color.copy(alpha = if (day.date == today) 1f else 0.85f),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )

            val value = formatSteps(day.steps)
            val valueLayout = measurer.measure(value, valueStyle)
            drawText(
                textLayoutResult = valueLayout,
                topLeft = Offset(
                    left + (barWidth - valueLayout.size.width) / 2f,
                    (top - valueLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
            )

            val label = day.date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
            val labelLayout = measurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(
                    left + (barWidth - labelLayout.size.width) / 2f,
                    size.height - labelBand + 4.dp.toPx(),
                ),
            )
        }
    }
}

private fun formatSteps(steps: Long): String {
    return if (steps >= 1000) {
        val k = steps / 100 / 10.0
        if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format("%.1fk", k)
    } else {
        steps.toString()
    }
}
