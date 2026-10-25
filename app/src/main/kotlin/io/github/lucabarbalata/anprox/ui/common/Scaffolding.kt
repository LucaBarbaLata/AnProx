package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.LocalBottomBarPadding
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/**
 * Standard screen frame: transparent so the glass background shows through, a title bar that
 * turns to glass when content scrolls under it, and a snackbar host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnProxScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = AnProxTheme.colors
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // With large fonts a title and subtitle need more than the bar's 64 dp, which would cut the subtitle off.
    val typography = MaterialTheme.typography
    val barHeight = if (subtitle == null) {
        TopAppBarDefaults.TopAppBarExpandedHeight
    } else {
        with(LocalDensity.current) { maxOf(TopAppBarDefaults.TopAppBarExpandedHeight, typography.titleLarge.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp() + 16.dp) }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = actions,
                expandedHeight = barHeight,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = c.glassStrong,
                    titleContentColor = c.textPrimary,
                    navigationIconContentColor = c.textPrimary,
                    actionIconContentColor = c.textSecondary,
                ),
            )
        },
        content = content,
    )
}

/** Padding for scrollable content inside [AnProxScaffold], clearing the floating bottom bar. */
@Composable
fun contentPadding(inner: PaddingValues, horizontal: Int = 16): PaddingValues = PaddingValues(
    start = horizontal.dp,
    end = horizontal.dp,
    top = inner.calculateTopPadding() + 4.dp,
    bottom = inner.calculateBottomPadding() + LocalBottomBarPadding.current + 16.dp,
)
