package com.textreader.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.textreader.app.ui.screens.BookshelfScreen
import com.textreader.app.ui.screens.ReaderScreen

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}"

    fun reader(bookId: Long) = "reader/$bookId"
}

@Composable
fun AppNavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.BOOKSHELF
    ) {
        composable(Routes.BOOKSHELF) {
            BookshelfScreen(
                onOpenBook = { bookId ->
                    navController.navigate(Routes.reader(bookId))
                }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
