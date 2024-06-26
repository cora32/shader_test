package io.iskopasi.shader_test.utils.camera_utils

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import androidx.opengl.EGLImageKHR
import io.iskopasi.shader_test.utils.e
import io.iskopasi.shader_test.utils.loadShader
import io.iskopasi.shader_test.utils.toOrientationTag
import java.nio.IntBuffer


const val PQ_STR = "PQ"
const val LINEAR_STR = "LINEAR"
const val HLG_STR = "HLG (Android 14 or above)"
const val HLG_WORKAROUND_STR = "HLG (Android 13)"
const val PQ_ID: Int = 0
const val LINEAR_ID: Int = 1
const val HLG_ID: Int = 2
const val HLG_WORKAROUND_ID: Int = 3

private val FULLSCREEN_QUAD = floatArrayOf(
    -1.0f, -1.0f,  // 0 bottom left
    1.0f, -1.0f,  // 1 bottom right
    -1.0f, 1.0f,  // 2 top left
    1.0f, 1.0f,  // 3 top right
)
private const val EGL_GL_COLORSPACE_KHR = 0x309D
private const val EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F
private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RX_EXT = 0x3341
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RY_EXT = 0x3342
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GX_EXT = 0x3343
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GY_EXT = 0x3344
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BX_EXT = 0x3345
private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BY_EXT = 0x3346
private const val EGL_SMPTE2086_WHITE_POINT_X_EXT = 0x3347
private const val EGL_SMPTE2086_WHITE_POINT_Y_EXT = 0x3348
private const val EGL_SMPTE2086_MAX_LUMINANCE_EXT = 0x3349
private const val EGL_SMPTE2086_MIN_LUMINANCE_EXT = 0x334A

fun idToStr(transferId: Int): String = when (transferId) {
    PQ_ID -> PQ_STR
    LINEAR_ID -> LINEAR_STR
    HLG_ID -> HLG_STR
    HLG_WORKAROUND_ID -> HLG_WORKAROUND_STR
    else -> throw RuntimeException("Unexpected transferId " + transferId)
}


