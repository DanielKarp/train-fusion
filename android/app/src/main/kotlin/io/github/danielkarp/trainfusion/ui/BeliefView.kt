package io.github.danielkarp.trainfusion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.DoorSliding
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielkarp.trainfusion.core.inference.Belief
import io.github.danielkarp.trainfusion.core.inference.modeNames
import io.github.danielkarp.trainfusion.core.inference.phoneNames
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the model believes, for someone glancing at their phone on a train: what the train is
 * doing, where it is, where it has stopped, and how good the measurements behind that are.
 * The numbers are under "Details" (ios/TrainRecorder/BeliefView.swift).
 *
 * Every card keeps its size whether or not it has anything to show yet (a greyed-out
 * skeleton stands in), so the screen doesn't jump as fixes arrive.
 */
@Composable
fun BeliefView(belief: Belief?, start: Long?, message: String?, waiting: String, units: Units, expandSensors: Boolean = false) {
    val b = belief ?: Belief()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.red)
        StatusCard(b, if (belief == null) waiting else null, units)
        PlaceCard(b, units)
        StopsCard(b, start)
        SensorRow(b, expandSensors)
        if (belief != null) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showDetails = !showDetails }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Details", style = MaterialTheme.typography.bodyMedium, color = Palette.secondary, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Palette.secondary,
                        modifier = Modifier.rotate(if (showDetails) 180f else 0f))
                }
                AnimatedVisibility(showDetails) { Box(Modifier.padding(top = 8.dp)) { DetailsView(b) } }
            }
        }
    }
}

/** Miles and mph, or kilometres and km/h (Settings). */
data class Units(val metric: Boolean) {
    val speedName: String get() = if (metric) "km/h" else "mph"
    fun speed(metresPerSecond: Double) = metresPerSecond * if (metric) 3.6 else 2.23694
    fun distance(metres: Double) = String.format(Locale.US, "%.1f %s", metres / if (metric) 1000.0 else 1609.344, if (metric) "km" else "mi")
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

private val Mono = TextStyle(fontFeatureSettings = "tnum")

/** The most probable mode (the first largest, as Swift's max(by:)), or -1 before there's a belief. */
private fun Belief.mode(): Int {
    if (modeP.isEmpty()) return -1
    var best = 0
    for (i in modeP.indices) if (modeP[best] < modeP[i]) best = i
    return best
}

/** One line: what the train is doing right now. */
@Composable
private fun StatusCard(belief: Belief, waiting: String?, units: Units) {
    val mode = belief.mode()
    val moving = belief.stops > 0 && mode == 0
    data class Look(val text: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color)
    val look = when {
        waiting != null -> Look(waiting, Icons.Filled.Hearing, Palette.secondary)
        // before the first door chime the model can't tell a platform from a train
        belief.stops == 0 -> Look("Waiting for the first stop…", Icons.Filled.Hearing, Palette.secondary)
        mode == 0 -> {
            // 90 % of the belief lies within 1.645 sd
            val v = units.speed(belief.speedMean)
            val ci = units.speed(1.645 * belief.speedSd)
            Look("${v.roundToInt()} ± ${ceil(ci).toInt()} ${units.speedName}", Icons.Filled.Tram, Palette.blue)
        }
        mode == 2 -> Look("Doors opening", Icons.Filled.DoorSliding, Palette.green)
        mode == 3 -> Look("Doors open", Icons.Filled.DoorSliding, Palette.green)
        mode == 4 -> Look("Doors closing", Icons.Filled.DoorFront, Palette.orange)
        else -> Look("Train stopped", Icons.Filled.Tram, Palette.teal)
    }
    Card {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(28.dp))
            FitText(look.text, 22.sp, Modifier.weight(1f), weight = FontWeight.SemiBold, style = Mono)
            val a = belief.acceleration
            if (moving && a != null) {
                Text(String.format(Locale.US, "%+.1f m/s²", if (a == 0.0) 0.0 else a), style = Mono.merge(MaterialTheme.typography.bodyMedium),
                    color = Palette.secondary, maxLines = 1)
            }
        }
    }
}

