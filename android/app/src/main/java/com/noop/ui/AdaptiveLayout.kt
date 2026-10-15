package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max

// MARK: - Adaptive layout (foldables · tablets · landscape)
//
// Choop was laid out for one phone-width column. On a book-style foldable's inner screen (Honor Magic V5 is
// ~850dp wide, Pixel Fold / Galaxy Z Fold are similar) that column stretched every card edge to edge. This
// file is the one place that decides how EVERY screen adapts, so the behaviour is the same everywhere:
//
//   • Columns — on a wide window the cards of a screen flow into as many phone-width columns as fit (Material's
//     "feed" canonical layout). A folded cover screen or a phone in portrait always gets one column, untouched.
//   • Fill the gaps — each card goes into whichever column is currently SHORTEST (a masonry / waterfall flow),
//     in the screen's own order. A long section in one column no longer leaves the other column empty.
//   • Your choice — Settings → Appearance → "Wide screens": Automatic (fit as many as the width allows),
//     One column (the phone layout, centred) or Two columns. Phones ignore it.
//   • Screen-wide pieces span all columns: the screen title, day/range selectors and banners are marked with
//     [spanAllColumns] / [AdaptiveListScope.fullWidthItem]; a [SectionHeader] always sticks to the card after
//     it, so a header never ends up in a different column from what it introduces.
//
// Both scaffolds go through this file: the eager [ScreenScaffold] lays its children out with [FlowColumns];
// the lazy [LazyScreenScaffold] swaps its LazyColumn for a LazyVerticalStaggeredGrid via [AdaptiveListScope].
// The decision reads [LocalConfiguration], which tracks the WINDOW (split-screen, freeform, fold/unfold), and
// MainActivity handles size changes itself (manifest configChanges), so a fold re-lays out in place.

/** Material 3 window width classes. */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** How a wide window (an unfolded foldable, a tablet, landscape) lays out a screen's cards. */
enum class WideLayoutMode(val storageValue: String, val label: String, val shortLabel: String) {
    AUTO("auto", "Automatic", "Auto"),
    ONE("one", "One column", "1 column"),
    TWO("two", "Two columns", "2 columns");

    companion object {
        fun fromStorage(raw: String?): WideLayoutMode = entries.firstOrNull { it.storageValue == raw } ?: AUTO
    }
}

/** The wide-screen layout preference, persisted in `noop_prefs` and mirrored in snapshot state so a change
 *  in Settings re-lays out every screen live. Lives under `theme.` so a .noopbak carries it with the other
 *  appearance choices. [load] runs once from MainActivity before first composition. */
object WideLayoutPrefs {
    private const val FILE = "noop_prefs"
    private const val KEY = "theme.wideColumns"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var mode by mutableStateOf(WideLayoutMode.AUTO)
        private set

    fun load(ctx: Context) {
        mode = WideLayoutMode.fromStorage(prefs(ctx).getString(KEY, WideLayoutMode.AUTO.storageValue))
    }

    fun set(ctx: Context, value: WideLayoutMode) {
        mode = value
        prefs(ctx).edit().putString(KEY, value.storageValue).apply()
    }
}

/** The pure breakpoints and maths behind the adaptive shell — no Compose state, unit-tested on the JVM. */
internal object AdaptiveLayout {
    /** First width (dp) that is no longer a phone column: the medium class. */
    const val MEDIUM_MIN_WIDTH_DP = 600

    /** First width (dp) of the expanded class. */
    const val EXPANDED_MIN_WIDTH_DP = 840

    /** The standard screen gutter both scaffolds use on a phone. */
    const val SCREEN_GUTTER_DP = 28f

    /** Widest a single-column screen's content grows before it is centred (a comfortable reading width). */
    const val READABLE_MAX_WIDTH_DP = 720f

    /** Narrowest a column may be in Automatic mode. Every card was designed at phone width (~300–360dp of
     *  content); 340dp keeps the widest fixed rows (the three Today score rings) from clipping. */
    const val MIN_COLUMN_WIDTH_DP = 340f

    /** Widest a column grows before the whole grid is centred instead, so a big tablet doesn't get 700dp cards. */
    const val MAX_COLUMN_WIDTH_DP = 560f

    /** The horizontal gap between columns. */
    const val COLUMN_GAP_DP = 16f

    /** Automatic mode never goes past this many columns. */
    const val MAX_AUTO_COLUMNS = 3

