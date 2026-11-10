package ring.protocol

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ring.protocol.commands.*

class CommandsTest {
    private val day = Instant.parse("2026-11-10T00:00:00Z")
    private fun at(h: Int, m: Int) = day.plusSeconds(h * 3600L + m * 60L)

    private val realLog = listOf(
        "15001805000000000000000000000032",
        "1501805ef26a00000000000000000050",
        "15020000000000000000000000000017",
        "15030000000000000000000000000018",
        "15040000000000000000000000000019",
        "1505000000000000000000000000001a",
        "1506000000000000000000000000001b",
        "1507000000000000000000000000001c",
        "1508000000000000000000000000001d",
        "1509000000000000000000000000001e",
        "150a000000000000000000000000001f",
        "150b0000000000000000000000000020",
        "150c0000000000000000000000000021",
        "150d0000000000000000000000000022",
        "150e0000000000000000600000000083",
        "150f0000000000000000000000000024",
        "15100000000000000000000000000025",
        "15110000000000000000000000000026",
        "15120000000000000000000000000027",
        "15130000000000000000000000000028",
        "15140000000000000000000000000029",
        "1515000000000000000000000000002a",
        "1516000000000000000000000000002b",
        "1517000000000000000000000000002c",
    )
    private val realStress = listOf(
        "3700051e00000000000000000000005a",
        "37010000000000000000000000000038",
        "37020000000000000000000000000039",
        "3703000000002300000000000000005d",
        "3704000000000000000000000000003b",
    )

    @Test fun `battery real vector`() {
        assertEquals(Battery(7, false), BatteryCommand.decode(packets("0307000000000000000000000000000a").single()))
        assertFailsWith<IllegalArgumentException> { BatteryCommand.decode(Packet(0x01, ByteArray(14))) }
    }

    @Test fun `set time encodes bcd utc and decodes real capabilities`() {
        assertContentEquals(
            byteArrayOf(0x26, 0x11, 0x10, 0x15, 0x07, 0x09, 0x01),
            SetTimeCommand.encodePayload(Instant.parse("2026-11-10T15:07:09Z")),
        )
        val caps = SetTimeCommand.decode(packets("01010000020000000001002000003055").single())
        assertTrue(caps.temperature && caps.bloodOxygen && caps.stress && caps.hrv && caps.newSleepProtocol)
        assertTrue(!caps.bloodPressure && !caps.manualHeartRate && !caps.bloodSugar)
    }

    @Test fun `hr settings real vectors and write encoding`() {
        assertEquals(HeartRateSettings(false, 30), HrSettingsCommand.decode(packets("1601021e05000000000000000000003c").single()))
        assertEquals(HeartRateSettings(true, 5), HrSettingsCommand.decode(packets("16010105050000000000000000000022").single()))
        assertContentEquals(byteArrayOf(2, 1, 5), HrSettingsCommand.encodeWrite(HeartRateSettings(true, 5)))
        assertFailsWith<IllegalArgumentException> { HrSettingsCommand.encodeWrite(HeartRateSettings(true, 0)) }
    }

    @Test fun `auto monitor encodings and real reads`() {
        assertContentEquals(byteArrayOf(1), AutoMonitorCommand.encodeRead(Feature.SPO2))
        assertContentEquals(byteArrayOf(3, 1), AutoMonitorCommand.encodeRead(Feature.TEMPERATURE))
        assertContentEquals(byteArrayOf(3, 2, 1), AutoMonitorCommand.encodeWrite(Feature.TEMPERATURE, true))
        assertEquals(true, AutoMonitorCommand.decode(Feature.SPO2, packets("2c01010000000000000000000000002e").single()))
        assertEquals(false, AutoMonitorCommand.decode(Feature.STRESS, packets("36010000000000000000000000000037").single()))
        assertEquals(true, AutoMonitorCommand.decode(Feature.TEMPERATURE, packets("3a03010100000000000000000000003f").single()))
    }

    @Test fun `hr log real vector has one reading at the right utc time`() {
        val log = assertNotNull(HrLogCommand.decode(packets(*realLog.toTypedArray())))
        assertEquals(day, log.dayStart)
        assertEquals(5, log.intervalMinutes)
        assertEquals(288, log.samples.size)
        assertEquals(listOf(Reading(at(14, 25), 96.0)), log.readings())
    }

    @Test fun `hr log no data and request encoding`() {
        assertNull(HrLogCommand.decode(packets("15ff0000000000000000000000000014")))
        assertContentEquals("805ef26a".hexToBytes(), HrLogCommand.encodeRequest(day))
    }

