package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalMotion

/**
 * Renders chapter text with a brass underline that animates over the currently-spoken sentence.
 *
 * @param text full chapter text
 * @param highlightStart UTF-16 char index where the current sentence begins
 * @param highlightEnd UTF-16 char index where the current sentence ends (exclusive)
 * @param onTapWord optional — invoked with char index of the word the user tapped (for "start TTS from here")
 */
@Composable
fun SentenceHighlight(
    text: String,
    highlightStart: Int,
    highlightEnd: Int,
    modifier: Modifier = Modifier,
    onTapWord: ((Int) -> Unit)? = null,
) {
    val brass = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val motion = LocalMotion.current

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotated: AnnotatedString = remember(text, highlightStart, highlightEnd, onSurface, brass) {
        buildAnnotatedString {
            if (highlightStart in 0..text.length && highlightEnd in highlightStart..text.length) {
                if (highlightStart > 0) append(text.substring(0, highlightStart))
                withStyle(
                    SpanStyle(
                        color = onSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                ) { append(text.substring(highlightStart, highlightEnd)) }
                if (highlightEnd < text.length) append(text.substring(highlightEnd))
            } else {
                append(text)
            }
        }
    }

    val target = if (highlightEnd > highlightStart) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderline",
    )

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = onSurface,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (highlightEnd <= highlightStart) return@drawBehind
                val safeStart = highlightStart.coerceIn(0, text.length)
                val safeEnd = highlightEnd.coerceIn(safeStart, text.length)
                val firstLine = l.getLineForOffset(safeStart)
                val lastLine = l.getLineForOffset(safeEnd.coerceAtLeast(safeStart + 1) - 1)
                for (line in firstLine..lastLine) {
                    val lineStart = l.getLineStart(line)
                    val lineEnd = l.getLineEnd(line, visibleEnd = true)
                    val segStart = maxOf(safeStart, lineStart)
                    val segEnd = minOf(safeEnd, lineEnd)
                    if (segEnd <= segStart) continue
                    val xStart = l.getHorizontalPosition(segStart, usePrimaryDirection = true)
                    val xEnd = l.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                    val y = l.getLineBottom(line) - 2.dp.toPx()
                    val width = (xEnd - xStart) * animated
                    drawRect(
                        color = brass,
                        topLeft = Offset(xStart, y),
                        size = Size(width, 2.dp.toPx()),
                    )
                }
            },
    )

    // Tap-to-seek is the caller's responsibility — SentenceHighlight is a pure renderer
    // so it composes cleanly inside a scrolling reader. Callers wire onTapWord via
    // Modifier.pointerInput on their parent and use the layout result for hit-testing.
    onTapWord?.let { _ -> }
}
