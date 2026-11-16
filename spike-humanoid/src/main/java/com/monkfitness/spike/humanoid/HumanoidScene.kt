package com.monkfitness.spike.humanoid

import android.content.Context
import android.os.Build
import android.view.Choreographer
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The smallest Filament host that can hold a character: one engine, one view, one directional light
 * with a shadow map, one glTF asset, and a frame loop that pushes bone matrices from the engine's
 * pose every frame.
 *
 * Everything the app would normally get from a scene framework is explicit here on purpose — a
 * spike should show which lifecycle events a character view actually needs.
 */
class HumanoidScene(private val context: Context) {

    private val engine: Engine = run {
        Filament.init()
        Engine.create()
    }
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val renderableManager = engine.renderableManager
    private val transformManager = engine.transformManager

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val choreographer = Choreographer.getInstance()

    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null
    private var instance: FilamentInstance? = null
    private var indirectLight: IndirectLight? = null

    private var skinnedRenderables: IntArray = IntArray(0)
    private var boneMatrices = FloatArray(0)
    private var boneBuffer: java.nio.FloatBuffer = java.nio.ByteBuffer
        .allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var jointCount = 0

    private var rig: GltfRig? = null
    private var retargeter: PoseRetargeter? = null
    private var restPelvisY = 0f
    private var unitScale = 1f

    private var morphTargetCount = 0
    private var morphWeights = FloatArray(0)

    val motion = MonkMotionDriver()

    var running: Boolean = false
        private set
    private var attached = false
    private var lastFrameNanos = 0L
    private var smoothedFps = 0f

