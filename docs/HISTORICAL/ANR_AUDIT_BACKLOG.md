# ANR / Main-Thread Freeze Audit — Backlog

**Status:** OPEN backlog. Created during the UI-layer ANR audit (pre-architecture-audit
pause). Deferred per plan: "we later return to it after the architecture audit."

**Scope audited:** UI layer, ViewModels, Composables, broadcast receiver
(`app/src/main/java/.../ui`, `.../viewmodel`, `.../util/NotificationReceiver.kt`).

**What was ruled OUT (not problems):**
- No `runBlocking` inside coroutines / Composables. The only `runBlocking` is in
  `NotificationReceiver` (see #1).
- No deadlocks / no UI-layer `synchronized` contention.
- The skeleton `Canvas` render loop (`ExerciseAnimation.kt` + `SkeletonRenderer`) is
  intentionally main-thread and allocation-free by design — NOT an ANR source.
- `calculateStreak` is a `suspend` Room call (auto-offloaded to IO).

---

## Findings (priority order)

### #1 CRITICAL — `runBlocking` on the broadcast receiver main thread
**File:** `app/src/main/java/com/monkfitness/app/util/NotificationReceiver.kt:19`
`onReceive()` runs on the main thread and must return within ~10s or the system
fires an ANR. `runBlocking { settingsManager.languageFlow.first() }` is a DataStore
read (can be slow / cold) executed synchronously.
**Fix:** use `goAsync()` + `Dispatchers.IO` and call `pendingResult.finish()` in a
`finally`. (Skeleton: broadcast ANR on every notification.)

### #2 HIGH — `withLocalizedSearchText` builds `createConfigurationContext` ×3 per
exercise for the whole catalog on the main thread
**Files:** `app/src/main/java/com/monkfitness/app/util/ExerciseSearch.kt:29`
(called via `enrichExercise` → `MainViewModel.kt:1634`, invoked from
`getExerciseLibrary` / `getWorkoutForDay` / `getPostureExercises` / `getWarmupExercises`
at `MainViewModel.kt:584–656`).
Catalog is ~99 exercises → ~300 `Context` creations per call. These getters run
**synchronously inside `combine`** operators (`homeUiState` `:364`,
`workoutSessionUiState` `:413`, `postureUiState` `:467`) on `viewModelScope`
(= Main.immediate). `postureUiState` re-emits on every keystroke/equipment toggle.
**Fix A:** cache `Resources` per locale in `ExerciseSearch.kt` (kill per-call cost).
**Fix B:** add `.flowOn(Dispatchers.Default)` to the three `combine` chains.

### #3 HIGH — Nutrition plan generation runs on the main thread
**Files:** `MainViewModel.kt:728` (`previewNextCycle`), `:1334`
(`generateNutritionFromAvailableProducts`), `:1350` (`replaceNutritionMeal`),
and the `nutritionPlan` chain at `:1231` via `mealEntitiesToNutritionPlan`.
`generateNutritionPlan` / `mealEntitiesToNutritionPlan` are combinatorial CPU
algorithms executed on `viewModelScope.launch` (Main) and inside `flatMapLatest` /
`combine` transforms (Main).
**Fix:** `viewModelScope.launch(Dispatchers.Default) { ... }` for the imperative
calls and `.flowOn(Dispatchers.Default)` on the `nutritionPlan` flow.

### #4 MEDIUM — `ToneGenerator` constructed on the main thread at ViewModel init
**File:** `MainViewModel.kt:1536` (`private val toneG = ToneGenerator(...)`).
Field initializers run during VM construction on the main thread (Activity `onCreate`
→ `by viewModels()`). `ToneGenerator` can touch audio hardware slowly on some devices.
**Fix:** lazy-init / build once via `viewModelScope.launch(Dispatchers.IO)` in `init`;
`release()` in `onCleared()`.

### #5 MEDIUM — `findExerciseById` rebuilds the full 99-exercise localized library
on the main thread during composition
**Files:** `MainViewModel.kt:654` (called from `ProgressScreen.kt:132` inside
`remember(personalRecords, currentProgramDay)`). `findExerciseById` calls
`getExerciseLibrary()` (→ `enrichExercise` ×99 → ~300 context creations) and repeats
per top-PR entry — runs during composition on main.
**Fix:** prebuild an `id -> Exercise` index once (off main), then O(1) lookup.

### #6 LOW–MEDIUM — `collectAsState` instead of `collectAsStateWithLifecycle`
**Files:** `MainActivity.kt` and all `*Screen.kt` (e.g. `ProgressScreen.kt:70–77`,
`HomeScreen.kt:46`, `WorkoutScreen.kt:58`, `PostureScreen.kt:47`).
Keeps the (heavy) `combine` transforms active when backgrounded; `lifecycle-runtime-ktx`
already a dependency.
**Fix:** switch to `collectAsStateWithLifecycle()`.

### #7 LOW — MPAndroidChart `AndroidView` re-invalidates on every recomposition
inside a scrollable `Column`
**File:** `app/src/main/java/com/monkfitness/app/ui/screens/ProgressScreen.kt:267,344,464,609`
(`update = { chart -> ... chart.invalidate() }`). Jank, not ANR.
**Fix:** assign `chart.data` only when data changes (e.g. `LaunchedEffect(data)`).

---

## Suggested follow-up
Return to this backlog after the architecture (Phase) audit concludes. Recommended
implementation order: #1 (tiny, critical) → #2 → #3 (both small, high) → #4 → #5
(medium) → #6/#7 (hygiene). All are localized; none touch the frozen animation
engine ownership contract.
