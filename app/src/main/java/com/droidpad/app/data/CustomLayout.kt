package com.droidpad.app.data

/**
 * A widget that can be placed on the custom control grid. Sizes are in grid cells; one cell is
 * the size of a single key.
 */
enum class WidgetType(val minW: Int = 1, val minH: Int = 1, val defaultW: Int = minW, val defaultH: Int = minH) {
    TOUCHPAD(minW = 2, minH = 2, defaultW = 4, defaultH = 4),
    INPUT_BAR(minW = 2, defaultW = 4),
    MOUSE_LEFT(defaultW = 2),
    MOUSE_RIGHT(defaultW = 2),
    MOUSE_MIDDLE,
    AIR_MOUSE,
    LANGUAGE,
    CTRL,
    SHIFT,
    ALT,
    META,
    ESC,
    TAB,
    BACKSPACE,
    DELETE,
    ENTER,
    SPACE(defaultW = 3),
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    COPY,
    PASTE,
    CUT,
    UNDO,
    SELECT_ALL,
    SWITCH_APP,
    HOST_HOME,
    BACK,
    ;

    val functionNumber: Int? get() = if (ordinal in F1.ordinal..F12.ordinal) ordinal - F1.ordinal + 1 else null
}

data class LayoutItem(val type: WidgetType, val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun overlaps(o: LayoutItem): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun fitsIn(cols: Int, rows: Int): Boolean =
        x >= 0 && y >= 0 && right <= cols && bottom <= rows && w >= type.minW && h >= type.minH
}

/** Grid-snapped control layout for one screen size ([cols] × [rows] cells). */
data class CustomLayout(val items: List<LayoutItem>) {

    /** True when [item] fits the grid without overlapping any other item (except [ignore]). */
    fun canPlace(item: LayoutItem, cols: Int, rows: Int, ignore: Int = -1): Boolean =
        item.fitsIn(cols, rows) && items.withIndex().none { (i, o) -> i != ignore && o.overlaps(item) }

    fun replace(index: Int, item: LayoutItem) = CustomLayout(items.toMutableList().also { it[index] = item })
    fun remove(index: Int) = CustomLayout(items.filterIndexed { i, _ -> i != index })
    fun add(item: LayoutItem) = CustomLayout(items + item)

    /**
     * Largest item of [type] whose top-left corner is ([x], [y]), shrinking from the default size
     * down to the minimum; null when even the minimum does not fit there.
     */
    fun placeAt(type: WidgetType, x: Int, y: Int, cols: Int, rows: Int): LayoutItem? {
        for (h in type.defaultH downTo type.minH) {
            for (w in type.defaultW downTo type.minW) {
                val item = LayoutItem(type, x, y, w, h)
                if (canPlace(item, cols, rows)) return item
            }
        }
        return null
    }

    /** First free spot (row-major) for [type]; null when the grid is full. */
    fun findSpot(type: WidgetType, cols: Int, rows: Int): LayoutItem? {
        for (y in 0 until rows) for (x in 0 until cols) placeAt(type, x, y, cols, rows)?.let { return it }
        return null
    }

    fun encode(): String = items.joinToString(";") { "${it.type.name},${it.x},${it.y},${it.w},${it.h}" }

    companion object {
        val EMPTY = CustomLayout(emptyList())

        fun decode(s: String): CustomLayout = CustomLayout(
            s.split(';').mapNotNull { part ->
                val f = part.split(',')
                if (f.size != 5) return@mapNotNull null
                val type = WidgetType.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
                val n = f.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
                LayoutItem(type, n[0], n[1], n[2], n[3])
            },
        )

        /** Key used to store a layout per grid size, e.g. "6x11". */
        fun gridKey(cols: Int, rows: Int) = "${cols}x$rows"

        fun encodeAll(layouts: Map<String, CustomLayout>): String =
            layouts.entries.joinToString("|") { (k, v) -> "$k=${v.encode()}" }

        fun decodeAll(s: String?): Map<String, CustomLayout> {
            if (s.isNullOrEmpty()) return emptyMap()
            return s.split('|').mapNotNull { entry ->
                val i = entry.indexOf('=')
                if (i <= 0) null else entry.substring(0, i) to decode(entry.substring(i + 1))
            }.toMap()
        }

        private val MODIFIERS = listOf(WidgetType.CTRL, WidgetType.SHIFT, WidgetType.ALT, WidgetType.META)
        private val KEY_ROWS = listOf(
            MODIFIERS,
            listOf(WidgetType.ESC, WidgetType.TAB, WidgetType.DELETE, WidgetType.BACKSPACE, WidgetType.ENTER),
            listOf(WidgetType.LANGUAGE, WidgetType.HOME, WidgetType.UP, WidgetType.END, WidgetType.SPACE),
            listOf(WidgetType.PAGE_UP, WidgetType.LEFT, WidgetType.DOWN, WidgetType.RIGHT, WidgetType.PAGE_DOWN),
            listOf(WidgetType.COPY, WidgetType.PASTE, WidgetType.CUT, WidgetType.UNDO, WidgetType.SELECT_ALL),
            listOf(WidgetType.SWITCH_APP, WidgetType.HOST_HOME, WidgetType.BACK),
        )

        /**
         * A starting layout for a [cols] × [rows] grid. Wide grids put the touchpad and keys side
         * by side ([swap] puts the keys on the left); narrow grids stack them.
         */
        fun default(cols: Int, rows: Int, wide: Boolean, swap: Boolean = false): CustomLayout {
            if (cols < 2 || rows < 3) return EMPTY
            var layout = EMPTY
            fun tryAdd(item: LayoutItem) {
                if (layout.canPlace(item, cols, rows)) layout = layout.add(item)
            }
            fun keyRows(x0: Int, y0: Int, width: Int, yEnd: Int) {
                KEY_ROWS.forEachIndexed { i, row ->
                    val y = y0 + i
                    if (y >= yEnd) return
                    row.take(width).forEachIndexed { x, type -> tryAdd(LayoutItem(type, x0 + x, y, 1, 1)) }
                }
            }
            fun mouseRow(x0: Int, y: Int, width: Int) {
                val left = (width + 1) / 2
                tryAdd(LayoutItem(WidgetType.MOUSE_LEFT, x0, y, left, 1))
                if (width - left >= 1) tryAdd(LayoutItem(WidgetType.MOUSE_RIGHT, x0 + left, y, width - left, 1))
            }
            if (wide && cols >= 4) {
                val padCols = (cols + 1) / 2
                val keyCols = cols - padCols
                val padX = if (swap) keyCols else 0
                val keyX = if (swap) 0 else padCols
                tryAdd(LayoutItem(WidgetType.TOUCHPAD, padX, 0, padCols, rows - 1))
                mouseRow(padX, rows - 1, padCols)
                tryAdd(LayoutItem(WidgetType.INPUT_BAR, keyX, 0, keyCols, 1))
                keyRows(keyX, 1, keyCols, rows)
            } else {
                val keyRowCount = ((rows - 2) / 2).coerceIn(0, KEY_ROWS.size)
                val padRows = rows - 2 - keyRowCount
                tryAdd(LayoutItem(WidgetType.INPUT_BAR, 0, 0, cols, 1))
                tryAdd(LayoutItem(WidgetType.TOUCHPAD, 0, 1, cols, padRows))
                mouseRow(0, 1 + padRows, cols)
                keyRows(0, 2 + padRows, cols, rows)
            }
            return layout
        }
    }
}
