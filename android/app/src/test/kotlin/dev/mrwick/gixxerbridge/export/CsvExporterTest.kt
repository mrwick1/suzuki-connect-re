package dev.mrwick.gixxerbridge.export

import dev.mrwick.gixxerbridge.ble.FrameEvent
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CsvExporter].
 *
 * Covers ride-sample CSV (header + comment metadata + row formatting + null handling)
 * and Inspector frame-buffer CSV (header + type byte extraction + comma-sanitized notes).
 */
class CsvExporterTest {

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

    // ---------- rideSamplesToCsv ----------

    @Test fun rideSamples_emptyListEmitsOnlyHeaderAndComments() {
        val csv = CsvExporter.rideSamplesToCsv(ride, emptyList())
        val lines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("# GixxerBridge ride #42"))
        assertTrue(lines[1].startsWith("# Start: 2023-11-14T22:13:20Z"))
        assertEquals(
            "timestamp_iso,t_millis,speed_kmh,odometer_km,trip_a_km,trip_b_km,fuel_bars,fuel_econ_kml",
            lines[2],
        )
    }

    @Test fun rideSamples_singleSampleFormatsAllColumns() {
        val sample = RideSampleEntity(
            id = 1,
            rideId = 42,
            tMillis = 1_700_000_005_000L, // 2023-11-14T22:13:25Z
            speedKmh = 57,
            odometerKm = 12_345,
            tripAKm = 12.3,
            tripBKm = 4.5,
            fuelBars = 6,
            fuelEconKml = 41.7,
        )
        val csv = CsvExporter.rideSamplesToCsv(ride, listOf(sample))
        val dataRow = csv.lineSequence().last { it.isNotEmpty() }
        assertEquals(
            "2023-11-14T22:13:25Z,1700000005000,57,12345,12.3,4.5,6,41.7",
            dataRow,
        )
    }

    @Test fun rideSamples_nullFuelFieldsEmitEmptyCells() {
        val sample = RideSampleEntity(
            id = 1,
            rideId = 42,
            tMillis = 1_700_000_000_000L,
            speedKmh = 30,
            odometerKm = 1_000,
            tripAKm = 0.0,
            tripBKm = 0.0,
            fuelBars = null,
            fuelEconKml = null,
        )
        val csv = CsvExporter.rideSamplesToCsv(ride, listOf(sample))
        val dataRow = csv.lineSequence().last { it.isNotEmpty() }
        // Trailing two cells must be empty: ",,\n" pattern at the end of the row.
        assertTrue("row should end with two empty cells; was: $dataRow", dataRow.endsWith(",,"))
        assertEquals(
            "2023-11-14T22:13:20Z,1700000000000,30,1000,0.0,0.0,,",
            dataRow,
        )
    }

    @Test fun rideSamples_multipleRowsPreserveOrder() {
        val a = RideSampleEntity(
            id = 1, rideId = 42, tMillis = 1_700_000_000_000L,
            speedKmh = 10, odometerKm = 100, tripAKm = 0.1, tripBKm = 0.2,
            fuelBars = 8, fuelEconKml = 50.0,
        )
        val b = RideSampleEntity(
            id = 2, rideId = 42, tMillis = 1_700_000_001_000L,
            speedKmh = 20, odometerKm = 101, tripAKm = 0.3, tripBKm = 0.4,
            fuelBars = 7, fuelEconKml = 45.5,
        )
        val csv = CsvExporter.rideSamplesToCsv(ride, listOf(a, b))
        val dataRows = csv.lineSequence().filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp_iso") }.toList()
        assertEquals(2, dataRows.size)
        assertTrue("row 0 has speed 10", dataRows[0].contains(",10,"))
        assertTrue("row 1 has speed 20", dataRows[1].contains(",20,"))
    }

    // ---------- framesToCsv ----------

    @Test fun frames_emptyListEmitsOnlyHeader() {
        val csv = CsvExporter.framesToCsv(emptyList())
        val lines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(1, lines.size)
        assertEquals("timestamp_iso,t_millis,direction,type_byte,hex_bytes,note", lines[0])
    }

    @Test fun frames_singleEventExtractsTypeByteAndHex() {
        val bytes = byteArrayOf(0xA5.toByte(), 0x37.toByte(), 0x01, 0x02, 0x03)
        val event = FrameEvent(
            direction = FrameEvent.Direction.TX,
            bytes = bytes,
            tMillis = 1_700_000_000_123L,
            note = null,
        )
        val csv = CsvExporter.framesToCsv(listOf(event))
        val dataRow = csv.lineSequence().last { it.isNotEmpty() }
        assertEquals(
            "2023-11-14T22:13:20.123Z,1700000000123,TX,0x37,a537010203,",
            dataRow,
        )
    }

    @Test fun frames_shortPayloadOmitsTypeByte() {
        val event = FrameEvent(
            direction = FrameEvent.Direction.RX,
            bytes = byteArrayOf(0xA5.toByte()),
            tMillis = 1_700_000_000_000L,
            note = null,
        )
        val csv = CsvExporter.framesToCsv(listOf(event))
        val dataRow = csv.lineSequence().last { it.isNotEmpty() }
        // type_byte column should be empty (between RX and a5).
        assertTrue("type byte empty for 1-byte payload; was: $dataRow", dataRow.contains(",RX,,a5,"))
    }

    @Test fun frames_noteCommasAreEscaped() {
        val event = FrameEvent(
            direction = FrameEvent.Direction.TX,
            bytes = byteArrayOf(0xA5.toByte(), 0x33.toByte()),
            tMillis = 1_700_000_000_000L,
            note = "heartbeat, idle, no nav",
        )
        val csv = CsvExporter.framesToCsv(listOf(event))
        val dataRow = csv.lineSequence().last { it.isNotEmpty() }
        // Original commas in the note would break column alignment; they should be ';'.
        assertFalse("raw comma must be stripped from note", dataRow.contains("heartbeat, idle"))
        assertTrue("note commas replaced with ';'; was: $dataRow", dataRow.endsWith("heartbeat; idle; no nav"))
    }
}
