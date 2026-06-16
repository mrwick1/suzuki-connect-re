package dev.mrwick.gixxerbridge.ui.trips

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Spotify-Wrapped-style post-ride summary.
 *
 * Full-screen [Dialog] (usePlatformDefaultWidth=false, transparent scrim) showing
 * a 4-card [HorizontalPager]:
 *   1. Distance + duration — big animated counter with [Motion.SpringSweep]
 *   2. Speed — avg + max + per-sample sparkline
 *   3. Fuel & range — bars used + fuel economy
 *   4. Map + share — GPS polyline canvas + PNG share via FileProvider
 *
 * Shown when [AppGraph.lastFinishedRideId] is non-null. Dismissed by tap-outside
 * or back, clearing the AppGraph signal via [AppGraph.clearLastFinishedRide].
 *
 * Wire at AppShell level in MainActivity (parallel to snackbar host).
 */
@Composable
fun PostRideSummaryHost(
    rideId: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { AppGraph.rideStore(context) }

    // Load ride entity from the live rides flow.
    var ride by remember(rideId) { mutableStateOf<RideEntity?>(null) }
    var samples by remember(rideId) { mutableStateOf<List<RideSampleEntity>>(emptyList()) }
    var locations by remember(rideId) { mutableStateOf<List<RideLocationEntity>>(emptyList()) }

    // Observe the rides flow to catch the just-committed ride row.
    val allRides by produceState(initialValue = emptyList<RideEntity>()) {
        store.observeRides().collect { value = it }
    }
    LaunchedEffect(allRides, rideId) {
        ride = allRides.firstOrNull { it.id == rideId }
    }

    // Load samples + locations off the main thread.
    LaunchedEffect(rideId) {
        withContext(Dispatchers.IO) {
            val s = store.getSamplesForView(rideId)
            val l = store.getLocationsForView(rideId)
            samples = s
            locations = l
        }
    }

    val fillKmPerL by produceState<Double?>(initialValue = null) {
        value = MileageAnalytics.averageKmPerL(
            FuelStore(GixxerDatabase.get(context).fuelFillDao()).all()
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        PostRideSummaryContent(
            rideId = rideId,
            ride = ride,
            samples = samples,
            locations = locations,
            onDismiss = onDismiss,
            fillKmPerL = fillKmPerL,
        )
    }
}

@Composable
private fun PostRideSummaryContent(
    rideId: Long,
    ride: RideEntity?,
    samples: List<RideSampleEntity>,
    locations: List<RideLocationEntity>,
    onDismiss: () -> Unit,
    fillKmPerL: Double?,
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val distanceForBurn = ride?.let { max(0, (it.endOdoKm ?: it.startOdoKm) - it.startOdoKm) } ?: 0
    val fuelBurntL = RideAnalytics.fuelBurnt(
        distanceKm = distanceForBurn,
        fillKmPerL = fillKmPerL,
        bikeKmPerL = RideAnalytics.avgBikeEcon(samples),
    )?.litres

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GixxerTokens.bg),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    0 -> DistanceDurationCard(ride, fuelBurntL)
                    1 -> SpeedCard(ride, samples)
                    2 -> FuelCard(ride)
                    3 -> MapShareCard(rideId, locations, onDismiss)
                    else -> Spacer(Modifier)
                }
            }
        }

        // Page indicator dots at the bottom.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) { idx ->
                Box(
                    modifier = Modifier
                        .size(if (pagerState.currentPage == idx) 10.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == idx) GixxerTokens.accent
                            else GixxerTokens.textMuted.copy(alpha = 0.4f),
                        ),
                )
            }
        }

        // Swipe hint at the top. (No "tap outside" — the content fills the whole
        // screen so there is no outside region; dismissal is via the close button
        // below or a back-press.)
        Text(
            text = "Swipe to explore",
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        )

        // Always-visible close affordance, top-end. The full-screen Box leaves no
        // tap-outside target, so this (and back-press) is how the user dismisses.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = GixxerTokens.textMuted,
            )
        }
    }
}