/** How the position card draws the line: on the way between two stations; pulling in; or standing at a station. */
private enum class PlacePhase { BETWEEN, ARRIVING, STATION }

/** The station of the stop before the last one, if it can stand on the line next to the others. */
private fun previous(b: Belief, p: Belief.Place): String? {
    if (b.stopLog.size < 2) return null
    val name = b.stopLog[b.stopLog.size - 2].station ?: return null
    return if (name != p.last && name != p.next) name else null
}

private fun phase(b: Belief, p: Belief.Place): PlacePhase {
    if (previous(b, p) == null || b.modeP.isEmpty()) return PlacePhase.BETWEEN
    // Only once this stop has been counted, which happens about 3 s into the chime: the
    // display runs ahead of that, and until then `p` is still the leg being finished.
    if (b.stopPending || b.distanceSinceStop >= 50) return PlacePhase.BETWEEN
    val mode = b.mode()
    if (mode == 2) return PlacePhase.ARRIVING  // the door-open chime
    // doors open, closing or closed again; and after that until the train is clearly moving
    if (mode >= 3 || b.modeP[0] < 0.8) return PlacePhase.STATION
    return PlacePhase.BETWEEN
}

/** Where the train is: the last station, the next one, and how far along it has got. */
@Composable
private fun PlaceCard(belief: Belief, units: Units) {
    val p = belief.place
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p != null) {
                    Text(if (p.southbound) "Southbound" else "Northbound", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    val other = p.alternative
                    if (other != null && p.confidence <= 0.9) {
                        FitText("or ${stationShortName(other)}", 12.sp, Modifier.weight(1f, fill = false), color = Palette.secondary)
                    }
                    val (word, color) = when {
                        p.confidence > 0.9 -> "confident" to Palette.green
                        p.confidence > 0.6 -> "fairly sure" to Palette.yellow
                        else -> "a guess" to Palette.orange
                    }
                    Text(word, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.background(color.copy(alpha = 0.25f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                } else {
                    Icon(Icons.Filled.LocationSearching, contentDescription = null, tint = Palette.secondary, modifier = Modifier.size(18.dp))
                    Text("Waiting for position fix…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Palette.secondary)
                    Spacer(Modifier.weight(1f))
                }
            }
            if (p != null) {
                StationBar(p, previous(belief, p), belief.legs.lastOrNull(), phase(belief, p), (belief.modeP.firstOrNull() ?: 0.0) > 0.5, units)
            } else {
                SegmentSkeleton()
                Row(Modifier.fillMaxWidth().height(LABEL_HEIGHT), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Placeholder("Station name", 15.sp, weight = FontWeight.SemiBold)
                        Placeholder(units.distance(0.0), 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Placeholder("Station name", 15.sp, weight = FontWeight.SemiBold)
                        Placeholder(units.distance(0.0), 13.sp)
                    }
                }
            }
        }
    }
}

private val LABEL_HEIGHT: Dp = 40.dp

/** A greyed-out stand-in for text not there yet (SwiftUI's .redacted). */
@Composable
private fun Placeholder(text: String, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, weight: FontWeight? = null) {
    Text(text, fontSize = size, fontWeight = weight, color = Color.Transparent, maxLines = 1,
        modifier = modifier.background(Palette.gray4.copy(alpha = 0.7f), RoundedCornerShape(4.dp)))
}

/**
 * The line with its stations and the train. Stations are placed along it by a number: 0 the
 * left end, 1 the right end, outside that off the card. The three phases differ only in
 * those numbers, so a change of phase slides the stations to their new places (and nothing
 * else is animated: the train's own progress, or a jump to another station, is not).
 */
@Composable
private fun StationBar(place: Belief.Place, previous: String?, previousLeg: Double?, phase: PlacePhase, moving: Boolean, units: Units) {
    data class Node(val id: String, val x: Float, val distance: String? = null)
    val nodes = buildList {
        when (phase) {
            PlacePhase.BETWEEN -> {
                if (previous != null) add(Node(previous, -1f))
                add(Node(place.last, 0f, units.distance(place.metresFromLast)))
                place.next?.let { add(Node(it, 1f, units.distance(place.metresToNext ?: 0.0))) }
            }
            PlacePhase.ARRIVING -> {
                if (previous != null) add(Node(previous, 0f, previousLeg?.let(units::distance)))
                add(Node(place.last, 1f, units.distance(0.0)))
                place.next?.let { add(Node(it, 2f)) }
            }
            PlacePhase.STATION -> {
                if (previous != null) add(Node(previous, 0f))
                add(Node(place.last, 0.5f))
                place.next?.let { add(Node(it, 1f)) }
            }
        }
    }
    val trainTarget = when (phase) {
        PlacePhase.BETWEEN -> if (place.next == null) 0f else place.progress.toFloat()
        PlacePhase.ARRIVING -> 1f
        PlacePhase.STATION -> 0.5f
    }
    // animate only when the phase changes
    val lastPhase = remember { mutableStateOf(phase) }
    val animate = lastPhase.value != phase
    SideEffect { lastPhase.value = phase }

    @Composable
    fun animated(key: String, target: Float): Float {
        val a = remember(key) { Animatable(target) }
        LaunchedEffect(key, target, animate) {
            if (animate) a.animateTo(target, tween(700, easing = FastOutSlowInEasing)) else a.snapTo(target)
        }
        return a.value
    }

    val trainX = animated("train", trainTarget)
    val xs = nodes.associate { it.id to animated(it.id, it.x) }
    val blue = Palette.blue
    val gray2 = Palette.gray2
    val gray4 = Palette.gray4
    val bg = Palette.card
    val teal = Palette.teal
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
            val width = maxWidth
            val w = width - 28.dp
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val y = size.height / 2
                val inset = 14.dp.toPx()
                drawRoundRect(gray4, Offset(inset, y - 3.dp.toPx()), Size(size.width - 2 * inset, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                drawRoundRect(blue, Offset(inset, y - 3.dp.toPx()), Size(max(w.toPx() * trainX, 0f), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                for (node in nodes) {
                    val x = xs[node.id] ?: node.x
                    if (x < -0.01f || x > 1.01f) continue
                    val cx = 14.dp.toPx() + w.toPx() * x
                    if (x <= trainX + 0.001f) {
                        drawCircle(blue, 6.dp.toPx(), Offset(cx, y))
                    } else {
                        drawCircle(bg, 6.dp.toPx(), Offset(cx, y))
                        drawCircle(gray2, 4.5.dp.toPx(), Offset(cx, y), style = Stroke(3.dp.toPx()))
                    }
                }
            }
            Box(Modifier.offset(x = w * trainX).size(28.dp).background(if (moving) blue else teal, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Tram, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(LABEL_HEIGHT)) {
            val total = maxWidth
            for (node in nodes) {
                val x = xs[node.id] ?: node.x
                val targetEdge = node.x.coerceIn(0f, 1f)
                val edge = x.coerceIn(0f, 1f)
                val side = phase == PlacePhase.STATION && node.x != 0.5f
                // each station gets its own share of the width, so names never overlap
                val share = if (phase == PlacePhase.STATION) (if (node.x == 0.5f) 0.4f else 0.3f) else 0.5f
                val labelWidth = total * share - 4.dp
                // left-aligned at 0, centred at 0.5, right-aligned at 1; off the card: pushed out from the edge
                val left = (total - labelWidth) * edge + total * ((x - edge) * 0.5f)
                val align = when (targetEdge) { 0f -> Alignment.Start; 1f -> Alignment.End; else -> Alignment.CenterHorizontally }
                val textAlign = when (targetEdge) { 0f -> TextAlign.Start; 1f -> TextAlign.End; else -> TextAlign.Center }
                val visible = x >= -0.01f && x <= 1.01f
                Column(Modifier.offset(x = left).width(labelWidth).alpha(if (visible) 1f else 0f), horizontalAlignment = align,
                    verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    FitText(stationShortName(node.id), if (side) 13.sp else 15.sp, Modifier.fillMaxWidth(), weight = if (side) null else FontWeight.SemiBold,
                        color = if (side) Palette.secondary else LocalContentColor.current, textAlign = textAlign)
                    val d = node.distance
                    val shown by animateFloatAsState(if (d != null) 1f else 0f, tween(300), label = "distance")
                    if (d != null) Text(d, fontSize = 13.sp, style = Mono, color = Palette.secondary, maxLines = 1, textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth().alpha(shown))
                }
            }
            if (place.next == null && phase == PlacePhase.BETWEEN) {
                Text("end of the line", fontSize = 15.sp, color = Palette.secondary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The names that don't fit on a small screen, shortened. */
fun stationShortName(name: String): String = mapOf(
    "San Francisco 4th & King Street" to "San Francisco",
    "South San Francisco" to "South SF",
    "California Avenue" to "California Ave",
    "San Jose Diridon" to "San Jose",
)[name] ?: name

/** The line from the last station to the next, with nothing on it yet. */
@Composable
private fun SegmentSkeleton() {
    val gray4 = Palette.gray4
    val gray2 = Palette.gray2
    val bg = Palette.card
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val y = size.height / 2
        val inset = 14.dp.toPx()
        drawRoundRect(gray4, Offset(inset, y - 3.dp.toPx()), Size(size.width - 2 * inset, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        drawCircle(gray4, 6.dp.toPx(), Offset(14.dp.toPx(), y))
        drawCircle(bg, 6.dp.toPx(), Offset(size.width - 14.dp.toPx(), y))
        drawCircle(gray2, 4.5.dp.toPx(), Offset(size.width - 14.dp.toPx(), y), style = Stroke(3.dp.toPx()))
    }
}

/** The stops so far: when the doors opened and closed at each (the start of each chime). The latest few, newest first. */
@Composable
private fun StopsCard(belief: Belief, start: Long?) {
    val rows = 4
    fun time(second: Int?): String {
        if (second == null) return "–"
        if (start == null) return String.format(Locale.US, "%d:%02d", second / 60, second % 60)
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(start + second * 1000L))
    }
    // station names are only worth showing once the fix is confident
    val confident = (belief.place?.confidence ?: 0.0) > 0.9
    val stops = if (confident) belief.stopLog.takeLast(rows).reversed() else emptyList()
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Station", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.secondary, modifier = Modifier.weight(1f))
                Text("Doors opened", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.secondary, textAlign = TextAlign.End, modifier = Modifier.width(98.dp))
                Text("Doors closed", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.secondary, textAlign = TextAlign.End, modifier = Modifier.width(98.dp))
            }
            for (k in 0 until rows) {
                Row(Modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (k < stops.size) {
                        FitText(stops[k].station?.let(::stationShortName) ?: "?", 15.sp, Modifier.weight(1f), minScale = 0.7f, weight = FontWeight.SemiBold)
                        Text(time(stops[k].opened), fontSize = 15.sp, style = Mono, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(98.dp))
                        Text(time(stops[k].closed), fontSize = 15.sp, style = Mono, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(98.dp))
                    } else {
                        Row(Modifier.weight(1f).alpha(0.5f)) { Placeholder("Station name", 15.sp, weight = FontWeight.SemiBold) }
                        Box(Modifier.width(98.dp).alpha(0.5f), contentAlignment = Alignment.CenterEnd) { Placeholder("0:00:00 PM", 15.sp) }
                        Box(Modifier.width(98.dp).alpha(0.5f), contentAlignment = Alignment.CenterEnd) { Placeholder("0:00:00 PM", 15.sp) }
                    }
                }
            }
        }
    }
}

private enum class Glyph { PHONE, SPRING, TONE, WHEEL }

private data class SensorItem(val name: String, val glyph: Glyph, val color: Color, val state: String, val number: String? = null)

/**
 * How good each source of information is right now, as coloured icons; tap for a line of
 * explanation each. Green good, yellow fair, red missing, grey not in play.
 */
@Composable
private fun SensorRow(belief: Belief, expandedAtFirst: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(expandedAtFirst) }
    val green = Palette.green; val yellow = Palette.yellow; val red = Palette.red; val gray = Palette.gray

    /** Evidence is a log likelihood ratio: how many times likelier this measurement makes the believed speed than an average speed. */
    fun grade(evidence: Double): Triple<Color, String, String> {
        val factor = exp(evidence)
        val number = if (factor >= 10) String.format(Locale.US, "%.0f×", factor) else String.format(Locale.US, "%.1f×", factor)
        return when {
            evidence >= 1.5 -> Triple(green, "clear", number)
            evidence >= 0.6 -> Triple(yellow, "faint", number)
            else -> Triple(red, "not found", number)
        }
    }

    val b = belief
    val items = if (b.phoneP.size != 3) {  // nothing measured yet
        listOf("Steadiness" to Glyph.PHONE, "Acceleration" to Glyph.SPRING, "Motor tone" to Glyph.TONE, "Wheel vibration" to Glyph.WHEEL)
            .map { SensorItem(it.first, it.second, gray, "waiting") }
    } else buildList {
        var phone = 0
        for (i in b.phoneP.indices) if (b.phoneP[phone] < b.phoneP[i]) phone = i
        val moving = b.stops > 0 && (b.modeP.firstOrNull() ?: 0.0) > 0.5
        add(SensorItem("Steadiness", Glyph.PHONE, listOf(green, yellow, red)[phone], listOf("lying still", "held", "moving")[phone],
            String.format(Locale.US, "%.0f %%", b.phoneP[phone] * 100)))
        val idle = if (b.stops > 0) "stopped" else "waiting"
        val reading = b.accelReading
        when {
            phone != 0 -> add(SensorItem("Acceleration", Glyph.SPRING, gray, "needs phone still"))
            reading != null -> add(SensorItem("Acceleration", Glyph.SPRING, green, "in use", String.format(Locale.US, "%+.2f m/s²", reading)))
            else -> add(SensorItem("Acceleration", Glyph.SPRING, yellow, "finding forward"))
        }
        val tone = b.toneEvidence
        when {
            !moving -> add(SensorItem("Motor tone", Glyph.TONE, gray, idle))
            b.speedMean < 10 -> add(SensorItem("Motor tone", Glyph.TONE, gray, "too slow"))
            tone != null -> grade(tone).let { (c, word, number) -> add(SensorItem("Motor tone", Glyph.TONE, c, word, number)) }
        }
        val wheel = b.wheelEvidence
        when {
            !moving -> add(SensorItem("Wheel vibration", Glyph.WHEEL, gray, idle))
            phone == 2 -> add(SensorItem("Wheel vibration", Glyph.WHEEL, gray, "phone moving"))
            wheel != null -> grade(wheel).let { (c, word, number) -> add(SensorItem("Wheel vibration", Glyph.WHEEL, c, word, number)) }
            else -> add(SensorItem("Wheel vibration", Glyph.WHEEL, gray, "no reading yet"))
        }
    }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                for (item in items) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Badge(item, 34.dp) }
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide" else "Show what these mean", tint = Palette.secondary,
                    modifier = Modifier.width(24.dp).rotate(if (expanded) 180f else 0f))
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in items) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(item.color, CircleShape))
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(item.name) }
                                append(": ${item.state}")
                            }, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            item.number?.let { Text(it, fontSize = 13.sp, style = Mono, color = Palette.secondary) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every sensor looks the same: a disc tinted with its status colour, and on it a plain glyph
 * of one weight and size in that colour. The glyph says which sensor; the colour how it's doing.
 */
@Composable
private fun Badge(item: SensorItem, size: Dp) {
    val tint = item.color
    Box(Modifier.size(size).background(tint.copy(alpha = if (item.color == Palette.gray) 0.15f else 0.2f), CircleShape), contentAlignment = Alignment.Center) {
        when (item.glyph) {
            Glyph.PHONE -> Icon(Icons.Filled.Smartphone, contentDescription = "${item.name}: ${item.state}", tint = tint, modifier = Modifier.size(size * 0.5f))
            Glyph.TONE -> Icon(Icons.Filled.GraphicEq, contentDescription = "${item.name}: ${item.state}", tint = tint, modifier = Modifier.size(size * 0.5f))
            Glyph.WHEEL, Glyph.SPRING -> Canvas(Modifier.size(size * 0.64f, size * 0.5f)) {
                val pen = Stroke(width = size.toPx() * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(if (item.glyph == Glyph.WHEEL) trainWheel(Rect(Offset.Zero, this.size)) else massOnSpring(Rect(Offset.Zero, this.size)), tint, style = pen)
            }
        }
    }
}

/** A railway wheel on its rail, shaking: a ring for the tyre, a hub, the rail, and vibration marks to either side. */
private fun trainWheel(r: Rect): Path = Path().apply {
    val railY = r.bottom
    val radius = r.height / 2 * 0.72f
    val c = Offset(r.center.x, railY - radius - r.height * 0.07f)
    addOval(Rect(c, radius))
    addOval(Rect(c, radius * 0.28f))
    moveTo(r.left, railY)
    lineTo(r.right, railY)
    // vibration: two short arcs off each side of the tyre, the outer one shorter
    for (side in listOf(180f, 0f)) {
        for ((reach, half) in listOf(1.4f to 30f, 1.8f to 20f)) {
            val a0 = Math.toRadians((side - half).toDouble())
            moveTo(c.x + radius * reach * cos(a0).toFloat(), c.y + radius * reach * sin(a0).toFloat())
            arcTo(Rect(c, radius * reach), side - half, 2 * half, forceMoveTo = false)
        }
    }
}

/** A mass on a spring, fixed to a wall on the left: what an accelerometer is. */
private fun massOnSpring(r: Rect): Path = Path().apply {
    val y = r.center.y
    moveTo(r.left, r.top + r.height * 0.12f)
    lineTo(r.left, r.bottom - r.height * 0.12f)
    val x0 = r.left
    val x1 = r.left + r.width * 0.6f
    val amp = r.height * 0.2f
    val lead = r.width * 0.07f
    val n = 4
    moveTo(x0, y)
    lineTo(x0 + lead, y)
    for (k in 0 until n) {
        val x = x0 + lead + (x1 - x0 - 2 * lead) * (k + 0.5f) / n
        lineTo(x, y + if (k % 2 == 0) -amp else amp)
    }
    lineTo(x1 - lead, y)
    lineTo(x1, y)
    val side = r.height * 0.56f
    addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(x1, y - side / 2, x1 + min(side, r.right - x1), y + side / 2), CornerRadius(side * 0.18f)))
}