class RenderHandler(
    looper: Looper,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val filterOn: Boolean,
    private val transfer: Int,
    private val dynamicRange: Long,
    private val encoder: EncoderWrapper,
    private val initialOrientation: Int,
    private val context: Context,
    private var currentGLSLFilename: String,
) : Handler(looper), SurfaceTexture.OnFrameAvailableListener {
    companion object {
        const val MSG_CREATE_RESOURCES = 0
        const val MSG_DESTROY_WINDOW_SURFACE = 1
        const val MSG_ACTION_DOWN = 2
        const val MSG_CLEAR_FRAME_LISTENER = 3
        const val MSG_CLEANUP = 4
        const val MSG_ON_FRAME_AVAILABLE = 5
        const val MSG_ON_SET_ORIENTATION = 6
        const val MSG_ON_SET_INITIAL_ORIENTATION = 7
        const val MSG_ACTION_TAKE_PHOTO = 8
        const val MSG_CHANGE_SHADER = 9
    }

//    private val isPortrait: Boolean
//        get() = orientation == 0 || orientation == 180

    private var orientation: Int = initialOrientation
    private var previewSize = Size(0, 0)

    /** OpenGL texture for the SurfaceTexture provided to the camera */
    private var cameraTexId: Int = 0

    /** The SurfaceTexture provided to the camera for capture */
    private lateinit var cameraTexture: SurfaceTexture

    /** The above SurfaceTexture cast as a Surface */
    private lateinit var cameraSurface: Surface

    /** OpenGL texture that will combine the camera output with rendering */
    private var renderTexId: Int = 0

    /** The SurfaceTexture we're rendering to */
    private lateinit var renderTexture: SurfaceTexture

    /** The above SurfaceTexture cast as a Surface */
    private lateinit var renderSurface: Surface

    /** Stuff needed for displaying HLG via SurfaceControl */
    private var contentSurfaceControl: SurfaceControl? = null
    private var windowTexId: Int = 0
    private var windowFboId: Int = 0

    private var supportsNativeFences = false

    /** Storage space for setting the texMatrix uniform */
    private val texMatrix = FloatArray(16)

    @Volatile
    private var currentlyRecording = false
    private var currentlyTakingPicture = false

    /** EGL / OpenGL data. */
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglRenderSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglEncoderSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglPhotoSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglWindowSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var vertexShader = 0

    private val cameraToRenderShaderProgram: ShaderProgram by lazy {
        PASSTHROUGH_FSHADER.toShaderProgram()
    }
    private lateinit var renderToPreviewShaderProgram: ShaderProgram
    private lateinit var renderToEncodeShaderProgram: ShaderProgram
    private lateinit var renderToPhotoShaderProgram: ShaderProgram
    private val cvResourcesCreated = ConditionVariable(false)
    private val cvDestroyWindowSurface = ConditionVariable(false)
    private val cvClearFrameListener = ConditionVariable(false)
    private val cvCleanup = ConditionVariable(false)

    fun startRecording() {
        currentlyRecording = true
    }

    fun stopRecording() {
        currentlyRecording = false
        "--> stopRecording called; currentlyRecording = $currentlyRecording".e
    }

    private fun changeShader(shaderFilename: String) {
        "--> changeShader: $shaderFilename ".e
        currentGLSLFilename = shaderFilename
        setupShaders()
    }

    private fun setupShaders() {
        "--> Setting up shader: $currentGLSLFilename ".e
        renderToPreviewShaderProgram = context.loadShader(currentGLSLFilename).toShaderProgram()
        renderToEncodeShaderProgram = context.loadShader(currentGLSLFilename).toShaderProgram()
        renderToPhotoShaderProgram = context.loadShader(currentGLSLFilename).toShaderProgram()
    }

    fun createRecordRequest(
        session: CameraCaptureSession, previewStabilization: Boolean
    ): CaptureRequest {
        cvResourcesCreated.block()

        // Capture request holds references to target surfaces
        return session.device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
            // Add the preview surface target
            addTarget(cameraSurface)

            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, 0.6f)
//                set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                set(
                    CaptureRequest.SENSOR_PIXEL_MODE,
                    CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                )
            }
            // That just doesn't work on Xiaomi
            set(CaptureRequest.JPEG_ORIENTATION, orientation.toOrientationTag)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (previewStabilization) {
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                    )
                }
            }
        }.build()
    }

    fun setPreviewSize(previewSize: Size) {
        this.previewSize = previewSize
    }

    fun getTargets(): List<Surface> {
        cvResourcesCreated.block()

        return listOf(cameraSurface)
    }

    /** Initialize the EGL display, context, and render surface */
    private fun initEGL() {
        "--> initEGL in ${Thread.currentThread().name}".e

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        HardwarePipeline.checkEglError("eglGetDisplay")

        val version = intArrayOf(0, 0)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = null
            throw RuntimeException("Unable to initialize EGL14")
        }
        HardwarePipeline.checkEglError("eglInitialize")

        val eglVersion = version[0] * 10 + version[1]
        Log.e(HardwarePipeline.TAG, "eglVersion: " + eglVersion)

        /** Check that the necessary extensions for color spaces are supported if HDR is enabled */
        if (isHDR()) {
            val requiredExtensionsList = mutableListOf<String>("EGL_KHR_gl_colorspace")
            if (transfer == PQ_ID) {
                requiredExtensionsList.add("EGL_EXT_gl_colorspace_bt2020_pq")
            } else if (transfer == LINEAR_ID) {
                requiredExtensionsList.add("EGL_EXT_gl_colorspace_bt2020_linear")
            } else if (transfer == HLG_ID) {
                requiredExtensionsList.add("EGL_EXT_gl_colorspace_bt2020_hlg")
            }

            val eglExtensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS)

            for (requiredExtension in requiredExtensionsList) {
                if (!eglExtensions.contains(requiredExtension)) {
                    Log.e(HardwarePipeline.TAG, "EGL extension not supported: " + requiredExtension)
                    Log.e(HardwarePipeline.TAG, "Supported extensions: ")
                    Log.e(HardwarePipeline.TAG, eglExtensions)
                    throw RuntimeException("EGL extension not supported: " + requiredExtension)
                }
            }

            // More devices can be supported if the eglCreateSyncKHR is used instead of
            // EGL15.eglCreateSync
            supportsNativeFences =
                eglVersion >= 15 && eglExtensions.contains("EGL_ANDROID_native_fence_sync")
        }

        Log.i(HardwarePipeline.TAG, "isHDR: " + isHDR())
        if (isHDR()) {
            Log.i(HardwarePipeline.TAG, "Preview transfer: " + idToStr(transfer))
        }

        var renderableType = EGL14.EGL_OPENGL_ES2_BIT
        if (isHDR()) {
            renderableType = EGLExt.EGL_OPENGL_ES3_BIT_KHR
        }

        var rgbBits = 8
        var alphaBits = 8
        if (isHDR()) {
            rgbBits = 10
            alphaBits = 2
        }

        val configAttribList = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE,
            renderableType,
            EGL14.EGL_RED_SIZE,
            rgbBits,
            EGL14.EGL_GREEN_SIZE,
            rgbBits,
            EGL14.EGL_BLUE_SIZE,
            rgbBits,
            EGL14.EGL_ALPHA_SIZE,
            alphaBits,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = intArrayOf(1)
        EGL14.eglChooseConfig(
            eglDisplay, configAttribList, 0, configs, 0, configs.size, numConfigs, 0
        )
        eglConfig = configs[0]!!

        var requestedVersion = 2
        if (isHDR()) {
            requestedVersion = 3
        }

        val contextAttribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, requestedVersion, EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        val clientVersion = intArrayOf(0)
        EGL14.eglQueryContext(
            eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, 0
        )
        Log.e(HardwarePipeline.TAG, "EGLContext created, client version " + clientVersion[0])

        val tmpSurfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE
        )
        val tmpSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, tmpSurfaceAttribs, /*offset*/ 0
        )
        EGL14.eglMakeCurrent(eglDisplay, tmpSurface, tmpSurface, eglContext)
    }

    private fun createResources(surface: Surface) {
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            initEGL()
        }

        var windowSurfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        if (isHDR()) {
            windowSurfaceAttribs = when (transfer) {
                PQ_ID -> intArrayOf(
                    EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE
                )

                LINEAR_ID -> intArrayOf(
                    EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, EGL14.EGL_NONE
                )
                // We configure HLG below
                HLG_ID -> intArrayOf(
                    EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE
                )

                HLG_WORKAROUND_ID -> intArrayOf(EGL14.EGL_NONE)
                else -> throw RuntimeException("Unexpected transfer " + transfer)
            }
        }

        if (!isHDR() or (transfer != HLG_WORKAROUND_ID)) {
            eglWindowSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, windowSurfaceAttribs, 0
            )
            if (eglWindowSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("Failed to create EGL texture view surface")
            }
        }

        if (eglWindowSurface != EGL14.EGL_NO_SURFACE) {
            /**
             * This is only experimental for the transfer function. It is intended to be
             * supplied alongside CTA 861.3 metadata
             * https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_surface_CTA861_3_metadata.txt.
             * which describes the max and average luminance of the content).
             *
             * The display will use these parameters to map the source content colors to a
             * colors that fill the display's capabilities.
             *
             * Without providing these parameters, the display will assume "reasonable defaults",
             * which may not be accurate for the source content. This would most likely result
             * in inaccurate colors, although the exact effect is device-dependent.
             *
             * The parameters needs to be tuned.
             * */
            if (isHDR() and (transfer == PQ_ID)) {
                val SMPTE2086_MULTIPLIER = 50000
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_MAX_LUMINANCE_EXT,
                    10000 * SMPTE2086_MULTIPLIER
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay, eglWindowSurface, EGL_SMPTE2086_MIN_LUMINANCE_EXT, 0
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_RX_EXT,
                    (0.708f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_RY_EXT,
                    (0.292f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_GX_EXT,
                    (0.170f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_GY_EXT,
                    (0.797f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_BX_EXT,
                    (0.131f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_DISPLAY_PRIMARY_BY_EXT,
                    (0.046f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_WHITE_POINT_X_EXT,
                    (0.3127f * SMPTE2086_MULTIPLIER).toInt()
                )
                EGL14.eglSurfaceAttrib(
                    eglDisplay,
                    eglWindowSurface,
                    EGL_SMPTE2086_WHITE_POINT_Y_EXT,
                    (0.3290f * SMPTE2086_MULTIPLIER).toInt()
                )
            }
        }

        "--> setDefaultBufferSize: $width $height".e
        cameraTexId = createTexture()
        cameraTexture = SurfaceTexture(cameraTexId)
        cameraTexture.setOnFrameAvailableListener(this)
        cameraTexture.setDefaultBufferSize(width, height)
        cameraSurface = Surface(cameraTexture)

//        if (isHDR() and (transfer == HLG_WORKAROUND_ID)) {
//            // Communicating HLG content may not be supported on EGLSurface in API 33, as there
//            // is no EGL extension for communicating the surface color space. Instead, create
//            // a child SurfaceControl whose parent is the viewFinder's SurfaceView and push
//            // buffers directly to the SurfaceControl.
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                contentSurfaceControl = SurfaceControl.Builder().setName("HardwarePipeline")
//                    .setParent(viewFinder.surfaceControl).setHidden(false).build()
//            }
//            windowTexId = createTexId()
//            windowFboId = createFboId()
//        }

        renderTexId = createTexture()
        renderTexture = SurfaceTexture(renderTexId)
        renderTexture.setDefaultBufferSize(width, height)
        renderSurface = Surface(renderTexture)

        val renderSurfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglRenderSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, renderSurface, renderSurfaceAttribs, 0
        )
        if (eglRenderSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL render surface")
        }

        createShaderResources()
        cvResourcesCreated.open()
    }

    private fun createShaderResources() {
        if (isHDR()) {
            /** Check that GL_EXT_YUV_target is supported for HDR */
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS)
            if (!extensions.contains("GL_EXT_YUV_target")) {
                throw RuntimeException("Device does not support GL_EXT_YUV_target")
            }

            vertexShader = createShader(GLES30.GL_VERTEX_SHADER, TRANSFORM_HDR_VSHADER)

//            cameraToRenderShaderProgram = when (filterOn) {
//                false -> YUV_TO_RGB_PASSTHROUGH_HDR_FSHADER.toShaderProgram()
//
//                true -> YUV_TO_RGB_PORTRAIT_HDR_FSHADER.toShaderProgram()
//            }
//            renderToPreviewShaderProgram = when (transfer) {
//                PQ_ID -> HLG_TO_PQ_HDR_FSHADER.toShaderProgram()
//                LINEAR_ID -> HLG_TO_LINEAR_HDR_FSHADER.toShaderProgram()
//                HLG_ID, HLG_WORKAROUND_ID -> PASSTHROUGH_HDR_FSHADER.toShaderProgram()
//                else -> throw RuntimeException("Unexpected transfer $transfer")
//            }
//            renderToEncodeShaderProgram = PASSTHROUGH_HDR_FSHADER.toShaderProgram()
//            renderToPhotoShaderProgram = PASSTHROUGH_HDR_FSHADER.toShaderProgram()
        } else {
            vertexShader = createShader(GLES30.GL_VERTEX_SHADER, TRANSFORM_VSHADER)
//            cameraToRenderShaderProgram = PASSTHROUGH_FSHADER.toShaderProgram()
            setupShaders()
        }
    }

    private fun String.toShaderProgram() = createShaderProgram(toShader())

    private fun String.toShader(): Int = createShader(
        GLES30.GL_FRAGMENT_SHADER, this
    )

    /** Creates the shader program used to copy data from one texture to another */
    private fun createShaderProgram(fragmentShader: Int): ShaderProgram {
        var shaderProgram = GLES30.glCreateProgram()
        HardwarePipeline.checkGlError("glCreateProgram")

        GLES30.glAttachShader(shaderProgram, vertexShader)
        HardwarePipeline.checkGlError("glAttachShader")
        GLES30.glAttachShader(shaderProgram, fragmentShader)
        HardwarePipeline.checkGlError("glAttachShader")
        GLES30.glLinkProgram(shaderProgram)
        HardwarePipeline.checkGlError("glLinkProgram")

        val linkStatus = intArrayOf(0)
        GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
        HardwarePipeline.checkGlError("glGetProgramiv")
        if (linkStatus[0] == 0) {
            val msg = "Could not link program: " + GLES30.glGetProgramInfoLog(shaderProgram)
            GLES30.glDeleteProgram(shaderProgram)
            throw RuntimeException(msg)
        }

        val vPositionLoc = GLES30.glGetAttribLocation(shaderProgram, "vPosition")
        HardwarePipeline.checkGlError("glGetAttribLocation")
        val texMatrixLoc = GLES30.glGetUniformLocation(shaderProgram, "texMatrix")
        HardwarePipeline.checkGlError("glGetUniformLocation")
        val uMVPMatrixHandle = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        HardwarePipeline.checkGlError("uMVPMatrixHandle")
        val iTimeHandle = GLES30.glGetUniformLocation(shaderProgram, "iTime")
        HardwarePipeline.checkGlError("iTimeHandle")
        val iRandHandle = GLES30.glGetUniformLocation(shaderProgram, "iRand")
        HardwarePipeline.checkGlError("iRandHandle")
        val orientationLoc = GLES30.glGetUniformLocation(shaderProgram, "orientation")
        HardwarePipeline.checkGlError("orientationLoc")

        return ShaderProgram(
            shaderProgram,
            vPositionLoc,
            texMatrixLoc,
            uMVPMatrixHandle,
            iTimeHandle,
            iRandHandle,
            orientationLoc
        )
    }

    /** Create a shader given its type and source string */
    private fun createShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        HardwarePipeline.checkGlError("glShaderSource")
        GLES30.glCompileShader(shader)
        HardwarePipeline.checkGlError("glCompileShader")
        val compiled = intArrayOf(0)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        HardwarePipeline.checkGlError("glGetShaderiv")
        if (compiled[0] == 0) {
            val msg = "Could not compile shader " + type + ": " + GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException(msg)
        }
        return shader
    }

    private fun createTexId(): Int {
        val buffer = IntBuffer.allocate(1)
        GLES30.glGenTextures(1, buffer)
        return buffer.get(0)
    }

    private fun destroyTexId(id: Int) {
        val buffer = IntBuffer.allocate(1)
        buffer.put(0, id)
        GLES30.glDeleteTextures(1, buffer)
    }

    private fun createFboId(): Int {
        val buffer = IntBuffer.allocate(1)
        GLES30.glGenFramebuffers(1, buffer)
        return buffer.get(0)
    }

    private fun destroyFboId(id: Int) {
        val buffer = IntBuffer.allocate(1)
        buffer.put(0, id)
        GLES30.glDeleteFramebuffers(1, buffer)
    }

    /** Create an OpenGL texture */
    private fun createTexture(): Int {/* Check that EGL has been initialized. */
        if (eglDisplay == null) {
            throw IllegalStateException("EGL not initialized before call to createTexture()");
        }

        val texId = createTexId()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        return texId
    }

    private fun destroyWindowSurface() {
        "--> destroyWindowSurface".e
        if (eglWindowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)
        }
        eglWindowSurface = EGL14.EGL_NO_SURFACE
        cvDestroyWindowSurface.open()
    }

    fun waitDestroyWindowSurface() {
        cvDestroyWindowSurface.block()
    }

    private fun onDrawFrame(
        texId: Int,
        texture: SurfaceTexture,
        viewportRect: Rect,
        shaderProgram: ShaderProgram,
        outputIsFramebuffer: Boolean
    ) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        HardwarePipeline.checkGlError("glClearColor")
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        HardwarePipeline.checkGlError("glClear")

        shaderProgram.useProgram()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        HardwarePipeline.checkGlError("glActiveTexture")
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        HardwarePipeline.checkGlError("glBindTexture")

        texture.getTransformMatrix(texMatrix)

        // HardwareBuffer coordinates are flipped relative to what GLES expects
        if (outputIsFramebuffer) {
            val flipMatrix = floatArrayOf(
                1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f
            )
            android.opengl.Matrix.multiplyMM(texMatrix, 0, flipMatrix, 0, texMatrix.clone(), 0)
        }
        shaderProgram.setTexMatrix(texMatrix)

        shaderProgram.setData(FULLSCREEN_QUAD, orientation)

        GLES30.glViewport(
            viewportRect.left, viewportRect.top, viewportRect.right, viewportRect.bottom
        )
        HardwarePipeline.checkGlError("glViewport")
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        HardwarePipeline.checkGlError("glDrawArrays")
    }

    private fun copyCameraToRender() {
        EGL14.eglMakeCurrent(eglDisplay, eglRenderSurface, eglRenderSurface, eglContext)

        onDrawFrame(
            cameraTexId,
            cameraTexture,
            Rect(0, 0, width, height),
            cameraToRenderShaderProgram!!,
            false
        )

        EGL14.eglSwapBuffers(eglDisplay, eglRenderSurface)
        renderTexture.updateTexImage()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun copyRenderToPreview() {
        var hardwareBuffer: HardwareBuffer? = null
        var eglImage: EGLImageKHR? = null
        if (transfer == HLG_WORKAROUND_ID) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)

            // TODO: use GLFrameBufferRenderer to optimize the performance
            // Note that pooling and reusing HardwareBuffers will have significantly better
            // memory utilization so the HardwareBuffers do not have to be allocated every frame
            hardwareBuffer = HardwareBuffer.create(
                previewSize.width,
                previewSize.height,
                HardwareBuffer.RGBA_1010102,
                1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_COMPOSER_OVERLAY
            )

            // If we're sending output buffers to a SurfaceControl we cannot render to an
            // EGLSurface. We need to render to a HardwareBuffer instead by importing the
            // HardwareBuffer into EGL, associating it with a texture, and framebuffer, and
            // drawing directly into the HardwareBuffer.
            eglImage = androidx.opengl.EGLExt.eglCreateImageFromHardwareBuffer(
                eglDisplay, hardwareBuffer
            )
            HardwarePipeline.checkGlError("eglCreateImageFromHardwareBuffer")

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, windowTexId)
            HardwarePipeline.checkGlError("glBindTexture")
            androidx.opengl.EGLExt.glEGLImageTargetTexture2DOES(GLES30.GL_TEXTURE_2D, eglImage!!)
            HardwarePipeline.checkGlError("glEGLImageTargetTexture2DOES")

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, windowFboId);
            HardwarePipeline.checkGlError("glBindFramebuffer")
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                windowTexId,
                0
            );
            HardwarePipeline.checkGlError("glFramebufferTexture2D")
        } else {
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglRenderSurface, eglContext)
        }

        val cameraAspectRatio = width.toFloat() / height.toFloat()
        val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
        var viewportWidth = previewSize.width
        var viewportHeight = previewSize.height
        var viewportX = 0
        var viewportY = 0

        /** The camera display is not the same size as the video. Letterbox the preview so that
         * we can see exactly how the video will turn out. */
        if (previewAspectRatio < cameraAspectRatio) {
            /** Avoid vertical stretching */
            viewportHeight =
                ((viewportHeight.toFloat() / previewAspectRatio) * cameraAspectRatio).toInt()
            viewportY = (previewSize.height - viewportHeight) / 2
        } else {
            /** Avoid horizontal stretching */
            viewportWidth =
                ((viewportWidth.toFloat() / cameraAspectRatio) * previewAspectRatio).toInt()
            viewportX = (previewSize.width - viewportWidth) / 2
        }

        onDrawFrame(
            renderTexId,
            renderTexture,
            Rect(viewportX, viewportY, viewportWidth, viewportHeight),
            renderToPreviewShaderProgram!!,
            hardwareBuffer != null
        )

        if (hardwareBuffer != null) {
            if (contentSurfaceControl == null) {
                throw RuntimeException("Forgot to set up SurfaceControl for HLG preview!")
            }

            // When rendering to HLG, send each camera frame to the display and communicate the
            // HLG colorspace here.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val fence = createSyncFence()
                if (fence == null) {
                    GLES30.glFinish()
                    HardwarePipeline.checkGlError("glFinish")
                }
                SurfaceControl.Transaction().setBuffer(
                    contentSurfaceControl!!, hardwareBuffer, fence
                ).setDataSpace(
                    contentSurfaceControl!!, DataSpace.pack(
                        DataSpace.STANDARD_BT2020, DataSpace.TRANSFER_HLG, DataSpace.RANGE_FULL
                    )
                ).apply()
                hardwareBuffer.close()
            }
        } else {
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)
        }

        if (eglImage != null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            androidx.opengl.EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)
        }
    }

    private fun copyRenderToPhoto() {
        EGL14.eglMakeCurrent(eglDisplay, eglPhotoSurface, eglRenderSurface, eglContext)

        var viewportWidth = height
        var viewportHeight = width

//        if (isPortrait) {
//            viewportWidth = height
//            viewportHeight = width
//        }

        onDrawFrame(
            renderTexId,
            renderTexture,
            Rect(0, 0, viewportWidth, viewportHeight),
            renderToPhotoShaderProgram!!,
            false
        )

        EGL14.eglSwapBuffers(eglDisplay, eglPhotoSurface)
    }

    private fun copyRenderToEncode() {
        EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglRenderSurface, eglContext)

        val viewportWidth = height
        val viewportHeight = width

