package io.github.danielkarp.trainfusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The pieces of a grouped list, as an iOS Form's (Settings, a ride's screen in History).

/** A section: a header, rows on a card, a footer. */
@Composable
fun FormSection(header: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) Text(header.uppercase(), fontSize = 13.sp, color = Palette.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().background(Palette.background, RoundedCornerShape(12.dp)), content = content)
        if (footer != null) Text(footer, fontSize = 13.sp, color = Palette.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
fun FormDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.separator, thickness = 0.5.dp)

/** A label and its value, on one line. */
@Composable
fun FormValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.width(16.dp).weight(1f))
        Text(value, color = Palette.secondary, textAlign = TextAlign.End, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** A row that's a button: blue, or red for one that deletes. */
@Composable
fun FormButtonRow(text: String, enabled: Boolean = true, destructive: Boolean = false, centered: Boolean = false,
                  detail: String? = null, onClick: () -> Unit) {
    val color = if (destructive) Palette.red else Palette.blue
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f))
        if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.secondary)
    }
}

/** A row that opens a screen of its own. */
@Composable
fun FormLinkRow(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.gray2)
    }
}

/** A pushed screen's bar: back, and its title. */
@Composable
fun FormTopBar(title: String, back: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, back, tint = Palette.blue) }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 16.dp))
    }
}
