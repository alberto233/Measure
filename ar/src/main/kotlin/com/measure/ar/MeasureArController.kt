package com.measure.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.measure.ar.render.BackgroundRenderer
import com.measure.ar.render.GlColour
import com.measure.ar.render.MarkerRenderer
import com.measure.ar.render.PlaneRenderer
import com.measure.ar.render.RibbonRenderer
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.CaptureOutcome
import com.measure.core.geometry.capture.CaptureRejection
import com.measure.core.geometry.capture.FloorSelector
import com.measure.core.geometry.capture.PlaneObservation
import com.measure.core.geometry.capture.PointAggregator
import com.measure.core.geometry.capture.PointSample
import com.measure.core.geometry.capture.RangeAdvice
import com.measure.core.geometry.capture.RangeGate
import com.measure.core.geometry.capture.TrackingAssessor
import com.measure.core.geometry.capture.TrackingIssue
import com.measure.core.geometry.capture.TrackingQuality
import com.measure.core.geometry.capture.TrackingStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Owns the ARCore session and the render loop, and is the only class in the app that
 * talks to ARCore at all.
 *
 * **Session lifecycle is treated as a feature, not plumbing.** It is the most crash-prone
 * part of an ARCore app and the place competing apps visibly fail (docs/PRODUCT_PLAN.md
 * §5). Every entry point here assumes the session may be absent, paused, or have had its
 * camera taken by another app, and none of those is allowed to throw past this class.
 *
 * Threading: [resume], [pause] and [close] are called from the main thread; the render
 * callbacks run on the GL thread. State crosses between them through a [MutableStateFlow]
 * and a small number of volatile fields, which is enough because the GL thread is the
 * only writer of frame state and the main thread is the only writer of scene content.
 */
class MeasureArController(private val context: Context) : GLSurfaceView.Renderer {

    private val _state = MutableStateFlow(ArUiState())
    val state: StateFlow<ArUiState> = _state.asStateFlow()

    /** Results of sample bursts. Replay 0: a rejection is news once, not on every resume. */
    private val _outcomes = MutableSharedFlow<CaptureOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<CaptureOutcome> = _outcomes.asSharedFlow()

    @Volatile
    private var scene = ArScene()

    /**
     * Guards every use of [session] against its own lifecycle.
     *
     * Closing an ARCore session while the render thread is inside `update()` is a native
     * crash, and it is reachable whenever a resume fails midway. The GL thread holds this
     * for at most one frame, and the main thread only contends with it during resume,
     * pause and close, so the cost is a few milliseconds at exactly the moments where
     * correctness matters more than latency.
     */
    private val sessionLock = Any()

    private var session: Session? = null
    private var sessionConfig: Config? = null
    private var installRequested = false

    private val background = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val markerRenderer = MarkerRenderer()
    private val ribbonRenderer = RibbonRenderer()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val worldPoint = FloatArray(4)
    private val clipPoint = FloatArray(4)

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayRotation = 0

    /** Set from the UI thread on tap; consumed once by the GL thread. */
    private val captureRequested = AtomicBoolean(false)

    /** GL-thread-only state. The main thread asks for cancellation via [burstCancelled]. */
    private var burst: MutableList<PointSample>? = null
    private var burstFramesRemaining = 0
    private val burstCancelled = AtomicBoolean(false)

    // --- lifecycle ------------------------------------------------------------------

    /**
     * Bring the session up. Safe to call repeatedly; it is idempotent once running.
     *
     * Needs an [Activity] rather than a context because ARCore's install flow is an
     * activity result. Returns nothing: the outcome is a [phase][ArPhase] on [state],
     * because every branch here is something the UI has to render anyway.
     */
    fun resume(activity: Activity) = synchronized(sessionLock) {
        if (!hasCameraPermission()) {
            _state.update { it.copy(phase = ArPhase.NEEDS_CAMERA_PERMISSION) }
            return
        }

        if (session == null && !createSession(activity)) return

        try {
            session?.resume()
        } catch (error: CameraNotAvailableException) {
            // Another app has the camera, or the system took it during a transition.
            // Dropping the session entirely is the reliable recovery: a session that
            // failed to resume cannot be resumed again.
            closeSession()
            fail("Camera unavailable", "Another app may be using it. Close it and try again.")
            return
        } catch (error: Throwable) {
            closeSession()
            fail("Could not start the AR session", error.readableMessage())
            return
        }

        _state.update { it.copy(phase = ArPhase.RUNNING, failure = null) }
    }