    @Test fun `stress real vector`() {
        val log = assertNotNull(SlotLogCommand.decode(packets(*realStress.toTypedArray())))
        assertEquals(30, log.intervalMinutes)
        assertEquals(listOf(Reading(at(14, 30), 35.0)), log.readings(day))
        assertNull(SlotLogCommand.decode(packets("39ff0000000000000000000000000038")))
    }

    @Test fun `temperature real payload`() {
        val payload = ("001e" + "00".repeat(29) + "a9" + "00".repeat(18)).hexToBytes()
        val days = TemperatureCommand.decode(BigFrame(0x25, payload))
        assertEquals(1, days.size)
        val reading = days.single().readings(day).single()
        assertEquals(at(14, 30), reading.time)
        assertEquals(36.9, reading.value, 1e-9)
        assertTrue(TemperatureCommand.decode(BigFrame(0x25, "001e".hexToBytes())).isEmpty())
        assertFailsWith<IllegalArgumentException> { TemperatureCommand.decode(BigFrame(0x25, payload.copyOf(49))) }
    }

    @Test fun `spo2 synthetic layout`() {
        val block = ByteArray(49).also { it[0] = 0; it[1 + 2 * 3] = 95.toByte(); it[2 + 2 * 3] = 99.toByte() }
        val days = Spo2Command.decode(BigFrame(0x2A, block))
        assertEquals(97.0, days.single().readings(day).single().value)
        assertEquals(at(3, 0), days.single().readings(day).single().time)
        assertTrue(Spo2Command.decode(BigFrame(0x2A, ByteArray(0))).isEmpty())
    }

    @Test fun `sleep synthetic layout and overnight window`() {
        val payload = byteArrayOf(1, 0, 10, 1400.toByte(), (1400 shr 8).toByte(), 400.toByte(), (400 shr 8).toByte(), 2, 30, 3, 60, 5, 0)
        val session = SleepCommand.decode(BigFrame(0x27, payload)).single()
        assertEquals(listOf(SleepStageType.LIGHT, SleepStageType.DEEP), session.stages.map { it.type })
        val (start, end) = session.window(day)
        assertEquals(day.minusSeconds(40 * 60L), start)
        assertEquals(day.plusSeconds(400 * 60L), end)
        assertTrue(SleepCommand.decode(BigFrame(0x27, byteArrayOf(0))).isEmpty())
    }

    @Test fun `real time decodes bpm and rr from real packets`() {
        val zeros = RealTimeCommand.decode(packets("6901000000000000000000000000006a").single())
        assertEquals(RealTimeReading(1, 0, 0, 0), zeros)
        val rr = RealTimeCommand.decode(packets("690100000000ee02000000000000005a").single())
        assertEquals(RealTimeReading(1, 0, 0, 750), rr)
    }

    @Test fun `real time measure collects distinct beats and derives bpm`() = runTest {
        fun rrPacket(ms: Int) = Packet.build(0x69, byteArrayOf(1, 0, 0, 0, 0, ms.toByte(), (ms shr 8).toByte()))
        val tr = FakeTransport().apply {
            responses[0x69] = List(5) { Packet.build(0x69, byteArrayOf(1, 0, 0)) } +
                listOf(750, 750, 723, 686, 700, 710).map(::rrPacket)
        }
        val progress = mutableListOf<Int>()
        val result = assertNotNull(RealTimeCommand.measure(RingClient(tr), targetBeats = 5) { progress += it })
        assertEquals(listOf(750, 723, 686, 700, 710), result.rr)
        assertEquals(85, result.bpm)
        assertEquals(0x6A, tr.sent.last()[0].toInt())
        assertTrue(progress.last() >= 5)
    }

    @Test fun `real time measure prefers ring bpm when present and returns null on error`() = runTest {
        val bpmPackets = FakeTransport().apply { responses[0x69] = List(10) { Packet.build(0x69, byteArrayOf(1, 0, 72)) } }
        assertEquals(72, RealTimeCommand.measure(RingClient(bpmPackets), targetBeats = 1)?.bpm)
    }

    @Test fun `hrv metrics and artifact cleaning`() {
        val rr = listOf(800, 810, 790, 805, 795)
        assertEquals(15.0, assertNotNull(Hrv.rmssd(rr)), 1.0)
        assertEquals(rr, Hrv.clean(rr))
        assertEquals(listOf(800, 810, 790), Hrv.clean(listOf(800, 810, 5000, 790, 100)))
        assertEquals(listOf(800, 810, 790, 805), Hrv.clean(listOf(800, 810, 790, 1500, 805)))
        assertNull(Hrv.rmssd(listOf(800)))
        assertNull(Hrv.sdnn(emptyList()))
        assertEquals(75, Hrv.meanBpm(listOf(800, 800, 800)))
    }
}