    val status: String
        get() = buildString {
            append(if (running) "rendering" else "paused")
            append(" · ").append(if (swapChain != null) "surface ok" else "no surface")
            append(" · ").append(jointCount).append(" bones")
            if (morphTargetCount > 0) append(" · ").append(morphTargetCount).append(" morphs")
            append(" · ").append(smoothedFps.toInt()).append(" fps")
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val delta = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (delta > 0) {
                val fps = 1_000_000_000f / delta
                smoothedFps = if (smoothedFps == 0f) fps else smoothedFps * 0.9f + fps * 0.1f
            }
            render(frameTimeNanos, delta)
            choreographer.postFrameCallback(this)
        }
    }

    private val renderCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: android.view.Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            swapChain = null
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

    // ------------------------------------------------------------------ scene

    private fun configureScene() {
        val sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.90f)
            .intensity(110_000f)
            .direction(-0.45f, -1.0f, -0.35f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // A constant sky term: without any indirect light, PBR metallic-roughness materials read as
        // black wherever the sun does not hit them.
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.05f, 0.06f, 0.08f))
            .intensity(30_000f)
            .build(engine)
        scene.indirectLight = indirectLight

        view.setShadowType(View.ShadowType.PCF)
        view.setAntiAliasing(View.AntiAliasing.FXAA)
        view.camera = camera
        view.setPostProcessingEnabled(true)

        val clearOptions = Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.05, 0.06, 0.08, 1.0)
            clear = true
            discard = false
        }
        renderer.setClearOptions(clearOptions)

        camera.setLensProjection(45.0, 1.0, 0.05, 100.0)
        camera.lookAt(0.0, 1.05, 3.4, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
    }

    private fun updateCameraProjection(width: Int, height: Int) {
        if (height == 0) return
        camera.setLensProjection(45.0, width.toDouble() / height, 0.05, 100.0)
    }

    // ------------------------------------------------------------------ asset

    fun loadCharacter(assetName: String = "humanoid_spike.glb") {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()

        val loader = AssetLoader(
            engine,
            UbershaderProvider(engine),
            EntityManager.get()
        )
        assetLoader = loader
        val loaded = loader.createAsset(buffer) ?: error("gltfio could not load $assetName")
        val instance = loaded.instance
        asset = loaded
        this.instance = instance

        val resources = ResourceLoader(engine)
        try {
            resources.loadResources(loaded)
        } finally {
            resources.destroy()
        }

        scene.addEntities(loaded.entities)

        val gltf = GltfRig.parse(bytes)
        rig = gltf
        val boneByJoint = MonkJointResolver.resolve(gltf)
        val mapper = PoseRetargeter(gltf, boneByJoint)
        retargeter = mapper

        jointCount = if (instance.skinCount > 0) instance.getJointCountAt(0) else 0
        boneMatrices = FloatArray(jointCount * 16)
        boneBuffer = ByteBuffer.allocateDirect(jointCount * 16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        restPelvisY = MonkJointResolver.restPelvisHeight(motion.definition)

        val groundEntities = loaded.getEntitiesByName("Ground").toSet()
        // gltfio does not expose a skin's target renderables, so the ground (the only other mesh in
        // this asset) is excluded by name. A production mapper should read the glTF node's `skin`
        // reference from the parsed rig instead of relying on a name.
        skinnedRenderables = loaded.renderableEntities.filter { it !in groundEntities }.toIntArray()

        morphTargetCount = skinnedRenderables.maxOfOrNull { entity ->
            loaded.getMorphTargetNames(entity)?.size ?: 0
        } ?: 0
        if (morphTargetCount > 0) morphWeights = FloatArray(morphTargetCount)

        motion.onPoseProduced = { pose -> applyPose(pose) }
        motion.prepare()
        applyMorphPreset(morphWeightsPrimary)
    }

    private val morphWeightsPrimary = floatArrayOf(0f, 0f, 0f, 0f, 0f)

    fun morphPreset(name: String): FloatArray = when (name) {
        "slim" -> floatArrayOf(0.35f, -0.25f, -0.55f, -0.45f, 0.0f)
        "heavy" -> floatArrayOf(0.15f, 0.85f, 1.0f, 0.8f, 0.0f)
        "muscular" -> floatArrayOf(1.0f, 0.7f, -0.35f, 0.9f, 0.0f)
        else -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
    }

    fun applyMorphPreset(weights: FloatArray) {
        if (morphTargetCount == 0) return
        val target = morphWeights
        for (i in 0 until minOf(target.size, weights.size)) target[i] = weights[i]
        skinnedRenderables.forEach { entity ->
            val renderable = renderableManager.getInstance(entity)
            if (renderable != 0) renderableManager.setMorphWeights(renderable, target, 0)
        }
    }

    private fun applyPose(pose: com.monkfitness.app.animation.SkeletonPose) {
        val mapper = retargeter ?: return
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
    }

    fun calibrateScale() {
        val pose = motion.firstFrame()
        val head = pose.getJoint(com.monkfitness.app.animation.Joint.HEAD_POS).y
        val foot = minOf(
            pose.getJoint(com.monkfitness.app.animation.Joint.TOE_F).y,
            pose.getJoint(com.monkfitness.app.animation.Joint.TOE_B).y
        )
        val sourceHeight = (head - foot).coerceAtLeast(1f)
        val targetHeight = retargeter?.restHeight() ?: 1.8f
        unitScale = targetHeight / sourceHeight
    }

    // ------------------------------------------------------------------ surface + loop

    fun attach(textureView: TextureView) {
        if (attached) return
        attached = true
        uiHelper.attachTo(textureView)
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        pause()
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        asset?.let { loaded ->
            scene.removeEntities(loaded.entities)
            assetLoader?.destroyAsset(loaded)
        }
        assetLoader?.destroy()
        indirectLight?.let { engine.destroyIndirectLight(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroy()
    }

    private fun render(frameTimeNanos: Long, deltaNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return

        motion.advance(deltaNanos)

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun deviceCapabilities(): String {
        val glEs = context.getSystemService(android.app.ActivityManager::class.java)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val gles = "${glEs shr 16}.${glEs and 0xffff}"
        if (Build.VERSION.SDK_INT < 29) return "GLES $gles · Vulkan: unknown (API < 29)"
        val pm = context.packageManager
        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
            return "GLES $gles · Vulkan: not advertised"
        }
        val level = context.packageManager
            .systemAvailableFeatures
            .firstOrNull { it.name == android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
        return "GLES $gles · Vulkan: level ${level?.version ?: 0}"
    }
}