    fun widthClass(widthDp: Int): WindowWidthClass = when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidthClass.COMPACT
        widthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }

    /**
     * How many card columns a window [windowWidthDp] wide gets under [mode]. A compact window (phone, folded
     * cover screen) is always 1, whatever the setting. Automatic fits as many [MIN_COLUMN_WIDTH_DP] columns as
     * the content width allows (2 on an unfolded Magic V5, 3 on a landscape tablet), capped at
     * [MAX_AUTO_COLUMNS]; Two columns forces 2 on any non-compact window.
     */
    fun columnsFor(mode: WideLayoutMode, windowWidthDp: Int): Int {
        if (widthClass(windowWidthDp) == WindowWidthClass.COMPACT) return 1
        return when (mode) {
            WideLayoutMode.ONE -> 1
            WideLayoutMode.TWO -> 2
            WideLayoutMode.AUTO -> {
                val content = windowWidthDp - 2 * SCREEN_GUTTER_DP
                val fit = ((content + COLUMN_GAP_DP) / (MIN_COLUMN_WIDTH_DP + COLUMN_GAP_DP)).toInt()
                fit.coerceIn(1, MAX_AUTO_COLUMNS)
            }
        }
    }

    /**
     * The left/right inset that centres content of at most [maxContentWidthDp] in a window [windowWidthDp]
     * wide, never tighter than the phone gutter [minGutterDp]. Any window up to `max + 2 × gutter` wide gets
     * the plain gutter, so phones are untouched; wider windows grow equal margins instead of wider cards.
     */
    fun sideInsetDp(
        windowWidthDp: Float,
        maxContentWidthDp: Float,
        minGutterDp: Float = SCREEN_GUTTER_DP,
    ): Float = max(minGutterDp, (windowWidthDp - maxContentWidthDp) / 2f)

    /** The widest a [columns]-column grid grows (each column at most [MAX_COLUMN_WIDTH_DP]) before centring. */
    fun gridMaxWidthDp(columns: Int): Float =
        columns * MAX_COLUMN_WIDTH_DP + (columns - 1) * COLUMN_GAP_DP

    /** Where one flowed unit lands: a [column] index, or [SPAN_ALL] for a unit across every column. */
    data class FlowSlot(val column: Int, val y: Int)

    const val SPAN_ALL = -1

    /**
     * The masonry placement behind [FlowColumns]: walk the units in order and drop each into the column whose
     * bottom is currently highest up (ties go left), so the columns stay as even as the content allows while
     * the screen's order still reads top-to-bottom. A [spanAll] unit starts below every column and resets them
     * all to its bottom edge. [spacing] goes between consecutive units in a column; a zero-height unit (a
     * conditional card that rendered nothing) takes no space and no spacing. Returns each unit's slot and the
     * total height.
     */
    fun flowSlots(heights: IntArray, spanAll: BooleanArray, columns: Int, spacing: Int): Pair<List<FlowSlot>, Int> {
        val cols = columns.coerceAtLeast(1)
        val bottom = IntArray(cols)
        val used = BooleanArray(cols)
        fun nextTop(c: Int) = if (used[c]) bottom[c] + spacing else bottom[c]
        val slots = ArrayList<FlowSlot>(heights.size)
        for (i in heights.indices) {
            val h = heights[i]
            if (h <= 0) {
                slots.add(FlowSlot(SPAN_ALL, 0))
                continue
            }
            if (spanAll[i] || cols == 1) {
                var top = 0
                for (c in 0 until cols) top = max(top, nextTop(c))
                slots.add(FlowSlot(SPAN_ALL, top))
                for (c in 0 until cols) { bottom[c] = top + h; used[c] = true }
            } else {
                var best = 0
                for (c in 1 until cols) if (nextTop(c) < nextTop(best)) best = c
                val top = nextTop(best)
                slots.add(FlowSlot(best, top))
                bottom[best] = top + h
                used[best] = true
            }
        }
        return slots to (bottom.maxOrNull() ?: 0)
    }
}

/** How many card columns the current window gets under the user's wide-screen setting. Recomposes when the
 *  window is resized, folded or unfolded, or the setting changes. */
@Composable
fun screenColumns(): Int =
    AdaptiveLayout.columnsFor(WideLayoutPrefs.mode, LocalConfiguration.current.screenWidthDp)

/** The left/right inset for a screen whose content should not grow wider than [maxContentWidth]: the plain
 *  [minGutter] on a phone, centred margins on a wide window. */
@Composable
fun adaptiveSideInset(
    maxContentWidth: Dp,
    minGutter: Dp = AdaptiveLayout.SCREEN_GUTTER_DP.dp,
): Dp =
    AdaptiveLayout.sideInsetDp(
        windowWidthDp = LocalConfiguration.current.screenWidthDp.toFloat(),
        maxContentWidthDp = maxContentWidth.value,
        minGutterDp = minGutter.value,
    ).dp

