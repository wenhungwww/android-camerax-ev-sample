/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wen.exposure

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val IMMERSIVE_FLAG_TIMEOUT = 500L

/** Combination of all flags required to put activity into immersive mode */
private const val FLAGS_FULLSCREEN =
    View.SYSTEM_UI_FLAG_LOW_PROFILE or
      View.SYSTEM_UI_FLAG_FULLSCREEN or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
)

/**
 * Main entry point into our app.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: PreviewView

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var slider: Slider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.main_container)
        viewFinder = container.findViewById(R.id.view_finder)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupPermissions()
    }

    /**
     * Request permission if missing.
     */
    private fun setupPermissions() {
        if (isPermissionMissing()) {
            val permissionLauncher = registerForActivityResult(
                RequestMultiplePermissions(),
                ActivityResultCallback<Map<String?, Boolean?>> { result: Map<String?, Boolean?> ->
                    for (permission in REQUIRED_PERMISSIONS) {
                        result[permission]?.let {
                            if (!it) {
                                Toast.makeText(
                                    applicationContext,
                                    "Camera permission denied.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                                return@ActivityResultCallback
                            }
                        }
                    }
                    start()
                })
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        } else {
            // Permissions already granted. Start camera.
            start()
        }
    }

    private fun isPermissionMissing(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
        }
        return false
    }

    private fun start() {
        // Wait for the views to be properly laid out
        viewFinder.post {
            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            lensFacing = when {
                cameraProvider.hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                cameraProvider.hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Enable or disable switching between cameras
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            bindCameraUseCases()

        }, ContextCompat.getMainExecutor(this))
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        val switchCamerasButton = container.findViewById<ImageButton>(R.id.camera_switch_button)
        try {
            switchCamerasButton.isEnabled =
                cameraProvider.hasBackCamera() && cameraProvider.hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            switchCamerasButton.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun ProcessCameraProvider?.hasBackCamera(): Boolean {
        return this?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun ProcessCameraProvider?.hasFrontCamera(): Boolean {
        return this?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        // Inflate a new view containing all UI for controlling the camera
        val controls = View.inflate(this, R.layout.camera_ui_container, container)
        slider = controls.findViewById(R.id.slider) as Slider

        // Listener for button used to capture photo
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    }
                ).build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                    outputFileOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        val startCaptureTime = SystemClock.elapsedRealtime()

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                            container.post {
                                Toast.makeText(
                                    applicationContext,
                                    "Failed to save image. ${exc.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                            container.post {
                                Toast.makeText(
                                    applicationContext,
                                    "Image captured in ${SystemClock.elapsedRealtime() - startCaptureTime} ms",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
            }
        }

        // Setup for button used to switch cameras
        controls.findViewById<ImageButton>(R.id.camera_switch_button).let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()

            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun Slider.setup(camera: Camera) {
        camera.cameraInfo.exposureState.let {
            value = it.exposureCompensationIndex.toFloat()
            valueFrom = it.exposureCompensationRange.lower.toFloat()
            valueTo = it.exposureCompensationRange.upper.toFloat()
            addOnChangeListener { _, value, _ ->
                camera.cameraControl.setExposureCompensationIndex(value.roundToInt())
            }
            setLabelFormatter { value: Float ->
                "%.2f".format((value * it.exposureCompensationStep.toFloat()))
            }
        }
    }

    /** Declare and bind preview and capture use cases */
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            val useCaseGroupBuilder = UseCaseGroup.Builder().setViewPort(
                ViewPort.Builder(
                    Rational(viewFinder.width, viewFinder.height),
                    viewFinder.display.rotation
                ).setScaleType(ViewPort.FILL_CENTER).build()
            ).apply {
                listOfNotNull(preview, imageCapture).forEach {
                    addUseCase(it)
                }
            }

            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, useCaseGroupBuilder.build()
            )

            // Update the EV slider UI according to the CameraInfo
            slider?.setup(camera!!)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    override fun onResume() {
        super.onResume()
        // Before setting full screen flags, we must wait a bit to let UI settle; otherwise, we may
        // be trying to set app to immersive mode before it's ready and the flags do not stick
        container.postDelayed(
            { container.systemUiVisibility = FLAGS_FULLSCREEN },
            IMMERSIVE_FLAG_TIMEOUT
        )

        camera?.let {
            slider?.setup(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXEvSample"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}
