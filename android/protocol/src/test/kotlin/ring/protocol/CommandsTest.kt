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

    @Test fun `real time keeps collecting beats after the ring starts reporting bpm`() = runTest {
        fun rrPacket(ms: Int, bpm: Int) = Packet.build(0x69, byteArrayOf(1, 0, bpm.toByte(), 0, 0, ms.toByte(), (ms shr 8).toByte()))
        val rrs = listOf(800, 810, 790, 805, 795, 812, 801, 789, 808, 797, 811, 803, 799, 806, 794, 807, 802, 796, 809, 798)
        val tr = FakeTransport().apply { responses[0x69] = rrs.mapIndexed { i, ms -> rrPacket(ms, if (i >= 2) 72 else 0) } }
        val result = assertNotNull(RealTimeCommand.measure(RingClient(tr), targetBeats = 18))
        assertEquals(18, result.rr.size)
        assertEquals(72, result.bpm)
    }

    @Test fun `real time returns partial beats when the stream stops early`() = runTest {
        fun rrPacket(ms: Int) = Packet.build(0x69, byteArrayOf(1, 0, 0, 0, 0, ms.toByte(), (ms shr 8).toByte()))
        val tr = FakeTransport().apply { responses[0x69] = listOf(800, 810, 790, 805, 795).map(::rrPacket) }
        val result = assertNotNull(RealTimeCommand.measure(RingClient(tr), targetBeats = 50))
        assertEquals(5, result.rr.size)
        assertEquals(75, result.bpm)
    }

    @Test fun `real time with too few beats and silence gives null`() = runTest {
        val tr = FakeTransport().apply { responses[0x69] = listOf(Packet.build(0x69, byteArrayOf(1, 0, 0, 0, 0, 0x20, 0x03))) }
        assertNull(RealTimeCommand.measure(RingClient(tr), targetBeats = 50))
    }

    @Test fun `single value measurement returns the median of the last stable readings`() = runTest {
        fun pkt(v: Int) = Packet.build(0x69, byteArrayOf(3, 0, v.toByte()))
        val tr = FakeTransport().apply { responses[0x69] = listOf(0, 0, 0, 91, 99, 96, 97, 97, 98, 97, 99, 99).map(::pkt) }
        assertEquals(97, RealTimeCommand.measureValue(RingClient(tr), RealTimeCommand.KIND_SPO2))
        assertEquals(0x6A, tr.sent.last()[0].toInt())
        assertEquals(3, tr.sent.last()[1].toInt())
    }

    @Test fun `single value measurement gives null on error or without enough readings`() = runTest {
        val err = Packet.build(0x69, byteArrayOf(3, 2, 0))
        assertNull(RealTimeCommand.measureValue(RingClient(FakeTransport().apply { responses[0x69] = listOf(err) }), 3))
        val few = FakeTransport().apply { responses[0x69] = listOf(97, 98).map { Packet.build(0x69, byteArrayOf(3, 0, it.toByte())) } }
        assertNull(RealTimeCommand.measureValue(RingClient(few), 3))
    }

    @Test fun `find ring and blink are fire and forget packets`() = runTest {
        val tr = FakeTransport()
        val client = RingClient(tr)
        FindRingCommand.find(client)
        FindRingCommand.blink(client)
        assertContentEquals(Packet.build(0x50, byteArrayOf(0x55, 0xAA.toByte())), tr.sent[0])
        assertContentEquals(Packet.build(0x10), tr.sent[1])
        assertContentEquals(byteArrayOf(0x55, 0xAA.toByte()), FindRingCommand.encodeFindPayload())
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

    private fun stepPacket(vararg b: Int) = Packet.build(0x43, ByteArray(b.size) { b[it].toByte() })

    // Real packets from a Colmi R02 (tahnok/colmi_r02_client docs): header + 5 slots on 2023-08-13.
    private val realSteps = listOf(
        stepPacket(0xF0, 0x05, 0x01),
        stepPacket(0x23, 0x08, 0x13, 0x10, 0x00, 0x05, 0xC8, 0x00, 0x30, 0x00, 0x1B, 0x00),
        stepPacket(0x23, 0x08, 0x13, 0x14, 0x01, 0x05, 0xB6, 0x18, 0xAA, 0x04, 0x69, 0x03),
        stepPacket(0x23, 0x08, 0x13, 0x18, 0x02, 0x05, 0x38, 0x04, 0xE1, 0x00, 0x95, 0x00),
        stepPacket(0x23, 0x08, 0x13, 0x1C, 0x03, 0x05, 0x05, 0x02, 0x6C, 0x00, 0x48, 0x00),
        stepPacket(0x23, 0x08, 0x13, 0x4C, 0x04, 0x05, 0xEF, 0x01, 0x63, 0x00, 0x44, 0x00),
    ).map { Packet.parse(it) }

    @Test fun `steps request encoding`() {
        assertContentEquals(byteArrayOf(0, 0x0F, 0x00, 0x5F, 0x01), StepsCommand.encodeRequest(0))
        assertContentEquals(byteArrayOf(3, 0x0F, 0x00, 0x5F, 0x01), StepsCommand.encodeRequest(3))
        assertFailsWith<IllegalArgumentException> { StepsCommand.encodeRequest(256) }
    }

    @Test fun `steps decode real packets`() {
        val slots = StepsCommand.decode(realSteps)
        assertEquals(5, slots.size)
        assertEquals(Instant.parse("2023-08-13T04:00:00Z"), slots[0].time)
        assertEquals(48, slots[0].steps)
        assertEquals(27, slots[0].distanceM)
        assertEquals(2000, slots[0].calories) // new calorie protocol: x10
        assertEquals(1194, slots[1].steps)
        assertEquals(873, slots[1].distanceM)
        assertEquals(Instant.parse("2023-08-13T19:00:00Z"), slots[4].time)
        assertEquals(99, slots[4].steps)
    }

    @Test fun `steps completion and no data`() {
        assertEquals(listOf(false, false, false, false, false, true), realSteps.indices.map { StepsCommand.isComplete(realSteps.subList(0, it + 1)) })
        val none = listOf(Packet.parse(stepPacket(0xFF)))
        assertTrue(StepsCommand.isComplete(none))
        assertTrue(StepsCommand.decode(none).isEmpty())
        assertTrue(StepsCommand.isComplete(listOf(Packet.parse(stepPacket(0xF0, 0x00, 0x00)))))
    }

    @Test fun `steps without the new calorie flag keep calories as is`() {
        val packets = listOf(Packet.parse(stepPacket(0x26, 0x11, 0x10, 0x08, 0x00, 0x01, 0x64, 0x00, 0x0A, 0x00, 0x07, 0x00)))
        assertEquals(100, StepsCommand.decode(packets).single().calories)
        assertFailsWith<IllegalArgumentException> { StepsCommand.decode(listOf(Packet.parse(stepPacket(0x2A, 0x11, 0x10, 0x08, 0, 1)))) }
    }

    @Test fun `steps read roundtrip`() = runTest {
        val tr = FakeTransport().apply { responses[0x43] = realSteps.map { Packet.build(0x43, it.payload.copyOf(12)) } }
        assertEquals(5, StepsCommand.read(RingClient(tr)).size)
        assertContentEquals(Packet.build(0x43, StepsCommand.encodeRequest(0)), tr.sent.single())
    }

    @Test fun `goals decode`() {
        val goals = GoalsCommand.decode(Packet.parse(Packet.build(0x21, byteArrayOf(1, 0x10, 0x27, 0x00, 0xE8.toByte(), 0x03, 0x00))))
        assertEquals(Goals(steps = 10000, calories = 1000), goals)
        assertContentEquals(byteArrayOf(1), GoalsCommand.encodeRead())
    }

    // Real R09 responses (FW RT09CR2_3.00.03_260929, 2026-11-10).
    @Test fun `steps and goals real r09 packets`() {
        val packets = packets(
            "43f00401000000000000000000000038",
            "43261110380004f001b80072000000e1",
            "432611103c010423000d000800000003",
            "43261110400204950184005d00000047",
            "432611104403048a002f001f000000ad",
        )
        val slots = StepsCommand.decode(packets)
        assertEquals(listOf(184, 13, 132, 47), slots.map { it.steps })
        assertEquals(Instant.parse("2026-11-10T14:00:00Z"), slots.first().time)
        assertEquals(376, slots.sumOf { it.steps })
        assertTrue(StepsCommand.isComplete(packets))
        assertTrue(StepsCommand.decode(packets("43ff0000000000000000000000000042")).isEmpty())
        val goals = GoalsCommand.decode(packets("2101881300e09304b80b0000000000f7").single())
        assertEquals(5000, goals.steps)
        assertEquals(300000, goals.calories)
    }

    @Test fun `alarm write payload and read decode roundtrip`() {
        val alarms = listOf(RingAlarm(true, 6 * 60, 0b0111110, "Work"), RingAlarm(false, 23 * 60 + 30, 0, ""))
        val payload = AlarmCommand.encodeWritePayload(alarms)
        assertEquals(2, payload[0].toInt())
        assertEquals(2, payload[1].toInt())
        // reply to a read has the same list preceded by op 1
        val reply = BigFrame(AlarmCommand.TYPE, byteArrayOf(1) + payload.copyOfRange(1, payload.size))
        assertEquals(alarms, AlarmCommand.decode(reply))
    }

    @Test fun `alarm record layout matches gadgetbridge`() {
        val payload = AlarmCommand.encodeWritePayload(listOf(RingAlarm(true, 7 * 60, 0b0000010, "A")))
        // [op][count][len=5][flags=0x80|0x02][minutes lo/hi = 420][name]
        assertContentEquals(byteArrayOf(2, 1, 5, 0x82.toByte(), 0xA4.toByte(), 0x01, 'A'.code.toByte()), payload)
    }

    @Test fun `alarm ack has no list and bad values are rejected`() {
        assertNull(AlarmCommand.decode(BigFrame(AlarmCommand.TYPE, byteArrayOf(2))))
        assertContentEquals(byteArrayOf(1), AlarmCommand.encodeReadRequest())
        assertFailsWith<IllegalArgumentException> { AlarmCommand.encodeWritePayload(listOf(RingAlarm(true, 1440, 0))) }
        assertFailsWith<IllegalArgumentException> { AlarmCommand.encodeWritePayload(listOf(RingAlarm(true, 10, 0x80))) }
        assertFailsWith<IllegalArgumentException> { AlarmCommand.decode(BigFrame(AlarmCommand.TYPE, byteArrayOf(1, 1, 9, 0))) }
    }

    @Test fun `alarm names are truncated on a character boundary`() {
        val long = "é".repeat(20) // 40 bytes
        val payload = AlarmCommand.encodeWritePayload(listOf(RingAlarm(true, 0, 0, long)))
        assertEquals(2 + 4 + 28, payload.size)
        val decoded = AlarmCommand.decode(BigFrame(AlarmCommand.TYPE, byteArrayOf(1) + payload.copyOfRange(1, payload.size)))
        assertEquals("é".repeat(14), decoded!!.single().name)
    }

    @Test fun `push message is chunked in 11 byte pieces`() {
        assertEquals(1, PushMessageCommand.encode(1, "Hi").size)
        val chunks = PushMessageCommand.encode(1, "A".repeat(25))
        assertEquals(3, chunks.size)
        assertContentEquals(byteArrayOf(1, 3, 1) + "A".repeat(11).toByteArray(), chunks[0])
        assertContentEquals(byteArrayOf(1, 3, 3) + "A".repeat(3).toByteArray(), chunks[2])
        assertEquals(4, PushMessageCommand.encode(1, "B".repeat(200)).size)
    }

    @Test fun `push message goes out as v1 packets`() = runTest {
        val tr = FakeTransport()
        PushMessageCommand.send(RingClient(tr), "Hello ring now!!")
        assertEquals(2, tr.sent.size)
        assertEquals(0x72, tr.sent[0][0].toInt())
    }

    @Test fun `alarm write sends the list then reads it back`() = runTest {
        val alarms = listOf(RingAlarm(true, 6 * 60, 0b0111110, ""))
        val written = AlarmCommand.encodeWritePayload(alarms)
        val reply = BigData.encodeFrame(0x2C, byteArrayOf(1) + written.copyOfRange(1, written.size))
        val tr = FakeTransport(keyIndex = 1).apply { responses[0x2C] = listOf(reply) }
        val confirmed = AlarmCommand.write(BigDataClient(tr), alarms)
        assertEquals(alarms, confirmed)
        assertEquals(2, tr.sent.size)
        assertEquals(2, tr.sent[0][6].toInt()) // first frame is the write (op 2), second the read request (op 1)
        assertEquals(1, tr.sent[1][6].toInt())
    }

    /** Transport answering each write with the next scripted batch of notifications. */
    private class ScriptedTransport(private val batches: MutableList<List<ByteArray>>) : Transport {
        val sent = mutableListOf<ByteArray>()
        private var handler: (ByteArray) -> Unit = {}

        override fun setNotifyHandler(handler: (ByteArray) -> Unit) {
            this.handler = handler
        }

        override suspend fun write(data: ByteArray) {
            sent += data
            batches.removeFirstOrNull()?.forEach(handler)
        }
    }

    @Test fun `alarm read skips a stray acknowledgement`() = runTest {
        val ack = BigData.encodeFrame(0x2C, byteArrayOf(2))
        val list = BigData.encodeFrame(0x2C, byteArrayOf(1, 0))
        val tr = ScriptedTransport(mutableListOf(listOf(ack), listOf(list)))
        assertEquals(emptyList(), AlarmCommand.read(BigDataClient(tr)))
        assertEquals(2, tr.sent.size)
    }
}
