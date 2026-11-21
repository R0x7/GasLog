package com.gaslog.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRowTest {
    private fun fill(row: Int, odo: Double?) = Fill(row, null, odo, null, "", null, null, null, null, "")

    private val fills = listOf(fill(6, 50_000.0), fill(7, 50_300.0), fill(8, null), fill(9, 50_610.0))

    @Test
    fun picksClosestOdometer() {
        assertEquals(7, GasSheet.serviceRow(fills, 50_350.0, emptySet()))
        assertEquals(9, GasSheet.serviceRow(fills, 51_200.0, emptySet()))
        assertEquals(6, GasSheet.serviceRow(fills, 49_000.0, emptySet()))
    }

    @Test
    fun tieGoesToEarlierRow() {
        assertEquals(6, GasSheet.serviceRow(fills, 50_150.0, emptySet()))
    }

    @Test
    fun skipsRowsAlreadyHoldingThatService() {
        assertEquals(9, GasSheet.serviceRow(fills, 50_400.0, setOf(7)))
    }

    @Test
    fun noFillUpsFallsBackToNextFreeRow() {
        assertEquals(6, GasSheet.serviceRow(emptyList(), 50_000.0, emptySet()))
        assertEquals(12, GasSheet.serviceRow(listOf(fill(8, null)), 50_000.0, setOf(6, 11)))
    }
}
