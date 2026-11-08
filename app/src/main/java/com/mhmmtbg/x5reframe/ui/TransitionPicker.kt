package com.mhmmtbg.x5reframe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mhmmtbg.x5reframe.project.Transition
import com.mhmmtbg.x5reframe.ui.theme.Palette

/**
 * Geçiş seçici: her geçiş iki örnek sahne (A → B) arasında sürekli oynayan küçük bir canlandırmayla gösterilir,
 * seçilenin açıklaması altta yazar. Böylece kullanıcı uygulamadan önce ne olacağını görür.
 */
@Composable
fun TransitionPicker(current: Transition, onDismiss: () -> Unit, onPick: (Transition) -> Unit) {
    var sel by remember { mutableStateOf(current) }
    val anim = rememberInfiniteTransition(label = "tr")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    // 0–0,25 A, 0,25–0,75 geçiş, 0,75–1 B
    val p = ((t - 0.25f) / 0.5f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sonraki parçaya geçiş") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Transition.values().toList().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tr ->
                            val on = tr == sel
                            Column(
                                Modifier.width(84.dp).clip(RoundedCornerShape(8.dp)).clickable { sel = tr },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Canvas(
                                    Modifier.size(84.dp, 52.dp).clip(RoundedCornerShape(8.dp))
                                        .border(if (on) 2.dp else 1.dp, if (on) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp)),
                                ) { drawTransition(tr, p) }
                                Text(tr.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (on) Palette.AccentText else Palette.Text, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
                Text(sel.desc, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(sel) }) { Text("Uygula") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
        containerColor = Palette.Surface, tonalElevation = 0.dp,
    )
}

/** Örnek sahne A: gökyüzü ve güneş. */
private fun DrawScope.sceneA(alpha: Float = 1f) {
    drawRect(Color(0xFF3D6DF2).copy(alpha = alpha))
    drawCircle(Color(0xFFFFD43B).copy(alpha = alpha), radius = size.height * 0.18f, center = Offset(size.width * 0.7f, size.height * 0.35f))
    drawRect(Color(0xFF2B4FB0).copy(alpha = alpha), Offset(0f, size.height * 0.72f), Size(size.width, size.height * 0.28f))
}

/** Örnek sahne B: yeşil tepe. */
private fun DrawScope.sceneB(alpha: Float = 1f) {
    drawRect(Color(0xFFE8A33D).copy(alpha = alpha))
    val path = Path().apply {
        moveTo(0f, size.height); lineTo(size.width * 0.35f, size.height * 0.35f); lineTo(size.width * 0.6f, size.height * 0.7f)
        lineTo(size.width * 0.8f, size.height * 0.5f); lineTo(size.width, size.height); close()
    }
    drawPath(path, Color(0xFF2F8F4E).copy(alpha = alpha))
}

private fun DrawScope.drawTransition(tr: Transition, p: Float) {
    val w = size.width; val h = size.height
    when (tr) {
        Transition.CUT -> if (p < 0.5f) sceneA() else sceneB()
        Transition.FADE -> {
            drawRect(Color.Black)
            if (p < 0.5f) sceneA(1f - p * 2) else sceneB((p - 0.5f) * 2)
        }
        Transition.DISSOLVE -> { sceneA(); sceneB(p) }
        Transition.BLUR -> { sceneA(); sceneB(p); drawRect(Color.White.copy(alpha = 0.25f * kotlin.math.sin(p * Math.PI).toFloat())) }
        Transition.SLIDE -> {
            translate(-w * p, 0f) { sceneA() }
            translate(w * (1 - p), 0f) { sceneB() }
        }
        Transition.SLIDE_UP -> {
            translate(0f, -h * p) { sceneA() }
            translate(0f, h * (1 - p)) { sceneB() }
        }
        Transition.WIPE -> {
            sceneA()
            clipRect(0f, 0f, w * p, h) { sceneB() }
            drawRect(Color.White, Offset(w * p - 1f, 0f), Size(2f, h))
        }
        Transition.ZOOM -> {
            sceneB(p)
            scale(1f + p * 0.6f) { sceneA(1f - p) }
        }
        Transition.CIRCLE -> {
            sceneA()
            val r = p * kotlin.math.hypot(w, h) / 2
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w / 2, h / 2), r)) }) { sceneB() }
        }
    }
}