/** Everything the model believes, as numbers: for the curious, and for debugging. */
@Composable
private fun DetailsView(b: Belief) {
    val modeColors = listOf(Palette.gray, Palette.blue, Palette.orange, Palette.teal, Palette.yellow, Palette.pink)
    val small = 13.sp
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Speed") {
            Text(String.format(Locale.US, "%.1f ± %.1f m/s (%.0f mph)", b.speedMean, b.speedSd, b.speedMean * 2.23694), fontSize = small, style = Mono)
            SpeedDistribution(b, Modifier.fillMaxWidth().height(80.dp))
        }
        Section("What the train is doing") {
            for (m in b.modeP.indices) ProbabilityBar(modeNames[m], b.modeP[m], modeColors[m])
        }
        Section("What the phone is doing") {
            for (s in b.phoneP.indices) ProbabilityBar(phoneNames[s], b.phoneP[s], Palette.secondary)
            Text(if (b.accelInUse) "Using the accelerometer for speed changes" else "Accelerometer not in use (needs a resting phone and one clear acceleration)",
                fontSize = 12.sp, color = Palette.secondary)
        }
        Section("Stations") {
            if (b.stations.isEmpty()) {
                Text(if (b.stops == 0) "No door opening heard yet." else "One stop heard: every station is still possible.", fontSize = small, color = Palette.secondary)
            } else {
                for (s in b.stations) ProbabilityBar("${s.name} ${if (s.southbound) "↓" else "↑"}", s.p, Palette.blue)
            }
            Text(String.format(Locale.US, "%d stops heard · %.2f km since the last", b.stops, b.distanceSinceStop / 1000), fontSize = small, style = Mono)
            if (b.stopLog.isNotEmpty()) {
                Text("Door chimes, seconds into the recording (open / close): " + b.stopLog.joinToString(", ") { "${it.opened} / ${it.closed?.toString() ?: "–"}" },
                    fontSize = 12.sp, style = Mono, color = Palette.secondary)
            }
            if (b.legs.isNotEmpty()) {
                Text("Legs: " + b.legs.joinToString(", ") { String.format(Locale.US, "%.2f", it / 1000) } + " km", fontSize = 12.sp, style = Mono, color = Palette.secondary)
            }
        }
        Text("${b.second / 60}:${String.format(Locale.US, "%02d", b.second % 60)} into the recording. Uses only what has been heard and felt so far; stops and stations are confirmed about 3 s after the door chime.",
            fontSize = 12.sp, color = Palette.secondary)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.secondary)
        content()
    }
}

