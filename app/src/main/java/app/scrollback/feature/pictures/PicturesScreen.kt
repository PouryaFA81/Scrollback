package app.scrollback.feature.pictures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.prefs.Settings
import app.scrollback.feature.feed.FeedSpec
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import kotlinx.coroutines.launch

@Composable
fun PicturesScreen(onOpen: (FeedSpec, Long) -> Unit) {
    val container = LocalContainer.current
    val session by container.session.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val newest = settings.picturesNewestFirst
    val images by remember(newest, session.hiddenRevealed) {
        container.library.images(newest, session.hiddenRevealed)
    }.collectAsStateWithLifecycle(null)
    val grid = rememberLazyGridState()
    val collapsed by grid.collapsedState()
    val haptics = rememberHaptics()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve

    GlassScreen(
        title = "Pictures",
        collapsed = collapsed,
        actions = {
            GlassPill(if (newest) "Newest" else "Oldest", selected = false, icon = Icons.Outlined.SwapVert, onClick = {
                haptics.tick()
                container.appScope.launch { container.settings.setPicturesNewestFirst(!newest) }
            })
        },
    ) { mod ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            state = grid,
            modifier = mod,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LargeTitle("Pictures", images?.let { "${it.size} pictures" })
            }
            val list = images.orEmpty()
            if (images != null && list.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Outlined.Image, "No pictures yet", "Pictures in your folders show up here.")
                }
            }
            items(list, key = { it.id }) { item ->
                MediaThumb(
                    item,
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onOpen(FeedSpec.Pictures(newest), item.id) },
                )
            }
        }
    }
}
