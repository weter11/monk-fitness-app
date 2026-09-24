package com.monkfitness.app

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.monkfitness.app.ui.screens.*
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.validation.ValidationPoseScreen
import com.monkfitness.app.ui.theme.MonkFitnessTheme
import com.monkfitness.app.viewmodel.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The app's single Activity.
 *
 * It owns no language of its own (localization stage §8): the app language is an application-level
 * setting, applied by the platform through `AppLanguageManager` and read by every `stringResource` call
 * in the tree below. A language change therefore recreates this Activity, which is the platform's normal
 * way of re-reading resources, and the app needs no second mechanism to make the UI follow.
 */
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The one-time hand-off of the language a pre-localization install stored for itself (§5). It is
        // triggered here, and not from the Application object, because the app-locale API reaches the
        // platform through the *active* AppCompat-attached Activity: asked any earlier, it silently does
        // nothing on Android 13+ and the user's own choice would be lost on upgrade.
        //
        // It runs in a coroutine rather than in a `runBlocking` on the main thread, which this code did at
        // first and which froze the app: the hand-off reads and writes DataStore, and DataStore notifies its
        // collectors through the dispatcher they were collected on — the main thread — so blocking the main
        // thread while a `viewModelScope` flow is collecting deadlocks the write. The cost of not blocking is
        // one frame in the system language on the first launch after an upgrade; the cost of blocking is an
        // app that never finishes starting.
        lifecycleScope.launch {
            viewModel.migrateLanguageSelectionIfNeeded()
        }

        viewModel.handleNotificationIntent(intent)
        setContent {
            // Keeping the screen on is the *session screen's* own business now (§30 step 15): it is the
            // only place that knows a workout is running, and the shipped step machine this used to read
            // is gone with the runtime it belonged to.
            MonkFitnessTheme {
                MainApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleNotificationIntent(intent)
    }
}

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.home, Icons.Default.Home)
    object Programs : Screen(MainViewModel.ROUTE_MY_PROGRAMS, R.string.programs_title, Icons.Default.List)
    object Nutrition : Screen("nutrition", R.string.nutrition, Icons.Default.Favorite)
    object Progress : Screen("progress", R.string.progress, Icons.Default.Star)
    object Posture : Screen("posture", R.string.exercises_tab, Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val notificationDestination by viewModel.notificationDestination.collectAsState()

    LaunchedEffect(notificationDestination, isOnboardingCompleted) {
        val dest = notificationDestination
        if (dest != null && isOnboardingCompleted) {
            viewModel.clearNotificationDestination()
            navController.navigate(dest) {
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (isOnboardingCompleted && (currentRoute == Screen.Home.route ||
                currentRoute == Screen.Programs.route ||
                currentRoute == Screen.Nutrition.route ||
                currentRoute == Screen.Progress.route ||
                currentRoute == Screen.Posture.route)) {
                NavigationBar {
                    val screens = listOf(
                        Screen.Home,
                        Screen.Programs,
                        Screen.Nutrition,
                        Screen.Progress,
                        Screen.Posture
                    )
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                            label = {
                                Text(
                                    text = stringResource(screen.titleRes),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
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
                    onStartWorkout = { slotId ->
                        navController.navigate(MainViewModel.programSessionRoute(slotId))
                    },
                    onOpenPrograms = {
                        navController.navigate(Screen.Programs.route)
                    },
                    onOpenSettings = {
                        navController.navigate("settings")
                    },
                    onStartPostureWorkout = {
                        navController.navigate("posture-workout")
                    }
                )
            }
            composable(Screen.Nutrition.route) {
                NutritionScreen(
                    viewModel = viewModel,
                    onOpenTodayPlan = {
                        navController.navigate("nutrition-today")
                    },
                    onOpenShoppingList = {
                        navController.navigate("nutrition-shopping-list")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings") {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Progress.route) {
                ProgressScreen(viewModel)
            }
            composable(Screen.Posture.route) {
                PostureScreen(
                    viewModel = viewModel,
                    onExerciseClick = { exercise ->
                        navController.navigate("exercise/${exercise.id}?isPosture=true")
                    },
                    onValidationPoseClick = { pose ->
                        navController.navigate("validation/${pose.id}")
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            // ---- the Program System (§30 step 14) -------------------------------------------------
            // Destinations of the app's ONE NavHost. They are addressed by stable identifiers — a
            // `programId`, and one of §2's two mode names — never by a domain object (§16).
            composable(MainViewModel.ROUTE_PROGRAMS) {
                ProgramsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMyPrograms = { navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS) },
                    onOpenCreate = { navController.navigate(MainViewModel.ROUTE_PROGRAM_CREATE) },
                    onOpenImport = { navController.navigate(MainViewModel.ROUTE_PROGRAM_IMPORT) }
                )
            }
            composable(MainViewModel.ROUTE_MY_PROGRAMS) {
                MyProgramsScreen(
                    controller = viewModel.programs,
                    onBack = { navController.popBackStack() },
                    onOpenProgram = { programId ->
                        navController.navigate(MainViewModel.programDetailRoute(programId))
                    },
                    onCreateProgram = { navController.navigate(MainViewModel.ROUTE_PROGRAM_CREATE) },
                    onImportProgram = { navController.navigate(MainViewModel.ROUTE_PROGRAM_IMPORT) },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable(MainViewModel.ROUTE_PROGRAM_CREATE) {
                ProgramCreateChoiceScreen(
                    onBack = { navController.popBackStack() },
                    onManual = {
                        navController.navigate(
                            MainViewModel.programEditorCreateRoute(ProgramMode.MANUAL.name)
                        )
                    },
                    onGenerated = {
                        navController.navigate(
                            MainViewModel.programEditorCreateRoute(ProgramMode.GENERATED.name)
                        )
                    }
                )
            }
            composable(
                route = MainViewModel.ROUTE_PROGRAM_DETAIL,
                arguments = listOf(navArgument("programId") { type = NavType.StringType })
            ) { backStackEntry ->
                val programId = backStackEntry.arguments?.getString("programId") ?: ""
                ProgramDetailScreen(
                    controller = viewModel.programs,
                    programId = programId,
                    onBack = { navController.popBackStack() },
                    onEditProgram = { id ->
                        navController.navigate(MainViewModel.programEditorEditRoute(id))
                    },
                    onCopyProgram = { id ->
                        navController.navigate(MainViewModel.programEditorCopyRoute(id))
                    }
                )
            }
            composable(
                route = MainViewModel.ROUTE_PROGRAM_EDITOR_CREATE,
                arguments = listOf(navArgument("mode") { type = NavType.StringType })
            ) { backStackEntry ->
                val mode = backStackEntry.arguments?.getString("mode") ?: ProgramMode.MANUAL.name
                ProgramEditorScreen(
                    controller = viewModel.programs,
                    seedKey = MainViewModel.ROUTE_PROGRAM_EDITOR_CREATE + mode,
                    seed = {
                        viewModel.programs.openCreateDraft(
                            ProgramMode.entries.firstOrNull { it.name == mode } ?: ProgramMode.MANUAL
                        )
                    },
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(
                route = MainViewModel.ROUTE_PROGRAM_EDITOR_EDIT,
                arguments = listOf(navArgument("programId") { type = NavType.StringType })
            ) { backStackEntry ->
                val programId = backStackEntry.arguments?.getString("programId") ?: ""
                ProgramEditorScreen(
                    controller = viewModel.programs,
                    seedKey = MainViewModel.ROUTE_PROGRAM_EDITOR_EDIT + programId,
                    seed = { viewModel.programs.openEditDraft(programId) },
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(
                route = MainViewModel.ROUTE_PROGRAM_EDITOR_COPY,
                arguments = listOf(navArgument("programId") { type = NavType.StringType })
            ) { backStackEntry ->
                val programId = backStackEntry.arguments?.getString("programId") ?: ""
                val copyLabel = stringResource(R.string.programs_copy_of)
                ProgramEditorScreen(
                    controller = viewModel.programs,
                    seedKey = MainViewModel.ROUTE_PROGRAM_EDITOR_COPY + programId,
                    seed = { viewModel.programs.openCopyDraft(programId, copyLabel) },
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(MainViewModel.ROUTE_PROGRAM_IMPORT) {
                ProgramImportScreen(
                    controller = viewModel.programs,
                    onBack = { navController.popBackStack() },
                    onImported = {
                        navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable("nutrition-shopping-list") {
                NutritionShoppingListScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("nutrition-today") {
                NutritionTodayScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = MainViewModel.ROUTE_PROGRAM_SESSION,
                arguments = listOf(navArgument("slotId") { type = NavType.StringType })
            ) { backStackEntry ->
                val slotId = backStackEntry.arguments?.getString("slotId") ?: ""
                ProgramSessionScreen(
                    controller = viewModel.programSession,
                    slotId = slotId,
                    vibrationEnabled = viewModel.vibrationEnabled.collectAsState().value,
                    onBack = { navController.popBackStack() },
                    onExerciseClick = { exerciseId ->
                        navController.navigate("exercise/$exerciseId")
                    }
                )
            }
            composable("posture-workout") {
                PostureSessionScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "exercise/{exerciseId}",
                arguments = listOf(
                    navArgument("exerciseId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
                val difficultyAdjustments by viewModel.exerciseDifficultyAdjustments.collectAsState()
                val flexibilityFocusAreas by viewModel.flexibilityFocusAreas.collectAsState()

                val exercise = viewModel.findExerciseById(
                    exerciseId = exerciseId,
                    difficultyAdjustments = difficultyAdjustments,
                    focusAreas = flexibilityFocusAreas
                )

                ExerciseScreen(
                    exercise = exercise,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "validation/{poseId}",
                arguments = listOf(navArgument("poseId") { type = NavType.StringType })
            ) { backStackEntry ->
                val poseId = backStackEntry.arguments?.getString("poseId") ?: ""
                val pose = viewModel.findValidationPoseById(poseId)
                ValidationPoseScreen(
                    pose = pose,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
