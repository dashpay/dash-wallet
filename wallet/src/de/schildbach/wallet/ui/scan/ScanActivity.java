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

package de.schildbach.wallet.ui.scan;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.dash.wallet.common.ui.DialogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.util.OnFirstPreDraw;
import de.schildbach.wallet_test.R;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

/**
 * @author Andreas Schildbach
 */
@SuppressWarnings("deprecation")
public final class ScanActivity extends AbstractWalletActivity
        implements SurfaceTextureListener, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String INTENT_EXTRA_SCENE_TRANSITION_X = "scene_transition_x";
    private static final String INTENT_EXTRA_SCENE_TRANSITION_Y = "scene_transition_y";
    public static final String INTENT_EXTRA_RESULT = "result";

    public static void startForResult(final Activity activity, @Nullable final View clickView, final int requestCode) {
        if (clickView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final int[] clickViewLocation = new int[2];
            clickView.getLocationOnScreen(clickViewLocation);
            final Intent intent = new Intent(activity, ScanActivity.class);
            intent.putExtra(ScanActivity.INTENT_EXTRA_SCENE_TRANSITION_X,
                    (int) (clickViewLocation[0] + clickView.getWidth() / 2));
            intent.putExtra(ScanActivity.INTENT_EXTRA_SCENE_TRANSITION_Y,
                    (int) (clickViewLocation[1] + clickView.getHeight() / 2));
            final ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(activity, clickView,
                    "transition");
            activity.startActivityForResult(intent, requestCode, options.toBundle());
        } else {
            startForResult(activity, requestCode);
        }
    }

    public static void startForResult(final Activity activity, final int resultCode) {
        activity.startActivityForResult(new Intent(activity, ScanActivity.class), resultCode);
    }

    public static void startForResult(final Fragment fragment, final Activity activity, final int resultCode) {
        fragment.startActivityForResult(new Intent(activity, ScanActivity.class), resultCode);
    }

    private static final long VIBRATE_DURATION = 50L;
    private static final long AUTO_FOCUS_INTERVAL_MS = 2500L;

    private final CameraManager cameraManager = new CameraManager();

    private View contentView;
    private ScannerView scannerView;
    private TextureView previewView;

    private volatile boolean surfaceCreated = false;
    private Animator sceneTransition = null;

    private Vibrator vibrator;
    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;

    private ScanViewModel viewModel;

    private static final Logger log = LoggerFactory.getLogger(ScanActivity.class);

    @SuppressLint("WrongConstant")
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        viewModel = ViewModelProviders.of(this).get(ScanViewModel.class);
        viewModel.showPermissionWarnDialog.observe(this, new Observer<Void>() {
            @Override
            public void onChanged(final Void v) {
                WarnDialogFragment.show(getSupportFragmentManager(), R.string.scan_camera_permission_dialog_title,
                        getString(R.string.scan_camera_permission_dialog_message));
            }
        });
        viewModel.showProblemWarnDialog.observe(this, new Observer<Void>() {
            @Override
            public void onChanged(final Void v) {
                WarnDialogFragment.show(getSupportFragmentManager(), R.string.scan_camera_problem_dialog_title,
                        getString(R.string.scan_camera_problem_dialog_message));
            }
        });

        // Stick to the orientation the activity was started with. We cannot declare this in the
        // AndroidManifest.xml, because it's not allowed in combination with the windowIsTranslucent=true
        // theme attribute.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        // Draw under navigation and status bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        getIntent().putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true);
        setContentView(R.layout.scan_activity);
        contentView = findViewById(android.R.id.content);
        scannerView = (ScannerView) findViewById(R.id.scan_activity_mask);
        previewView = (TextureView) findViewById(R.id.scan_activity_preview);
        previewView.setSurfaceTextureListener(this);

        cameraThread = new HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, 0);

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final Intent intent = getIntent();
            final int x = intent.getIntExtra(INTENT_EXTRA_SCENE_TRANSITION_X, -1);
            final int y = intent.getIntExtra(INTENT_EXTRA_SCENE_TRANSITION_Y, -1);
            if (x != -1 || y != -1) {
                // Using alpha rather than visibility because 'invisible' will cause the surface view to never
                // start up, so the animation will never start.
                contentView.setAlpha(0);
                getWindow()
                        .setBackgroundDrawable(new ColorDrawable(getResources().getColor(android.R.color.transparent)));
                OnFirstPreDraw.listen(contentView, new OnFirstPreDraw.Callback() {
                    @Override
                    public boolean onFirstPreDraw() {
                        float finalRadius = (float) (Math.max(contentView.getWidth(), contentView.getHeight()));
                        final int duration = getResources().getInteger(android.R.integer.config_mediumAnimTime);
                        sceneTransition = ViewAnimationUtils.createCircularReveal(contentView, x, y, 0, finalRadius);
                        sceneTransition.setDuration(duration);
                        sceneTransition.setInterpolator(new AccelerateInterpolator());
                        // TODO Here, the transition should start in a paused state, showing the first frame
                        // of the animation. Sadly, RevealAnimator doesn't seem to support this, unlike
                        // (subclasses of) ValueAnimator.
                        return false;
                    }
                });
            }
        }
    }

    private void maybeTriggerSceneTransition() {
        if (sceneTransition != null) {
            contentView.setAlpha(1);
            sceneTransition.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    getWindow()
                            .setBackgroundDrawable(new ColorDrawable(getResources().getColor(android.R.color.black)));
                }
            });
            sceneTransition.start();
            sceneTransition = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        maybeOpenCamera();
    }

    @Override
    protected void onPause() {
        cameraHandler.post(closeRunnable);

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // cancel background thread
        cameraHandler.removeCallbacksAndMessages(null);
        cameraThread.quit();

        previewView.setSurfaceTextureListener(null);

        // We're removing the requested orientation because if we don't, somehow the requested orientation is
        // bleeding through to the calling activity, forcing it into a locked state until it is restarted.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
            final int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            maybeOpenCamera();
        else
            viewModel.showPermissionWarnDialog.call();
    }

    private void maybeOpenCamera() {
        if (surfaceCreated && ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            cameraHandler.post(openRunnable);
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        surfaceCreated = true;
        maybeOpenCamera();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
        surfaceCreated = false;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
    }

    @Override
    public void onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
    }

    @Override
    public void onBackPressed() {
        scannerView.setVisibility(View.GONE);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_FOCUS:
        case KeyEvent.KEYCODE_CAMERA:
            // don't launch camera app
            return true;
        case KeyEvent.KEYCODE_VOLUME_DOWN:
        case KeyEvent.KEYCODE_VOLUME_UP:
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
                }
            });
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    public void handleResult(final Result scanResult) {
        vibrator.vibrate(VIBRATE_DURATION);

        scannerView.setIsResult(true);

        final Intent result = new Intent();
        result.putExtra(INTENT_EXTRA_RESULT, scanResult.getText());
        setResult(RESULT_OK, result);
        postFinish();
    }

    private void postFinish() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 50);
    }

    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                final Camera camera = cameraManager.open(previewView, displayRotation());

                final Rect framingRect = cameraManager.getFrame();
                final RectF framingRectInPreview = new RectF(cameraManager.getFramePreview());
                framingRectInPreview.offsetTo(0, 0);
                final boolean cameraFlip = cameraManager.getFacing() == CameraInfo.CAMERA_FACING_FRONT;
                final int cameraRotation = cameraManager.getOrientation();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scannerView.setFraming(framingRect, framingRectInPreview, displayRotation(), cameraRotation,
                                cameraFlip);
                    }
                });

                final String focusMode = camera.getParameters().getFocusMode();
                final boolean nonContinuousAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
                        || Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode);

                if (nonContinuousAutoFocus)
                    cameraHandler.post(new AutoFocusRunnable(camera));

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        maybeTriggerSceneTransition();
                    }
                });
                cameraHandler.post(fetchAndDecodeRunnable);
            } catch (final Exception x) {
                log.info("problem opening camera", x);
                viewModel.showProblemWarnDialog.postCall();
            }
        }

        private int displayRotation() {
            final int rotation = getWindowManager().getDefaultDisplay().getRotation();
            if (rotation == Surface.ROTATION_0)
                return 0;
            else if (rotation == Surface.ROTATION_90)
                return 90;
            else if (rotation == Surface.ROTATION_180)
                return 180;
            else if (rotation == Surface.ROTATION_270)
                return 270;
            else
                throw new IllegalStateException("rotation: " + rotation);
        }
    };

    private final Runnable closeRunnable = new Runnable() {
        @Override
        public void run() {
            cameraHandler.removeCallbacksAndMessages(null);
            cameraManager.close();
        }
    };

    private final class AutoFocusRunnable implements Runnable {
        private final Camera camera;

        public AutoFocusRunnable(final Camera camera) {
            this.camera = camera;
        }

        @Override
        public void run() {
            try {
                camera.autoFocus(autoFocusCallback);
            } catch (final Exception x) {
                log.info("problem with auto-focus, will not schedule again", x);
            }
        }

        private final Camera.AutoFocusCallback autoFocusCallback = new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(final boolean success, final Camera camera) {
                // schedule again
                cameraHandler.postDelayed(AutoFocusRunnable.this, AUTO_FOCUS_INTERVAL_MS);
            }
        };
    }

    private final Runnable fetchAndDecodeRunnable = new Runnable() {
        private final QRCodeReader reader = new QRCodeReader();
        private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

        @Override
        public void run() {
            cameraManager.requestPreviewFrame(new PreviewCallback() {
                @Override
                public void onPreviewFrame(final byte[] data, final Camera camera) {
                    decode(data);
                }
            });
        }

        private void decode(final byte[] data) {
            final PlanarYUVLuminanceSource source = cameraManager.buildLuminanceSource(data);
            final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            try {
                hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, new ResultPointCallback() {
                    @Override
                    public void foundPossibleResultPoint(final ResultPoint dot) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                scannerView.addDot(dot);
                            }
                        });
                    }
                });
                try {
                    final Result scanResult = reader.decode(bitmap, hints);

                    runOnUiThread(() -> handleResult(scanResult));
                } catch (ReaderException x) {
                    // Invert and check for a code
                    LuminanceSource invertedSource = source.invert();
                    BinaryBitmap invertedBitmap = new BinaryBitmap(new HybridBinarizer(invertedSource));

                    final Result invertedScanResult = reader.decode(invertedBitmap, hints);

                    runOnUiThread(() -> handleResult(invertedScanResult));
                }

            } catch (final ReaderException x) {
                // retry
                cameraHandler.post(fetchAndDecodeRunnable);
            } finally {
                reader.reset();
            }
        }
    };

    public static class WarnDialogFragment extends DialogFragment {
        private static final String FRAGMENT_TAG = WarnDialogFragment.class.getName();

        public static void show(final FragmentManager fm, final int titleResId, final String message) {
            final WarnDialogFragment newFragment = new WarnDialogFragment();
            final Bundle args = new Bundle();
            args.putInt("title", titleResId);
            args.putString("message", message);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final Bundle args = getArguments();
            final DialogBuilder dialog = DialogBuilder.warn(getActivity(), args.getInt("title"));
            dialog.setMessage(args.getString("message"));
            dialog.singleDismissButton(new OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    getActivity().finish();
                }
            });
            return dialog.create();
        }

        @Override
        public void onCancel(final DialogInterface dialog) {
            getActivity().finish();
        }
    }
}