    /** Returns false when the phase was set to something the caller should render instead. */
    private fun createSession(activity: Activity): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    // requestInstall may only ask once per user gesture; the next resume
                    // after the install flow returns must not ask again.
                    installRequested = true
                    _state.update { it.copy(phase = ArPhase.NEEDS_ARCORE_INSTALL) }
                    return false
                }

                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val created = Session(activity)
            val depthSupported = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val config = configure(created, depthSupported)

            // Whether the torch can be driven at all is a property of the camera config
            // ARCore chose, not just of the hardware, so it has to be asked rather than
            // assumed. isSupported takes the whole config: a flash mode that clashes
            // with something else we set would fail here rather than at configure time.
            val torchSupported = created.isSupported(
                Config(created).apply {
                    depthMode = config.depthMode
                    planeFindingMode = config.planeFindingMode
                    instantPlacementMode = config.instantPlacementMode
                    focusMode = config.focusMode
                    lightEstimationMode = config.lightEstimationMode
                    updateMode = config.updateMode
                    flashMode = Config.FlashMode.TORCH
                },
            )

            created.configure(config)
            session = created
            sessionConfig = config
            _state.update { it.copy(depthEnabled = depthSupported, torchSupported = torchSupported) }
            return true
        } catch (error: UnavailableUserDeclinedInstallationException) {
            fail("ARCore is needed to measure", "Install Google Play Services for AR to continue.")
        } catch (error: UnavailableArcoreNotInstalledException) {
            fail("ARCore is not installed", "Install Google Play Services for AR to continue.")
        } catch (error: UnavailableApkTooOldException) {
            fail("ARCore needs updating", "Update Google Play Services for AR to continue.")
        } catch (error: UnavailableSdkTooOldException) {
            fail("This build of Measure is too old", "Update the app.", recoverable = false)
        } catch (error: UnavailableDeviceNotCompatibleException) {
            fail("This device cannot run AR", "Measuring by camera is not available here.", recoverable = false)
        } catch (error: Throwable) {
            fail("Could not start AR", error.readableMessage())
        }
        return false
    }

    private fun configure(session: Session, depthSupported: Boolean) = Config(session).apply {
        // Depth improves hit tests on surfaces ARCore has not yet fitted a plane to,
        // which is most of the room for the first several seconds.
        depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED

        // Walls matter as much as floors: vertical planes are what plumb-mode heights
        // and future wall-face capture (docs/ACCURACY.md M10) are fitted against.
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

        // Off deliberately. Instant placement guesses at scale and refines it later,
        // which is a fine trade for placing a virtual sofa and a bad one for a
        // measurement the user will read off the screen in the next half second.
        instantPlacementMode = Config.InstantPlacementMode.DISABLED

        focusMode = Config.FocusMode.AUTO
        lightEstimationMode = Config.LightEstimationMode.DISABLED

        // The GL thread drives the loop, so blocking on the newest camera image keeps
        // the reticle in step with what the user sees rather than a frame behind.
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

        // Off at the start. A torch that switches itself on is startling in a lit room
        // and expensive everywhere; the user asks for it when the scene needs it.
        flashMode = Config.FlashMode.OFF
    }

    fun pause() = synchronized(sessionLock) {
        try {
            session?.pause()
        } catch (error: Throwable) {
            Log.w(TAG, "pausing the session failed", error)
        }
        // The burst itself belongs to the GL thread; all the main thread may do is ask
        // for it to be abandoned on the next frame.
        burstCancelled.set(true)
        captureRequested.set(false)
        // The camera is released on pause, so the torch goes out whatever we think.
        sessionConfig?.flashMode = Config.FlashMode.OFF
        _state.update {
            if (it.phase == ArPhase.RUNNING) {
                it.copy(phase = ArPhase.PAUSED, target = null, preview = null, sampling = null, torchOn = false)
            } else {
                it.copy(torchOn = false)
            }
        }
    }

    fun close() = synchronized(sessionLock) { closeSession() }

    private fun closeSession() {
        try {
            session?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "closing the session failed", error)
        }
        session = null
    }

    private fun fail(message: String, detail: String?, recoverable: Boolean = true) {
        _state.update {
            it.copy(
                phase = ArPhase.FAILED,
                failure = ArFailure(message, detail, recoverable),
                target = null,
                preview = null,
                sampling = null,
            )
        }
    }

    // --- input from the UI ----------------------------------------------------------

    fun updateScene(scene: ArScene) {
        this.scene = scene
    }

    /** Ask for a sample burst. Ignored unless the session is in a state to honour it. */
    fun requestCapture() {
        val state = _state.value
        // Room capture is gated harder: without an established floor there is nothing
        // to project the corner onto. Checked here as well as in the UI, because the
        // button is not the only thing that can ask.
        val allowed = when (scene.captureMode) {
            CaptureMode.ROOM -> state.canCaptureCorner
            CaptureMode.DISTANCE -> state.canCapture
        }
        if (!allowed) return
        captureRequested.set(true)
    }

    /**
     * Turn the camera torch on or off.
     *
     * Driven through ARCore's own config rather than `CameraManager.setTorchMode`,
     * because ARCore holds the camera and a second client asking the framework for the
     * same device is a fight we would lose intermittently and inexplicably. Reconfiguring
     * a running session is cheap and does not disturb tracking.
     */
    fun setTorch(enabled: Boolean) = synchronized(sessionLock) {
        val session = session ?: return
        val config = sessionConfig ?: return
        if (!_state.value.torchSupported) return

        config.flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
        try {
            session.configure(config)
            _state.update { it.copy(torchOn = enabled) }
        } catch (error: Throwable) {
            // Some devices refuse the torch while the camera is thermally throttled.
            // Losing the light is not worth losing the session over.
            Log.w(TAG, "could not change the torch", error)
            config.flashMode = Config.FlashMode.OFF
            _state.update { it.copy(torchOn = false) }
        }
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
        applyDisplayGeometry()
    }

    private fun applyDisplayGeometry() {
        if (viewportWidth == 0 || viewportHeight == 0) return
        synchronized(sessionLock) {
            try {
                session?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            } catch (error: Throwable) {
                Log.w(TAG, "setDisplayGeometry failed", error)
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // --- GL -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // Shader compilation is the one part of startup that depends on the specific GPU
        // driver, so it is the part most likely to fail on a device we have never seen.
        // Uncaught, it would propagate out of GLSurfaceView's render thread and take the
        // process with it — the app would simply vanish, with nothing on screen to
        // explain why. Reported instead, it becomes a message the user can read to us.
        try {
            background.createOnGlThread()
            planeRenderer.createOnGlThread()
            markerRenderer.createOnGlThread()
            ribbonRenderer.createOnGlThread()
        } catch (error: Throwable) {
            Log.e(TAG, "GL initialisation failed", error)
            fail("Graphics setup failed", error.readableMessage(), recoverable = false)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        applyDisplayGeometry()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        synchronized(sessionLock) {
            val session = session ?: return
            if (_state.value.phase != ArPhase.RUNNING) return

            val frame = try {
                session.setCameraTextureName(background.textureId)
                session.update()
            } catch (error: CameraNotAvailableException) {
                fail("Camera unavailable", "Another app may be using it.")
                return
            } catch (error: Throwable) {
                // A single bad frame is not worth killing the session over. Skip it.
                Log.w(TAG, "frame update failed", error)
                return
            }

            try {
                renderFrame(session, frame)
            } catch (error: Throwable) {
                Log.e(TAG, "rendering failed", error)
            }
        }
    }

    private fun renderFrame(session: Session, frame: Frame) {
        val camera = frame.camera
        background.draw(frame)

        val tracking = assessTracking(session, camera, frame)
        if (camera.trackingState != TrackingState.TRACKING) {
            cancelBurst()
            publish(tracking, target = null, preview = null, anchors = emptyList())
            return
        }

        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE, FAR_PLANE)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)

        val cameraPose = camera.pose
        val cameraPosition = Vec3(
            cameraPose.tx().toDouble(),
            cameraPose.ty().toDouble(),
            cameraPose.tz().toDouble(),
        )

        val currentScene = scene
        val floor = findFloor(session)

        // Room corners come only from the floor plane itself, so the point is where the
        // line of sight meets the floor (docs/ACCURACY.md M1 and M2). Flattening some
        // other surface's hit onto the floor height was the earlier approach and it put
        // corners underneath whatever was piled in front of them.
        val hits = safeHitTest(frame)
        val roomCapture = currentScene.captureMode == CaptureMode.ROOM && floor?.isEstablished == true
        val hit = if (roomCapture) {
            HitRanking.bestOnFloor(hits, floor.height)
        } else {
            HitRanking.best(hits)
        }
        // Aiming at something that is not the floor during a room capture. The user needs
        // telling, because the reticle looks perfectly happy and the shutter does not.
        val offFloor = roomCapture && hit == null && HitRanking.best(hits) != null

        // The fixed end of the rubber-band line: the placed point in distance mode, the
        // last corner in room mode.
        val anchor = when (currentScene.captureMode) {
            CaptureMode.DISTANCE -> currentScene.pendingAnchor
            CaptureMode.ROOM -> if (currentScene.roomClosed) null else currentScene.roomCorners.lastOrNull()
        }

        // Level and plumb apply to a free-standing distance. A room corner is already
        // constrained, by the floor.
        val constrained = if (anchor != null && hit != null && currentScene.captureMode == CaptureMode.DISTANCE) {
            currentScene.mode.constrain(anchor, hit.position)
        } else {
            null
        }
        val movingEnd = constrained?.position ?: hit?.position.takeIf { anchor != null }

        collectBurstSample(hit, tracking.quality)

        drawScene(session, currentScene, cameraPosition, anchor, movingEnd, hit?.position)

        val preview = if (anchor != null && movingEnd != null) {
            MeasurementPreview(
                lengthMetres = anchor.distanceTo(movingEnd),
                correction = constrained?.correction ?: 0.0,
                correctionIsNotable = constrained?.isNotable ?: false,
            )
        } else {
            null
        }

        publish(
            tracking = tracking,
            target = hit?.let { ReticleTarget(it.position, it.range, it.source) },
            preview = preview,
            anchors = screenAnchors(currentScene, anchor, movingEnd),
            floor = floor,
            offFloor = offFloor,
        )
    }

    /**
     * Re-derives the dominant floor every frame rather than latching it.
     *
     * ARCore keeps refining planes as the user walks, and a floor latched from the first
     * two seconds of a session is latched from its worst two seconds. The selection is
     * cheap and stable — it is dominated by the largest surface, which does not flicker
     * once found — so recomputing costs nothing and tracks the improving estimate.
     */
    private fun findFloor(session: Session): FloorState? {
        val observations = try {
            session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
                .map { plane ->
                    PlaneObservation(
                        id = plane.hashCode().toLong(),
                        height = plane.centerPose.ty().toDouble(),
                        area = (plane.extentX * plane.extentZ).toDouble(),
                        isUpwardHorizontal = plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                        isSubsumed = plane.subsumedBy != null,
                    )
                }
        } catch (error: Throwable) {
            return null
        }
        return FloorSelector.select(observations)?.let(FloorState::from)
    }

    private fun assessTracking(session: Session, camera: Camera, frame: Frame): TrackingStatus {
        val planeCount = try {
            session.getAllTrackables(Plane::class.java)
                .count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        } catch (error: Throwable) {
            0
        }

        // The point cloud must be released or ARCore stops handing out new ones.
        val featureCount = try {
            frame.acquirePointCloud().use { it.points.remaining() / POINT_CLOUD_STRIDE }
        } catch (error: Throwable) {
            0
        }

        return TrackingAssessor.assess(
            isTracking = camera.trackingState == TrackingState.TRACKING,
            reportedIssue = camera.trackingFailureReason.toIssue(),
            trackedPlaneCount = planeCount,
            featurePointCount = featureCount,
        )
    }

    /** Hit tests the screen centre, which is where the reticle is drawn. */
    private fun safeHitTest(frame: Frame): List<com.google.ar.core.HitResult> {
        if (viewportWidth == 0 || viewportHeight == 0) return emptyList()
        return try {
            frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
        } catch (error: Throwable) {
            emptyList()
        }
    }

    // --- sampling -------------------------------------------------------------------

    /**
     * Multi-frame sampling, driven from the render loop — docs/ACCURACY.md M3.
     *
     * Running on the GL thread rather than in a coroutine is deliberate: this must sample
     * *frames*, and the GL thread is the only place that sees each one exactly once. A
     * coroutine polling the latest hit would sample the clock instead, double-counting on
     * a fast device and missing frames on a slow one.
     */
    private fun collectBurstSample(hit: RankedHit?, quality: TrackingQuality) {
        val config = scene.samplingConfig

        // A pause that landed mid-burst. Drop it silently: the user knows they left.
        if (burstCancelled.compareAndSet(true, false)) {
            burst = null
            captureRequested.set(false)
            return
        }

        if (burst == null) {
            if (!captureRequested.compareAndSet(true, false)) return
            burst = ArrayList(config.targetFrames)
            // A frame budget wider than the sample target, so a burst that keeps losing
            // its hit terminates instead of hanging until the user taps something else.
            burstFramesRemaining = config.targetFrames * FRAME_BUDGET_MULTIPLIER
        }

        val samples = burst ?: return
        burstFramesRemaining--

        if (hit != null) {
            samples += PointSample(hit.position, hit.range, hit.source, quality)
        }

        if (samples.size < config.targetFrames && burstFramesRemaining > 0) return

        burst = null
        // A second tap can land in the one-frame window before `sampling` is published
        // and the capture button disables itself. Clearing the request here stops that
        // stray tap from immediately starting another burst and placing a point the user
        // never asked for.
        captureRequested.set(false)
        _outcomes.tryEmit(PointAggregator.aggregate(samples, config))
    }

    /** GL thread only. Abandons an in-flight burst and tells the user why. */
    private fun cancelBurst() {
        burstCancelled.set(false)
        if (burst != null) {
            burst = null
            _outcomes.tryEmit(CaptureOutcome.Rejected(CaptureRejection.TRACKING_LOST))
        }
        captureRequested.set(false)
    }

    // --- drawing --------------------------------------------------------------------

    private fun drawScene(
        session: Session,
        scene: ArScene,
        cameraPosition: Vec3,
        anchor: Vec3?,
        previewEnd: Vec3?,
        rawTarget: Vec3?,
    ) {
        if (scene.showPlanes) {
            planeRenderer.draw(session.getAllTrackables(Plane::class.java), viewProjection)
        }

        val ribbonWidth = RibbonRenderer.widthFactorFor(projectionMatrix, viewportHeight, LINE_WIDTH_PX)

        val committed = when (scene.captureMode) {
            CaptureMode.DISTANCE -> scene.segments.map { it.from to it.to }
            CaptureMode.ROOM -> scene.roomCorners.zipWithNext() +
                // The closing wall only exists once the loop is shut.
                if (scene.roomClosed && scene.roomCorners.size >= 3) {
                    listOf(scene.roomCorners.last() to scene.roomCorners.first())
                } else {
                    emptyList()
                }
        }
        ribbonRenderer.draw(committed, viewProjection, cameraPosition, MEASURED, ribbonWidth)

        // While the loop is still open, show where it would close. Seeing the room shut
        // itself is what tells the user they have walked far enough.
        if (scene.captureMode == CaptureMode.ROOM && !scene.roomClosed && scene.roomCorners.size >= 2) {
            val start = scene.roomCorners.first()
            val end = previewEnd ?: scene.roomCorners.last()
            ribbonRenderer.draw(
                listOf(end to start),
                viewProjection,
                cameraPosition,
                CLOSING,
                ribbonWidth * 0.6f,
            )
        }

        if (anchor != null && previewEnd != null) {
            ribbonRenderer.draw(
                listOf(anchor to previewEnd),
                viewProjection,
                cameraPosition,
                PENDING,
                ribbonWidth,
            )
        }

        val endpoints = when (scene.captureMode) {
            CaptureMode.DISTANCE -> scene.segments.flatMap { listOf(it.from, it.to) }
            CaptureMode.ROOM -> scene.roomCorners
        }
        markerRenderer.draw(endpoints, viewProjection, MEASURED, MARKER_SIZE_PX)

        // The first corner is the one the user has to come back to, so it is marked out.
        if (scene.captureMode == CaptureMode.ROOM && !scene.roomClosed) {
            scene.roomCorners.firstOrNull()?.let {
                markerRenderer.draw(listOf(it), viewProjection, START_CORNER, MARKER_SIZE_PX * 1.35f)
            }
        }

        if (anchor != null) {
            markerRenderer.draw(listOf(anchor), viewProjection, PENDING, MARKER_SIZE_PX)
        }

        // The trajectory guide: the current wall's line, continued well past the aim
        // point. When the corner you are heading for is behind a wardrobe you cannot aim
        // at it, but you can line this up with the skirting board you *can* see and know
        // the direction is right. CamToPlan has the same idea.
        if (anchor != null && previewEnd != null) {
            val along = previewEnd - anchor
            if (along.lengthSquared > GUIDE_MIN_LENGTH_SQUARED) {
                val extended = previewEnd + along.normalised() * GUIDE_EXTENSION_METRES
                ribbonRenderer.draw(
                    listOf(previewEnd to extended),
                    viewProjection,
                    cameraPosition,
                    GUIDE,
                    ribbonWidth * 0.5f,
                )
            }
        }

        // The surface dot under the reticle. Drawn in 3D rather than as part of the 2D
        // reticle so it visibly lies on the surface and tracks with it.
        val targetDot = previewEnd ?: rawTarget
        if (targetDot != null) {
            markerRenderer.draw(listOf(targetDot), viewProjection, RETICLE, RETICLE_DOT_SIZE_PX)
        }
    }

    private fun screenAnchors(scene: ArScene, anchor: Vec3?, previewEnd: Vec3?): List<ScreenAnchor> {
        val anchors = ArrayList<ScreenAnchor>(scene.segments.size + scene.roomCorners.size + 1)

        if (scene.captureMode == CaptureMode.ROOM) {
            // Ids are wall indices, which the view model resolves against the same
            // corner list it pushed, so the two cannot disagree.
            val walls = scene.roomCorners.zipWithNext() +
                if (scene.roomClosed && scene.roomCorners.size >= 3) {
                    listOf(scene.roomCorners.last() to scene.roomCorners.first())
                } else {
                    emptyList()
                }
            walls.forEachIndexed { index, (from, to) ->
                project(midpoint(from, to))?.let { anchors += ScreenAnchor(index.toLong(), it[0], it[1]) }
            }
            if (anchor != null && previewEnd != null) {
                project(midpoint(anchor, previewEnd))?.let {
                    anchors += ScreenAnchor(ArScene.PREVIEW_ANCHOR_ID, it[0], it[1])
                }
            }
            return anchors
        }

        scene.segments.forEach { segment ->
            val midpoint = midpoint(segment.from, segment.to)
            project(midpoint)?.let { anchors += ScreenAnchor(segment.id, it[0], it[1]) }
        }
        if (anchor != null && previewEnd != null) {
            project(midpoint(anchor, previewEnd))?.let {
                anchors += ScreenAnchor(ArScene.PREVIEW_ANCHOR_ID, it[0], it[1])
            }
        }
        return anchors
    }

    private fun midpoint(a: Vec3, b: Vec3) =
        Vec3((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0)

    /** World to pixels, or null when the point is behind the camera. */
    private fun project(point: Vec3): FloatArray? {
        worldPoint[0] = point.x.toFloat()
        worldPoint[1] = point.y.toFloat()
        worldPoint[2] = point.z.toFloat()
        worldPoint[3] = 1f
        Matrix.multiplyMV(clipPoint, 0, viewProjection, 0, worldPoint, 0)

        val w = clipPoint[3]
        if (w <= 0f) return null

        val x = (clipPoint[0] / w * 0.5f + 0.5f) * viewportWidth
        // Clip space has +y up; Android screen coordinates have +y down.
        val y = (1f - (clipPoint[1] / w * 0.5f + 0.5f)) * viewportHeight
        // Rounded to whole pixels so a stationary phone stops republishing state.
        return floatArrayOf(x.roundToInt().toFloat(), y.roundToInt().toFloat())
    }

    // --- publishing -----------------------------------------------------------------

    private fun publish(
        tracking: TrackingStatus,
        target: ReticleTarget?,
        preview: MeasurementPreview?,
        anchors: List<ScreenAnchor>,
        floor: FloorState? = null,
        offFloor: Boolean = false,
    ) {
        val quantisedTarget = target?.copy(range = quantise(target.range, RANGE_STEP))
        val quantisedPreview = preview?.copy(
            lengthMetres = quantise(preview.lengthMetres, LENGTH_STEP),
            correction = quantise(preview.correction, LENGTH_STEP),
        )
        val sampling = burst?.let { SamplingProgress(it.size, scene.samplingConfig.targetFrames) }

        // StateFlow drops equal values, so a still phone costs no recompositions at all.
        _state.update {
            it.copy(
                tracking = tracking,
                target = quantisedTarget,
                rangeAdvice = target?.let { hit -> RangeGate.advise(hit.range) } ?: RangeAdvice.IDEAL,
                sampling = sampling,
                preview = quantisedPreview,
                anchors = anchors,
                // Quantised like everything else, so a settled floor stops republishing.
                floor = floor?.copy(
                    height = quantise(floor.height, RANGE_STEP),
                    area = quantise(floor.area, AREA_STEP),
                ),
                offFloor = offFloor,
            )
        }
    }

    private fun quantise(value: Double, step: Double): Double = (value / step).roundToInt() * step

    private fun Throwable.readableMessage(): String =
        message ?: this::class.java.simpleName

    // The else branch is redundant today and deliberately kept: ARCore is a Play
    // Services module that updates independently of this app, so a reason we have never
    // compiled against can appear at runtime. Without it that would be an exception
    // thrown from the render loop.
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun TrackingFailureReason.toIssue(): TrackingIssue = when (this) {
        TrackingFailureReason.NONE -> TrackingIssue.NONE
        TrackingFailureReason.BAD_STATE -> TrackingIssue.UNKNOWN
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingIssue.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingIssue.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingIssue.CAMERA_UNAVAILABLE
        else -> TrackingIssue.UNKNOWN
    }

    private companion object {
        const val TAG = "MeasureAr"

        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 100f

        /** ARCore's point cloud is packed as (x, y, z, confidence). */
        const val POINT_CLOUD_STRIDE = 4

        /** How many frames a burst may run for, as a multiple of the sample target. */
        const val FRAME_BUDGET_MULTIPLIER = 3

        const val MARKER_SIZE_PX = 34f
        const val RETICLE_DOT_SIZE_PX = 22f
        const val LINE_WIDTH_PX = 5f

        /** Publication granularity: half a centimetre of range, one millimetre of length. */
        const val RANGE_STEP = 0.005
        const val LENGTH_STEP = 0.001
        const val AREA_STEP = 0.25

        val MEASURED = GlColour.of(0xFFFFFF, 0.95f)
        val PENDING = GlColour.of(0x2ED3B7, 0.95f)
        val RETICLE = GlColour.of(0xFFD166, 0.9f)
        val CLOSING = GlColour.of(0xFFD166, 0.5f)
        val GUIDE = GlColour.of(0x2ED3B7, 0.35f)

        /** How far past the aim point the trajectory guide continues, in metres. */
        const val GUIDE_EXTENSION_METRES = 4.0

        /** Below this the segment has no reliable direction to extend. */
        const val GUIDE_MIN_LENGTH_SQUARED = 0.04
        val START_CORNER = GlColour.of(0xFF6B6B, 0.95f)
    }
}
