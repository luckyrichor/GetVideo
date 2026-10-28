package com.getvideo.app.ui

import com.getvideo.app.ui.screen.QueueSlot
import com.getvideo.app.ui.screen.dropTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表拖动的落位判定。
 *
 * 用例对应 2026-10-28 真机反馈的三个毛病：
 *  - 「第一条往下拖瞬间跳到 26~30 条」：目标只在可视区里选，任何一帧都不可能跨出屏幕；
 *  - 「往上拖页面疯狂抖动、拖着自己停下」：行不再留在列表里做位移补偿，浮层位置只跟手指走；
 *  - 「拖着拖着自己停」：每帧都重新判定，贴边滚动滚出来的行会接着落到手指下面。
 */
class QueueDragStepTest {

    /** 30 行、行高 100、行距 110、视口高 800 的简化列表。 */
    private class Sim(count: Int = 30) {
        val order = (1L..count).toMutableList()
        var index = 0
        var scroll = 0f
        val height = 100f
        val pitch = 110f
        val viewport = 800f

        /** 当前可视行，坐标是「相对视口」。 */
        fun slots(): List<QueueSlot> = order.indices.mapNotNull { i ->
            val top = i * pitch - scroll
            if (top + height <= 0f || top >= viewport) null else QueueSlot(i, top, height)
        }

        /** 推进一帧，返回发生换位时的新下标。 */
        fun frame(y: Float): Int? {
            val to = dropTarget(index, y, slots(), 2f) ?: return null
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
    fun draggingFirstRowDownFollowsTheFingerRowByRow() {
        val sim = Sim()
        val steps = mutableListOf<Int>()
        var y = 50f
        // 手指从第一行中心慢慢往下推 640px，全程不贴边、不滚动。
        while (y < 690f) {
            y += 2f
            sim.frame(y)?.let { steps += it }
        }
        assertSingleSteps(steps, 1)
        // 手指停在 y=690：第 7 行占 660..760，所以落在下标 6，绝不是第 26 条以后。
        assertEquals(6, sim.index)
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
        while (sim.index < 29 && frames < 4000) {
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
        while (sim.index > 0 && frames < 4000) {
            frames++
            y = maxOf(y - 6f, 1f)
            if (y <= 1f) sim.scroll = maxOf(0f, sim.scroll - 6f)
            sim.frame(y)?.let { steps += it }
        }
        assertSingleSteps(steps, -1)
        assertEquals(0, sim.index)
        assertEquals(30, sim.order.distinct().size)
    }

    @Test
    fun holdingStillNeverSwapsBackAndForth() {
        val sim = Sim()
        var y = 50f
        while (y < 300f) { y += 2f; sim.frame(y) }
        val settled = sim.index
        // 手指不动，之后每帧都不该再换位（抖动就是这么来的）。
        repeat(40) { assertNull("手指没动却换位了", sim.frame(y)) }
        assertEquals(settled, sim.index)
    }

    @Test
    fun targetIsAlwaysInsideTheVisibleWindow() {
        val sim = Sim()
        sim.scroll = 1000f
        val slots = sim.slots()
        assertTrue("可视区应该有若干行", slots.isNotEmpty())
        listOf(-9999f, 0f, 400f, 9999f).forEach { y ->
            val to = dropTarget(12, y, slots, 2f) ?: return@forEach
            assertTrue("落在可视区外了：y=$y → $to", to in slots.first().index..slots.last().index)
        }
    }

    @Test
    fun extremeDragNeverLeavesTheVisibleWindow() {
        // 手指被拖到面板外很远（真实场景：手指滑出底部弹层），也只能落在最后一条可见行。
        val sim = Sim()
        val slots = sim.slots()
        var guard = 0
        while (guard++ < 100) {
            val to = dropTarget(sim.index, 100000f, slots, 2f) ?: break
            sim.order.add(to, sim.order.removeAt(sim.index))
            sim.index = to
        }
        assertEquals(slots.last().index, sim.index)
        assertTrue("不该跑到可视区外，实际 ${sim.index}", sim.index < 8)
    }

    @Test
    fun emptySlotsAreIgnored() {
        assertNull(dropTarget(0, 100f, emptyList(), 2f))
    }

    /**
     * 真机上「第一个往下拖立刻跳到最后」的真正原因：LazyColumn 换位时会把第一个可见项
     * 钉在原来的像素上，槽位坐标整体平移。判定如果直接用屏幕坐标，就会每帧再往后换一格。
     * 这里用「锚定模型」跑：换位后被拖行始终排在第 0 个槽位。
     */
    private class AnchoredSim(val count: Int = 10, val visible: Int = 6) {
        var index = 0
        var residual = 0f
        var moves = 0
        fun slots(): List<QueueSlot> = (0 until minOf(visible, count - index)).map { i ->
            QueueSlot(index + i, i * 110f, 100f)
        }
        fun frame(dy: Float) {
            residual += dy
            val slots = slots()
            val me = slots.first { it.index == index }
            val y = me.top + me.height / 2f + residual
            val target = dropTarget(index, y, slots, 2f) ?: return
            val targetSlot = slots.first { it.index == target }
            index = target
            // 与实现一致：换位后保留手指在新一行里的相对位置。
            residual = y - (targetSlot.top + targetSlot.height / 2f)
            moves++
        }
    }

    @Test
    fun anchorShiftDoesNotChainJumpPastTheFinger() {
        val sim = AnchoredSim()
        // 手指只往下走了一行多一点（4 帧 × 33px），最终只该挪一格。
        repeat(4) { sim.frame(33f) }
        assertEquals(1, sim.index)
        assertEquals(1, sim.moves)
    }

    @Test
    fun anchorShiftDoesNotMoveWhenTheFingerIsStill() {
        val sim = AnchoredSim()
        repeat(60) { sim.frame(0f) }
        assertEquals(0, sim.index)
        assertEquals(0, sim.moves)
    }

    @Test
    fun anchoredDragDownMovesOneRowPerFingerRow() {
        val sim = AnchoredSim()
        // 手指一共走了 5 行（5 × 110px），每次只能挪一格，绝不允许连跳。
        repeat(25) { sim.frame(22f) }
        assertEquals(5, sim.index)
        assertEquals(5, sim.moves)
    }
}
