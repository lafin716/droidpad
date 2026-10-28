package com.droidpad.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLayoutTest {

    private val sample = CustomLayout(
        listOf(
            LayoutItem(WidgetType.TOUCHPAD, 0, 0, 4, 4),
            LayoutItem(WidgetType.CTRL, 4, 0, 1, 1),
            LayoutItem(WidgetType.SPACE, 4, 1, 2, 1),
        ),
    )

    @Test
    fun encodeDecodeRoundTrip() {
        assertEquals(sample, CustomLayout.decode(sample.encode()))
    }

    @Test
    fun encodeAllDecodeAllRoundTrip() {
        val all = mapOf("6x5" to sample, "10x8" to CustomLayout(listOf(LayoutItem(WidgetType.F12, 1, 2, 1, 1))))
        assertEquals(all, CustomLayout.decodeAll(CustomLayout.encodeAll(all)))
    }

    @Test
    fun decodeSkipsGarbage() {
        val decoded = CustomLayout.decode("NOPE,0,0,1,1;CTRL,1,x,1,1;ALT,2,3,1,1;bad")
        assertEquals(listOf(LayoutItem(WidgetType.ALT, 2, 3, 1, 1)), decoded.items)
        assertTrue(CustomLayout.decodeAll(null).isEmpty())
        assertTrue(CustomLayout.decodeAll("").isEmpty())
    }

    @Test
    fun overlapsIsExclusiveOfEdges() {
        val a = LayoutItem(WidgetType.SPACE, 0, 0, 2, 1)
        assertTrue(a.overlaps(LayoutItem(WidgetType.ESC, 1, 0, 1, 1)))
        assertFalse(a.overlaps(LayoutItem(WidgetType.ESC, 2, 0, 1, 1)))
        assertFalse(a.overlaps(LayoutItem(WidgetType.ESC, 0, 1, 1, 1)))
    }

    @Test
    fun canPlaceRespectsBoundsMinSizeAndIgnore() {
        assertFalse(sample.canPlace(LayoutItem(WidgetType.ESC, 3, 3, 1, 1), 6, 5))
        assertTrue(sample.canPlace(LayoutItem(WidgetType.ESC, 5, 0, 1, 1), 6, 5))
        assertFalse(sample.canPlace(LayoutItem(WidgetType.ESC, 6, 0, 1, 1), 6, 5))
        assertFalse(sample.canPlace(LayoutItem(WidgetType.TOUCHPAD, 4, 2, 1, 1), 6, 5))
        // Moving the touchpad one row down only collides with itself, which is ignored.
        assertTrue(sample.canPlace(LayoutItem(WidgetType.TOUCHPAD, 0, 1, 4, 4), 6, 5, ignore = 0))
        assertFalse(sample.canPlace(LayoutItem(WidgetType.TOUCHPAD, 0, 1, 4, 4), 6, 5))
    }

    @Test
    fun placeAtShrinksToFit() {
        val placed = sample.placeAt(WidgetType.SPACE, 5, 0, 6, 5)
        assertEquals(LayoutItem(WidgetType.SPACE, 5, 0, 1, 1), placed)
        assertNull(sample.placeAt(WidgetType.TOUCHPAD, 5, 0, 6, 5))
    }

    @Test
    fun findSpotReturnsFirstFreeCellOrNull() {
        assertEquals(LayoutItem(WidgetType.ESC, 5, 0, 1, 1), sample.findSpot(WidgetType.ESC, 6, 5))
        val full = CustomLayout(listOf(LayoutItem(WidgetType.TOUCHPAD, 0, 0, 2, 2)))
        assertNull(full.findSpot(WidgetType.ESC, 2, 2))
    }

    @Test
    fun defaultLayoutsFitWithoutOverlap() {
        for (cols in 2..20) for (rows in 3..16) for (wide in listOf(false, true)) for (swap in listOf(false, true)) {
            val layout = CustomLayout.default(cols, rows, wide, swap)
            layout.items.forEachIndexed { i, item ->
                assertTrue("$item fits ${cols}x$rows", item.fitsIn(cols, rows))
                layout.items.drop(i + 1).forEach { other -> assertFalse("$item vs $other", item.overlaps(other)) }
            }
        }
    }

    @Test
    fun defaultWideLayoutSwapsSides() {
        val normal = CustomLayout.default(12, 9, wide = true)
        val swapped = CustomLayout.default(12, 9, wide = true, swap = true)
        val pad = normal.items.first { it.type == WidgetType.TOUCHPAD }
        val swappedPad = swapped.items.first { it.type == WidgetType.TOUCHPAD }
        assertEquals(0, pad.x)
        assertEquals(12 - swappedPad.w, swappedPad.x)
        assertNotNull(swapped.items.firstOrNull { it.type == WidgetType.INPUT_BAR && it.x == 0 })
    }
}
