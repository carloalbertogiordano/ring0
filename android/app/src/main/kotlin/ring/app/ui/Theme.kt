package ring.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette terminale: verde fosforo su nero caldo; warn/crit separati dall'accento. */
object Term {
    val bg = Color(0xFF040906)
    val bg2 = Color(0xFF07100B)
    val panel = Color(0xFF0B1710)
    val panel2 = Color(0xFF0F1F16)
    val line = Color(0xFF15382A)
    val line2 = Color(0xFF1D4D38)
    val accent = Color(0xFF00FF9C)
    val accentDim = Color(0xFF00B36B)
    val accentInk = Color(0xFF022415)
    val text = Color(0xFFD9FFEC)
    val dim = Color(0xFF6B9A82)
    val faint = Color(0xFF3D6552)
    val warn = Color(0xFFFFB020)
    val crit = Color(0xFFFF4D6D)
    val info = Color(0xFF5CC8FF)
    val rem = Color(0xFFB388FF)
}

private val Mono = FontFamily.Monospace

@Composable
fun RingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Term.accent,
            onPrimary = Term.accentInk,
            background = Term.bg,
            onBackground = Term.text,
            surface = Term.panel,
            onSurface = Term.text,
            error = Term.crit,
            outline = Term.line2,
            surfaceVariant = Term.panel2,
            onSurfaceVariant = Term.dim,
            secondary = Term.accentDim,
        ),
        content = content,
    )
}

/** Testo monospace con i colori del tema. */
@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = Term.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    spacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text,
    modifier,
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = Mono,
    textAlign = align,
    letterSpacing = spacing,
    maxLines = maxLines,
    lineHeight = size * 1.4f,
)

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = T(text.uppercase(), modifier, 10.sp, Term.dim, spacing = 1.4.sp)

@Composable
fun Num(text: String, unit: String = "", size: TextUnit = 22.sp, color: Color = Term.text) = Row(verticalAlignment = Alignment.Bottom) {
    T(text, size = size, color = color, weight = FontWeight.SemiBold)
    if (unit.isNotEmpty()) T(unit, Modifier.padding(start = 3.dp, bottom = 2.dp), 11.sp, Term.dim)
}

@Composable
fun TCard(modifier: Modifier = Modifier, hot: Boolean = false, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier
        .fillMaxWidth()
        .background(if (hot) Term.panel2 else Term.panel)
        .border(BorderStroke(1.dp, accent ?: if (hot) Term.line2 else Term.line))
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    content = content,
)

enum class BtnKind { PRIMARY, GHOST, CRIT }

@Composable
fun TButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.PRIMARY, enabled: Boolean = true) {
    val (bg, fg, border) = when (kind) {
        BtnKind.PRIMARY -> Triple(Term.accent, Term.accentInk, Term.accent)
        BtnKind.GHOST -> Triple(Color.Transparent, Term.accent, Term.accent)
        BtnKind.CRIT -> Triple(Color.Transparent, Term.crit, Term.crit)
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg)
            .border(BorderStroke(1.dp, border))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { T(text.uppercase(), size = 12.sp, color = fg, weight = FontWeight.Bold, spacing = 1.2.sp) }
}

@Composable
fun TChip(text: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier
        .background(if (selected) Term.accent else Color.Transparent)
        .border(BorderStroke(1.dp, if (selected) Term.accent else Term.line2))
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 5.dp),
) { T(text, size = 11.sp, color = if (selected) Term.accentInk else Term.dim, weight = if (selected) FontWeight.Bold else FontWeight.Normal) }

@Composable
fun TSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val knob = if (checked) Term.accent else Term.faint
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .width(36.dp).height(20.dp)
            .border(BorderStroke(1.dp, if (checked) Term.accent else Term.line2), CircleShape)
            .background(if (checked) Term.accent.copy(alpha = 0.15f) else Term.bg, CircleShape)
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(
            Modifier.padding(start = if (checked) 19.dp else 3.dp, top = 3.dp).size(12.dp).background(knob, CircleShape),
        )
    }
}

@Composable
fun TPill(text: String, color: Color = Term.accent, pulse: Boolean = false) = Row(
    Modifier.border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), CircleShape).background(Term.panel, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
        a
    } else {
        1f
    }
    Box(Modifier.size(7.dp).alpha(alpha).background(color, CircleShape))
    T(text, size = 11.sp, color = color)
}

@Composable
fun Banner(title: String, body: String, color: Color = Term.warn) = Column(
    Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f)).border(BorderStroke(1.dp, color.copy(alpha = 0.55f))).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    T(title, size = 12.sp, color = color, weight = FontWeight.Bold)
    T(body, size = 12.sp, color = Term.text.copy(alpha = 0.85f))
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) = Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    T(label, size = 12.sp)
    Row(Modifier.border(BorderStroke(1.dp, Term.line2)), verticalAlignment = Alignment.CenterVertically) {
        T("−", Modifier.clickable(onClick = onMinus).padding(horizontal = 12.dp, vertical = 5.dp), 14.sp, Term.accent)
        T(value, Modifier.width(52.dp), 12.sp, align = TextAlign.Center)
        T("+", Modifier.clickable(onClick = onPlus).padding(horizontal = 12.dp, vertical = 5.dp), 14.sp, Term.accent)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Term.text) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    T(key, size = 12.sp, color = Term.dim)
    T(value, size = 12.sp, color = valueColor)
}