////        /** Swap width and height if the camera is rotated on its side. */
//        if (initialOrientation == 90 || initialOrientation == 270) {
//            "--> Recording as landscape!".e
//            viewportWidth = height
//            viewportHeight = width
//        } else {
//            "--> Recording as portrait!".e
//        }

        onDrawFrame(
            renderTexId,
            renderTexture,
            Rect(0, 0, viewportWidth, viewportHeight),
            renderToEncodeShaderProgram,
            false
        )

        encoder.frameAvailable()

        EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createSyncFence(): SyncFence? {
        if (!supportsNativeFences) {
            return null
        }

        val eglSync = EGL15.eglCreateSync(
            eglDisplay,
            androidx.opengl.EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID,
            longArrayOf(EGL14.EGL_NONE.toLong()),
            0
        )
        HardwarePipeline.checkGlError("eglCreateSync")
        GLES30.glFlush()
        HardwarePipeline.checkGlError("glFlush")
        return eglSync?.let {
            val fence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, it)
            HardwarePipeline.checkGlError("eglDupNativeFenceFDANDROID")
            fence
        }
    }

    private fun actionDown(encoderSurface: Surface) {
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        if (eglEncoderSurface == null || eglEncoderSurface == EGL14.EGL_NO_SURFACE) {
            "--> Creating new eglEncoderSurface in ${Thread.currentThread()}".e
            eglEncoderSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0
            )
            if (eglEncoderSurface == EGL14.EGL_NO_SURFACE) {
                val error = EGL14.eglGetError()
                throw RuntimeException(
                    "Failed to create EGL encoder surface" + ": eglGetError = 0x" + Integer.toHexString(
                        error
                    )
                )
            }
        }
    }

    private fun actionTakePhoto(imageSurface: Surface) {
        currentlyTakingPicture = true
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        if (eglPhotoSurface == null || eglPhotoSurface == EGL14.EGL_NO_SURFACE) {
            "--> Creating new eglImageSurface in ${Thread.currentThread()}".e
            eglPhotoSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, imageSurface, surfaceAttribs, 0
            )
            if (eglPhotoSurface == EGL14.EGL_NO_SURFACE) {
                val error = EGL14.eglGetError()
                throw RuntimeException(
                    "Failed to create EGL encoder surface" + ": eglGetError = 0x" + Integer.toHexString(
                        error
                    )
                )
            }
        }
    }

    private fun clearFrameListener() {
        cameraTexture.setOnFrameAvailableListener(null)
        cvClearFrameListener.open()
    }

    fun waitClearFrameListener() {
        cvClearFrameListener.block()
    }

    private fun cleanup() {
        "--> RenderHandler cleanup()".e
        EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface)
        eglEncoderSurface = EGL14.EGL_NO_SURFACE
        EGL14.eglDestroySurface(eglDisplay, eglRenderSurface)
        eglRenderSurface = EGL14.EGL_NO_SURFACE
        EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)

        cameraTexture.release()
        renderTexture.release()

        if (cameraTexId > 0) {
            destroyTexId(cameraTexId)
        }

        if (renderTexId > 0) {
            destroyTexId(renderTexId)
        }

        if (windowTexId > 0) {
            destroyTexId(windowTexId)
        }

        if (windowFboId > 0) {
            destroyFboId(windowFboId)
        }

        EGL14.eglDestroyContext(eglDisplay, eglContext)

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT

        cvCleanup.open()
    }

    fun waitCleanup() {
        cvCleanup.block()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("UNUSED_PARAMETER")
    private fun onFrameAvailableImpl(surfaceTexture: SurfaceTexture) {
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            return
        }

        /** The camera API does not update the tex image. Do so here. */
        cameraTexture.updateTexImage()

        /** Copy from the camera texture to the render texture */
        if (eglRenderSurface != EGL14.EGL_NO_SURFACE) {
            copyCameraToRender()
        }

        /** Copy from the render texture to the TextureView */
        copyRenderToPreview()

        /** Copy to the encoder surface if we're currently recording. */
        if (eglEncoderSurface != EGL14.EGL_NO_SURFACE && currentlyRecording) {
            copyRenderToEncode()
        }

        /** Copy to the image surface if we're currently taking a photo. */
        if (eglPhotoSurface != EGL14.EGL_NO_SURFACE && currentlyTakingPicture) {
            currentlyTakingPicture = false
            copyRenderToPhoto()
        }
    }

    private fun isHDR(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dynamicRange != DynamicRangeProfiles.STANDARD
        } else {
            false
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        sendMessage(obtainMessage(MSG_ON_FRAME_AVAILABLE, 0, 0, surfaceTexture))
    }

    private fun setOrientation(mOrientation: Int) {
        "--> Setting renderer orientation: $mOrientation".e
        orientation = mOrientation
    }

    private fun setInitialOrientation(initOrientation: Int) {
//        "--> Setting renderer initial orientation: $initOrientation".e
//        initialOrientation = initOrientation
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_CHANGE_SHADER -> changeShader(msg.obj as String)
            MSG_CREATE_RESOURCES -> createResources(msg.obj as Surface)
            MSG_DESTROY_WINDOW_SURFACE -> destroyWindowSurface()
            MSG_ACTION_DOWN -> actionDown(msg.obj as Surface)
            MSG_ACTION_TAKE_PHOTO -> actionTakePhoto(msg.obj as Surface)
            MSG_CLEAR_FRAME_LISTENER -> clearFrameListener()
            MSG_CLEANUP -> cleanup()
            MSG_ON_FRAME_AVAILABLE -> onFrameAvailableImpl(msg.obj as SurfaceTexture)
            MSG_ON_SET_ORIENTATION -> setOrientation(msg.obj as Int)
            MSG_ON_SET_INITIAL_ORIENTATION -> setInitialOrientation(msg.obj as Int)
        }
    }
}
