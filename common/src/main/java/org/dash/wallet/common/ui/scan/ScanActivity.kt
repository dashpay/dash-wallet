/*
 * Copyright the original author or authors.
 * Copyright 2024 Dash Core Group
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
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.VibrationEffect
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
import org.dash.wallet.common.databinding.ScanActivityBinding
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.OnFirstPreDraw
import org.dash.wallet.common.util.openAppSettings
import org.slf4j.LoggerFactory
import java.util.EnumMap

/**
 * @author Andreas Schildbach (original Java source)
 */
@AndroidEntryPoint
class ScanActivity : SecureActivity(), TextureView.SurfaceTextureListener {
    private val cameraManager = CameraManager()
    private lateinit var contentView: View
    private lateinit var scannerView: ScannerView
    private lateinit var previewView: TextureView

    @Volatile
    private var surfaceCreated = false
    private var sceneTransition: Animator? = null
    private var cameraUnavailable = false
    private lateinit var vibrator: Vibrator
    private lateinit var cameraThread: HandlerThread

    @Volatile
    private lateinit var cameraHandler: Handler
    private val viewModel by viewModels<ScanViewModel>()
    private lateinit var binding: ScanActivityBinding

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

        binding = ScanActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        contentView = binding.root
        scannerView = binding.scanActivityMask
        previewView = binding.scanActivityPreview
        previewView.surfaceTextureListener = this
        cameraThread = HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND)
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)

        // Registered after the views are inflated: both dialogs also reveal the content view.
        viewModel.showPermissionWarnDialog.observe(this) {
            showCameraUnavailableUi()
            showPermissionWarnDialog()
        }
        viewModel.showProblemWarnDialog.observe(this) {
            showCameraUnavailableUi()
            showProblemWarnDialog()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        }

        onBackPressedDispatcher.addCallback(this) {
            scannerView.visibility = View.GONE
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.scanCloseButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

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
                    // The camera may already have been refused before the first draw; in that case
                    // the content is showing without a transition and must not be hidden again.
                    if (!cameraUnavailable) {
                        val finalRadius =
                            (contentView.width.coerceAtLeast(contentView.height)).toFloat()
                        val duration = resources.getInteger(android.R.integer.config_mediumAnimTime)
                        sceneTransition =
                            ViewAnimationUtils.createCircularReveal(contentView, x, y, 0f, finalRadius)
                        sceneTransition!!.duration = duration.toLong()
                        sceneTransition!!.interpolator = AccelerateInterpolator()
                    }
                    // TODO Here, the transition should start in a paused state, showing the first frame
                    // of the animation. Sadly, RevealAnimator doesn't seem to support this, unlike
                    // (subclasses of) ValueAnimator.
                    false
                }
            }
        }
    }

    /**
     * Called when the camera cannot be used at all: the permission was refused, or the device
     * failed to hand the camera over. When the scanner is opened with a circular-reveal
     * transition, the content view starts fully transparent over a transparent window and is
     * only made visible by [maybeTriggerSceneTransition], which runs off the successful
     * camera-open path. Without the camera that never happens, leaving an all-black screen with
     * no visible close button and no way back out of the scanner (MO-1016). Show the controls
     * over an opaque background instead.
     */
    private fun showCameraUnavailableUi() {
        cameraUnavailable = true
        sceneTransition?.cancel()
        sceneTransition = null
        contentView.alpha = 1f
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.dash_black)))
        // The close icon is nearly black, which is legible over the camera preview but not over
        // the empty background that replaces it.
        binding.scanCloseButton.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dash_white))
    }

    private fun showProblemWarnDialog() {
        AdaptiveDialog.create(
            null,
            getString(R.string.scan_camera_problem_dialog_title),
            getString(R.string.scan_camera_problem_dialog_message),
            getString(R.string.button_dismiss)
        ).show(this) { leaveScanner() }
    }

    private fun showPermissionWarnDialog() {
        // Asked after a refusal, a false rationale flag means the system will not prompt again
        // (the user chose "don't allow" twice, or picked "don't ask again"), so re-requesting
        // is a no-op and App info is the only way back. A true flag means we may still ask
        // in-app, which is a far smaller detour than sending the user to system settings.
        val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

        AdaptiveDialog.create(
            null,
            getString(R.string.scan_camera_permission_dialog_title),
            getString(
                if (canAskAgain) {
                    R.string.scan_camera_permission_dialog_message
                } else {
                    R.string.scan_camera_permission_denied_dialog_message
                }
            ),
            getString(R.string.button_not_now),
            getString(if (canAskAgain) R.string.permission_allow else R.string.button_settings)
        ).show(this) { accepted ->
            if (accepted == true) {
                if (canAskAgain) {
                    requestCameraPermission()
                } else {
                    // onResume() opens the camera on the way back, so granting it in system
                    // settings drops the user straight into a working scanner.
                    openAppSettings()
                }
            } else {
                leaveScanner()
            }
        }
    }

    /**
     * Leaves a scanner that has nothing left to offer. The screen has one job and cannot do it,
     * so rather than parking the user on a dead camera preview behind a dismissed dialog, hand
     * them back to where they came from — every caller has another way in, whether that is
     * typing an address, pasting one, or picking a contact.
     */
    private fun leaveScanner() {
        setResult(RESULT_CANCELED)
        finish()
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
        cameraHandler.post(closeRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        // cancel background thread
        cameraHandler.removeCallbacksAndMessages(null)
        cameraThread.quit()
        previewView.surfaceTextureListener = null

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
            cameraHandler.post(openRunnable)
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
                cameraHandler.post { cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(VIBRATE_DURATION, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            vibrator.vibrate(VIBRATE_DURATION)
        }
    }

    fun handleResult(scanResult: Result) {
        vibrate()
        scannerView.setIsResult(true)
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
                    scannerView.setFraming(
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
                if (nonContinuousAutoFocus) cameraHandler.post(AutoFocusRunnable(camera))
                runOnUiThread { maybeTriggerSceneTransition() }
                cameraHandler.post(fetchAndDecodeRunnable)
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
        cameraHandler.removeCallbacksAndMessages(null)
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

        private val autoFocusCallback = Camera.AutoFocusCallback { success: Boolean, camera: Camera ->
            // schedule again
            cameraHandler.postDelayed(this@AutoFocusRunnable, AUTO_FOCUS_INTERVAL_MS)
        }
    }

    private val fetchAndDecodeRunnable: Runnable = object : Runnable {
        private val reader = QRCodeReader()
        private val hints: MutableMap<DecodeHintType, Any?> = EnumMap(
            DecodeHintType::class.java
        )
        private var frameCount = 0

        override fun run() {
            cameraManager.requestPreviewFrame() { data, _ ->
                decode(data)
            }
        }

        private fun decode(data: ByteArray) {
            frameCount++
            // Alternate between the camera image and a highlight-stretched variant that
            // recovers codes the camera overexposed (bright QR card on a dark screen).
            val source = if (frameCount % 2 == 0) {
                cameraManager.buildStretchedLuminanceSource(data)
            } else {
                cameraManager.buildLuminanceSource(data)
            }
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                // Spend more time searching the frame — needed for dense codes like
                // DashConnect login/registration QRs (small modules at preview resolution).
                hints[DecodeHintType.TRY_HARDER] = true
                hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK] = ResultPointCallback { dot ->
                    runOnUiThread {
                        scannerView.addDot(dot)
                    }
                }
                try {
                    val scanResult = reader.decode(bitmap, hints)
                    log.info("decoded QR after {} frames", frameCount)
                    runOnUiThread { handleResult(scanResult) }
                } catch (x: ReaderException) {
                    // Invert and check for a code
                    val invertedSource = source.invert()
                    val invertedBitmap = BinaryBitmap(HybridBinarizer(invertedSource))
                    val invertedScanResult = reader.decode(invertedBitmap, hints)
                    log.info("decoded inverted QR after {} frames", frameCount)
                    runOnUiThread { handleResult(invertedScanResult) }
                }
            } catch (x: ReaderException) {
                // retry
                cameraHandler.post(this)
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
