package com.gaslog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReaderTest {
    private var y = 0f
    private fun line(text: String, x: Float = 0f): OcrLine {
        y += 60f
        return OcrLine(text, x, y, x + 300f, y + 40f)
    }

    private fun List<Pick>.value(f: LiveField) = firstOrNull { it.field == f }?.value

    @Test
    fun pumpMathFindsAllThree() {
        val picks = LiveReader.analyze(
            listOf(line("TOTAL SALE"), line("$ 47.16"), line("GALLONS"), line("8.021"), line("PRICE PER GALLON"), line("5.879")),
            LiveHint(),
        )
        assertEquals(5.879, picks.value(LiveField.PPG)!!, 1e-9)
        assertEquals(8.021, picks.value(LiveField.GALLONS)!!, 1e-9)
        assertEquals(47.16, picks.value(LiveField.COST)!!, 1e-9)
        assertTrue(picks.all { it.weight == 2 })
    }

    @Test
    fun pumpMathWithoutLabelsUsesLastPrice() {
        // A small 3-gallon top-up: price and gallons are both plausible prices; last price decides.
        val picks = LiveReader.analyze(listOf(line("17.64"), line("3.000"), line("5.879")), LiveHint(lastPpg = 5.6))
        assertEquals(5.879, picks.value(LiveField.PPG)!!, 1e-9)
        assertEquals(3.0, picks.value(LiveField.GALLONS)!!, 1e-9)
    }

    @Test
    fun lookalikeLettersAreFixed() {
        val picks = LiveReader.analyze(listOf(line("47.I6"), line("8.O21"), line("5.879")), LiveHint())
        assertEquals(47.16, picks.value(LiveField.COST)!!, 1e-9)
        assertEquals(8.021, picks.value(LiveField.GALLONS)!!, 1e-9)
    }

    @Test
    fun dashOdometerAndTripA() {
        val picks = LiveReader.analyze(
            listOf(line("ODO 52,318 mi"), line("TRIP A 312.4 mi"), line("TRIP B 1022.7"), line("AVG 31.2 MPG")),
            LiveHint(lastOdo = 52006.0),
        )
        assertEquals(52318.0, picks.value(LiveField.ODO)!!, 1e-9)
        assertEquals(312.4, picks.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun unlabeledTripMatchesOdometerChange() {
        val picks = LiveReader.analyze(listOf(line("52318"), line("312.0"), line("31.2")), LiveHint(lastOdo = 52006.0))
        assertEquals(52318.0, picks.value(LiveField.ODO)!!, 1e-9)
        assertEquals(312.0, picks.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun tripBAndAverageAreIgnored() {
        val picks = LiveReader.analyze(listOf(line("TRIP B 312.4"), line("31.2 MPG")), LiveHint())
        assertNull(picks.value(LiveField.TRIP_A))
    }

    @Test
    fun votesLockAfterRepeatedReadsAndRejectSticks() {
        val votes = LiveVotes()
        val l = line("x")
        val strong = listOf(Pick(LiveField.ODO, 52318.0, 2, l))
        assertTrue(votes.add(strong).isEmpty())
        assertEquals(setOf(LiveField.ODO), votes.add(strong))
        votes.reject(LiveField.ODO)
        repeat(5) { votes.add(strong) }
        assertNull(votes.locked[LiveField.ODO])
        val other = listOf(Pick(LiveField.ODO, 52319.0, 2, l))
        votes.add(other)
        votes.add(other)
        assertEquals(52319.0, votes.locked[LiveField.ODO]!!, 1e-9)
    }

    /* ── Dashes from real photos: boxes are where the text sits in a 2000×1500 shot. ── */

    private fun at(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())

    /** 2014-ish Mazda3: LCD "TRIP [A] 140.4 mile" beside the tach, speedometer scales around it. */
    private fun mazda3(vararg trip: OcrLine) = listOf(
        at("x1000", 585, 765, 665, 790), at("r/min", 590, 790, 655, 810),
        at("3", 295, 875, 320, 900), at("4", 355, 845, 380, 870), at("5", 430, 835, 455, 860), at("6", 495, 850, 520, 880),
        at("1", 205, 975, 225, 1000), at("0", 195, 1035, 220, 1060),
        at("TRIP", 280, 1005, 350, 1030), at("A", 275, 1035, 300, 1058),
        *trip,
        at("20", 920, 850, 1000, 900), at("40", 955, 680, 1035, 730), at("60", 1075, 560, 1150, 610),
        at("80", 1245, 510, 1325, 560), at("mph", 1255, 565, 1315, 590), at("100", 1410, 560, 1500, 610),
        at("120", 1530, 680, 1615, 735), at("140", 1580, 840, 1665, 895), at("160", 1550, 1000, 1630, 1055),
        at("100", 1170, 670, 1220, 700), at("120 140", 1235, 645, 1365, 675), at("km/h", 1260, 678, 1320, 700),
        at("160", 1380, 670, 1430, 700), at("80", 1115, 710, 1160, 740), at("180", 1425, 720, 1475, 750),
        at("60", 1080, 765, 1120, 795), at("200", 1465, 775, 1510, 805), at("40", 1060, 835, 1100, 865),
        at("220", 1470, 840, 1525, 870), at("20", 1065, 900, 1105, 930), at("240", 1470, 905, 1525, 935),
    )

    @Test
    fun mazdaTripWithUnitStuckOn() {
        for (read in listOf("140.4mile", "140.4 mile", "14O.4mile", "I40.4 mile", "140 4mile")) {
            val picks = LiveReader.analyze(mazda3(at(read, 465, 1005, 675, 1058)), LiveHint())
            assertEquals(read, 140.4, picks.value(LiveField.TRIP_A)!!, 1e-9)
            assertEquals(read, 2, picks.first { it.field == LiveField.TRIP_A }.weight)
            assertNull(read, picks.value(LiveField.ODO))
        }
    }

    @Test
    fun mazdaTripWithUnitOnItsOwnLine() {
        val picks = LiveReader.analyze(mazda3(at("140.4", 465, 1005, 610, 1058), at("mile", 615, 1025, 675, 1050)), LiveHint())
        assertEquals(140.4, picks.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun mazdaTripWithoutTheDecimalPoint() {
        val picks = LiveReader.analyze(mazda3(at("1404mile", 465, 1005, 675, 1058)), LiveHint())
        assertEquals(140.4, picks.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun tachMultiplierIsNotATrip() {
        // Last fill was 100 miles ago: "x1000" must not pass for 100.0.
        val picks = LiveReader.analyze(mazda3(), LiveHint(lastOdo = 60000.0, odoNow = 60100.0))
        assertNull(picks.value(LiveField.TRIP_A))
    }

    /** NA Miata: mechanical odometer "054444", trip counter "106̲7̲" (no point, tenths drum in white) below it. */
    private fun miata(odo: String, vararg trip: OcrLine) = listOf(
        at("30", 305, 415, 380, 455), at("60", 445, 415, 520, 455), at("0", 165, 520, 205, 555), at("90", 560, 495, 635, 530),
        at("lb/in2", 360, 800, 505, 840),
        at("60", 1055, 495, 1165, 555), at("80", 1390, 460, 1500, 520), at("40", 835, 740, 950, 800),
        at("100", 1620, 660, 1765, 720), at("20", 815, 1050, 925, 1110), at("120", 1700, 955, 1840, 1015),
        at("0", 955, 1300, 1015, 1365), at("140", 1580, 1250, 1720, 1310),
        at("100", 1180, 645, 1280, 690), at("120", 1305, 625, 1385, 665), at("140", 1440, 665, 1530, 705),
        at("80", 1085, 695, 1155, 735), at("160", 1530, 755, 1620, 800), at("60", 1005, 830, 1075, 870),
        at("180", 1555, 870, 1650, 910), at("40", 980, 975, 1050, 1010), at("200", 1550, 995, 1650, 1040),
        at("20", 1015, 1105, 1085, 1140), at("220", 1530, 1110, 1640, 1150), at("0", 1080, 1170, 1115, 1205),
        at(odo, 1115, 735, 1500, 795),
        *trip,
        at("km/h", 1250, 1270, 1370, 1310), at("MPH", 1240, 1310, 1440, 1365), at("UNLEADED FUEL ONLY", 1000, 1380, 1650, 1440),
    )

    @Test
    fun miataDrumCountersWithNoHistory() {
        for (read in listOf("1067", "1 0 6 7", "I 0 6 7", "106 7")) {
            val picks = LiveReader.analyze(miata("054444", at(read, 1140, 1120, 1510, 1185)), LiveHint())
            assertEquals(read, 54444.0, picks.value(LiveField.ODO)!!, 1e-9)
            assertEquals(read, 106.7, picks.value(LiveField.TRIP_A)!!, 1e-9)
        }
        // The white tenths drum read as a separate line.
        val split = LiveReader.analyze(
            miata(")54444", at("106", 1140, 1120, 1420, 1185), at("7", 1445, 1120, 1510, 1185)),
            LiveHint(),
        )
        assertEquals(54444.0, split.value(LiveField.ODO)!!, 1e-9)
        assertEquals(106.7, split.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun miataWithHistoryEvenWhenTheNeedleHidesTheTenths() {
        val hint = LiveHint(lastOdo = 54338.0)
        val full = LiveReader.analyze(miata("054444", at("1067", 1140, 1120, 1510, 1185)), hint)
        assertEquals(106.7, full.value(LiveField.TRIP_A)!!, 1e-9)
        assertEquals(2, full.first { it.field == LiveField.TRIP_A }.weight)
        val hidden = LiveReader.analyze(miata("54444", at("106", 1140, 1120, 1420, 1185)), hint)
        assertEquals(54444.0, hidden.value(LiveField.ODO)!!, 1e-9)
        assertEquals(106.0, hidden.value(LiveField.TRIP_A)!!, 1e-9)
    }

    @Test
    fun roundGaugeNumbersAreNotTrips() {
        // Odometer change is 100: the "100" on the speedometer must not be taken for Trip A.
        val picks = LiveReader.analyze(miata("054438"), LiveHint(lastOdo = 54338.0))
        assertNull(picks.value(LiveField.TRIP_A))
    }

    @Test
    fun rangeInMilesIsNotOdometerOrTrip() {
        val picks = LiveReader.analyze(listOf(line("RANGE"), line("245.0 mi")), LiveHint())
        assertNull(picks.value(LiveField.TRIP_A))
    }
}
