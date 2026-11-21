package com.skullbeat.app.domain.util

import java.util.Locale

data class EtaInfo(
    val processed: Int,
    val total: Int,
    val remainingItems: Int,
    val remainingSeconds: Long,
    val formattedEta: String,
    val itemsPerSecond: Double,
    val speedFormatted: String,
    val fullSummary: String,
    val progressPercent: Int
)

class EtaEstimator(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var startTimeMs: Long = 0L

    fun start() {
        startTimeMs = clock()
    }

    fun calculate(processed: Int, total: Int): EtaInfo {
        val now = clock()
        if (startTimeMs == 0L) {
            startTimeMs = now
        }

        val safeTotal = total.coerceAtLeast(1)
        val safeProcessed = processed.coerceIn(0, safeTotal)
        val remaining = (safeTotal - safeProcessed).coerceAtLeast(0)
        val percent = ((safeProcessed.toDouble() / safeTotal) * 100).toInt().coerceIn(0, 100)

        val elapsedMs = (now - startTimeMs).coerceAtLeast(0L)
        val elapsedSec = elapsedMs / 1000.0

        if (safeProcessed == 0 || elapsedSec < 0.8) {
            return EtaInfo(
                processed = safeProcessed,
                total = safeTotal,
                remainingItems = remaining,
                remainingSeconds = -1L,
                formattedEta = "Estimating...",
                itemsPerSecond = 0.0,
                speedFormatted = "0.0 songs/s",
                fullSummary = "Estimating time remaining...",
                progressPercent = percent
            )
        }

        if (remaining == 0) {
            val finalSpeed = safeProcessed / elapsedSec.coerceAtLeast(0.001)
            return EtaInfo(
                processed = safeProcessed,
                total = safeTotal,
                remainingItems = 0,
                remainingSeconds = 0L,
                formattedEta = "Done",
                itemsPerSecond = finalSpeed,
                speedFormatted = String.format(Locale.US, "%.1f songs/s", finalSpeed),
                fullSummary = "Finalizing playlist...",
                progressPercent = 100
            )
        }

        val speed = safeProcessed / elapsedSec
        val remainingSec = if (speed > 0.01) (remaining / speed).toLong() else 0L

        val formattedEta = formatRemaining(remainingSec)
        val speedFormatted = String.format(Locale.US, "%.1f songs/s", speed)
        val fullSummary = "~$formattedEta left ($speedFormatted)"

        return EtaInfo(
            processed = safeProcessed,
            total = safeTotal,
            remainingItems = remaining,
            remainingSeconds = remainingSec,
            formattedEta = formattedEta,
            itemsPerSecond = speed,
            speedFormatted = speedFormatted,
            fullSummary = fullSummary,
            progressPercent = percent
        )
    }

    companion object {
        fun formatRemaining(sec: Long): String {
            return when {
                sec <= 2 -> "a few seconds"
                sec < 60 -> "${sec}s"
                sec < 3600 -> {
                    val m = sec / 60
                    val s = sec % 60
                    if (s == 0L) "${m}m" else "${m}m ${s}s"
                }
                else -> {
                    val h = sec / 3600
                    val m = (sec % 3600) / 60
                    if (m == 0L) "${h}h" else "${h}h ${m}m"
                }
            }
        }
    }
}