/** Default content cap for single-column screens (see [AdaptiveLayout.READABLE_MAX_WIDTH_DP]). */
val ReadableMaxContentWidth: Dp = AdaptiveLayout.READABLE_MAX_WIDTH_DP.dp

/** The gap between card columns. */
val ColumnGap: Dp = AdaptiveLayout.COLUMN_GAP_DP.dp

// MARK: - Flow hints (parent data)

/** What a direct child of [FlowColumns] tells it: span every column, stay glued to the next child, and the
 *  horizontal alignment it asked for through ColumnScope.align. Ignored by every other layout. */
internal class FlowItemData(
    var spanAll: Boolean = false,
    var keepWithNext: Boolean = false,
    var align: Alignment.Horizontal? = null,
)

private data class FlowItemModifier(
    val spanAll: Boolean = false,
    val keepWithNext: Boolean = false,
    val align: Alignment.Horizontal? = null,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any? {
        // Never clobber another layout's parent data (a Row weight, a Box alignment): the hint is only for
        // FlowColumns, so when something else already claimed this child, it wins.
        val data = when (parentData) {
            null -> FlowItemData()
            is FlowItemData -> parentData
            else -> return parentData
        }
        if (spanAll) data.spanAll = true
        if (keepWithNext) data.keepWithNext = true
        if (align != null) data.align = align
        return data
    }
}

/** On a multi-column screen, lay this top-level card across every column (a screen-wide selector, banner or
 *  hero chart). No effect on a phone, where everything is one column anyway. */
fun Modifier.spanAllColumns(): Modifier = this.then(FlowItemModifier(spanAll = true))

/** Keep this top-level child in the same column as the one after it (used by [SectionHeader]). */
fun Modifier.keepWithNextInFlow(): Modifier = this.then(FlowItemModifier(keepWithNext = true))

/** The ColumnScope handed to a multi-column screen's content, so screens written for a Column compile and
 *  behave unchanged: align() is honoured inside the child's column; weight/alignBy have no meaning in a
 *  scrolling flow (they had none in the scrolling Column either). */
private object FlowColumnScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier =
        this.then(FlowItemModifier(align = alignment))
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

// MARK: - FlowColumns (eager screens)

/**
 * The multi-column body of [ScreenScaffold]: the screen's top-level children flow into [columns] equal
 * columns, each into the currently shortest one ([AdaptiveLayout.flowSlots]). A [SectionHeader] (or anything
 * marked [keepWithNextInFlow]) travels with the child after it; [spanAllColumns] children cross every column.
 * A screen whose content is a single unit (a chat, an empty state) simply spans the full width rather than
 * leaving a column empty. [header] (the scaffold's title row) always spans.
 */
@Composable
internal fun FlowColumns(
    columns: Int,
    spacing: Dp,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            header?.invoke()
            FlowColumnScope.content()
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gapPx = ColumnGap.roundToPx()
        val spacingPx = spacing.roundToPx()
        val colWidth = ((width - gapPx * (columns - 1)) / columns).coerceAtLeast(0)

        // 1. Group children into units: a keep-with-next child opens a unit the following child joins.
        val units = ArrayList<MutableList<Measurable>>()
        var open: MutableList<Measurable>? = null
        measurables.forEachIndexed { index, m ->
            val unit = open ?: ArrayList<Measurable>().also { units.add(it) }
            unit.add(m)
            val data = m.parentData as? FlowItemData
            // The header slot (index 0 when present) is its own span-all unit, never glued to content.
            open = if (data?.keepWithNext == true && !(header != null && index == 0)) unit else null
        }
        // Content units only (the header, when present, is always the first unit).
        val contentUnits = units.size - if (header != null) 1 else 0

        // 2. Measure each unit at its column width (or full width when it spans) and stack its members.
        val placeables = ArrayList<List<Placeable>>(units.size)
        val offsets = ArrayList<IntArray>(units.size)
        val aligns = ArrayList<List<Alignment.Horizontal?>>(units.size)
        val heights = IntArray(units.size)
        val spans = BooleanArray(units.size)
        units.forEachIndexed { u, members ->
            val isHeader = header != null && u == 0
            val span = isHeader || contentUnits <= 1 || columns == 1 ||
                members.any { (it.parentData as? FlowItemData)?.spanAll == true }
            val childConstraints = Constraints(minWidth = 0, maxWidth = if (span) width else colWidth)
            val ps = members.map { it.measure(childConstraints) }
            val offs = IntArray(ps.size)
            var y = 0
            var any = false
            ps.forEachIndexed { i, p ->
                if (p.height > 0 && any) y += spacingPx
                offs[i] = y
                if (p.height > 0) { y += p.height; any = true }
            }
            placeables.add(ps)
            offsets.add(offs)
            aligns.add(members.map { (it.parentData as? FlowItemData)?.align })
            heights[u] = y
            spans[u] = span
        }

        // 3. Masonry placement.
        val (slots, total) = AdaptiveLayout.flowSlots(heights, spans, columns, spacingPx)
        layout(width, total) {
            placeables.forEachIndexed { u, ps ->
                val slot = slots[u]
                val left = if (slot.column == AdaptiveLayout.SPAN_ALL) 0 else slot.column * (colWidth + gapPx)
                val avail = if (slot.column == AdaptiveLayout.SPAN_ALL) width else colWidth
                ps.forEachIndexed { i, p ->
                    // Computed left-to-right; placeRelative mirrors the whole grid for right-to-left locales.
                    val x = left + (aligns[u][i] ?: Alignment.Start).align(p.width, avail, LayoutDirection.Ltr)
                    p.placeRelative(x, slot.y + offsets[u][i])
                }
            }
        }
    }
}

