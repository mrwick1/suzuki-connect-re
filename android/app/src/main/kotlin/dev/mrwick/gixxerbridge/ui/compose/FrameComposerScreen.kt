package dev.mrwick.gixxerbridge.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.protocol.CallFrame
import dev.mrwick.gixxerbridge.protocol.FrameType
import dev.mrwick.gixxerbridge.protocol.HeartbeatFrame
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.MissedCallFrame
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.protocol.SmsFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame

/**
 * Developer tool: pick any frame type (a531..a537), edit its fields by hand, and send
 * the resulting 30-byte frame to the bike via [AppGraph.sendFrame][dev.mrwick.gixxerbridge.app.AppGraph.sendFrame].
 * Last send's outcome (with hex) is shown at the bottom.
 */
@Composable
fun FrameComposerScreen(vm: FrameComposerViewModel) {
    val frameType by vm.frameType.collectAsStateWithLifecycle()
    val result by vm.lastResult.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        val types = FrameType.entries
        ScrollableTabRow(selectedTabIndex = types.indexOf(frameType)) {
            types.forEach { t ->
                Tab(
                    selected = t == frameType,
                    onClick = { vm.selectType(t) },
                    text = { Text(tabLabel(t), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (frameType) {
                FrameType.NAV -> NavForm(vm)
                FrameType.CALL -> CallForm(vm)
                FrameType.HEARTBEAT -> HeartbeatForm(vm)
                FrameType.MISSED_CALL -> MissedCallForm(vm)
                FrameType.SMS -> SmsForm(vm)
                FrameType.IDENTITY -> IdentityForm(vm)
                FrameType.TELEMETRY -> TelemetryForm(vm)
            }
        }
        result?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun tabLabel(t: FrameType): String = "a5%02x".format(t.code.toInt() and 0xFF)

/** a531 nav frame composer — turn-by-turn guidance fields. */
@Composable
private fun NavForm(vm: FrameComposerViewModel) {
    var maneuverId by remember { mutableStateOf("8") }
    var distNext by remember { mutableStateOf("0220") }
    var distNextUnit by remember { mutableStateOf("M") }
    var eta by remember { mutableStateOf("0530PM") }
    var distTotal by remember { mutableStateOf("01.2") }
    var distTotalUnit by remember { mutableStateOf("K") }
    var status by remember { mutableStateOf("1") }
    var continueFlag by remember { mutableStateOf("1") }

    OutlinedTextField(
        value = maneuverId,
        onValueChange = { maneuverId = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Maneuver ID (0-75)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedTextField(
            value = distNext,
            onValueChange = { distNext = it.take(4) },
            label = { Text("Dist next (4 chars)") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = distNextUnit,
            onValueChange = { distNextUnit = it.take(1) },
            label = { Text("Unit") },
            modifier = Modifier.width(96.dp),
        )
    }
    OutlinedTextField(
        value = eta,
        onValueChange = { eta = it.take(6) },
        label = { Text("ETA (6 chars, e.g. 0530PM)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedTextField(
            value = distTotal,
            onValueChange = { distTotal = it.take(4) },
            label = { Text("Dist total") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = distTotalUnit,
            onValueChange = { distTotalUnit = it.take(1) },
            label = { Text("Unit") },
            modifier = Modifier.width(96.dp),
        )
    }
    Row {
        OutlinedTextField(
            value = status,
            onValueChange = { status = it.take(1) },
            label = { Text("Status (0-6)") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = continueFlag,
            onValueChange = { continueFlag = it.take(1) },
            label = { Text("Continue") },
            modifier = Modifier.weight(1f),
        )
    }
    Button(onClick = {
        vm.send(
            NavFrame(
                maneuverId = maneuverId.toIntOrNull() ?: 8,
                distNext = distNext,
                distNextUnit = distNextUnit,
                eta = eta,
                distTotal = distTotal,
                distTotalUnit = distTotalUnit,
                status = status,
                continueFlag = continueFlag,
            ),
        )
    }) { Text("Send a531") }
}

/** a532 incoming-call composer — phone number + cellular/WhatsApp source + state. */
@Composable
private fun CallForm(vm: FrameComposerViewModel) {
    var number by remember { mutableStateOf("9876543210") }
    var isWhatsapp by remember { mutableStateOf("false") }
    var state by remember { mutableStateOf("49") } // 0x31 = '1'

    OutlinedTextField(
        value = number,
        onValueChange = { number = it.take(20) },
        label = { Text("Number (<=20 chars)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = isWhatsapp,
        onValueChange = { isWhatsapp = it.take(5) },
        label = { Text("isWhatsapp (true/false)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state,
        onValueChange = { state = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("State byte int (decimal, 0x31='1'=49, 0x32='2'=50)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        vm.send(
            CallFrame(
                number = number,
                isWhatsapp = isWhatsapp.equals("true", ignoreCase = true),
                state = state.toIntOrNull() ?: 0x31,
            ),
        )
    }) { Text("Send a532") }
}

/** a533 heartbeat composer — phone/env dashboard the bike polls every second. */
@Composable
private fun HeartbeatForm(vm: FrameComposerViewModel) {
    var batteryBucket by remember { mutableStateOf("9") }
    var charging by remember { mutableStateOf("N") }
    var speedStr by remember { mutableStateOf("000") }
    var signalStatus by remember { mutableStateOf("4") }
    var timeHhmmss by remember { mutableStateOf("123045") }
    var smsPending by remember { mutableStateOf("N") }
    var callPending by remember { mutableStateOf("N") }
    var weather by remember { mutableStateOf("1") }
    var tempFPlus115 by remember { mutableStateOf("0") }
    var tailConst by remember { mutableStateOf("1") }

    OutlinedTextField(
        value = batteryBucket,
        onValueChange = { batteryBucket = it.take(1) },
        label = { Text("Battery bucket ('0'-'9')") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = charging,
        onValueChange = { charging = it.take(1) },
        label = { Text("Charging ('Y'/'N')") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedTextField(
            value = speedStr,
            onValueChange = { speedStr = it.take(3) },
            label = { Text("Speed (3 chars or empty)") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = signalStatus,
            onValueChange = { signalStatus = it.take(1) },
            label = { Text("Signal") },
            modifier = Modifier.width(96.dp),
        )
    }
    OutlinedTextField(
        value = timeHhmmss,
        onValueChange = { timeHhmmss = it.take(6) },
        label = { Text("Time HHMMSS (6 chars, or empty)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedTextField(
            value = smsPending,
            onValueChange = { smsPending = it.take(1) },
            label = { Text("SMS ('Y'/'N')") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = callPending,
            onValueChange = { callPending = it.take(1) },
            label = { Text("Call ('Y'/'N')") },
            modifier = Modifier.weight(1f),
        )
    }
    Row {
        OutlinedTextField(
            value = weather,
            onValueChange = { weather = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Weather (0-255)") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = tempFPlus115,
            onValueChange = { tempFPlus115 = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("TempF+115") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = tailConst,
            onValueChange = { tailConst = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Tail") },
            modifier = Modifier.weight(1f),
        )
    }
    Button(onClick = {
        vm.send(
            HeartbeatFrame(
                batteryBucket = batteryBucket,
                charging = charging,
                speedStr = speedStr,
                signalStatus = signalStatus,
                timeHhmmss = timeHhmmss,
                smsPending = smsPending,
                callPending = callPending,
                weather = weather.toIntOrNull() ?: 0x01,
                tempFPlus115 = tempFPlus115.toIntOrNull() ?: 0x00,
                tailConst = tailConst.toIntOrNull() ?: 0x01,
            ),
        )
    }) { Text("Send a533") }
}

/** a534 missed-call composer — caller name + missed count + source. */
@Composable
private fun MissedCallForm(vm: FrameComposerViewModel) {
    var name by remember { mutableStateOf("Mom") }
    var missedCount by remember { mutableStateOf("1") }
    var isWhatsapp by remember { mutableStateOf("false") }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(18) },
        label = { Text("Caller name (<=18 chars)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = missedCount,
        onValueChange = { missedCount = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Missed count") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = isWhatsapp,
        onValueChange = { isWhatsapp = it.take(5) },
        label = { Text("isWhatsapp (true/false)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        vm.send(
            MissedCallFrame(
                name = name,
                missedCount = missedCount.toIntOrNull() ?: 1,
                isWhatsapp = isWhatsapp.equals("true", ignoreCase = true),
            ),
        )
    }) { Text("Send a534") }
}

/** a535 SMS / notification composer — sender name + count + silenced flag. */
@Composable
private fun SmsForm(vm: FrameComposerViewModel) {
    var sender by remember { mutableStateOf("WhatsApp") }
    var messageCount by remember { mutableStateOf("1") }
    var silenced by remember { mutableStateOf("true") }
    var typeByte by remember { mutableStateOf("78") } // 0x4E = 'N'

    OutlinedTextField(
        value = sender,
        onValueChange = { sender = it.take(20) },
        label = { Text("Sender (<=20 chars)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = messageCount,
        onValueChange = { messageCount = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Message count") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = silenced,
        onValueChange = { silenced = it.take(5) },
        label = { Text("Silenced (true/false)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = typeByte,
        onValueChange = { typeByte = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Type byte int (0x4E='N'=78)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        vm.send(
            SmsFrame(
                sender = sender,
                messageCount = messageCount.toIntOrNull() ?: 1,
                silenced = silenced.equals("true", ignoreCase = true),
                typeByte = typeByte.toIntOrNull() ?: 0x4E,
            ),
        )
    }) { Text("Send a535") }
}

/** a536 identity composer — user name + fresh/reconnect flag (sent on connect). */
@Composable
private fun IdentityForm(vm: FrameComposerViewModel) {
    var name by remember { mutableStateOf("Arjun") }
    var isFresh by remember { mutableStateOf("false") }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(20) },
        label = { Text("Name (<=20 chars)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = isFresh,
        onValueChange = { isFresh = it.take(5) },
        label = { Text("isFresh (true='F' / false='R')") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        vm.send(
            IdentityFrame(
                name = name,
                isFresh = isFresh.equals("true", ignoreCase = true),
            ),
        )
    }) { Text("Send a536") }
}

/**
 * a537 telemetry composer — primarily RX-only (bike -> phone). Form exists so
 * you can forge a frame back at the bike for experiments; defaults match a typical
 * Gixxer SF 150 capture.
 */
@Composable
private fun TelemetryForm(vm: FrameComposerViewModel) {
    var speedKmh by remember { mutableStateOf("0") }
    var odometerKm by remember { mutableStateOf("12345") }
    var tripAKm by remember { mutableStateOf("12.3") }
    var tripBKm by remember { mutableStateOf("4.5") }
    var fuelBars by remember { mutableStateOf("4") }
    var fuelEconKml by remember { mutableStateOf("48.0") }

    Text(
        "Note: a537 is normally bike->phone (RX). Sending one TO the bike is for experiments only.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = speedKmh,
        onValueChange = { speedKmh = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Speed km/h") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = odometerKm,
        onValueChange = { odometerKm = it.filter { c -> c.isDigit() }.take(6) },
        label = { Text("Odometer km") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedTextField(
            value = tripAKm,
            onValueChange = { tripAKm = it.take(7) },
            label = { Text("Trip A km") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = tripBKm,
            onValueChange = { tripBKm = it.take(7) },
            label = { Text("Trip B km") },
            modifier = Modifier.weight(1f),
        )
    }
    Row {
        OutlinedTextField(
            value = fuelBars,
            onValueChange = { fuelBars = it.filter { c -> c.isDigit() }.take(1) },
            label = { Text("Fuel bars (1-6, blank=null)") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = fuelEconKml,
            onValueChange = { fuelEconKml = it.take(7) },
            label = { Text("Fuel econ km/L (blank=null)") },
            modifier = Modifier.weight(1f),
        )
    }
    Button(onClick = {
        vm.send(
            TelemetryFrame(
                speedKmh = speedKmh.toIntOrNull() ?: 0,
                odometerKm = odometerKm.toIntOrNull() ?: 0,
                tripAKm = tripAKm.toDoubleOrNull() ?: 0.0,
                tripBKm = tripBKm.toDoubleOrNull() ?: 0.0,
                fuelBars = fuelBars.toIntOrNull(),
                fuelEconKml = fuelEconKml.toDoubleOrNull(),
            ),
        )
    }) { Text("Send a537") }
}