@Composable
private fun ProbabilityBar(name: String, p: Double, color: Color) {
    val track = Palette.gray4
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
        Canvas(Modifier.weight(1f).height(7.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            val w = max(size.width * p.toFloat(), if (p > 0.005) 3.dp.toPx() else 0f)
            if (w > 0) drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Text(String.format(Locale.US, "%.2f", p), fontSize = 13.sp, style = Mono, color = Palette.secondary, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
    }
}

/** P(speed) in 1 m/s bins, with the probability of standing as its own bar on the left. */
@Composable
private fun SpeedDistribution(b: Belief, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val teal = Palette.teal
    val blue = Palette.blue
    val secondary = Palette.secondary
    Canvas(modifier) {
        val n = b.speedHist.size
        if (n == 0) return@Canvas
        val gap = 14.dp.toPx()
        val labelH = 14.dp.toPx()
        val w = (size.width - gap - 14.dp.toPx()) / n
        val h = size.height - labelH
        val top = maxOf(b.speedHist.maxOrNull() ?: 0f, b.pStanding.toFloat(), 0.05f)
        fun rect(x: Float, p: Float, width: Float): Pair<Offset, Size> {
            val bh = p / top * h
            return Offset(x, h - bh) to Size(width, bh)
        }
        rect(0f, b.pStanding.toFloat(), 12.dp.toPx()).let { (o, s) -> drawRoundRect(teal, o, s, CornerRadius(2.dp.toPx())) }
        for (k in 0 until n) {
            rect(14.dp.toPx() + gap + k * w, b.speedHist[k], max(w - 1, 1f)).let { (o, s) -> drawRoundRect(blue, o, s, CornerRadius(1.5.dp.toPx())) }
        }
        drawLine(secondary.copy(alpha = 0.4f), Offset(0f, h), Offset(size.width, h), 0.5.dp.toPx())
        label(measurer, "still", Offset(6.dp.toPx(), h + 8.dp.toPx()), secondary)
        for (v in 0..30 step 10) label(measurer, "$v", Offset(14.dp.toPx() + gap + v * w, h + 8.dp.toPx()), secondary)
        label(measurer, "m/s", Offset(size.width - 10.dp.toPx(), h + 8.dp.toPx()), secondary)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(measurer: TextMeasurer, text: String, centre: Offset, color: Color) {
    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, color = color))
    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
}
