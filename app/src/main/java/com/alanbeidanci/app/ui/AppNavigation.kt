package com.alanbeidanci.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.DictionaryPrepareState
import com.alanbeidanci.app.data.MainlineStep
import com.alanbeidanci.app.data.MainlineTestKind
import com.alanbeidanci.app.data.TypingPurpose
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.ui.components.AlanBottomBar
import com.alanbeidanci.app.ui.components.AlanTopBar
import com.alanbeidanci.app.ui.screens.AboutScreen
import com.alanbeidanci.app.ui.screens.BooksScreen
import com.alanbeidanci.app.ui.screens.HomeScreen
import com.alanbeidanci.app.ui.screens.OnboardingScreen
import com.alanbeidanci.app.ui.screens.PrivacyScreen
import com.alanbeidanci.app.ui.screens.RemedyTestScreen
import com.alanbeidanci.app.ui.screens.ReviewScreen
import com.alanbeidanci.app.ui.screens.SearchScreen
import com.alanbeidanci.app.ui.screens.SettingsScreen
import com.alanbeidanci.app.ui.screens.StatsScreen
import com.alanbeidanci.app.ui.screens.TypingStudyScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val BOOKS = "books"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val MAINLINE_LEARN = "mainline/learn"
    const val MAINLINE_REVIEW = "mainline/review"
    const val AUTONOMOUS_REVIEW = "review/practice"
    const val RELEARN = "review/relearn"
    const val TEST_REMEDY = "mainline/test/remedy"
    const val TEST_CONSOLIDATION = "mainline/test/consolidation"
    const val TEST_NEW_WORDS = "mainline/test/new-words"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val STATS = "stats"
}

