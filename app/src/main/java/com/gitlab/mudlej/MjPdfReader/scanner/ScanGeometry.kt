package com.gitlab.mudlej.MjPdfReader.scanner

import kotlin.math.atan2
import kotlin.math.hypot

@androidx.annotation.Keep
data class ScanPoint(val x: Float, val y: Float)
object ScanGeometry {
    fun fullPage() = listOf(ScanPoint(0f, 0f), ScanPoint(1f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f))
    /** Clockwise screen coordinates, beginning near the upper left. */
    fun ordered(points: List<ScanPoint>): List<ScanPoint> {
        require(points.size == 4)
        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }.also { require(valid(it)) }
    }
    fun valid(points: List<ScanPoint>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        var area = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross < 0.0001f || hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) < 0.025) return false
            area += a.x * b.y - a.y * b.x
        }
        return area / 2 >= 0.025
    }
    fun outputSize(points: List<ScanPoint>, width: Int, height: Int, maxSide: Int = 3508): Pair<Int, Int> {
        require(valid(points) && width > 0 && height > 0 && maxSide > 0)
        fun distance(a: ScanPoint, b: ScanPoint) = hypot((a.x - b.x) * width.toDouble(), (a.y - b.y) * height.toDouble())
        val w = (distance(points[0], points[1]) + distance(points[3], points[2])) / 2
        val h = (distance(points[0], points[3]) + distance(points[1], points[2])) / 2
        val scale = minOf(1.0, maxSide / maxOf(w, h))
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
