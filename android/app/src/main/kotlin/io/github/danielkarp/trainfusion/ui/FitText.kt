package io.github.danielkarp.trainfusion.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** One line that shrinks (down to `minScale` of its size) rather than be cut off: SwiftUI's lineLimit(1).minimumScaleFactor. */
@Composable
fun FitText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
    weight: FontWeight? = null,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text, modifier,
        style = style.merge(TextStyle(fontSize = size, fontWeight = weight, textAlign = textAlign,
            color = if (color == Color.Unspecified) LocalContentColor.current else color)),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * minScale, maxFontSize = size, stepSize = 0.25.sp),
    )
}
