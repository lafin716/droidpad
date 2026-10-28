package com.droidpad.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SouthEast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidpad.app.MainViewModel
import com.droidpad.app.R
import com.droidpad.app.data.CustomLayout
import com.droidpad.app.data.LayoutItem
import com.droidpad.app.data.LayoutMode
import com.droidpad.app.data.WidgetType
import com.droidpad.app.ui.components.ControlGrid
import com.droidpad.app.ui.components.GRID_GAP
import com.droidpad.app.ui.components.GridMetrics
import com.droidpad.app.ui.components.GridWidgetPreview
import com.droidpad.app.ui.components.gridItem
import com.droidpad.app.ui.components.widgetLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Where the widget picker should place its choice: a tapped cell, or the first free spot. */
private data class PickRequest(val x: Int? = null, val y: Int? = null)

private val PICKER_GROUPS: List<Pair<Int, List<WidgetType>>> = listOf(
    R.string.editor_group_pointer to listOf(
        WidgetType.TOUCHPAD, WidgetType.MOUSE_LEFT, WidgetType.MOUSE_RIGHT, WidgetType.MOUSE_MIDDLE,
    ),
    R.string.editor_group_keys to listOf(WidgetType.INPUT_BAR) +
        WidgetType.entries.filter { it.ordinal in WidgetType.LANGUAGE.ordinal..WidgetType.F12.ordinal },
    R.string.editor_group_shortcuts to WidgetType.entries.filter { it.ordinal >= WidgetType.COPY.ordinal },
)

