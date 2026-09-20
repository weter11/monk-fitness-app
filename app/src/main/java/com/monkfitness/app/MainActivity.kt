package com.monkfitness.app

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.key
import androidx.navigation.navArgument
import com.monkfitness.app.ui.screens.*
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.validation.ValidationPoseScreen
import com.monkfitness.app.ui.theme.MonkFitnessTheme
import com.monkfitness.app.viewmodel.MainViewModel
import java.util.Locale
import android.content.res.Configuration

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.handleNotificationIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleNotificationIntent(intent)
    }
}

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.home, Icons.Default.Home)
    object Nutrition : Screen("nutrition", R.string.nutrition, Icons.Default.Favorite)
    object Progress : Screen("progress", R.string.progress, Icons.Default.Star)
    object Posture : Screen("posture", R.string.exercises_tab, Icons.Default.Person)
    object Settings : Screen("settings", R.string.settings, Icons.Default.Settings)
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
                currentRoute == Screen.Nutrition.route ||
                currentRoute == Screen.Progress.route ||
                currentRoute == Screen.Posture.route ||
                currentRoute == Screen.Settings.route)) {
                NavigationBar {
                    val screens = listOf(
                        Screen.Home,
                        Screen.Nutrition,
                        Screen.Progress,
                        Screen.Posture,
                        Screen.Settings
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
                    onStartWorkout = { day ->
                        navController.navigate("workout/$day")
                    },
                    onStartPostureWorkout = { day ->
                        navController.navigate("posture-workout/$day")
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
                        navController.navigate(Screen.Settings.route) {
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
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenPrograms = {
                        navController.navigate(MainViewModel.ROUTE_PROGRAMS)
                    },
                    onOpenCustomProgram = {
                        viewModel.openCustomProgramEditor()
                        navController.navigate(MainViewModel.ROUTE_CUSTOM_PROGRAM)
                    }
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
                    onImportProgram = { navController.navigate(MainViewModel.ROUTE_PROGRAM_IMPORT) }
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
                            popUpTo(MainViewModel.ROUTE_PROGRAMS)
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
                            popUpTo(MainViewModel.ROUTE_PROGRAMS)
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
                            popUpTo(MainViewModel.ROUTE_PROGRAMS)
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
                            popUpTo(MainViewModel.ROUTE_PROGRAMS)
                        }
                    }
                )
            }
            composable(MainViewModel.ROUTE_CUSTOM_PROGRAM) {
                CustomProgramScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
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
                val difficultyAdjustments by viewModel.exerciseDifficultyAdjustments.collectAsState()
                val flexibilityTrainingType by viewModel.flexibilityTrainingType.collectAsState()
                val flexibilityFocusAreas by viewModel.flexibilityFocusAreas.collectAsState()

                val exercise = viewModel.findExerciseById(
                    exerciseId = exerciseId,
                    day = day,
                    difficultyAdjustments = difficultyAdjustments,
                    trainingType = flexibilityTrainingType,
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
