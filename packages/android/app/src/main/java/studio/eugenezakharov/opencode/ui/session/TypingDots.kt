package studio.eugenezakharov.opencode.ui.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Pulsing dots shown where the reply will appear while the agent is thinking but
 *  hasn't streamed any text yet — Claude-style. Mirrors iOS TypingIndicator. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "phase",
    )
    Row(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val base = MaterialTheme.colorScheme.onSurfaceVariant
        for (i in 0..2) {
            val phase = (t - i + 3f) % 3f
            val alpha = 0.3f + 0.7f * (1f - (abs(phase - 0.5f).coerceAtMost(1.5f)) / 1.5f)
            Spacer(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(base.copy(alpha = alpha)),
            )
        }
    }
}
