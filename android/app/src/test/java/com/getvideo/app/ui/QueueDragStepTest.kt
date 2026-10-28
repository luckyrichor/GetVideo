package com.getvideo.app.ui

import com.getvideo.app.ui.screen.reorderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表拖动的换位判定。
 *
 * 这几条用例对应 2026-10-28 真机上反馈的两个毛病：
 *  - 「第一条往下拖会瞬间跳到最后一条」：以前拿「离中心最近的一行」当目标，中心一漂就跨几十行；
 *  - 「从下往上拖页面抖得厉害」：以前跨行换位后布局重排，下一帧又判到反向，来回跳。
 * 现在的要求只有两条：一次最多挪一格；中心不动时不换位。
 */
class QueueDragStepTest {

    /** 30 行、行高 100、行距 110、可视区 0..800 的简化列表。 */
    private class Sim(count: Int = 30) {
        val order = (1L..count).toMutableList()
        var index = 0
        var scroll = 0f
        val height = 100f
        val pitch = 110f
        val viewport = 800f

        fun bounds(id: Long): Pair<Float, Float>? {
            val i = order.indexOf(id)
            if (i < 0) return null
            val top = i * pitch - scroll
            return if (top + height <= 0f || top >= viewport) null else top to height
        }

        /** 推进一帧，返回发生换位时的新下标。 */
        fun frame(y: Float): Int? {
            val to = reorderStep(order, index, y.coerceIn(1f, viewport - 1f), ::bounds) ?: return null
            order.add(to, order.removeAt(index))
            index = to
            return to
        }
    }

    private fun assertSingleSteps(steps: List<Int>, direction: Int) {
        assertTrue("应该有换位发生", steps.isNotEmpty())
        steps.zipWithNext().forEach { (a, b) ->
            assertEquals("每次只能挪一格，实际从 $a 直接到 $b", 1, kotlin.math.abs(b - a))
        }
        steps.zipWithNext().forEach { (a, b) ->
            assertEquals("方向不能反转，实际从 $a 到 $b", direction, b - a)
        }
    }

    @Test
    fun draggingFirstRowDownMovesOneRowAtATime() {
        val sim = Sim()
        val steps = mutableListOf<Int>()
        var y = 50f
        // 手指从第一行中心慢慢往下推 640px，全程不贴边、不滚动。
        while (y < 690f) {
            y += 2f
            sim.frame(y)?.let { steps += it }
        }
        assertSingleSteps(steps, 1)
        // 手指停在 y=690：第 5 行中心 600、第 6 行中心 710，所以应该正好落在 5。
        assertEquals(5, sim.index)
    }

    @Test
    fun draggingFirstRowDownNeverJumpsToTheEnd() {
        val sim = Sim()
        var y = 50f
        repeat(60) {
            val before = sim.index
            y += 2f
            val after = sim.frame(y) ?: before
            assertTrue("一次只能挪一格，实际从 $before 到 $after", after - before in 0..1)
        }
        // 手指只走了 120px，没有贴边滚动，最多只该过一行。
        assertTrue("没有贴边滚动时不该跑到列表后半段，实际 ${sim.index}", sim.index <= 1)
    }

    @Test
    fun holdingAtBottomEdgeScrollsThroughOneRowAtATime() {
        val sim = Sim()
        var y = 50f
        var frames = 0
        val steps = mutableListOf<Int>()
        // 先把手指推到下边缘，再让列表每帧滚 6px，直到滚到底。
        while (sim.index < 29 && frames < 3000) {
            frames++
            y = minOf(y + 6f, sim.viewport - 1f)
            if (y >= sim.viewport - 1f) sim.scroll += 6f
            sim.frame(y)?.let { steps += it }
        }
        assertSingleSteps(steps, 1)
        assertEquals(29, sim.index)
        assertEquals(30, sim.order.distinct().size)
    }

    @Test
    fun draggingLastRowUpMovesOneRowAtATimeWithoutJitter() {
        val sim = Sim()
        sim.index = 29
        sim.scroll = 29 * sim.pitch - 700f
        var y = 750f
        var frames = 0
        val steps = mutableListOf<Int>()
        while (sim.index > 0 && frames < 3000) {
            frames++
            y = maxOf(y - 6f, 1f)
            if (y <= 1f) sim.scroll -= 6f
            sim.frame(y)?.let { steps += it }
        }
        assertSingleSteps(steps, -1)
        assertEquals(0, sim.index)
    }

    @Test
    fun holdingStillNeverSwapsBackAndForth() {
        val sim = Sim()
        var y = 50f
        while (y < 300f) { y += 2f; sim.frame(y) }
        val settled = sim.index
        // 中心不动，之后每帧都不该再换位（抖动就是这么来的）。
        repeat(30) { assertNull("中心没动却换位了", sim.frame(y)) }
        assertEquals(settled, sim.index)
    }

    @Test
    fun offscreenNeighbourBlocksTheMoveInsteadOfJumping() {
        val sim = Sim()
        // 目标行滚出可视区时返回 null，此时宁可不动，也不能跳到别处。
        sim.scroll = 1000f
        val order = sim.order.toList()
        assertNull(reorderStep(order, 0, 400f) { null })
    }
}
