package dev.mrwick.redline.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.applyCanvas
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a 1080x1920 PNG share card for a single ride.
 * Pure Android Canvas (no Compose) so it works off-thread without a window.
 * Returns the file in cacheDir.
 */
object ShareCardRenderer {
    /** Render the share card to a PNG in [Context.getCacheDir] and return the file. */
    fun render(context: Context, ride: RideEntity, locations: List<RideLocationEntity>): File {
        // ASSUMED: 1080x1920 (9:16) matches Instagram Stories / WhatsApp Status canvas.
        val w = 1080
        val h = 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            // ASSUMED: deep-navy vertical gradient background reads well on dark + light feeds.
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    AndroidColor.parseColor("#050B1A"),
                    AndroidColor.parseColor("#0F1E3D"),
                    Shader.TileMode.CLAMP,
                )
            }
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

            // Header: brand mark
            val titlePaint = Paint().apply {
                color = AndroidColor.parseColor("#22D3EE")
                textSize = 90f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            drawText("REDLINE", 80f, 200f, titlePaint)

            // Subtitle: ride start date/time
            val datePaint = Paint().apply {
                color = AndroidColor.parseColor("#94A3B8")
                textSize = 42f
                isAntiAlias = true
            }
            val dateFmt = SimpleDateFormat("EEE, MMM d  HH:mm", Locale.US)
            drawText(dateFmt.format(Date(ride.startedAtMillis)), 80f, 280f, datePaint)

            // Hero stat: distance
            val distance = ((ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
            val statBig = Paint().apply {
                color = AndroidColor.WHITE
                textSize = 220f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }
            drawText("$distance", 80f, 540f, statBig)
            val unitPaint = Paint().apply {
                color = AndroidColor.parseColor("#94A3B8")
                textSize = 72f
                isAntiAlias = true
            }
            val widthOfNumber = statBig.measureText("$distance")
            drawText("km", 80f + widthOfNumber + 32f, 540f, unitPaint)

            // Secondary stats grid (duration / max / avg)
            val statMed = Paint().apply {
                color = AndroidColor.WHITE
                textSize = 80f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }
            val statLabel = Paint().apply {
                color = AndroidColor.parseColor("#94A3B8")
                textSize = 36f
                isAntiAlias = true
            }
            val durMin = ((ride.endedAtMillis ?: System.currentTimeMillis()) - ride.startedAtMillis) / 60_000
            drawText("DURATION", 80f, 700f, statLabel)
            drawText("$durMin min", 80f, 790f, statMed)
            drawText("MAX SPEED", 580f, 700f, statLabel)
            drawText("${ride.maxSpeedKmh} km/h", 580f, 790f, statMed)
            drawText("AVG SPEED", 80f, 920f, statLabel)
            drawText("${ride.avgSpeedKmh.toInt()} km/h", 80f, 1010f, statMed)

            // GPS polyline (if any) — square area, bottom half
            if (locations.size >= 2) {
                // ASSUMED: 80px side margin, 920x680 map area sits in the lower third.
                val mapX = 80f
                val mapY = 1100f
                val mapW = 920f
                val mapH = 680f
                val framePaint = Paint().apply {
                    color = AndroidColor.parseColor("#1E293B")
                    style = Paint.Style.FILL
                }
                drawRect(mapX, mapY, mapX + mapW, mapY + mapH, framePaint)

                val lats = locations.map { it.lat }
                val lngs = locations.map { it.lng }
                val minLat = lats.min()
                val maxLat = lats.max()
                val minLng = lngs.min()
                val maxLng = lngs.max()
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
                val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)
                val scale = (mapW / lngSpan).coerceAtMost(mapH / latSpan).toFloat()
                val xPad = (mapW - lngSpan.toFloat() * scale) / 2f
                val yPad = (mapH - latSpan.toFloat() * scale) / 2f

                fun project(lat: Double, lng: Double): Pair<Float, Float> = Pair(
                    mapX + xPad + ((lng - minLng) * scale).toFloat(),
                    mapY + mapH - yPad - ((lat - minLat) * scale).toFloat(),
                )

                val path = AndroidPath()
                locations.forEachIndexed { i, loc ->
                    val (x, y) = project(loc.lat, loc.lng)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val routePaint = Paint().apply {
                    color = AndroidColor.parseColor("#22D3EE")
                    style = Paint.Style.STROKE
                    strokeWidth = 8f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                drawPath(path, routePaint)

                val firstLoc = locations.first()
                val lastLoc = locations.last()
                val (sx, sy) = project(firstLoc.lat, firstLoc.lng)
                val (ex, ey) = project(lastLoc.lat, lastLoc.lng)
                drawCircle(
                    sx, sy, 18f,
                    Paint().apply { color = AndroidColor.parseColor("#10B981"); isAntiAlias = true },
                )
                drawCircle(
                    ex, ey, 18f,
                    Paint().apply { color = AndroidColor.parseColor("#22D3EE"); isAntiAlias = true },
                )
            } else {
                val noteP = Paint().apply {
                    color = AndroidColor.parseColor("#64748B")
                    textSize = 36f
                    isAntiAlias = true
                }
                drawText("No GPS track recorded", 80f, 1200f, noteP)
            }

            // Footer tag
            val footerP = Paint().apply {
                color = AndroidColor.parseColor("#475569")
                textSize = 36f
                isAntiAlias = true
            }
            drawText("• REDLINE", 80f, 1850f, footerP)
        }
        val file = File(context.cacheDir, "ride-${ride.id}-card.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        bitmap.recycle()
        return file
    }
}
