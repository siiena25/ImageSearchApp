package uz.imagesearch.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uz.imagesearch.feature.capture.CaptureScreen
import uz.imagesearch.feature.home.HomeScreen
import uz.imagesearch.feature.results.ResultsScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val RESULTS = "results/{queryUri}"
    fun results(queryUri: String): String =
        "results/${URLEncoder.encode(queryUri, StandardCharsets.UTF_8.toString())}"
}

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onPhotoSearchClick = { nav.navigate(Routes.CAPTURE) })
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onClose = { nav.popBackStack() },
                onImageSelected = { uri ->
                    nav.navigate(Routes.results(uri.toString())) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(
            Routes.RESULTS,
            arguments = listOf(navArgument("queryUri") { type = NavType.StringType })
        ) { backStack ->
            val raw = backStack.arguments?.getString("queryUri").orEmpty()
            val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
            ResultsScreen(queryUri = decoded, onBack = { nav.popBackStack() })
        }
    }
}

