package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** The page backdrop: one flat colour, so cards and data carry the page (docs/design-system.md). */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = AnProxTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.backgroundTop, c.backgroundBottom))),
        content = content,
    )
}

/**
 * A card: a solid surface with rounded corners, no border and no shadow. [strong] raises it
 * (a selected row). Pass [onClick] to make the whole card a button.
 */
// combinedClickable was experimental in older Compose versions; harmless where it is stable.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AnProxTheme.colors
    var m = modifier
        .clip(shape)
        .background(if (strong) c.glassStrong else c.glass)
    if (onLongClick != null) {
        m = m.combinedClickable(onClickLabel = onClickLabel, role = Role.Button, onLongClick = onLongClick, onClick = onClick ?: {})
    } else if (onClick != null) {
        m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    }
    Column(m.padding(contentPadding), content = content)
}
