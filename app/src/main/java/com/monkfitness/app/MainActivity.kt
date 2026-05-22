package com.monkfitness.app

import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.monkfitness.app.ui.screens.*
import com.monkfitness.app.ui.theme.MonkFitnessTheme
import com.monkfitness.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonkFitnessTheme {
                MainApp(viewModel)
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Progress : Screen("progress", "Progress", Icons.Default.Star)
    object Posture : Screen("posture", "Posture", Icons.Default.Person)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
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
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
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
                HomeScreen(viewModel) { day ->
                    navController.navigate("workout/$day")
                }
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

                val exercise = viewModel.getWorkoutForDay(day).exercises.find { it.id == exerciseId }
                    ?: viewModel.getWarmupExercises().find { it.id == exerciseId }
                    ?: viewModel.getPostureExercises().find { it.id == exerciseId }

                ExerciseScreen(
                    exercise = exercise,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
