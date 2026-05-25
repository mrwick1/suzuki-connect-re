package dev.mrwick.gixxerbridge.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion

/**
 * Single tab entry for the bottom nav.
 *
 * `route` matches the NavHost route. `icon` is rendered at 22 dp; choose
 * outlined-weight icons (Material Symbols) for the unselected state and
 * the same shape filled for the selected state if visually distinct.
 */
data class GixxerNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * GixxerBridge bottom nav — Wave 1 redesign (icons-only, no container).
 *
 * Mirrors the Instagram / X / Threads pattern: just the icons sitting at
 * the bottom of the screen on transparent background, with a small accent
 * dot indicating the selected tab. No labels, no surface fill, no pill.
 *
 *   - Background: transparent — the screen below shows through.
 *   - Each tab: 24 dp icon + 4 dp accent dot below.
 *   - Selected: textPrimary icon + accent dot visible.
 *   - Unselected: textMuted icon + dot hidden.
 *   - Sized just enough for the gesture-nav inset + small breathing room.
 *
 * Why custom: the M3 NavigationBar can't be reduced this much (forces a
 * 80 dp min height, paints a surface). And we want the icons sitting on
 * pure bg with no chrome.
 */
@Composable
fun GixxerBottomNav(
    tabs: List<GixxerNavTab>,
    currentRoute: String?,
    onTabSelected: (GixxerNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No Surface wrapper — the bar is whatever colour the screen below it is.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSelected = currentRoute == tab.route
            NavTabItem(
                tab = tab,
                isSelected = isSelected,
                onClick = { onTabSelected(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavTabItem(
    tab: GixxerNavTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fgColor by animateColorAsState(
        targetValue = if (isSelected) GixxerTokens.textPrimary else GixxerTokens.textMuted,
        animationSpec = Motion.SpringStandard.colorSpec(),
        label = "navTabFg",
    )
    val dotColor by animateColorAsState(
        targetValue = if (isSelected) GixxerTokens.accent else Color.Transparent,
        animationSpec = Motion.SpringStandard.colorSpec(),
        label = "navTabDot",
    )

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            // No ripple/indication — a custom premium nav uses color shift
            // rather than the M3 ripple-on-press visual.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,   // a11y — label still readable to screen readers
            tint = fgColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

/**
 * The Motion springs are typed `AnimationSpec<Float>`; animateColorAsState
 * needs `AnimationSpec<Color>`. This helper makes a fresh color spec with
 * the same physics so we don't have to widen Motion's types. Trivial cost.
 */
private fun androidx.compose.animation.core.AnimationSpec<Float>.colorSpec(): androidx.compose.animation.core.AnimationSpec<Color> {
    val source = this as? androidx.compose.animation.core.SpringSpec<Float>
        ?: return androidx.compose.animation.core.spring(stiffness = 400f, dampingRatio = 0.85f)
    return androidx.compose.animation.core.spring(
        dampingRatio = source.dampingRatio,
        stiffness = source.stiffness,
    )
}
