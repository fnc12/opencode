package studio.eugenezakharov.opencode.ui.session

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Placeholder conversation shown during the initial history load instead of a
 * bare spinner — grey bubble rows with a shimmer sweep, so the screen reads as
 * "content is coming" rather than "nothing here". Replaced by the real list once
 * the newest page lands (usually well under a second, see pagination). Mirrors
 * iOS `MessageSkeletonView`.
 */
@Composable
fun MessageSkeleton(modifier: Modifier = Modifier) {
    // A fixed, deterministic layout — alternating assistant (wide, left) and user
    // (narrower, right) rows of varying line counts, resembling a real transcript
    // without looking mechanically uniform.
    val rows = listOf(
        SkeletonRow(fromUser = false, lines = 3, widthFraction = 0.82f),
        SkeletonRow(fromUser = true, lines = 1, widthFraction = 0.50f),
        SkeletonRow(fromUser = false, lines = 2, widthFraction = 0.70f),
        SkeletonRow(fromUser = false, lines = 4, widthFraction = 0.85f),
        SkeletonRow(fromUser = true, lines = 2, widthFraction = 0.55f),
        SkeletonRow(fromUser = false, lines = 2, widthFraction = 0.66f),
    )

    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1150), RepeatMode.Restart),
        label = "shimmer",
    )
    // A moving highlight band swept across a wide gradient (translate the stops by
    // the animated fraction over roughly twice the screen width).
    val base = Color.Gray.copy(alpha = 0.22f)
    val highlight = Color.Gray.copy(alpha = 0.10f)
    val sweep = 900f
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(sweep * shimmer - sweep, 0f),
        end = Offset(sweep * shimmer, 0f),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("session.skeleton"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for (row in rows) SkeletonBubble(row, brush)
    }
}

@Composable
private fun SkeletonBubble(row: SkeletonRow, brush: Brush) {
    Row(Modifier.fillMaxWidth()) {
        if (row.fromUser) Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth(row.widthFraction)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Gray.copy(alpha = 0.12f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (line in 0 until row.lines) {
                // The last line of a paragraph is short, like real wrapped text.
                val lineWidth = if (line == row.lines - 1) 0.6f else 1f
                Spacer(
                    Modifier
                        .fillMaxWidth(lineWidth)
                        .height(12.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(brush),
                )
            }
        }
        if (!row.fromUser) Spacer(Modifier.weight(1f))
    }
}

private data class SkeletonRow(val fromUser: Boolean, val lines: Int, val widthFraction: Float)
