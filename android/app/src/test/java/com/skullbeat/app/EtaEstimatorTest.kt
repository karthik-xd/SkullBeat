package com.skullbeat.app

import com.skullbeat.app.domain.util.EtaEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEstimatorTest {

    @Test
    fun testInitialStateReturnsEstimating() {
        var mockTime = 1000L
        val estimator = EtaEstimator(clock = { mockTime })
        estimator.start()

        val info = estimator.calculate(processed = 0, total = 50)
        assertEquals(0, info.processed)
        assertEquals(50, info.total)
        assertEquals(50, info.remainingItems)
        assertEquals("Estimating...", info.formattedEta)
        assertEquals("Estimating time remaining...", info.fullSummary)
    }

    @Test
    fun testAccurateRemainingSecondsAndSpeed() {
        var mockTime = 1000L
        val estimator = EtaEstimator(clock = { mockTime })
        estimator.start()

        // 10 items processed in 5 seconds -> 2 items/sec
        mockTime = 6000L
        val info = estimator.calculate(processed = 10, total = 50)

        assertEquals(10, info.processed)
        assertEquals(50, info.total)
        assertEquals(40, info.remainingItems)
        assertEquals(20L, info.remainingSeconds) // 40 items / 2.0 items/s = 20s
        assertEquals("20s", info.formattedEta)
        assertEquals("2.0 songs/s", info.speedFormatted)
        assertEquals("~20s left (2.0 songs/s)", info.fullSummary)
        assertEquals(20, info.progressPercent)
    }

    @Test
    fun testMinuteAndSecondFormatting() {
        var mockTime = 10_000L
        val estimator = EtaEstimator(clock = { mockTime })
        estimator.start()

        // 20 items processed in 20 seconds -> 1 item/sec
        // 75 items remaining -> 75 seconds left = 1m 15s
        mockTime = 30_000L
        val info = estimator.calculate(processed = 20, total = 95)

        assertEquals(75L, info.remainingSeconds)
        assertEquals("1m 15s", info.formattedEta)
        assertEquals("1.0 songs/s", info.speedFormatted)
        assertEquals("~1m 15s left (1.0 songs/s)", info.fullSummary)
    }

    @Test
    fun testCompletionState() {
        var mockTime = 1000L
        val estimator = EtaEstimator(clock = { mockTime })
        estimator.start()

        mockTime = 11_000L
        val info = estimator.calculate(processed = 50, total = 50)

        assertEquals(50, info.processed)
        assertEquals(50, info.total)
        assertEquals(0, info.remainingItems)
        assertEquals(0L, info.remainingSeconds)
        assertEquals("Done", info.formattedEta)
        assertEquals("Finalizing playlist...", info.fullSummary)
        assertEquals(100, info.progressPercent)
    }

    @Test
    fun testFormatRemainingHelper() {
        assertEquals("a few seconds", EtaEstimator.formatRemaining(1L))
        assertEquals("a few seconds", EtaEstimator.formatRemaining(2L))
        assertEquals("45s", EtaEstimator.formatRemaining(45L))
        assertEquals("2m", EtaEstimator.formatRemaining(120L))
        assertEquals("2m 30s", EtaEstimator.formatRemaining(150L))
        assertEquals("1h", EtaEstimator.formatRemaining(3600L))
        assertEquals("1h 10m", EtaEstimator.formatRemaining(4200L))
    }
}
