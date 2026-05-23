package com.monkfitness.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.key
import androidx.navigation.navArgument
import com.monkfitness.app.ui.screens.*
import com.monkfitness.app.ui.theme.MonkFitnessTheme
import com.monkfitness.app.viewmodel.MainViewModel
import java.util.Locale
import android.content.res.Configuration

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val language by viewModel.settingsManager.languageFlow.collectAsState(initial = "ru")
            val currentStep by viewModel.currentStep.collectAsState()

            // Keep screen on during workout
            LaunchedEffect(currentStep) {
                if (currentStep != WorkoutStep.OVERVIEW && currentStep != WorkoutStep.COMPLETE) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val context = LocalContext.current
            val localizedContext = remember(language) {
                val locale = Locale(language)
                Locale.setDefault(locale)
                val config = Configuration(context.resources.configuration)
                config.setLocale(locale)
                context.createConfigurationContext(config)
            }

            CompositionLocalProvider(LocalContext provides localizedContext) {
                MonkFitnessTheme {
                    // Keying MainApp with language ensures full recomposition on language change
                    key(language) {
                        MainApp(viewModel)
                    }
                }
            }
        }
    }
}

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.home, Icons.Default.Home)
    object Progress : Screen("progress", R.string.progress, Icons.Default.Star)
    object Posture : Screen("posture", R.string.exercise_library, Icons.Default.Person)
    object Settings : Screen("settings", R.string.settings, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (isOnboardingCompleted && (currentRoute == Screen.Home.route ||
                currentRoute == Screen.Progress.route ||
                currentRoute == Screen.Posture.route ||
                currentRoute == Screen.Settings.route)) {
                NavigationBar {
                    val screens = listOf(Screen.Home, Screen.Progress, Screen.Posture, Screen.Settings)
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                            label = { Text(stringResource(screen.titleRes)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isOnboardingCompleted) Screen.Home.route else "onboarding",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("onboarding") {
                OnboardingScreen(onFinish = {
                    viewModel.setOnboardingCompleted()
                    navController.navigate(Screen.Home.route) {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onStartWorkout = { day ->
                        navController.navigate("workout/$day")
                    },
                    onStartPostureWorkout = { day ->
                        navController.navigate("posture-workout/$day")
                    }
                )
            }
            composable(Screen.Progress.route) {
                ProgressScreen(viewModel)
            }
            composable(Screen.Posture.route) {
                PostureScreen(viewModel) { exercise ->
                    navController.navigate("exercise/${exercise.id}?isPosture=true")
                }
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel, onBack = { navController.popBackStack() })
            }
            composable(
                route = "workout/{day}",
                arguments = listOf(navArgument("day") { type = NavType.IntType })
            ) { backStackEntry ->
                val day = backStackEntry.arguments?.getInt("day") ?: 1
                WorkoutScreen(
                    day = day,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onExerciseClick = { exercise ->
                        navController.navigate("exercise/${exercise.id}?day=$day")
                    }
                )
            }
            composable(
                route = "posture-workout/{day}",
                arguments = listOf(navArgument("day") { type = NavType.IntType })
            ) { backStackEntry ->
                val day = backStackEntry.arguments?.getInt("day") ?: 1
                WorkoutScreen(
                    day = day,
                    viewModel = viewModel,
                    isPostureMobilitySession = true,
                    onBack = { navController.popBackStack() },
                    onExerciseClick = { exercise ->
                        navController.navigate("exercise/${exercise.id}?day=$day&isPosture=true")
                    }
                )
            }
            composable(
                route = "exercise/{exerciseId}?day={day}&isPosture={isPosture}",
                arguments = listOf(
                    navArgument("exerciseId") { type = NavType.StringType },
                    navArgument("day") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("isPosture") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
                val day = backStackEntry.arguments?.getInt("day") ?: -1
                val isPosture = backStackEntry.arguments?.getBoolean("isPosture") ?: false
                val difficultyAdjustments by viewModel.exerciseDifficultyAdjustments.collectAsState()

                val stretchFocusArea by viewModel.stretchFocusArea.collectAsState()
                val postureFocusArea by viewModel.postureFocusArea.collectAsState()

                val exercise = viewModel.getWorkoutForDay(day, difficultyAdjustments, stretchFocusArea).exercises.find { it.id == exerciseId }
                    ?: viewModel.getPostureMobilityWorkout(day, difficultyAdjustments, postureFocusArea).exercises.find { it.id == exerciseId }
                    ?: viewModel.getWarmupExercises(difficultyAdjustments).find { it.id == exerciseId }
                    ?: viewModel.getExerciseLibrary(difficultyAdjustments).find { it.id == exerciseId }
                    ?: viewModel.getPostureExercises(difficultyAdjustments).find { it.id == exerciseId }

                ExerciseScreen(
                    exercise = exercise,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
