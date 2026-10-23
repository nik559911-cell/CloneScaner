package app.skaner

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.skaner.ui.CameraScreen
import app.skaner.ui.CropScreen
import app.skaner.ui.DocScreen
import app.skaner.ui.DocsScreen
import app.skaner.ui.FilterScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkanerTheme { Nav() } }
    }
}

@Composable
fun SkanerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Маршруты = экраны из recon.md:
 * docs = S01, camera = S02, crop = S03, filter = S04, doc = S05 (+ экспорт S07 из него).
 */
@Composable
private fun Nav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "docs") {
        composable("docs") {
            DocsScreen(
                openCamera = { docId -> nav.navigate("camera/$docId") },
                openDoc = { docId -> nav.navigate("doc/$docId") },
            )
        }
        composable("camera/{docId}") { e ->
            val docId = e.arguments?.getString("docId")!!
            CameraScreen(
                docId = docId,
                onCaptured = { pageId -> nav.navigate("crop/$pageId") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("crop/{pageId}") { e ->
            val pageId = e.arguments?.getString("pageId")!!
            CropScreen(
                pageId = pageId,
                onNext = { nav.navigate("filter/$pageId") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("filter/{pageId}") { e ->
            val pageId = e.arguments?.getString("pageId")!!
            FilterScreen(
                pageId = pageId,
                onMorePages = { docId -> nav.navigate("camera/$docId") { popUpTo("docs") } },
                onDone = { docId ->
                    nav.navigate("doc/$docId") { popUpTo("docs") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("doc/{docId}") { e ->
            val docId = e.arguments?.getString("docId")!!
            DocScreen(
                docId = docId,
                addPage = { nav.navigate("camera/$docId") },
                openPage = { pageId -> nav.navigate("crop/$pageId") },
                onBack = { nav.popBackStack("docs", inclusive = false) },
            )
        }
    }
}
