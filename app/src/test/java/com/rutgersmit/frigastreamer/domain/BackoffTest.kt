package com.rutgersmit.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun `doubles from 1 second up to a maximum of 30 seconds`() {
        val b = Backoff(1_000, 30_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (1..8).map { b.delayFor(it) })
    }

    @Test
    fun `very high attempt numbers stay at the maximum without overflow`() {
        val b = Backoff(1_000, 30_000)
        assertEquals(30_000L, b.delayFor(1_000))
        assertEquals(30_000L, b.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun `custom bounds`() {
        val b = Backoff(2_000, 10_000)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 10_000L), (1..4).map { b.delayFor(it) })
        assertEquals(2_000L, b.delayFor(0))
    }
}
