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
}