/**
 * Grid editor for the custom control layout. The grid is measured exactly like the control
 * screen's, and layouts are saved per grid size so each orientation keeps its own arrangement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorScreen(vm: MainViewModel, wide: Boolean, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var grid by remember { mutableStateOf<GridMetrics?>(null) }
    var selected by remember { mutableIntStateOf(-1) }
    var pick by remember { mutableStateOf<PickRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val hint = stringResource(R.string.editor_hint)
    LaunchedEffect(Unit) { snackbar.showSnackbar(hint, withDismissAction = true) }
    val noSpace = stringResource(R.string.editor_no_space)

    fun layoutFor(g: GridMetrics): CustomLayout =
        settings.customLayouts[CustomLayout.gridKey(g.cols, g.rows)]
            ?: CustomLayout.default(g.cols, g.rows, wide, settings.swapPanes)

    fun save(g: GridMetrics, layout: CustomLayout) = vm.updateSettings {
        it.copy(
            customLayouts = it.customLayouts + (CustomLayout.gridKey(g.cols, g.rows) to layout),
            layoutMode = LayoutMode.CUSTOM,
        )
    }

    fun place(type: WidgetType, request: PickRequest) {
        val g = grid ?: return
        val layout = layoutFor(g)
        val item = request.x?.let { x -> layout.placeAt(type, x, request.y ?: 0, g.cols, g.rows) }
            ?: layout.findSpot(type, g.cols, g.rows)
        if (item == null) {
            scope.launch { snackbar.showSnackbar(noSpace) }
        } else {
            save(g, layout.add(item))
            selected = layout.items.size
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val g = grid
                        Text(stringResource(R.string.editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (g != null) Text(
                            stringResource(R.string.editor_grid_size, g.cols, g.rows),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { selected = -1; pick = PickRequest() }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.editor_add))
                    }
                    IconButton(
                        onClick = {
                            val g = grid ?: return@IconButton
                            selected = -1
                            vm.updateSettings { it.copy(customLayouts = it.customLayouts - CustomLayout.gridKey(g.cols, g.rows)) }
                        },
                    ) { Icon(Icons.Outlined.RestartAlt, stringResource(R.string.editor_reset)) }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.editor_done)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ControlGrid(Modifier.controlGridArea(padding)) { g ->
            SideEffect { grid = g }
            val layout = layoutFor(g)
            val density = LocalDensity.current
            val cellW = with(density) { g.cellWidth.toPx() }
            val cellH = with(density) { g.cellHeight.toPx() }
            val gap = with(density) { GRID_GAP.toPx() }
            val outline = MaterialTheme.colorScheme.outlineVariant
            val currentLayout by rememberUpdatedState(layout)

            Canvas(
                Modifier.fillMaxSize().pointerInput(g) {
                    detectTapGestures { pos ->
                        selected = -1
                        val x = (pos.x / cellW).toInt()
                        val y = (pos.y / cellH).toInt()
                        val free = currentLayout.items.none { x in it.x until it.right && y in it.y until it.bottom }
                        if (x in 0 until g.cols && y in 0 until g.rows && free) pick = PickRequest(x, y)
                    }
                },
            ) {
                for (y in 0 until g.rows) for (x in 0 until g.cols) {
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(x * cellW + gap, y * cellH + gap),
                        size = Size(cellW - 2 * gap, cellH - 2 * gap),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }

            layout.items.forEachIndexed { index, item ->
                key(index, item) {
                    EditorItem(
                        item = item,
                        index = index,
                        layout = layout,
                        grid = g,
                        cellW = cellW,
                        cellH = cellH,
                        selected = selected == index,
                        onSelect = { selected = index },
                        onCommit = { moved -> save(g, layout.replace(index, moved)) },
                        onDelete = {
                            selected = -1
                            save(g, layout.remove(index))
                        },
                    )
                }
            }
        }
    }

    pick?.let { request ->
        val hasInputBar = grid?.let { g -> layoutFor(g).items.any { it.type == WidgetType.INPUT_BAR } } ?: false
        WidgetPicker(
            inputBarTaken = hasInputBar,
            onPick = { type ->
                pick = null
                place(type, request)
            },
            onDismiss = { pick = null },
        )
    }
}

@Composable
private fun EditorItem(
    item: LayoutItem,
    index: Int,
    layout: CustomLayout,
    grid: GridMetrics,
    cellW: Float,
    cellH: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onCommit: (LayoutItem) -> Unit,
    onDelete: () -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    var resizing by remember { mutableStateOf(false) }

    fun moved() = item.copy(x = item.x + (drag.x / cellW).roundToInt(), y = item.y + (drag.y / cellH).roundToInt())
    fun resized() = item.copy(
        w = (item.w + (resize.x / cellW).roundToInt()).coerceAtLeast(item.type.minW),
        h = (item.h + (resize.y / cellH).roundToInt()).coerceAtLeast(item.type.minH),
    )
    fun valid(target: LayoutItem) = layout.canPlace(target, grid.cols, grid.rows, ignore = index)

    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val target = when {
        dragging -> moved()
        resizing -> resized()
        else -> null
    }
    if (target != null && target != item) {
        val tint = if (valid(target)) colors.primary else colors.error
        Box(
            Modifier.gridItem(grid, target)
                .zIndex(1f)
                .background(tint.copy(alpha = 0.15f), shape)
                .border(2.dp, tint, shape),
        )
    }

    Box(
        Modifier.gridItem(grid, item)
            .zIndex(if (selected || dragging) 2f else 0f)
            .graphicsLayer {
                translationX = drag.x
                translationY = drag.y
                alpha = if (dragging) 0.85f else 1f
            }
            .pointerInput(Unit) { detectTapGestures { onSelect() } }
            .pointerInput(item, layout) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        onSelect()
                    },
                    onDragEnd = {
                        val m = moved()
                        if (m != item && valid(m)) onCommit(m)
                        drag = Offset.Zero
                        dragging = false
                    },
                    onDragCancel = {
                        drag = Offset.Zero
                        dragging = false
                    },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
    ) {
        GridWidgetPreview(item.type, Modifier.fillMaxSize(), highlighted = selected)
        if (selected && !dragging) {
            Surface(
                onClick = onDelete,
                shape = CircleShape,
                color = colors.error,
                contentColor = colors.onError,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp),
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.editor_delete), Modifier.padding(4.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(24.dp)
                    .background(colors.primary, CircleShape)
                    .pointerInput(item, layout) {
                        detectDragGestures(
                            onDragStart = { resizing = true },
                            onDragEnd = {
                                val r = resized()
                                if (r != item && valid(r)) onCommit(r)
                                resize = Offset.Zero
                                resizing = false
                            },
                            onDragCancel = {
                                resize = Offset.Zero
                                resizing = false
                            },
                        ) { change, amount ->
                            change.consume()
                            resize += amount
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.SouthEast, null, Modifier.size(16.dp), tint = colors.onPrimary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetPicker(inputBarTaken: Boolean, onPick: (WidgetType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.editor_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PICKER_GROUPS.forEach { (title, types) ->
                    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { type ->
                            AssistChip(
                                onClick = { onPick(type) },
                                label = { Text(widgetLabel(type)) },
                                enabled = type != WidgetType.INPUT_BAR || !inputBarTaken,
                            )
                        }
                    }
                }
            }
        },
    )
}