@Composable
fun AlanVocabRoot(container: AppContainer, settings: UserSettings) {
    val session by container.sessionStore.session.collectAsState()
    val prepare by container.repository.prepareState.collectAsState()
    val scope = rememberCoroutineScope()
    if (session == null || prepare !is DictionaryPrepareState.Ready) {
        DictionaryPrepareScreen(
            sessionReady = session != null,
            state = prepare,
            onRetry = { scope.launch { runCatching { container.repository.ensureSeeded() } } },
        )
        return
    }
    if (!settings.onboardingComplete) {
        OnboardingScreen(container)
        return
    }

    LaunchedEffect(session?.userId) {
        session?.userId?.let { container.repository.prepareForUser(it) }
    }

    val navController = rememberNavController()
    var preparingMainline by remember { mutableStateOf(false) }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val protectedMainlineRoutes = setOf(
        Routes.MAINLINE_LEARN,
        Routes.MAINLINE_REVIEW,
        Routes.TEST_REMEDY,
        Routes.TEST_CONSOLIDATION,
        Routes.TEST_NEW_WORDS,
    )
    LaunchedEffect(session?.mainlineEnabled, currentRoute) {
        if (session?.mainlineEnabled == false && currentRoute in protectedMainlineRoutes) {
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    val immersive = currentRoute in setOf(
        Routes.MAINLINE_LEARN,
        Routes.MAINLINE_REVIEW,
        Routes.AUTONOMOUS_REVIEW,
        Routes.RELEARN,
        Routes.TEST_REMEDY,
        Routes.TEST_CONSOLIDATION,
        Routes.TEST_NEW_WORDS,
    )

    Scaffold(
        topBar = {
            if (!immersive) {
                AlanTopBar(
                    darkMode = settings.darkMode,
                    onToggleTheme = { scope.launch { container.preferences.setDarkMode(!settings.darkMode) } },
                )
            }
        },
        bottomBar = {
            if (!immersive) {
                AlanBottomBar(currentRoute = currentRoute) { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { slideInHorizontally(initialOffsetX = { it / 5 }) + fadeIn(animationSpec = tween(220)) },
            exitTransition = { fadeOut(animationSpec = tween(160)) },
            popEnterTransition = { fadeIn(animationSpec = tween(220)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 5 }) + fadeOut(animationSpec = tween(160)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    mainlinePreparing = preparingMainline,
                    mainlineEnabled = session?.mainlineEnabled != false,
                    onStartMainline = {
                        if (session?.mainlineEnabled != false && !preparingMainline) scope.launch {
                            preparingMainline = true
                            try {
                                val route = when (container.repository.currentMainlinePlan().step) {
                                    MainlineStep.REVIEW -> Routes.MAINLINE_REVIEW
                                    MainlineStep.REMEDY_TEST -> Routes.TEST_REMEDY
                                    MainlineStep.CONSOLIDATION_TEST -> Routes.TEST_CONSOLIDATION
                                    MainlineStep.NEW_WORD_TEST -> Routes.TEST_NEW_WORDS
                                    MainlineStep.LEARN -> Routes.MAINLINE_LEARN
                                    MainlineStep.DONE -> null
                                }
                                route?.let(navController::navigate)
                            } finally {
                                preparingMainline = false
                            }
                        }
                    },
                    onBooks = { navController.navigate(Routes.BOOKS) },
                )
            }
            composable(Routes.REVIEW) {
                ReviewScreen(
                    container = container,
                    onStartReview = { navController.navigate(Routes.AUTONOMOUS_REVIEW) },
                    onStartForgotten = { navController.navigate(Routes.RELEARN) },
                )
            }
            composable(Routes.BOOKS) {
                BooksScreen(container = container)
            }
            composable(Routes.SEARCH) { SearchScreen(container) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onBooks = { navController.navigate(Routes.BOOKS) },
                    onStats = { navController.navigate(Routes.STATS) },
                )
            }
            composable(Routes.MAINLINE_LEARN) {
                TypingStudyScreen(container, TypingPurpose.MAINLINE_LEARN, settings, onClose = { navController.popBackStack() }, onSelectBooks = { navController.navigate(Routes.BOOKS) })
            }
            composable(Routes.MAINLINE_REVIEW) {
                TypingStudyScreen(container, TypingPurpose.MAINLINE_REVIEW, settings, onClose = { navController.popBackStack() }, onSelectBooks = { navController.navigate(Routes.BOOKS) })
            }
            composable(Routes.AUTONOMOUS_REVIEW) {
                TypingStudyScreen(container, TypingPurpose.AUTONOMOUS_REVIEW, settings, onClose = { navController.popBackStack() }, onSelectBooks = { navController.navigate(Routes.BOOKS) })
            }
            composable(Routes.RELEARN) {
                TypingStudyScreen(container, TypingPurpose.RELEARN, settings, onClose = { navController.popBackStack() }, onSelectBooks = { navController.navigate(Routes.BOOKS) })
            }
            composable(Routes.TEST_REMEDY) {
                RemedyTestScreen(container, settings, MainlineTestKind.REMEDY, onClose = { navController.popBackStack() })
            }
            composable(Routes.TEST_CONSOLIDATION) {
                TypingStudyScreen(container, TypingPurpose.MAINLINE_CONSOLIDATE, settings, onClose = { navController.popBackStack() }, onSelectBooks = { navController.navigate(Routes.BOOKS) })
            }
            composable(Routes.TEST_NEW_WORDS) {
                RemedyTestScreen(container, settings, MainlineTestKind.NEW_WORDS, onClose = { navController.popBackStack() })
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.STATS) {
                StatsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun DictionaryPrepareScreen(
    sessionReady: Boolean,
    state: DictionaryPrepareState,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is DictionaryPrepareState.Failed -> {
                Text("词库没有准备好", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.message,
                    modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRetry) { Text("重试") }
            }
            is DictionaryPrepareState.Importing -> {
                val total = state.total.coerceAtLeast(1)
                Text("正在导入词库", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.bookName,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "第 ${state.index + 1} / $total 套",
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                LinearProgressIndicator(
                    progress = { ((state.index + 1f) / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                CircularProgressIndicator()
                Text(
                    if (sessionReady) "正在准备词库" else "正在准备本机学习",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
