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
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** One live character and every Filament object it owns; [destroy] is the only teardown path. */
class CharacterSession(
    private val context: Context,
    private val engine: Engine,
    private val request: BackendRequest,
    private val selection: BackendSelection<Engine>,
    private val capabilities: GraphicsCapabilities,
    private val assetPath: String,
    private val poseSource: CharacterPoseSource,
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

    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null
    private var indirectLight: IndirectLight? = null

    private var skinnedRenderables: IntArray = IntArray(0)
    private var morphTargetsByRenderable: Map<Int, Int> = emptyMap()
    private var boneMatrices = FloatArray(0)
    private var boneBuffer: FloatBuffer = ByteBuffer.allocateDirect(16)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var jointCount = 0
    private var restPelvisY = 0f
    private var unitScale = 1f

    private var retargeter: PoseRetargeter? = null
    private var morphology = MorphologyPreset.NEUTRAL

    private var attached = false
    private var destroyed = false
    private var lastFrameNanos = 0L
    private var smoothedFps = 0f
    private var frames = 0L
    private val frameTimesNanos = LongArray(FRAME_SAMPLE_COUNT)
    private var frameTimeCursor = 0
    private var frameTimeSamples = 0
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
            updateCameraProjection(width, height)
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

            val loader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
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
            val mapper = PoseRetargeter(rig, boneByJoint)
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
            restPelvisY = MonkJointResolver.restPelvisHeight(definition)
            unitScale = CharacterCalibration.unitScale(definition, mapper.restHeight())

            applyMorphology(morphology)
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
        if (morphTargetsByRenderable.isEmpty()) return
        val source = preset.weights
        morphTargetsByRenderable.forEach { (entity, count) ->
            if (count <= 0) return@forEach
            val renderable = renderableManager.getInstance(entity)
            if (renderable == 0) return@forEach
            val weights = FloatArray(count)
            for (i in 0 until minOf(count, source.size)) weights[i] = source[i]
            renderableManager.setMorphWeights(renderable, weights, 0)
        }
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
        view.camera = camera
        view.setPostProcessingEnabled(true)

        renderer.setClearOptions(Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.05, 0.06, 0.08, 1.0)
            clear = true
            discard = false
        })

        camera.setLensProjection(45.0, 1.0, 0.05, 100.0)
        camera.lookAt(0.0, 1.05, 3.4, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
    }

    private fun updateCameraProjection(width: Int, height: Int) {
        if (height == 0) return
        camera.setLensProjection(45.0, width.toDouble() / height, 0.05, 100.0)
    }

    fun attach(textureView: TextureView) {
        if (attached || destroyed) return
        attached = true
        uiHelper.attachTo(textureView)
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

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        asset?.let { loaded ->
            scene.removeEntities(loaded.entities)
            assetLoader?.destroyAsset(loaded)
        }
        assetLoader?.destroy()
        assetLoader = null
        asset = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        indirectLight = null
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroy()
    }

    fun diagnostics(): CharacterDiagnostics {
        lastDiagnostics?.takeIf { destroyed }?.let { return it }
        return CharacterDiagnostics(
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
            error = error
        ).also { lastDiagnostics = it }
    }

    private fun render(frameTimeNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        val started = System.nanoTime()
        val mapper = retargeter ?: return

        val pose = pipeline.produceFrame(poseSource.pose()).pose
        mapper.boneMatrices(pose, restPelvisY, unitScale, out = boneMatrices)
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

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            recordFrameTime(System.nanoTime() - started)
            frames++
        }
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
    }
}
