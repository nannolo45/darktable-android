// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.HistoryItem
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Brief P3h: snapshots (take, select, delete, the limit, kept only while their history holds), the split view's
 * state, and "before" and the snapshot pictures rendered as a history position without moving the history end.
 */
class P3hSnapshotsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val exposureKey = InstanceKey("exposure", 0)
    private val slider by lazy {
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
    }

    /** the engine with every historyGoto and every render's options noted */
    private class Spy(val e: DarktableEngine) : DarktableEngine by e {
        val gotos: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val renders: MutableList<RenderOptions> = Collections.synchronizedList(ArrayList())
        override suspend fun historyGoto(end: Int) { gotos += end; e.historyGoto(end) }
        override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage {
            renders += options
            return e.renderPreview(maxSize, options)
        }
    }

    private fun controller(): Pair<DarkroomController, Spy> {
        val dir = tmp.root
        val fake = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { fake.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val spy = Spy(fake)
        val c = DarkroomController(spy, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), previewMaxSize = 900)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c to spy
    }

    private fun item(n: Int, op: String = "exposure") = HistoryItem(n, op, 0, op, "", true)
    private fun history(end: Int, vararg ops: String) = History(end, ops.mapIndexed { i, op -> item(i, op) })

    @Test
    fun takeSelectDeleteAndTheLimit() {
        val h = history(2, "exposure", "colorbalancergb")
        var s = SnapshotsState()
        s = SnapshotRules.take(s, h, emptyList())!!
        assertEquals(1, s.list.size)
        assertEquals("named by the item at the end, as desktop", "2 · colorbalancergb", s.list[0].name)
        assertEquals("a new snapshot is shown at once", s.list[0].id, s.selected)
        s = SnapshotRules.take(s, history(0, "exposure"), emptyList())!!
        assertEquals("0 · original", s.list[1].name)
        s = SnapshotRules.select(s, s.list[0].id)
        assertEquals(s.list[0].id, s.selected)
        s = SnapshotRules.select(s, s.list[0].id)
        assertNull("a second tap ends the split", s.selected)
        s = SnapshotRules.select(s, s.list[1].id)
        s = SnapshotRules.delete(s, s.list[1].id)
        assertEquals(1, s.list.size)
        assertNull("deleting the shown snapshot ends the split", s.selected)
        repeat(SnapshotRules.MAX - 1) { s = SnapshotRules.take(s, h, emptyList())!! }
        assertEquals(SnapshotRules.MAX, s.list.size)
        assertTrue(s.full)
        assertNull("desktop keeps at most ${SnapshotRules.MAX}", SnapshotRules.take(s, h, emptyList()))
        // l1's compare: the newest one, then off
        s = SnapshotRules.select(s, s.list[0].id)
        s = SnapshotRules.select(s, s.list[0].id)
        s = SnapshotRules.toggleLast(s)
        assertEquals(s.list.last().id, s.selected)
        s = SnapshotRules.toggleLast(s)
        assertNull(s.selected)
    }

    @Test
    fun splitState() {
        var s = SnapshotsState()
        assertTrue("desktop starts with a vertical divider, the snapshot on the left", s.split.vertical && s.split.snapshotFirst)
        assertEquals(0.5f, s.split.position)
        s = SnapshotRules.moveDivider(s, 0.25f)
        assertEquals(0.25f, s.split.position)
        s = SnapshotRules.moveDivider(s, -3f)
        assertEquals(SnapshotRules.MIN_POSITION, s.split.position)
        s = SnapshotRules.moveDivider(s, 3f)
        assertEquals(SnapshotRules.MAX_POSITION, s.split.position)
        s = SnapshotRules.moveDivider(s, 0.5f)
        assertTrue(SnapshotRules.onSnapshotSide(s.split, 0.2f, 0.9f))
        assertFalse(SnapshotRules.onSnapshotSide(s.split, 0.8f, 0.1f))
        s = SnapshotRules.switchSides(s)
        assertTrue(SnapshotRules.onSnapshotSide(s.split, 0.8f, 0.1f))
        s = SnapshotRules.rotate(s)
        assertFalse(s.split.vertical)
        assertTrue("horizontal: the snapshot below now", SnapshotRules.onSnapshotSide(s.split, 0.1f, 0.8f))
        assertFalse(SnapshotRules.onSnapshotSide(s.split, 0.9f, 0.2f))
    }

    @Test
    fun aSnapshotGoesWhenItsHistoryChanges() {
        val h3 = history(3, "exposure", "colorbalancergb", "exposure")
        var s = SnapshotRules.take(SnapshotsState(), History(2, h3.items), emptyList())!!
        s = SnapshotRules.take(s, h3, emptyList())!!
        // an item on top keeps both
        val h4 = History(4, h3.items + item(3, "sigmoid"))
        assertEquals(2, SnapshotRules.prune(s, h3, h4).list.size)
        // undo of the top item: the snapshot at 3 still holds (its three items are there)
        assertEquals(2, SnapshotRules.prune(s, h4, h3).list.size)
        // back at 2, then a change: the third item is replaced (even by one that looks the same): the one at 3 goes
        val at2 = History(2, h3.items)
        val replaced = History(3, h3.items.take(2) + item(2, "exposure").copy(enabled = true))
        val after = SnapshotRules.prune(s, at2, replaced)
        assertEquals(listOf(2), after.list.map { it.historyEnd })
        assertNull("the shown one went: no split", after.selected)
        // a compress: every position means something else
        assertTrue(SnapshotRules.prune(s, h3, h3, compressed = true).list.isEmpty())
        // fewer items than its position
        assertEquals(listOf(2), SnapshotRules.prune(s, h3, History(2, h3.items.take(2))).list.map { it.historyEnd })
    }

    @Test
    fun beforeAndSnapshotsRenderAHistoryPositionWithoutGotos() = runBlocking<Unit> {
        val (c, spy) = controller()
        // an edit, so that position 0 differs
        c.setSlider(exposureKey, slider.binding, 1.0, false)
        c.renderNow()
        val end0 = c.state.value.history.end
        c.setBefore(true)
        assertNotNull(c.state.value.beforePreview)
        assertTrue("before: no history_goto", spy.gotos.isEmpty())
        assertTrue("before: a render of position 0", spy.renders.any { it.historyEnd == 0 })
        c.setBefore(false)

        c.takeSnapshot()
        c.renderNow()
        val snap = c.state.value.snapshots.selectedSnapshot
        assertNotNull(snap)
        assertEquals(end0, snap!!.historyEnd)
        val pic = c.state.value.snapshots.preview
        assertNotNull("the snapshot's picture", pic)
        assertTrue(spy.renders.any { it.historyEnd == end0 })
        // an edit on the live side: a new item, the snapshot's picture stays, the history end is the edit's
        c.setSlider(exposureKey, slider.binding, 2.0, false)
        c.renderNow()
        assertEquals("the snapshot sealed the item: the change is a new one", end0 + 1, c.state.value.history.end)
        assertTrue("the snapshot's picture is not rendered again", pic === c.state.value.snapshots.preview)
        assertTrue("no history_goto for snapshots", spy.gotos.isEmpty())
        val live = c.state.value.preview!!.image
        val mean = { img: RenderedImage -> img.rgba.indices.filter { it % 4 != 3 }.sumOf { img.rgba[it].toInt() and 0xff }.toDouble() / (img.rgba.size * 3 / 4) }
        assertTrue("the live side is brighter than the snapshot", mean(live) > mean(pic!!.image) + 1)
        // a tap on an older history item and a change there drop the snapshot above it
        c.historyGoto(0)
        c.setSlider(exposureKey, slider.binding, -1.0, false)
        c.renderNow()
        assertTrue("the snapshot's position was discarded", c.state.value.snapshots.list.none { it.historyEnd > 0 })
    }

    @Test
    fun anotherPhotoHasNoSnapshots() = runBlocking<Unit> {
        val (c, _) = controller()
        c.takeSnapshot()
        assertEquals(1, c.state.value.snapshots.list.size)
        c.openPhoto(1)
        assertTrue(c.state.value.snapshots.list.isEmpty())
    }
}
