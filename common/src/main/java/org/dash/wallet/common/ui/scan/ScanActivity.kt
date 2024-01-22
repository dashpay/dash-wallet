/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dash.wallet.common.ui.scan

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Vibrator
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewAnimationUtils
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPointCallback
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.R
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.OnFirstPreDraw
import org.slf4j.LoggerFactory
import java.util.EnumMap

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
class ScanActivity : SecureActivity(), TextureView.SurfaceTextureListener {
    private val cameraManager = CameraManager()
    private lateinit var contentView: View
    private var scannerView: ScannerView? = null
    private var previewView: TextureView? = null

    @Volatile
    private var surfaceCreated = false
    private var sceneTransition: Animator? = null
    private var vibrator: Vibrator? = null
    private var cameraThread: HandlerThread? = null

    @Volatile
    private var cameraHandler: Handler? = null
    private val viewModel by viewModels<ScanViewModel>()

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your app.
            maybeOpenCamera()
        } else {
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied.
            // At the same time, respect the user's decision.
            viewModel.showPermissionWarnDialog.postCall()
        }
    }
    @SuppressLint("WrongConstant")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        viewModel.showPermissionWarnDialog.observe(this) {
            showPermissionWarnDialog()
        }
        viewModel.showProblemWarnDialog.observe(this) {
            showProblemWarnDialog()
        }

        // Stick to the orientation the activity was started with. We cannot declare this in the
        // AndroidManifest.xml, because it's not allowed in combination with the windowIsTranslucent=true
        // theme attribute.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        // Draw under navigation and status bars.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        turnOffAutoLogout()
        setContentView(R.layout.scan_activity)
        contentView = findViewById(android.R.id.content)
        scannerView = findViewById<View>(R.id.scan_activity_mask) as ScannerView
        previewView = findViewById<View>(R.id.scan_activity_preview) as TextureView
        previewView!!.surfaceTextureListener = this
        cameraThread = HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND)
        cameraThread!!.start()
        cameraHandler = Handler(cameraThread!!.looper)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        }

        onBackPressedDispatcher.addCallback(this) {
            scannerView!!.visibility = View.GONE
            setResult(RESULT_CANCELED)
            finish()
        }

        if (savedInstanceState == null) {
            val intent = intent
            val x = intent.getIntExtra(INTENT_EXTRA_SCENE_TRANSITION_X, -1)
            val y = intent.getIntExtra(INTENT_EXTRA_SCENE_TRANSITION_Y, -1)
            if (x != -1 || y != -1) {
                // Using alpha rather than visibility because 'invisible' will cause the surface view to never
                // start up, so the animation will never start.
                contentView.alpha = 0f
                window
                    .setBackgroundDrawable(ColorDrawable(resources.getColor(android.R.color.transparent)))
                OnFirstPreDraw.listen(contentView) {
                    val finalRadius =
                        (contentView.width.coerceAtLeast(contentView.height)).toFloat()
                    val duration = resources.getInteger(android.R.integer.config_mediumAnimTime)
                    sceneTransition =
                        ViewAnimationUtils.createCircularReveal(contentView, x, y, 0f, finalRadius)
                    sceneTransition!!.duration = duration.toLong()
                    sceneTransition!!.interpolator = AccelerateInterpolator()
                    // TODO Here, the transition should start in a paused state, showing the first frame
                    // of the animation. Sadly, RevealAnimator doesn't seem to support this, unlike
                    // (subclasses of) ValueAnimator.
                    false
                }
            }
        }
    }

    private fun showProblemWarnDialog() {
        AdaptiveDialog.create(
            null,
            getString(R.string.scan_camera_permission_dialog_title),
            getString(R.string.scan_camera_problem_dialog_message),
            getString(R.string.button_dismiss)
        ).show(this)
    }

    private fun showPermissionWarnDialog() {
        AdaptiveDialog.create(
            null,
            getString(R.string.scan_camera_permission_dialog_title),
            getString(R.string.scan_camera_permission_dialog_message),
            getString(R.string.button_dismiss)
        ).show(this)
    }

    private fun requestCameraPermission() {
        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun maybeTriggerSceneTransition() {
        if (sceneTransition != null) {
            contentView.alpha = 1f
            sceneTransition!!.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    window
                        .setBackgroundDrawable(ColorDrawable(resources.getColor(android.R.color.black)))
                }
            })
            sceneTransition!!.start()
            sceneTransition = null
        }
    }

    override fun onResume() {
        super.onResume()
        maybeOpenCamera()
    }

    override fun onPause() {
        cameraHandler!!.post(closeRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        // cancel background thread
        cameraHandler!!.removeCallbacksAndMessages(null)
        cameraThread!!.quit()
        previewView!!.surfaceTextureListener = null

        // We're removing the requested orientation because if we don't, somehow the requested orientation is
        // bleeding through to the calling activity, forcing it into a locked state until it is restarted.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        turnOnAutoLogout()
        super.onDestroy()
    }

    private fun maybeOpenCamera() {
        if (surfaceCreated && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            cameraHandler!!.post(openRunnable)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceCreated = true
        maybeOpenCamera()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceCreated = false
        return true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_CAMERA -> // don't launch camera app
                return true

            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                cameraHandler!!.post { cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun handleResult(scanResult: Result) {
        vibrator!!.vibrate(VIBRATE_DURATION)
        scannerView!!.setIsResult(true)
        val result = Intent()
        result.putExtra(INTENT_EXTRA_RESULT, scanResult.text)
        setResult(RESULT_OK, result)
        postFinish()
    }

    private fun postFinish() {
        Handler().postDelayed(
            { finish() },
            50
        )
    }

    private val openRunnable: Runnable = object : Runnable {
        override fun run() {
            try {
                val camera = cameraManager.open(previewView, displayRotation())
                val framingRect = cameraManager.frame
                val framingRectInPreview = RectF(cameraManager.framePreview)
                framingRectInPreview.offsetTo(0f, 0f)
                val cameraFlip = cameraManager.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                val cameraRotation = cameraManager.orientation
                runOnUiThread {
                    scannerView!!.setFraming(
                        framingRect,
                        framingRectInPreview,
                        displayRotation(),
                        cameraRotation,
                        cameraFlip
                    )
                }
                val focusMode = camera.parameters.focusMode
                val nonContinuousAutoFocus =
                    ((Camera.Parameters.FOCUS_MODE_AUTO == focusMode) || (Camera.Parameters.FOCUS_MODE_MACRO == focusMode))
                if (nonContinuousAutoFocus) cameraHandler!!.post(AutoFocusRunnable(camera))
                runOnUiThread { maybeTriggerSceneTransition() }
                cameraHandler!!.post(fetchAndDecodeRunnable)
            } catch (x: Exception) {
                log.info("problem opening camera", x)
                viewModel.showProblemWarnDialog.postCall()
            }
        }

        private fun displayRotation(): Int {
            val rotation = windowManager.defaultDisplay.rotation
            return if (rotation == Surface.ROTATION_0) {
                0
            } else if (rotation == Surface.ROTATION_90) {
                90
            } else if (rotation == Surface.ROTATION_180) {
                180
            } else {
                if (rotation == Surface.ROTATION_270) {
                    270
                } else {
                    throw IllegalStateException(
                        "rotation: $rotation"
                    )
                }
            }
        }
    }
    private val closeRunnable: Runnable = Runnable {
        cameraHandler!!.removeCallbacksAndMessages(null)
        cameraManager.close()
    }

    private inner class AutoFocusRunnable(private val camera: Camera) : Runnable {
        override fun run() {
            try {
                camera.autoFocus(autoFocusCallback)
            } catch (x: Exception) {
                log.info("problem with auto-focus, will not schedule again", x)
            }
        }

        private val autoFocusCallback: Camera.AutoFocusCallback =
            object : Camera.AutoFocusCallback {
                override fun onAutoFocus(success: Boolean, camera: Camera) {
                    // schedule again
                    cameraHandler!!.postDelayed(this@AutoFocusRunnable, AUTO_FOCUS_INTERVAL_MS)
                }
            }
    }

    private val fetchAndDecodeRunnable: Runnable = object : Runnable {
        private val reader = QRCodeReader()
        private val hints: MutableMap<DecodeHintType, Any?> = EnumMap(
            DecodeHintType::class.java
        )

        override fun run() {
            cameraManager.requestPreviewFrame(object : Camera.PreviewCallback {
                override fun onPreviewFrame(data: ByteArray, camera: Camera) {
                    decode(data)
                }
            })
        }

        private fun decode(data: ByteArray) {
            val source = cameraManager.buildLuminanceSource(data)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK] = ResultPointCallback { dot ->
                    runOnUiThread {
                        scannerView!!.addDot(dot)
                    }
                }
                try {
                    val scanResult = reader.decode(bitmap, hints)
                    runOnUiThread { handleResult(scanResult) }
                } catch (x: ReaderException) {
                    // Invert and check for a code
                    val invertedSource = source.invert()
                    val invertedBitmap = BinaryBitmap(HybridBinarizer(invertedSource))
                    val invertedScanResult = reader.decode(invertedBitmap, hints)
                    runOnUiThread { handleResult(invertedScanResult) }
                }
            } catch (x: ReaderException) {
                // retry
                cameraHandler!!.post(this)
            } finally {
                reader.reset()
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_SCENE_TRANSITION_X = "scene_transition_x"
        private const val INTENT_EXTRA_SCENE_TRANSITION_Y = "scene_transition_y"
        const val INTENT_EXTRA_RESULT = "result"
        @JvmStatic
        fun startForResult(activity: Activity, clickView: View?, requestCode: Int) {
            if (clickView != null) {
                val options = getLaunchOptions(activity, clickView)
                val intent = getTransitionIntent(activity, clickView)
                activity.startActivityForResult(intent, requestCode, options.toBundle())
            } else {
                val intent = getIntent(activity)
                activity.startActivityForResult(intent, requestCode)
            }
        }

        fun getIntent(activity: Activity?): Intent {
            return Intent(activity, ScanActivity::class.java)
        }

        fun getTransitionIntent(activity: Activity?, clickView: View): Intent {
            val intent = Intent(activity, ScanActivity::class.java)
            val clickViewLocation = IntArray(2)
            clickView.getLocationOnScreen(clickViewLocation)
            intent.putExtra(
                INTENT_EXTRA_SCENE_TRANSITION_X,
                clickViewLocation[0] + clickView.width / 2
            )
            intent.putExtra(
                INTENT_EXTRA_SCENE_TRANSITION_Y,
                clickViewLocation[1] + clickView.height / 2
            )
            return intent
        }

        fun getLaunchOptions(activity: Activity?, clickView: View): ActivityOptionsCompat {
            return ActivityOptionsCompat.makeSceneTransitionAnimation(
                (activity)!!,
                clickView,
                "transition"
            )
        }

        fun startForResult(fragment: Fragment, activity: Activity?, resultCode: Int) {
            fragment.startActivityForResult(Intent(activity, ScanActivity::class.java), resultCode)
        }

        private const val VIBRATE_DURATION = 50L
        private const val AUTO_FOCUS_INTERVAL_MS = 2500L
        private val log = LoggerFactory.getLogger(ScanActivity::class.java)
    }
}