/** Card 1: distance counter animated from 0 with SpringSweep, plus duration caption. */
@Composable
private fun DistanceDurationCard(ride: RideEntity?, fuelBurntL: Double?) {
    val distanceKm = remember(ride) {
        ride?.let { max(0, (it.endOdoKm ?: it.startOdoKm) - it.startOdoKm) } ?: 0
    }
    val durationMin = remember(ride) {
        ride?.let {
            val end = it.endedAtMillis ?: System.currentTimeMillis()
            (end - it.startedAtMillis) / 60_000
        } ?: 0L
    }

    val animatedDistance = remember { Animatable(0f) }
    LaunchedEffect(distanceKm) {
        if (distanceKm > 0) {
            animatedDistance.animateTo(distanceKm.toFloat(), animationSpec = Motion.SpringSweep)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Ride complete",
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textMuted,
        )
        Text(
            text = "${animatedDistance.value.toInt()}",
            style = GixxerMono.display.copy(fontSize = 96.sp),
            color = GixxerTokens.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "km · $durationMin min" +
                (fuelBurntL?.let { " · ~${"%.2f".format(it)} L (est.)" } ?: ""),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W600,
            color = GixxerTokens.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Card 2: avg + max speed in two columns, plus sparkline of per-second speed samples. */
@Composable
private fun SpeedCard(ride: RideEntity?, samples: List<RideSampleEntity>) {
    val avgSpeed = ride?.avgSpeedKmh ?: 0.0
    val maxSpeed = ride?.maxSpeedKmh ?: 0
    val speedValues = remember(samples) { samples.map { it.speedKmh.toFloat() } }

    val animatedAvg = remember { Animatable(0f) }
    val animatedMax = remember { Animatable(0f) }
    LaunchedEffect(avgSpeed, maxSpeed) {
        animatedAvg.animateTo(avgSpeed.toFloat(), animationSpec = Motion.SpringSweep)
        animatedMax.animateTo(maxSpeed.toFloat(), animationSpec = Motion.SpringSweep)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Speed",
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SpeedStat(label = "Avg", value = "${"%.0f".format(animatedAvg.value)} km/h")
            SpeedStat(label = "Max", value = "${animatedMax.value.toInt()} km/h")
        }
        if (speedValues.size > 1) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
            ) {
                val maxV = (speedValues.maxOrNull() ?: 1f).coerceAtLeast(1f)
                val xStep = size.width / (speedValues.size - 1)
                val pts = speedValues.mapIndexed { i, v ->
                    Offset(i * xStep, size.height - (v / maxV) * size.height)
                }
                for (i in 1 until pts.size) {
                    drawLine(
                        color = GixxerTokens.accent,
                        start = pts[i - 1],
                        end = pts[i],
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = GixxerTokens.textMuted)
        Text(value, style = GixxerMono.headline, color = GixxerTokens.textPrimary)
    }
}

/** Card 3: fuel bars used (visual bar strip) + avg fuel economy. */
@Composable
private fun FuelCard(ride: RideEntity?) {
    val fuelStart = ride?.fuelBarsStart
    val fuelEnd = ride?.fuelBarsEnd
    val barsUsed = if (fuelStart != null && fuelEnd != null) {
        (fuelStart - fuelEnd).coerceAtLeast(0)
    } else null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Fuel & range", style = MaterialTheme.typography.titleMedium, color = GixxerTokens.textMuted)

        if (barsUsed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { idx ->
                    Box(
                        modifier = Modifier
                            .size(width = 28.dp, height = 48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (idx < barsUsed) GixxerTokens.accent
                                else GixxerTokens.surfaceElevated,
                            ),
                    )
                }
            }
            Text(
                "$barsUsed bar${if (barsUsed == 1) "" else "s"} used",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
            )
        } else {
            Text(
                "Fuel data not available\nfor this ride",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Card 4: GPS polyline on Canvas + "Share PNG" button via FileProvider. */
@Composable
private fun MapShareCard(
    rideId: Long,
    locations: List<RideLocationEntity>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Route", style = MaterialTheme.typography.titleMedium, color = GixxerTokens.textMuted)

        if (locations.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GixxerTokens.surfaceElevated),
            ) {
                val lats = locations.map { it.lat }
                val lngs = locations.map { it.lng }
                val minLat = lats.min(); val maxLat = lats.max()
                val minLng = lngs.min(); val maxLng = lngs.max()
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
                val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)
                val scale = (size.width / lngSpan).coerceAtMost(size.height / latSpan)
                val xOffset = (size.width - lngSpan * scale) / 2
                val yOffset = (size.height - latSpan * scale) / 2
                fun project(lat: Double, lng: Double) = Offset(
                    x = ((lng - minLng) * scale + xOffset).toFloat(),
                    y = (size.height - ((lat - minLat) * scale + yOffset)).toFloat(),
                )
                val path = Path()
                val first = project(locations[0].lat, locations[0].lng)
                path.moveTo(first.x, first.y)
                for (i in 1 until locations.size) {
                    val p = project(locations[i].lat, locations[i].lng)
                    path.lineTo(p.x, p.y)
                }
                drawPath(path, color = GixxerTokens.accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
                drawCircle(GixxerTokens.success, radius = 12f, center = first)
                drawCircle(GixxerTokens.accent, radius = 12f, center = project(locations.last().lat, locations.last().lng))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GixxerTokens.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No GPS track recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GixxerTokens.textMuted,
                )
            }
        }

        // Share PNG button: renders same polyline to a Bitmap, saves to filesDir/rides/.
        Button(
            onClick = {
                Thread {
                    try {
                        val bitmapSize = 1080
                        val bmp = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
                        val c = AndroidCanvas(bmp)
                        c.drawColor(GixxerTokens.surfaceElevated.toArgb())

                        if (locations.size >= 2) {
                            val lats = locations.map { it.lat }
                            val lngs = locations.map { it.lng }
                            val minLat = lats.min(); val maxLat = lats.max()
                            val minLng = lngs.min(); val maxLng = lngs.max()
                            val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
                            val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)
                            val padding = bitmapSize * 0.1f
                            val drawW = bitmapSize - 2 * padding
                            val drawH = bitmapSize - 2 * padding
                            val scale = (drawW / lngSpan).coerceAtMost(drawH / latSpan)
                            val xOff = padding + (drawW - lngSpan * scale) / 2
                            val yOff = padding + (drawH - latSpan * scale) / 2
                            fun proj(lat: Double, lng: Double) = android.graphics.PointF(
                                (xOff + (lng - minLng) * scale).toFloat(),
                                (bitmapSize - (yOff + (lat - minLat) * scale)).toFloat(),
                            )
                            val paint = android.graphics.Paint().apply {
                                color = GixxerTokens.accent.toArgb()
                                strokeWidth = 8f
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                isAntiAlias = true
                            }
                            val path = android.graphics.Path()
                            val first = proj(locations[0].lat, locations[0].lng)
                            path.moveTo(first.x, first.y)
                            for (i in 1 until locations.size) {
                                val p = proj(locations[i].lat, locations[i].lng)
                                path.lineTo(p.x, p.y)
                            }
                            c.drawPath(path, paint)
                        }

                        // Save to filesDir/rides/ride-<id>.png.
                        val ridesDir = File(context.filesDir, "rides").also { it.mkdirs() }
                        val pngFile = File(ridesDir, "ride-$rideId.png")
                        pngFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                        }
                        bmp.recycle()

                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            pngFile,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Share ride").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("PostRideSummary", "PNG share failed", t)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GixxerTokens.accent),
        ) {
            Text("Share PNG", color = GixxerTokens.textPrimary)
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close", color = GixxerTokens.textMuted)
        }
    }
}
