package com.dragonsim.ar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonsim.ar.ar.CreatureSim

private val Low = Color(0xFF00E5FF)
private val High = Color(0xFFFF4081)
private val Panel = Color.Black.copy(alpha = 0.55f)

/**
 * Scan phase overlay: the 3D point cloud and surface grid render in the AR
 * scene; this adds coverage ring, stats and a growing top-down map of surfaces. Finish unlocks at a minimal coverage;
 * Skip is always available.
 */
@Composable
fun ScanOverlay(sim: CreatureSim, onFinish: () -> Unit, onSkip: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp, start = 16.dp, end = 16.dp),
        ) {
            Text("Scan your space", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            sim.hint?.let {
                Text(it, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 4.dp))
            }
        }

        MiniMap(
            sim = sim,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 90.dp, end = 12.dp).size(130.dp),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(24.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { sim.scanProgress },
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp,
                    )
                    Text("${(sim.scanProgress * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("%.1f m² of surfaces".format(sim.scanAreaM2), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${sim.planeCount} surfaces · ${sim.scanPoints} 3D points", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                OutlinedButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = onFinish, enabled = sim.planeCount > 0) {
                    Text(if (sim.scanProgress >= 1f) "Finish scan ✓" else "Finish scan")
                }
            }
        }
    }
}

/**
 * Top-down map of the scanned room, centred on the phone: detected surfaces as
 * filled outlines coloured by height, the phone as a white wedge, the creature
 * as a gold dot.
 */
@Composable
fun MiniMap(sim: CreatureSim, modifier: Modifier = Modifier, rangeM: Float = 2.5f) {
    val polys = sim.mapPolygons
    val (camX, camZ, camYaw) = sim.camMarker
    val creature = sim.creatureMarker
    Canvas(
        modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val k = size.minDimension / (2f * rangeM) // px per metre
        clipRect {
            for ((pts, height) in polys) {
                if (pts.size < 6) continue
                val path = Path()
                for (i in 0 until pts.size / 2) {
                    val x = c.x + (pts[i * 2] - camX) * k
                    val y = c.y + (pts[i * 2 + 1] - camZ) * k
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, lerp(Low, High, height).copy(alpha = 0.6f))
            }
        }
        creature?.let { (x, z) ->
            drawCircle(Color(0xFFFFD54F), radius = 6f, center = Offset(c.x + (x - camX) * k, c.y + (z - camZ) * k))
        }
        // Phone: wedge pointing along its view direction (yaw 0 = +Z = down on the map).
        rotate(degrees = -camYaw, pivot = c) {
            val wedge = Path().apply {
                moveTo(c.x, c.y + 12f)
                lineTo(c.x - 7f, c.y - 6f)
                lineTo(c.x + 7f, c.y - 6f)
                close()
            }
            drawPath(wedge, Color.White)
        }
    }
}
