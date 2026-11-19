package app.scrollback.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.scrollback.data.prefs.Settings
import app.scrollback.feature.feed.FeedScreen
import app.scrollback.feature.files.CategoryScreen
import app.scrollback.feature.files.FileBrowserScreen
import app.scrollback.feature.files.FileRoute
import app.scrollback.feature.files.FilesHomeScreen
import app.scrollback.files.Category
import app.scrollback.files.FileNode
import app.scrollback.feature.feed.FeedSpec
import app.scrollback.feature.library.LibraryScreen
import app.scrollback.feature.library.PlaylistDetailScreen
import app.scrollback.feature.lock.LockScreen
import app.scrollback.feature.pictures.PicturesScreen
import app.scrollback.feature.settings.DuplicatesScreen
import app.scrollback.feature.settings.FoldersScreen
import app.scrollback.feature.settings.SettingsScreen
import app.scrollback.ui.components.FloatingGlassNav
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.LocalHazeState
import app.scrollback.ui.components.NavItem
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

private val TABS = listOf(
    NavItem("random", "Random", Icons.Outlined.Shuffle),
    NavItem("saved", "Saved", Icons.Outlined.VideoLibrary),
    NavItem("pictures", "Pictures", Icons.Outlined.Image),
    NavItem("files", "Files", Icons.Outlined.Folder),
    NavItem("library", "Library", Icons.Outlined.CollectionsBookmark),
    NavItem("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun ScrollbackRoot() {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(null)
    val session by container.session.collectAsStateWithLifecycle()
    val s = settings ?: return // wait for DataStore before deciding about the lock
    if (s.hasPin && s.requirePinOnLaunch && !session.appUnlocked) {
        LockScreen(verify = { container.settings.verifyPin(it) }, onUnlocked = { container.unlockApp() })
    } else {
        AppNav()
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val openFeed: (FeedSpec, Long) -> Unit = { spec, id -> nav.navigate(spec.toRoute(id)) }
    NavHost(
        navController = nav,
        startDestination = "tabs",
        modifier = Modifier.fillMaxSize().background(SbColors.Background),
        enterTransition = { slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(200)) },
    ) {
        composable("tabs") {
            MainTabs(
                openFeed = openFeed,
                openPlaylist = { nav.navigate("playlist/$it") },
                openFolders = { nav.navigate("folders") },
                openDuplicates = { nav.navigate("duplicates") },
                openBrowse = { nav.navigate("browse?root=${FileRoute.encode(it)}") },
                openCategory = { nav.navigate("category/${it.name}") },
            )
        }
        composable("browse?root={root}", arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" })) { entry ->
            val root = FileRoute.decode(entry.arguments?.getString("root").orEmpty())
            if (root != null) FileBrowserScreen(root, onBack = { nav.popBackStack() }) else nav.popBackStack()
        }
        composable("category/{cat}") { entry ->
            val cat = runCatching { Category.valueOf(entry.arguments?.getString("cat").orEmpty()) }.getOrDefault(Category.VIDEOS)
            CategoryScreen(cat, onBack = { nav.popBackStack() })
        }
        composable(
            "feed/{kind}?arg={arg}&start={start}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("arg") { type = NavType.StringType; defaultValue = "" },
                navArgument("start") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val args = entry.arguments
            val spec = FeedSpec.fromRoute(args?.getString("kind").orEmpty(), args?.getString("arg").orEmpty())
            FeedScreen(
                spec = spec,
                startId = args?.getLong("start") ?: -1L,
                isTab = false,
                onBack = { nav.popBackStack() },
                onOpenFolders = { nav.navigate("folders") },
            )
        }
        composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            PlaylistDetailScreen(entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() }, onOpen = openFeed)
        }
        composable("folders") { FoldersScreen(onBack = { nav.popBackStack() }) }
        composable("duplicates") { DuplicatesScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun MainTabs(
    openFeed: (FeedSpec, Long) -> Unit,
    openPlaylist: (Long) -> Unit,
    openFolders: () -> Unit,
    openDuplicates: () -> Unit,
    openBrowse: (FileNode) -> Unit,
    openCategory: (Category) -> Unit,
) {
    val container = LocalContainer.current
    val haptics = rememberHaptics()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    var tab by rememberSaveable { mutableStateOf("random") }
    var seed by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val haze = rememberHazeState()

    CompositionLocalProvider(LocalHazeState provides haze) {
        Box(Modifier.fillMaxSize().background(SbColors.Background)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "tabs",
            ) { t ->
                when (t) {
                    "random" -> FeedScreen(
                        spec = FeedSpec.Random(seed),
                        startId = -1,
                        isTab = true,
                        onOpenFolders = openFolders,
                        header = {
                            GlassIconButton(Icons.Outlined.Shuffle, "Reshuffle", onClick = {
                                haptics.confirm()
                                seed = System.currentTimeMillis()
                            }, size = 40.dp)
                        },
                    )
                    "saved" -> FeedScreen(
                        spec = FeedSpec.Saved(settings.savedNewestFirst),
                        startId = -1,
                        isTab = true,
                        onOpenFolders = openFolders,
                        header = {
                            GlassPill(
                                if (settings.savedNewestFirst) "Newest" else "Oldest",
                                selected = false,
                                icon = Icons.Outlined.SwapVert,
                                onClick = {
                                    haptics.tick()
                                    container.appScope.launch { container.settings.setSavedNewestFirst(!settings.savedNewestFirst) }
                                },
                            )
                        },
                    )
                    "pictures" -> PicturesScreen(onOpen = openFeed)
                    "files" -> FilesHomeScreen(onBrowse = openBrowse, onCategory = openCategory)
                    "library" -> LibraryScreen(onOpen = openFeed, onOpenPlaylist = openPlaylist)
                    else -> SettingsScreen(onOpenFolders = openFolders, onOpenDuplicates = openDuplicates)
                }
            }
            FloatingGlassNav(
                items = TABS,
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
