package dev.mrwick.redline.export

import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream

/**
 * Unit tests for [GpxExporter].
 *
 * Validates:
 *  - Output is well-formed XML (parses with the standard JDK SAX/DOM stack).
 *  - GPX 1.1 prologue + root element + namespace.
 *  - One `<trkpt>` per input location, in input order.
 *  - Elevation is omitted when absent and present when supplied.
 *  - Timestamps are ISO-8601 UTC ("Z" suffix).
 */
class GpxExporterTest {

    private val ride = RideEntity(
        id = 42L,
        startedAtMillis = 1_700_000_000_000L, // 2023-11-14T22:13:20Z
        endedAtMillis = 1_700_000_300_000L,
        startOdoKm = 1000,
        endOdoKm = 1005,
        maxSpeedKmh = 80,
        avgSpeedKmh = 55.0,
        sampleCount = 60,
        fuelBarsStart = 8,
        fuelBarsEnd = 7,
    )

    @Test fun emitsValidXmlWithCorrectStructure() {
        val locations = listOf(
            RideLocationEntity(rideId = 42, tMillis = 1_700_000_000_000L, lat = 12.971, lng = 77.594, altitudeM = 920.5, accuracyM = 5.0f),
            RideLocationEntity(rideId = 42, tMillis = 1_700_000_005_000L, lat = 12.972, lng = 77.595, altitudeM = null, accuracyM = 6.0f),
            RideLocationEntity(rideId = 42, tMillis = 1_700_000_010_000L, lat = 12.973, lng = 77.596, altitudeM = 921.0, accuracyM = 4.0f),
        )

        val xml = GpxExporter.toGpx(ride, locations)

        // Parse — throws if malformed.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertNotNull(doc)

        // Root must be <gpx> with version=1.1 and the topografix namespace.
        val root = doc.documentElement
        assertEquals("gpx", root.tagName)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", root.getAttribute("xmlns"))
        assertEquals("REDLINE", root.getAttribute("creator"))

        // Exactly one trkpt per input location, in order.
        val trkpts = doc.getElementsByTagName("trkpt")
        assertEquals(locations.size, trkpts.length)
        for (i in locations.indices) {
            val pt = trkpts.item(i) as org.w3c.dom.Element
            assertEquals(locations[i].lat.toString(), pt.getAttribute("lat"))
            assertEquals(locations[i].lng.toString(), pt.getAttribute("lon"))
        }
    }

    @Test fun omitsEleWhenAltitudeIsNull() {
        val xml = GpxExporter.toGpx(
            ride,
            listOf(
                RideLocationEntity(rideId = 42, tMillis = 1_700_000_000_000L, lat = 0.0, lng = 0.0, altitudeM = null, accuracyM = null),
            ),
        )
        assertFalse("no <ele> tag when altitude is null", xml.contains("<ele>"))
    }

    @Test fun emitsEleWhenAltitudePresent() {
        val xml = GpxExporter.toGpx(
            ride,
            listOf(
                RideLocationEntity(rideId = 42, tMillis = 1_700_000_000_000L, lat = 0.0, lng = 0.0, altitudeM = 123.4, accuracyM = null),
            ),
        )
        assertTrue("<ele>123.4</ele> present", xml.contains("<ele>123.4</ele>"))
    }

    @Test fun timestampsAreIso8601Utc() {
        val xml = GpxExporter.toGpx(
            ride,
            listOf(
                RideLocationEntity(rideId = 42, tMillis = 1_700_000_000_000L, lat = 0.0, lng = 0.0, altitudeM = null, accuracyM = null),
            ),
        )
        // 1_700_000_000_000 ms = 2023-11-14T22:13:20Z
        assertTrue(
            "expected ISO-8601 UTC trkpt time; xml was:\n$xml",
            xml.contains("<time>2023-11-14T22:13:20Z</time>"),
        )
    }

    @Test fun emptyLocationListProducesValidEmptyTrack() {
        val xml = GpxExporter.toGpx(ride, emptyList())
        // Still parses and contains no trkpt children.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(0, doc.getElementsByTagName("trkpt").length)
        // But trk + trkseg should still be there.
        assertEquals(1, doc.getElementsByTagName("trk").length)
        assertEquals(1, doc.getElementsByTagName("trkseg").length)
    }
}
