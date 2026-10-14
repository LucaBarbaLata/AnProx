package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** One top-level destination. */
data class NavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * Extra bottom padding screens must add to scrollable content so the last item isn't hidden
 * behind the floating bottom bar. 0 on tablets (side rail) and on screens without the bar.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

/** True when the window is at least 600dp wide (tablets, unfolded foldables, landscape). */
@Composable
fun isWideLayout(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Questionnaire Q31: a floating glass bottom bar on phones that becomes a side rail on wide
 * screens. [showNavigation] hides it on detail screens.
 */
@Composable
fun AdaptiveNavScaffold(
    items: List<NavItem>,
    selectedKey: String?,
    onSelect: (NavItem) -> Unit,
    showNavigation: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isWideLayout()) {
        Row(modifier.fillMaxSize()) {
            if (showNavigation) GlassRail(items, selectedKey, onSelect)
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalBottomBarPadding provides 0.dp) { content() }
            }
        }
    } else {
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBottomBarPadding provides if (showNavigation) BAR_HEIGHT + 24.dp else 0.dp) {
                content()
            }
            if (showNavigation) {
                GlassBottomBar(
                    items, selectedKey, onSelect,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = navInset + 12.dp),
                )
            }
        }
    }
}

private val BAR_HEIGHT: Dp = 64.dp

@Composable
private fun GlassBottomBar(items: List<NavItem>, selectedKey: String?, onSelect: (NavItem) -> Unit, modifier: Modifier) {
    val c = AnProxTheme.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(shape)
            .background(c.glassStrong)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            NavButton(item, item.key == selectedKey, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GlassRail(items: List<NavItem>, selectedKey: String?, onSelect: (NavItem) -> Unit) {
    val c = AnProxTheme.colors
    Column(
        Modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .width(80.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(c.glassStrong)
            .border(1.dp, c.hairline, RoundedCornerShape(28.dp))
            .padding(vertical = 16.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEach { item -> NavButton(item, item.key == selectedKey, onSelect, Modifier.height(64.dp)) }
    }
}

@Composable
private fun NavButton(item: NavItem, selected: Boolean, onSelect: (NavItem) -> Unit, modifier: Modifier) {
    val c = AnProxTheme.colors
    val tint by animateColorAsState(if (selected) c.accent else c.textSecondary, label = "navTint")
    val pill by animateColorAsState(if (selected) c.accent.copy(alpha = 0.16f) else Color.Transparent, label = "navPill")
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item) })
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(pill)
                .padding(PaddingValues(horizontal = 16.dp, vertical = 4.dp)),
        ) {
            Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
