package dev.mrwick.redline.ui.nav

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
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
import dev.mrwick.redline.ui.theme.GixxerTokens
import dev.mrwick.redline.ui.theme.Motion

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
 * REDLINE bottom nav — Wave 1 redesign (floating pill).
 *
 * Single rounded container hugging the 5 icons, floating above the bottom
 * of the screen with padding from the edges. Looks "lifted" rather than
 * bolted to the bezel.
 *
 *   - Outer Box: full-width, bottom-center, padded for gesture-nav inset.
 *   - Inner pill: GixxerTokens.surfaceElevated, 28 dp rounded corners,
 *     1 dp hairline border, ~64 dp wide of internal content with the 5
 *     icons packed close.
 *   - Each tab: 22 dp icon + 4 dp accent dot below.
 *   - Selected: textPrimary icon + accent dot.
 *   - Unselected: textMuted icon + dot hidden.
 */
@Composable
fun GixxerBottomNav(
    tabs: List<GixxerNavTab>,
    currentRoute: String?,
    onTabSelected: (GixxerNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = GixxerTokens.surfaceElevated,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GixxerTokens.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
        animationSpec = Motion.SpringSnap.colorSpec(),
        label = "navTabFg",
    )

    // Box (not Column) — single icon, no dot, no spacer. Color contrast
    // (bright white vs muted grey) IS the selection indicator.
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            // a11y: 48dp minimum touch target (was ~36dp = 24dp icon + 6dp pad).
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                // Subtle bounded-less ripple for press feedback — colour contrast
                // still carries selection, but a tap now confirms it registered.
                indication = ripple(bounded = false, radius = 28.dp),
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,   // a11y — label still announced by screen readers
            tint = fgColor,
            modifier = Modifier.size(24.dp),
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
        ?: return androidx.compose.animation.core.spring(stiffness = 700f, dampingRatio = 0.6f)
    return androidx.compose.animation.core.spring(
        dampingRatio = source.dampingRatio,
        stiffness = source.stiffness,
    )
}
