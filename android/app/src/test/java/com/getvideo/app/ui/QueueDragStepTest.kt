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
     * 真机第二轮复现（0.9.8）：「第一条往下拖，文件跟着手指走了，但它原来那块变空；
     * 拖到 7、8 之间，画面还停在最上面，还留下一大段透明空白」。
     *
     * 原因是 LazyColumn 换位时会把「第一个可见项」按 **key** 钉回原像素。拖第一条时，
     * 第一个可见项恰好就是被拖行 —— 每换一格，列表就自己往下滚一行（日志里
     * `firstVisibleItemIndex` 一路 0→1→2）。于是内容被推走、判定基准跟着漂。
     *
     * 修法：占位项的 key 每次换位都不一样（`gap-$index`），上一帧的 key 在新列表里找不到，
     * 锚定只能退回「保持原下标」，列表不再自己滚。判定也就回到「手指的视口纵坐标」。
     * 下面这个模型里 [scroll] 恒为 0：换位绝不允许改变它。
     */
    private class StableSim(val count: Int = 10, val pinGap: Boolean = false) {
        val pitch = 110f
        val height = 100f
        val viewport = 660f
        var index = 0
        var scroll = 0f
        var moves = 0
        fun slots(): List<QueueSlot> = (0 until count).mapNotNull { i ->
            val top = i * pitch - scroll
            if (top + height <= 0f || top >= viewport) null else QueueSlot(i, top, height)
        }
        fun frame(fingerY: Float) {
            val from = index
            val target = dropTarget(from, fingerY, slots(), 2f) ?: return
            index = target
            moves++
            // 锚定模型：占位项的 key 如果「钉得住」，列表会滚到让它保持原像素的位置。
            if (pinGap) scroll += (target - from) * pitch
        }
    }

    @Test
    fun theGapStaysUnderTheFingerWhileDraggingTheFirstRow() {
        val sim = StableSim()
        var y = 10f
        while (y < 650f) {
            sim.frame(y)
            val gap = sim.slots().first { it.index == sim.index }
            val center = gap.top + gap.height / 2f
            assertTrue(
                "手指 y=$y 离占位行中心 $center 超过一行，画面和手指脱节了",
                kotlin.math.abs(y - center) <= sim.pitch,
            )
            y += 3f
        }
    }

    @Test
    fun draggingTheFirstRowNeverScrollsTheListByItself() {
        val sim = StableSim()
        var y = 10f
        while (y < 650f) {
            sim.frame(y)
            assertEquals("换位不该让列表自己滚动", 0f, sim.scroll, 0f)
            y += 3f
        }
        // 视口 660 高、每行 110：手指走到底只能走到第 6 行（下标 5），而且一次一格。
        assertEquals(5, sim.index)
        assertEquals(5, sim.moves)
    }

    @Test
    fun stillFingerKeepsTheGapAndTheOrder() {
        val sim = StableSim()
        sim.frame(255f)
        val settled = sim.index
        val moves = sim.moves
        repeat(60) { sim.frame(255f) }
        assertEquals("手指没动，占位行不该再跑", settled, sim.index)
        assertEquals("手指没动却换位了", moves, sim.moves)
    }

    /**
     * 行高不等造成的双稳态。标题两行 283px、三行 353px，占位行和下面那行交换位置后，
     * 那 353px 的行从「400 开始」挪到「117 开始」，手指停在 403 时两种布局下都落在它里面 ——
     * 于是每帧「换过去 → 换回来」，真机上就是疯狂抖动。死区让「换回去」必须先走半行。
     */
    private class MixedHeightSim(val deadzone: Boolean) {
        // 每项自带高度，换位后高度跟着走，槽位因此会重排 —— 这就是抖动的成因。
        val heights = mutableListOf(353f, 283f, 353f, 283f, 353f, 283f, 353f, 283f)
        var index = 0
        var lastSwapY = 0f
        var lastSwapDir = 0
        var swaps = 0
        fun slots(): List<QueueSlot> {
            var y = 0f
            return heights.mapIndexed { i, h ->
                QueueSlot(i, y, h).also { y += h + 10f }
            }
        }
        fun frame(y: Float) {
            val slots = slots()
            val target = dropTarget(index, y, slots, 2f) ?: return
            val dir = if (target > index) 1 else -1
            val step = 0.5f * minOf(heights[index], heights[target])
            if (deadzone && lastSwapDir != 0 && dir != lastSwapDir &&
                kotlin.math.abs(y - lastSwapY) < step
            ) return
            heights.add(target, heights.removeAt(index))
            index = target
            lastSwapY = y
            lastSwapDir = dir
            swaps++
        }
    }

    @Test
    fun mixedRowHeightsDoNotFlipFlopUnderAStillFinger() {
        val sim = MixedHeightSim(deadzone = true)
        sim.index = 3
        val finger = sim.slots()[3].let { it.top + it.height + 3f }
        repeat(40) { sim.frame(finger) }
        assertEquals("手指没动却在两行之间来回换（抖动）", 1, sim.swaps)
    }

    @Test
    fun withoutDeadzoneTheSameFingerFlipsEveryFrame() {
        // 反证：把死区关掉，手指原地不动也会每帧换位 —— 这就是 0.9.8 真机上的「疯狂抖动」。
        val sim = MixedHeightSim(deadzone = false)
        sim.index = 3
        val finger = sim.slots()[3].let { it.top + it.height + 3f }
        repeat(40) { sim.frame(finger) }
        assertTrue("应该能复现出每帧来回换，实际只换了 ${sim.swaps} 次", sim.swaps >= 20)
    }

    @Test
    fun aPinnableGapKeyWouldWalkTheListAway() {
        // 反证：占位项 key 钉得住时（0.9.8 的实现），手指只挪一点，列表就被锚定带着一路往下滚。
        val buggy = StableSim(pinGap = true)
        var y = 50f
        repeat(6) { y += 30f; buggy.frame(y) }
        assertTrue("旧实现应该能复现出漂移，实际只挪了 ${buggy.index} 格", buggy.index >= 5)
        assertTrue("旧实现里列表自己被滚走了", buggy.scroll > 0f)
    }
}
