package dev.personalterminal.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.emptyFlow

/**
 * The physical fold of the current window, as reported by the window library.
 *
 * A flat-open inner screen reports the hinge with **zero width**; half-folded (book posture /
 * Flex Mode) it becomes a real divider. Both carry the same centre coordinate, which is what
 * layouts align to.
 *
 * @param foldXDp centre x of a *vertical* fold (inner screen, portrait) or null on plain phones
 * @param foldYDp centre y of a *half-open horizontal* fold (Flex / tabletop) or null
 * @param isTabletop true when the device stands half-folded in landscape – the Fold's signature
 *   posture: countdown on the top half, controls on the bottom half
 */
class WindowFold(val foldXDp: Dp?, val foldYDp: Dp?, val isTabletop: Boolean) {
    companion object {
        val None = WindowFold(null, null, false)
    }
}

/**
 * Collects [WindowInfoTracker] for the current activity and distils it into a [WindowFold].
 * Every field stays null on devices without a hinge, so callers can treat "no fold" as the
 * normal phone/tablet case.
 */
@Composable
fun rememberWindowFold(): WindowFold {
    val activity = LocalContext.current as? ComponentActivity
    val layoutInfo by remember(activity) {
        if (activity != null) WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) else emptyFlow()
    }.collectAsState(initial = null)
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val features = layoutInfo?.displayFeatures.orEmpty()
    val vertical = features.filterIsInstance<FoldingFeature>()
        .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    val tabletopFold = features.filterIsInstance<FoldingFeature>()
        .firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL && it.state == FoldingFeature.State.HALF_OPENED }
    val windowW = with(density) { config.screenWidthDp.dp.toPx() }
    val windowH = with(density) { config.screenHeightDp.dp.toPx() }
    val foldX = vertical?.bounds
        ?.takeIf { it.centerX() > 0 && it.centerX() < windowW }
        ?.let { with(density) { it.centerX().toFloat().toDp() } }
    val foldY = tabletopFold?.bounds
        ?.takeIf { it.centerY() > 0 && it.centerY() < windowH }
        ?.let { with(density) { it.centerY().toFloat().toDp() } }
    val tabletop = tabletopFold != null && foldY != null
    return remember(foldX, foldY, tabletop) { WindowFold(foldX, foldY, tabletop) }
}