// MARK: - AdaptiveListScope (lazy screens)

/**
 * The DSL a [LazyScreenScaffold] screen builds its rows with — the familiar `item { }` / `items(...)`, backed
 * by a LazyColumn on a phone and by a LazyVerticalStaggeredGrid (the masonry flow) on a wide window.
 */
interface AdaptiveListScope {
    /** True when rows flow into more than one column. */
    val isMultiColumn: Boolean

    /** One card. On a wide window it flows into the shortest column. */
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable () -> Unit)

    /** A row that spans every column on a wide window: screen-wide selectors, banners, the screen header. */
    fun fullWidthItem(key: Any? = null, contentType: Any? = null, content: @Composable () -> Unit)

    /** [count] cards built lazily, each flowing like [item]. */
    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable (index: Int) -> Unit,
    )

    /** Vertical breathing room between phone rows. Dropped on a multi-column screen, where a lone spacer
     *  would become a stray gap in one column. */
    fun spacer(height: Dp)
}

/** [items] over a list, mirroring Compose's `LazyListScope.items(List)`. */
inline fun <T> AdaptiveListScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    crossinline itemContent: @Composable (item: T) -> Unit,
) = items(
    count = items.size,
    key = if (key != null) { index: Int -> key(items[index]) } else null,
) { index -> itemContent(items[index]) }

/** Phone path: a straight pass-through to the LazyColumn's scope (rows exactly as before). */
internal class ListAdaptiveScope(private val list: LazyListScope) : AdaptiveListScope {
    override val isMultiColumn: Boolean get() = false
    override fun item(key: Any?, contentType: Any?, content: @Composable () -> Unit) =
        list.item(key = key, contentType = contentType) { content() }
    override fun fullWidthItem(key: Any?, contentType: Any?, content: @Composable () -> Unit) =
        item(key, contentType, content)
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable (index: Int) -> Unit,
    ) = list.items(count = count, key = key, contentType = contentType) { itemContent(it) }
    override fun spacer(height: Dp) = list.item { Spacer(Modifier.height(height)) }
}

/** Wide path: the staggered grid's scope; [fullWidthItem] maps to a full-line span. */
@OptIn(ExperimentalFoundationApi::class)
internal class GridAdaptiveScope(private val grid: LazyStaggeredGridScope) : AdaptiveListScope {
    override val isMultiColumn: Boolean get() = true
    override fun item(key: Any?, contentType: Any?, content: @Composable () -> Unit) =
        grid.item(key = key, contentType = contentType) { content() }
    override fun fullWidthItem(key: Any?, contentType: Any?, content: @Composable () -> Unit) =
        grid.item(key = key, contentType = contentType, span = StaggeredGridItemSpan.FullLine) { content() }
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable (index: Int) -> Unit,
    ) = grid.items(count = count, key = key, contentType = contentType) { itemContent(it) }
    override fun spacer(height: Dp) = Unit
}

/** Counts the cards a screen would emit on a wide window, so a screen showing a single card (an empty state,
 *  a loading note) spans the width instead of filling one column and leaving the rest blank. */
internal class CountingAdaptiveScope : AdaptiveListScope {
    var count = 0
        private set
    override val isMultiColumn: Boolean get() = true
    override fun item(key: Any?, contentType: Any?, content: @Composable () -> Unit) { count++ }
    override fun fullWidthItem(key: Any?, contentType: Any?, content: @Composable () -> Unit) { count++ }
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable (index: Int) -> Unit,
    ) { this.count += count }
    override fun spacer(height: Dp) = Unit
}
