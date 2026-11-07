package com.mhmmtbg.x5reframe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mhmmtbg.x5reframe.model.SpeedCurve
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.model.SpeedPoints
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Hız ekseni logaritmik: 0,1× alt kenar, 1× orta, 10× üst kenar. */
private fun yOf(mult: Double, h: Float, pad: Float): Float {
    val v = (log10(mult.coerceIn(SpeedCurve.MIN, SpeedCurve.MAX)) + 1) / 2   // 0..1
    return pad + (1 - v.toFloat()) * (h - 2 * pad)
}

private fun multOf(y: Float, h: Float, pad: Float): Double {
    val v = 1 - ((y - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
    return 10.0.pow(v * 2.0 - 1.0)
}

private fun DrawScope.curvePath(points: DoubleArray, pad: Float): Path {
    val p = Path()
    val steps = 48
    for (k in 0..steps) {
        val u = k / steps.toDouble()
        val x = pad + (size.width - 2 * pad) * u.toFloat()
        val y = yOf(SpeedPoints.mult(points, u), size.height, pad)
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    return p
}

/** Hız eğrisi seçeneği: grafiği ve adı. */
@Composable
fun CurveThumb(curve: SpeedCurve, points: DoubleArray, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(78.dp).semantics { selected = on; contentDescription = "${curve.label}: ${curve.hint}" }
            .clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier.width(74.dp).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)
                .border(if (on) 2.dp else 0.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp)),
        ) {
            val pad = 5f
            // 1× çizgisi
            val y1 = yOf(1.0, size.height, pad)
            drawLine(Palette.Muted.copy(alpha = 0.35f), Offset(0f, y1), Offset(size.width, y1), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            if (curve == SpeedCurve.NONE) {
                drawLine(if (on) Palette.Accent else Palette.Text, Offset(pad, y1), Offset(size.width - pad, y1), 3f)
            } else {
                drawPath(curvePath(points, pad), if (on) Palette.Accent else Palette.Text, style = Stroke(3f))
            }
        }
        Text(curve.label, color = if (on) Palette.AccentText else Palette.Text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Özel hız eğrisi düzenleyici: eşit aralıklı noktalar yukarı / aşağı sürüklenir (0,1×–10×, log ölçek).
 * Çift dokunma noktayı 1×'e döndürür. [playU] oynatma çizgisinin parça içindeki konumu (0..1).
 */
@Composable
fun CurveEditor(points: List<Double>, playU: Float, onChange: (List<Double>) -> Unit, onCommit: (List<Double>) -> Unit) {
    var draft by remember(points) { mutableStateOf(points) }
    var active by remember { mutableStateOf(-1) }
    val pad = 18f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)) {
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .semantics { contentDescription = "Özel hız eğrisi: " + draft.joinToString { "%.2f×".format(it) } }
                    .pointerInput(points) {
                        fun nearest(x: Float): Int {
                            val n = draft.size
                            val step = (size.width - 2 * pad) / (n - 1)
                            return ((x - pad) / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, n - 1)
                        }
                        detectDragGestures(
                            onDragStart = { o -> active = nearest(o.x) },
                            onDragEnd = { active = -1; onCommit(draft) },
                            onDragCancel = { active = -1; onCommit(draft) },
                        ) { change, _ ->
                            if (active >= 0) {
                                var m = multOf(change.position.y, size.height.toFloat(), pad)
                                // 1×'e yapış
                                if (abs(log10(m)) < 0.04) m = 1.0
                                draft = draft.toMutableList().also { it[active] = Math.round(m * 100) / 100.0 }
                                onChange(draft)
                            }
                        }
                    }
                    .pointerInput(points) {
                        detectTapGestures(onDoubleTap = { o ->
                            val n = draft.size
                            val step = (size.width - 2 * pad) / (n - 1)
                            val k = kotlin.math.round((o.x - pad) / step).toInt().coerceIn(0, n - 1)
                            draft = draft.toMutableList().also { it[k] = 1.0 }
                            onCommit(draft)
                        })
                    },
            ) {
                val h = size.height
                // Izgara: 0,25× 0,5× 1× 2× 4×
                for (g in listOf(0.25, 0.5, 1.0, 2.0, 4.0)) {
                    val y = yOf(g, h, pad)
                    drawLine(Palette.Muted.copy(alpha = if (g == 1.0) 0.6f else 0.22f), Offset(pad, y), Offset(size.width - pad, y), 1f,
                        pathEffect = if (g == 1.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                val arr = draft.toDoubleArray()
                drawPath(curvePath(arr, pad), Palette.Accent, style = Stroke(4f))
                // Oynatma çizgisi
                val px = pad + (size.width - 2 * pad) * playU.coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = 0.8f), Offset(px, 0f), Offset(px, h), 2f)
                // Noktalar
                val step = (size.width - 2 * pad) / (draft.size - 1)
                draft.forEachIndexed { k, m ->
                    val c = Offset(pad + k * step, yOf(m, h, pad))
                    drawCircle(Color.White, if (k == active) 13f else 10f, c)
                    drawCircle(Palette.Accent, if (k == active) 9f else 7f, c)
                }
            }
            Text("10×", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            Text("1×", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterEnd).padding(4.dp))
            Text("0,1×", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
            if (active >= 0) {
                Text("%.2f×".format(draft[active]), color = Palette.Text, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Surface).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Text("Noktaları yukarı (hızlı) / aşağı (yavaş) sürükle; çift dokunma 1×'e döndürür.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

