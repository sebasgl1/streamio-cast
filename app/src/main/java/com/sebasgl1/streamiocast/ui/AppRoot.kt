package com.sebasgl1.streamiocast.ui

import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sebasgl1.streamiocast.data.MetaItem

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("home", "Inicio", Icons.Filled.Home),
    Destination("search", "Buscar", Icons.Filled.Search),
    Destination("settings", "Ajustes", Icons.Filled.Settings),
)

private fun detailRoute(item: MetaItem) = "detail/${Uri.encode(item.type)}/${Uri.encode(item.id)}"

private fun goTo(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Una sola app para celular y tablet: barra inferior en pantallas angostas,
 * riel lateral cuando hay ancho suficiente.
 */
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showNavigation = destinations.any { it.route == route }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showNavigation && !wide) {
                    NavigationBar {
                        destinations.forEach { d ->
                            NavigationBarItem(
                                selected = route == d.route,
                                onClick = { goTo(nav, d.route) },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (showNavigation && wide) {
                    NavigationRail {
                        destinations.forEach { d ->
                            NavigationRailItem(
                                selected = route == d.route,
                                onClick = { goTo(nav, d.route) },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                }

                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    composable("home") {
                        HomeScreen(onOpen = { nav.navigate(detailRoute(it)) })
                    }
                    composable("search") {
                        SearchScreen(onOpen = { nav.navigate(detailRoute(it)) })
                    }
                    composable("settings") {
                        SettingsScreen()
                    }
                    composable(
                        route = "detail/{type}/{id}",
                        arguments = listOf(
                            navArgument("type") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                        ),
                    ) {
                        DetailScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
