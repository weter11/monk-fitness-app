package com.monkfitness.app.character

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.MuscleInstanceId
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One live character and every Filament object it owns; [destroy] is the only teardown path.
 *
 * A session is per exercise: [CharacterStage] builds a new one when the pose changes, so the camera
 * is framed once for the exercise here and re-framed only when the surface resizes — never per frame.
 */
class CharacterSession(
    private val context: Context,
    private val engine: Engine,
    private val request: BackendRequest,
    private val selection: BackendSelection<Engine>,
    private val capabilities: GraphicsCapabilities,
    private val assetPath: String,
    private val poseSource: CharacterPoseSource,
    private val poseBuilder: PoseBuilder,
    private val alternating: Boolean,
    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
) {

    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val renderableManager = engine.renderableManager
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val choreographer = Choreographer.getInstance()
    private val pipeline = SkeletonPipeline(definition)
    private val frameProducer = CharacterFrameProducer(pipeline, poseBuilder.metadata, poseSource)
    private val framer = ExerciseCameraFramer3D(definition)

    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null
    private var materialProvider: UbershaderProvider? = null
    private var indirectLight: IndirectLight? = null

    private var skinnedRenderables: IntArray = IntArray(0)
    private var morphTargetsByRenderable: Map<Int, Int> = emptyMap()
    private var boneMatrices = FloatArray(0)
    private var boneBuffer: FloatBuffer = ByteBuffer.allocateDirect(16)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var jointCount = 0
    private var unitsPerMetre = 1f

    private var retargeter: PoseRetargeter? = null
    private var morphology = MorphologyPreset.NEUTRAL

    /** The dev-only layer switchboard: toggled from the debug overlay, read by the diagnostics. */
    val layers: CharacterLayerController = CharacterLayerController()

    private var anatomySolver: AnatomySolver? = null
    private var anatomyFrame: AnatomyFrame? = null
    private var muscleMesh: ProceduralMuscleMesh? = null
    private var sceneFrame: CharacterSceneFrame? = null
    private var tubeMesh: TubeMesh? = null
    private var tubeError: String? = null
    private var muscleStage: MuscleLayerStage? = null
    private var muscleError: String? = null
    private var morphWeights: MorphWeightSet? = null
    private var anatomyError: String? = null
    private var muscleTriangles = 0
    private var muscleHeadsRefused = 0
    private var muscleInstancesDrawn = 0
    private var musclesSolved = false
    private var tubeSlotCleared = false

    private var destroyed = false
    private var lastFrameNanos = 0L
    private var smoothedFps = 0f
    private var frames = 0L
    private val frameTimesNanos = LongArray(FRAME_SAMPLE_COUNT)
    private var frameTimeCursor = 0
    private var frameTimeSamples = 0
    private val stageTimings = FrameStageTimings(FRAME_SAMPLE_COUNT)
    private var lastDiagnostics: CharacterDiagnostics? = null

    // Read once, while the engine is valid: the report must still be readable after destroy().
    private val backendName: String = engine.backend?.name ?: "unknown"

    var error: String? = null
        private set

    var running: Boolean = false
        private set

    val backend: String get() = backendName

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || destroyed) return
            val delta = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (delta > 0) {
                val fps = 1_000_000_000f / delta
                smoothedFps = if (smoothedFps == 0f) fps else smoothedFps * 0.9f + fps * 0.1f
            }
            render(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    private val renderCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            swapChain = null
            // The loop keeps running but renders nothing until a new chain exists; pausing here
            // would leave a resized split-screen view frozen with no lifecycle event to restart it.
        }

        override fun onResized(width: Int, height: Int) {
            view.viewport = Viewport(0, 0, width, height)
            if (height <= 0 || width <= 0) return
            applyFraming(width, height)
        }
    }

    init {
        uiHelper.setRenderCallback(renderCallback)
        configureScene()
    }

    /** Loads and binds the character. Returns an error message, or null on success. */
    fun load(): String? {
        if (destroyed) return "session already destroyed"
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes)
            buffer.rewind()

            // The provider owns every material instance the session clones; dropping it here would leave
            // the tube layer holding instances whose materials no longer exist.
            val provider = UbershaderProvider(engine)
            materialProvider = provider
            val loader = AssetLoader(engine, provider, EntityManager.get())
            assetLoader = loader
            val loaded = loader.createAsset(buffer) ?: return "gltfio could not load $assetPath"
            asset = loaded

            val resources = ResourceLoader(engine)
            try {
                resources.loadResources(loaded)
            } finally {
                resources.destroy()
            }
            scene.addEntities(loaded.entities)

            val rig = GltfRig.parse(bytes)
            val boneByJoint = MonkJointResolver.resolve(rig)
            val mapper = PoseRetargeter(rig, boneByJoint, BoneMap.basis, definition)
            retargeter = mapper

            val instance = loaded.instance
            jointCount = if (instance.skinCount > 0) instance.getJointCountAt(0) else 0
            if (jointCount == 0) return "$assetPath has no skin"
            // The upload stride must equal the skin's joint count; a parser that disagrees with the
            // asset would deform the mesh silently, so fail the load instead.
            if (rig.jointNodes.size != jointCount) {
                return "parser found ${rig.jointNodes.size} joints, skin has $jointCount"
            }

            val skinnedNames = rig.skinnedNodes.map { rig.nodes[it].name }.toSet()
            skinnedRenderables = loaded.renderableEntities.filter { loaded.getName(it) in skinnedNames }.toIntArray()
            if (skinnedRenderables.isEmpty()) return "$assetPath has no skinned renderable"

            morphTargetsByRenderable = skinnedRenderables.associateWith { entity ->
                loaded.getMorphTargetNames(entity)?.size ?: 0
            }
            boneMatrices = FloatArray(jointCount * 16)
            boneBuffer = ByteBuffer.allocateDirect(jointCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            unitsPerMetre = X1Bind.unitsPerMetre(definition)
            morphWeights = MorphWeightSet(morphTargetsByRenderable.values.maxOrNull() ?: 0)

            applyMorphology(morphology)
            prepareAnatomy(instance)
            null
        } catch (error: Throwable) {
            val message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            this.error = message
            message
        }
    }

    fun setMorphology(preset: MorphologyPreset) {
        morphology = preset
        applyMorphology(preset)
    }

    private fun applyMorphology(preset: MorphologyPreset) {
        val weights = morphWeights ?: return
        if (morphTargetsByRenderable.isEmpty()) return
        weights.apply(preset)
        morphTargetsByRenderable.forEach { (entity, count) ->
            if (count <= 0) return@forEach
            val renderable = renderableManager.getInstance(entity)
            if (renderable == 0) return@forEach
            val buffer = FloatArray(count)
            weights.copyInto(buffer)
            renderableManager.setMorphWeights(renderable, buffer, 0)
        }
    }

    /**
     * Builds the anatomy solve this session will run every frame — only when the character flag is on,
     * because the rest solve is a one-off cost the 2D renderer must never pay.
     */
    private fun prepareAnatomy(instance: FilamentInstance) {
        if (!CharacterFeature.isEnabled) return
        // The body is the character and the tubes are an overlay: a broken anatomy asset must leave the
        // skinned figure on screen and report itself, not take the whole load down with it.
        try {
            prepareAnatomyUnchecked(instance)
        } catch (error: Throwable) {
            anatomyError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
        }
    }

    private fun prepareAnatomyUnchecked(instance: FilamentInstance) {
        val anatomy: AnatomyDefinition = AnatomyDefinitionLoader.load(
            context.assets.open(CharacterFeature.ANATOMY_PATH).bufferedReader().use { it.readText() }
        )
        // The host builds the reference carrier through the constructor: the presenter consumes a
        // pose, it never edits one, and the bind stance is the only carrier it creates.
        val bindJoints = Array(Joint.entries.size) { Vector3() }
        for ((joint, position) in X1Bind.bindJoints(definition)) {
            bindJoints[joint.index].set(position)
        }
        val bind = SkeletonPose(joints = bindJoints)
        // The rest stance the anatomy is measured against is the rig's own bind stance, so a muscle's
        // reference length and the character's reference pose cannot drift apart.
        val solver = AnatomySolver(anatomy, definition, unitsPerMetre.toDouble(), bind, lanes = SOLVE_LANES)
        val frame = AnatomyFrame.of(anatomy)
        val mesh = ProceduralMuscleMesh(anatomy, unitsPerMetre.toDouble())
        anatomySolver = solver
        anatomyFrame = frame
        muscleMesh = mesh
        val geometry = mesh.slot()
        val sectionFrame = CharacterSceneFrame(
            anatomy = frame,
            morph = morphWeights ?: MorphWeightSet(0),
            geometry = geometry,
            selection = layers.selection
        )
        sceneFrame = sectionFrame
        // The GPU layer is built here, where the engine and the asset both exist and the buffers can be
        // sized from the definition once instead of per frame.
        val tubes = TubeMesh(
            engine = engine,
            scene = scene,
            vertexCapacity = mesh.vertexCapacity,
            indexCapacity = mesh.triangleCapacity * 3,
            palette = SkinPalette.DEFAULT,
            provider = materialProvider,
            assetInstance = instance
        )
        tubeMesh = tubes
        tubeError = tubes.prepare()
        prepareMuscleLayer(frame, sectionFrame.partition)
    }

    /**
     * Loads the helper-bound muscle asset and the skinner that drives it. A failure is reported and
     * the body still draws: the layer is an overlay, and its absence is not a character failure.
     */
    private fun prepareMuscleLayer(frame: AnatomyFrame, partition: MuscleSectionPartition) {
        try {
            val binds = HelperBinds.read(
                context.assets.open(HelperBinds.ASSET_PATH).bufferedReader().use { it.readText() }
            )
            val bytes = context.assets.open(CharacterFeature.MUSCLE_ASSET_PATH).use { it.readBytes() }
            val rig = GltfRig.parse(bytes)
            val skinner = MuscleLayerSkinner(
                rig = rig,
                binds = binds,
                heads = frame.heads,
                unitsPerMetre = unitsPerMetre,
                pointCapacity = frame.pointCapacity
            )
            // The renderer is handed the section keys and an index, never a muscle or a side.
            val stage = MuscleLayerStage(engine, scene, materialProvider, skinner, partition.keys)
            muscleError = stage.prepare(bytes)
            muscleStage = stage
        } catch (error: Throwable) {
            muscleError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
        }
    }

    /**
     * The anatomy solve, between the produced pose and the bone upload: the pose is read here and the
     * result never reaches the renderer directly, because the tube geometry it fills is consumed by its
     * own drawable rather than by the skinned body.
     */
    private fun solveMuscles(pose: SkeletonPose) {
        val solver = if (CharacterFeature.isEnabled) anatomySolver else null
        val frame = anatomyFrame
        val mesh = muscleMesh
        val scene = sceneFrame
        if (solver == null || frame == null || mesh == null || scene == null) {
            // The 2D renderer never pays for the rest solve, so these stages read zero rather than missing.
            stageTimings.record(FrameStage.ANATOMY_SOLVE, 0L)
            stageTimings.record(FrameStage.ANATOMY_DERIVE, 0L)
            stageTimings.record(FrameStage.ANATOMY_PATHS, 0L)
            stageTimings.record(FrameStage.TUBE_WRITE_UPLOAD, 0L)
            return
        }

        val selection = layers.selection
        if (!selection.showsAnyLayer) {
            skipMuscles(frame, scene, selection)
            return
        }

        val solveStart = System.nanoTime()
        solver.solve(pose, frame)
        val solveNanos = System.nanoTime() - solveStart
        stageTimings.record(FrameStage.ANATOMY_DERIVE, solver.telemetry.deriveNanos)
        stageTimings.record(FrameStage.ANATOMY_PATHS, solver.telemetry.pathsNanos)
        musclesSolved = true

        scene.select(selection)

        var refused = 0
        for (slot in 0 until frame.headCount) {
            if (frame.statuses[slot] != PathStatus.VALID.ordinal) refused++
        }
        muscleHeadsRefused = refused
        muscleInstancesDrawn = frame.renderable.count { it }

        val rebuild = scene.needsRebuild(layers.revision)
        if (selection.showsTubes) {
            val writeStart = System.nanoTime()
            muscleTriangles = mesh.write(frame, scene.geometry, scene.selection)
            tubeMesh?.let { tubes ->
                tubes.upload(scene.geometry, rebuild)
                tubes.setVisible(scene.geometry.indexCount > 0)
            }
            tubeSlotCleared = false
            scene.markBuilt(layers.revision)
            stageTimings.record(FrameStage.TUBE_WRITE_UPLOAD, System.nanoTime() - writeStart)
        } else if (!tubeSlotCleared) {
            // The path still feeds the skinned layer, but a hidden tube must not leave stale vertices
            // uploaded or hold a drawable that only a layer mask keeps invisible.
            val writeStart = System.nanoTime()
            scene.geometry.begin()
            tubeMesh?.setVisible(false)
            tubeMesh?.upload(scene.geometry, rebuild)
            tubeSlotCleared = true
            muscleTriangles = 0
            stageTimings.record(FrameStage.TUBE_WRITE_UPLOAD, System.nanoTime() - writeStart)
        } else {
            stageTimings.record(FrameStage.TUBE_WRITE_UPLOAD, 0L)
        }

        stageTimings.record(FrameStage.ANATOMY_SOLVE, solveNanos)
    }

    /**
     * Nothing is drawn this frame, so nothing may be left solved: the carrier goes back to
     * `NOT_PROCESSED`, the tube drawable is hidden before its stale vertices can reach a frame, and
     * the counters follow the geometry to zero. The next visible frame rebuilds because the toggle
     * that hid the layer also advanced the revision the scene frame is built against.
     */
    private fun skipMuscles(frame: AnatomyFrame, scene: CharacterSceneFrame, selection: MuscleSelection) {
        frame.clear()
        scene.select(selection)
        scene.geometry.begin()
        tubeMesh?.setVisible(false)
        muscleTriangles = 0
        muscleHeadsRefused = 0
        muscleInstancesDrawn = 0
        musclesSolved = false
        tubeSlotCleared = false
        stageTimings.record(FrameStage.ANATOMY_SOLVE, 0L)
        stageTimings.record(FrameStage.ANATOMY_DERIVE, 0L)
        stageTimings.record(FrameStage.ANATOMY_PATHS, 0L)
        stageTimings.record(FrameStage.TUBE_WRITE_UPLOAD, 0L)
    }

    private fun configureScene() {
        val sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.90f)
            .intensity(110_000f)
            .direction(-0.45f, -1.0f, -0.35f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // A constant sky term; PBR metals read as black without any indirect light.
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.05f, 0.06f, 0.08f))
            .intensity(30_000f)
            .build(engine)
        scene.indirectLight = indirectLight

        view.setShadowType(View.ShadowType.PCF)
        view.setAntiAliasing(View.AntiAliasing.FXAA)
        // A view with no scene draws nothing at all: the surface keeps its default black and even
        // the clear colour never lands.
        view.scene = scene
        view.camera = camera
        view.setPostProcessingEnabled(true)

        renderer.setClearOptions(Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.05, 0.06, 0.08, 1.0)
            clear = true
            discard = false
        })

        // The surface size is not known until the first resize, which re-frames for the aspect it
        // actually got; composing for a square placeholder keeps the first frame composed too.
        applyFraming(PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT)
    }

    /**
     * Frames the exercise for the surface it is drawn on: one view for the whole rep, re-derived only
     * when the exercise or the surface aspect changes. Both are inputs, never the current phase.
     */
    private fun applyFraming(width: Int, height: Int) {
        val view = framer.frame(poseBuilder, alternating, width, height)
        camera.setLensProjection(
            view.fovVerticalDegrees.toDouble(), width.toDouble() / height.toDouble(), 0.05, 100.0
        )
        camera.lookAt(
            view.eyeX.toDouble(), view.eyeY.toDouble(), view.eyeZ.toDouble(),
            view.targetX.toDouble(), view.targetY.toDouble(), view.targetZ.toDouble(),
            view.upX.toDouble(), view.upY.toDouble(), view.upZ.toDouble()
        )
    }

    fun attach(textureView: TextureView) {
        if (destroyed) return
        uiHelper.attachTo(textureView)
    }

    fun detach() {
        if (destroyed) return
        uiHelper.detach()
    }

    fun start() {
        if (destroyed || running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /** The authored layer's instances in solve order; empty until the anatomy is built. */
    val muscleInstances: List<MuscleInstanceId> get() = sceneFrame?.partition?.instances.orEmpty()

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        // The render thread drains before anything a queued frame could still reference is freed: the
        // one native crash this layer produced on hardware was in FEngine::loop during a session swap.
        engine.flushAndWait()
        // After the frame loop stops, so no solve is in flight: the lanes are threads, and a session
        // that leaves them parked holds four stacks per exercise switch.
        anatomySolver?.close()
        anatomySolver = null
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        // Before the asset and the provider that own this layer's material: the tube instance was made
        // by the provider and, on the fallback path, copied an instance the asset is about to free.
        tubeMesh?.destroy()
        tubeMesh = null
        // Before the provider that owns the asset's materials, and before the asset loader.
        muscleStage?.destroy()
        muscleStage = null
        asset?.let { loaded ->
            scene.removeEntities(loaded.entities)
            assetLoader?.destroyAsset(loaded)
        }
        assetLoader?.destroy()
        assetLoader = null
        // Materials outlive every instance that references them, so the provider goes last of the three.
        materialProvider?.destroyMaterials()
        materialProvider?.destroy()
        materialProvider = null
        asset = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        indirectLight = null
        engine.destroyRenderer(renderer)
        // The view is bound to the scene, so it is torn down before the scene it points at.
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroy()
    }

    fun diagnostics(): CharacterDiagnostics {
        lastDiagnostics?.takeIf { destroyed }?.let { return it }
        val selectionGate = layers.selection
        val diagnostics = CharacterDiagnostics(
            requested = request,
            activeBackend = backend,
            attempts = selection.attempts,
            capabilities = capabilities,
            fallbackReason = selection.fallbackReason,
            joints = jointCount,
            morphTargets = morphTargetsByRenderable.values.maxOrNull() ?: 0,
            morphology = morphology.id,
            frames = frames,
            fps = smoothedFps,
            frameTimeP95Ms = frameTimeP95Nanos() / 1_000_000f,
            hasSurface = swapChain != null,
            running = running,
            error = listOfNotNull(error, anatomyError).joinToString("; ").ifEmpty { null },
            muscleHeads = anatomyFrame?.headCount ?: 0,
            muscleHeadsRefused = muscleHeadsRefused,
            muscleInstancesDrawn = muscleInstancesDrawn,
            muscleTriangles = muscleTriangles,
            musclesSolved = musclesSolved,
            muscleReadings = if (musclesSolved) muscleReadings() else emptyList(),
            anatomyPassCounts = anatomyPassCounts(),
            anatomyPassCap = anatomySolver?.telemetry?.passCap ?: 0,
            muscleAttributes = tubeMesh?.materialAttributes.orEmpty(),
            muscleMaterialError = tubeError,
            muscleMaterialCandidate = tubeMesh?.materialCandidate ?: -1,
            muscleTubeBuilds = tubeMesh?.primitiveBuilds ?: 0,
            muscleAssetSections = muscleStage?.renderableCount ?: 0,
            muscleAssetDrawn = muscleStage?.visibleCount ?: 0,
            muscleAssetUploads = muscleStage?.skinUploads ?: 0,
            muscleAssetError = muscleError,
            muscleAssetSelected = muscleStage?.state?.selected ?: MuscleSectionState.NO_SECTION,
            muscleHighlightError = muscleStage?.highlightError,
            muscleHighlightApplied = muscleStage?.highlightApplied == true,
            muscleHiddenInstances = selectionGate.hidden.map { "${it.muscleId}:${it.side.name}" },
            muscleSelectedInstance = selectionGate.selected?.let { "${it.muscleId}:${it.side.name}" },
            frameStages = stageTimings.readings()
        )
        lastDiagnostics = diagnostics
        return diagnostics
    }

    /**
     * Head-solves by the pass that reported them, index `0` for a refusal before the loop ran.
     * Cumulative for the session: a single frame of the bounded search is too noisy to read.
     */
    private fun anatomyPassCounts(): List<Int> {
        val telemetry = anatomySolver?.telemetry ?: return emptyList()
        val counts = ArrayList<Int>(telemetry.passCap + 1)
        for (pass in 0..telemetry.passCap) counts += telemetry.passCount(pass)
        return counts
    }

    private fun muscleReadings(): List<MuscleHeadReading> {
        val frame = anatomyFrame ?: return emptyList()
        val readings = ArrayList<MuscleHeadReading>(frame.headCount)
        for (slot in 0 until frame.headCount) {
            val state = frame.muscleStates[slot]
            val label = state.headLabel.ifBlank { "head" + state.headIndex }
            readings += MuscleHeadReading(
                head = state.id + " " + state.side.name + " " + label,
                relativePathLengthChange = state.relativePathLengthChange,
                drawn = state.renderable
            )
        }
        return readings
    }

    private fun render(frameTimeNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        val started = System.nanoTime()
        val mapper = retargeter ?: return

        val poseStart = System.nanoTime()
        val pose = frameProducer.next()
        stageTimings.record(FrameStage.POSE_BUILD, System.nanoTime() - poseStart)

        solveMuscles(pose)

        val retargetStart = System.nanoTime()
        mapper.boneMatrices(pose, unitsPerMetre, out = boneMatrices)
        boneBuffer.clear()
        boneBuffer.put(boneMatrices)
        boneBuffer.rewind()
        skinnedRenderables.forEach { entity ->
            val renderable = renderableManager.getInstance(entity)
            if (renderable != 0) {
                boneBuffer.rewind()
                renderableManager.setBonesAsMatrices(renderable, boneBuffer, jointCount, 0)
            }
        }
        stageTimings.record(FrameStage.RETARGET_UPLOAD, System.nanoTime() - retargetStart)

        uploadMuscleLayer(layers.selection)

        // beginFrame blocks until the swapchain is free, so this window is the vsync wait and not our work.
        val beginStart = System.nanoTime()
        val began = renderer.beginFrame(chain, frameTimeNanos)
        stageTimings.record(FrameStage.BEGIN_FRAME_WAIT, System.nanoTime() - beginStart)
        if (began) {
            val renderStart = System.nanoTime()
            renderer.render(view)
            renderer.endFrame()
            stageTimings.record(FrameStage.RENDER_END_FRAME, System.nanoTime() - renderStart)
            recordFrameTime(System.nanoTime() - started)
            frames++
        } else {
            // A refused frame draws nothing, and a zero keeps its sample count aligned with the others.
            stageTimings.record(FrameStage.RENDER_END_FRAME, 0L)
        }
    }

    /**
     * Uploads the skinned layer's palette and pushes this frame's per-section decisions. The solved
     * frame is read here and never written: the skinner owns the matrices, the anatomy owns the paths.
     */
    private fun uploadMuscleLayer(selection: MuscleSelection) {
        val stage = muscleStage ?: return
        val frame = anatomyFrame
        val scene = sceneFrame
        if (frame == null || scene == null || !selection.showsMeshes || !musclesSolved) {
            stage.hideAll()
            return
        }
        val start = System.nanoTime()
        if (!stage.skin(frame, boneMatrices)) return
        val partition = scene.partition
        for (section in 0 until partition.size) {
            // A head the solve refused leaves its instance hidden rather than drawing its bind pose
            // over the body; the mask returns on the frame that head solves again.
            stage.setShown(section, selection.showsSection(partition, section, frame))
        }
        stage.select(selection.selected?.let(partition::indexOf) ?: MuscleSectionState.NO_SECTION)
        stageTimings.record(FrameStage.MUSCLE_SKIN, System.nanoTime() - start)
    }

    private fun recordFrameTime(nanos: Long) {
        frameTimesNanos[frameTimeCursor] = nanos
        frameTimeCursor = (frameTimeCursor + 1) % frameTimesNanos.size
        if (frameTimeSamples < frameTimesNanos.size) frameTimeSamples++
    }

    private fun frameTimeP95Nanos(): Float {
        if (frameTimeSamples == 0) return 0f
        val sorted = frameTimesNanos.copyOf(frameTimeSamples).apply { sort() }
        val index = ((frameTimeSamples - 1) * 0.95f).toInt().coerceIn(0, frameTimeSamples - 1)
        return sorted[index].toFloat()
    }

    private companion object {
        const val FRAME_SAMPLE_COUNT = 120

        /**
         * Four lanes: the frame thread and three workers. The set is deliberately smaller than the
         * cores a handset publishes, because the frame also needs one for the render thread's own work
         * and the tubes' upload, and the solve splits into whole heads rather than finer tasks.
         */
        const val SOLVE_LANES = 4

        /** The square surface the camera is composed for until the first size arrives. */
        const val PLACEHOLDER_WIDTH = 1
        const val PLACEHOLDER_HEIGHT = 1
    }
}
