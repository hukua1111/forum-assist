package com.forumassist.app.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.forumassist.app.App
import com.forumassist.app.data.Site
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.screens.BoardsScreen
import com.forumassist.app.ui.screens.ComposeScreen
import com.forumassist.app.ui.screens.FavoritesScreen
import com.forumassist.app.ui.screens.HomeScreen
import com.forumassist.app.ui.screens.LoginScreen
import com.forumassist.app.ui.screens.MeScreen
import com.forumassist.app.ui.screens.PinnedBoardsScreen
import com.forumassist.app.ui.screens.SearchScreen
import com.forumassist.app.ui.screens.SettingsScreen
import com.forumassist.app.ui.screens.SitesScreen
import com.forumassist.app.ui.screens.ThreadDetailScreen
import com.forumassist.app.ui.screens.ThreadListScreen
import com.forumassist.app.ui.screens.WebScreen
import com.forumassist.app.ui.theme.ForumTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Outlined.Home),
    Tab("sites", "论坛", Icons.Outlined.Forum),
    Tab("pinned", "板块", Icons.Outlined.GridView),
    Tab("me", "我的", Icons.Outlined.Person)
)

@Composable
fun AppRoot() {
    val state = App.state
    ForumTheme(state.settings) {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val backStackEntry by nav.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        val showBar = route in setOf("home", "sites", "pinned", "me")

        val dark = when (state.settings.themeMode) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        LaunchedEffect(state.toast) {
            val msg = state.toast
            if (msg != null) {
                snackbar.showSnackbar(msg)
                state.toast = null
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        tabs.forEach { tab ->
                            val selected = route == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (route != tab.route) {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, tab.label) },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
            ) {
                NavHost(navController = nav, startDestination = "home") {

                    composable("home") {
                        HomeScreen(
                            state = state,
                            onOpenThread = { t -> nav.navigate(threadRoute(t)) },
                            onSearch = { nav.navigate("search") },
                            onOpenSites = { nav.navigate("sites") }
                        )
                    }

                    composable("sites") {
                        SitesScreen(
                            state = state,
                            onOpenSite = { s -> nav.navigate("boards/${s.id}") },
                            onOpenWeb = { s -> nav.navigate(webRoute(s, s.baseUrl)) },
                            onLogin = { s -> nav.navigate("login/${s.id}") }
                        )
                    }

                    composable("pinned") {
                        PinnedBoardsScreen(
                            state = state,
                            onOpenBoard = { pin ->
                                nav.navigate("threads/${pin.siteId}/${pin.fid}?name=${Uri.encode(pin.name)}")
                            },
                            onManage = { nav.navigate("sites") }
                        )
                    }

                    composable("shelf") {
                        FavoritesScreen(
                            state = state,
                            onOpenThread = { t -> nav.navigate(threadRoute(t)) },
                            onBrowse = { nav.navigate("home") }
                        )
                    }

                    composable("me") {
                        MeScreen(
                            state = state,
                            onSettings = { nav.navigate("settings") },
                            onSites = { nav.navigate("sites") },
                            onFavorites = { nav.navigate("shelf") }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(state = state, onBack = { nav.popBackStack() })
                    }

                    composable("search") {
                        SearchScreen(
                            state = state,
                            onBack = { nav.popBackStack() },
                            onOpenThread = { t -> nav.navigate(threadRoute(t)) }
                        )
                    }

                    composable(
                        "boards/{siteId}",
                        arguments = listOf(navArgument("siteId") { type = NavType.StringType })
                    ) { entry ->
                        val site = state.site(entry.arguments?.getString("siteId") ?: "")
                        if (site == null) {
                            nav.popBackStack(); return@composable
                        }
                        BoardsScreen(
                            state = state,
                            site = site,
                            onBack = { nav.popBackStack() },
                            onOpenBoard = { b ->
                                nav.navigate("threads/${site.id}/${b.fid}?name=${Uri.encode(b.name)}")
                            },
                            onOpenWeb = { nav.navigate(webRoute(site, site.baseUrl)) },
                            onSearch = { nav.navigate("search") }
                        )
                    }

                    composable(
                        "threads/{siteId}/{fid}?name={name}",
                        arguments = listOf(
                            navArgument("siteId") { type = NavType.StringType },
                            navArgument("fid") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { entry ->
                        val site = state.site(entry.arguments?.getString("siteId") ?: "")
                        val fid = entry.arguments?.getString("fid") ?: ""
                        val name = entry.arguments?.getString("name").orEmpty()
                        if (site == null) {
                            nav.popBackStack(); return@composable
                        }
                        ThreadListScreen(
                            state = state,
                            site = site,
                            fid = fid,
                            boardName = name,
                            onBack = { nav.popBackStack() },
                            onOpenThread = { t -> nav.navigate(threadRoute(t)) },
                            onCompose = { nav.navigate("compose/${site.id}?fid=$fid") }
                        )
                    }

                    composable(
                        "thread/{siteId}/{tid}?title={title}",
                        arguments = listOf(
                            navArgument("siteId") { type = NavType.StringType },
                            navArgument("tid") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { entry ->
                        val siteId = entry.arguments?.getString("siteId") ?: ""
                        val tid = entry.arguments?.getString("tid") ?: ""
                        val site = state.site(siteId)
                        if (site == null) {
                            nav.popBackStack(); return@composable
                        }
                        val title = entry.arguments?.getString("title").orEmpty()
                        val summary = state.findThread(siteId, tid)
                            ?: ThreadSummary(
                                siteId = siteId,
                                tid = tid,
                                title = title.ifBlank { "帖子" },
                                boardName = ""
                            )
                        ThreadDetailScreen(
                            state = state,
                            site = site,
                            thread = summary,
                            onBack = { nav.popBackStack() },
                            onReply = {
                                nav.navigate(
                                    "compose/${site.id}?fid=${summary.fid}&tid=${summary.tid}" +
                                        "&title=${Uri.encode(summary.title)}"
                                )
                            },
                            onOpenWeb = {
                                nav.navigate(
                                    webRoute(
                                        site,
                                        "${site.baseUrl}/forum.php?mod=viewthread&tid=${summary.tid}"
                                    )
                                )
                            },
                            onLogin = { nav.navigate("login/${site.id}") },
                            onOpenUrl = { target ->
                                // 附件/付费页要在站内打开：先把原生登录态补给 WebView
                                runCatching { state.http.ensureWebSession(target) }
                                nav.navigate(webRoute(site, target))
                            }
                        )
                    }

                    composable(
                        "compose/{siteId}?fid={fid}&tid={tid}&title={title}",
                        arguments = listOf(
                            navArgument("siteId") { type = NavType.StringType },
                            navArgument("fid") { type = NavType.StringType; defaultValue = "" },
                            navArgument("tid") { type = NavType.StringType; defaultValue = "" },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { entry ->
                        val site = state.site(entry.arguments?.getString("siteId") ?: "")
                        if (site == null) {
                            nav.popBackStack(); return@composable
                        }
                        ComposeScreen(
                            state = state,
                            site = site,
                            fid = entry.arguments?.getString("fid").orEmpty(),
                            tid = entry.arguments?.getString("tid")?.takeIf { it.isNotBlank() },
                            quotedTitle = entry.arguments?.getString("title").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onLogin = { nav.navigate("login/${site.id}") }
                        )
                    }

                    composable(
                        "login/{siteId}",
                        arguments = listOf(navArgument("siteId") { type = NavType.StringType })
                    ) { entry ->
                        val site = state.site(entry.arguments?.getString("siteId") ?: "")
                        if (site == null) {
                            nav.popBackStack(); return@composable
                        }
                        LoginScreen(state = state, site = site, onBack = { nav.popBackStack() })
                    }

                    composable(
                        "web/{siteId}?url={url}",
                        arguments = listOf(
                            navArgument("siteId") { type = NavType.StringType },
                            navArgument("url") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { entry ->
                        val site = state.site(entry.arguments?.getString("siteId") ?: "")
                        val url = entry.arguments?.getString("url").orEmpty()
                        WebScreen(
                            url = url.ifBlank { site?.baseUrl ?: "https://www.baidu.com" },
                            title = site?.name ?: "网页",
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

private fun threadRoute(t: ThreadSummary): String =
    "thread/${t.siteId}/${t.tid}?title=${Uri.encode(t.title)}"

private fun webRoute(site: Site, url: String): String =
    "web/${site.id}?url=${Uri.encode(url)}"
