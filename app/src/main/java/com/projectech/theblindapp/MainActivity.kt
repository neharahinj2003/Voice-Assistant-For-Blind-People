package com.projectech.theblindapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.google.android.material.tabs.TabLayout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var overlayText: TextView
    private lateinit var view_finder: PreviewView
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var tts: TextToSpeech
    private var currentTabIndex = 0
    private lateinit var imageCapture: ImageCapture

    // New variables for Ask Surrounding functionality
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var recordedText: String = ""
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startCamera()

        tabLayout = findViewById(R.id.tabLayout)
        overlayText = findViewById(R.id.overlayText)
        view_finder = findViewById(R.id.view_finder)

        // Add tabs to TabLayout
        tabLayout.addTab(tabLayout.newTab().setText("Ask Surrounding"))
        tabLayout.addTab(tabLayout.newTab().setText("Make Call"))
        tabLayout.addTab(tabLayout.newTab().setText("Send SMS"))
        tabLayout.addTab(tabLayout.newTab().setText("Location"))
        tabLayout.addTab(tabLayout.newTab().setText("Navigation"))
        tabLayout.addTab(tabLayout.newTab().setText("Detect Objects"))

        // Initialize Text-to-Speech
        tts = TextToSpeech(this) {}

        // Initialize Speech Recognizer (for Ask Surrounding)
        initSpeechRecognizer()

        // Listen for tab selection changes
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                updateUI()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Gesture detector for swipe and double tap
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY)) { // Horizontal swipe
                    if (diffX > 100) { // Swipe Right (Previous Tab)
                        switchToPreviousTab()
                    } else if (diffX < -100) { // Swipe Left (Next Tab)
                        switchToNextTab()
                    }
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                startActivityForCurrentTab()
                return true
            }
        })

        // Set touch listener on FrameLayout
        findViewById<FrameLayout>(R.id.frameLayout).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        updateUI()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view_finder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchToNextTab() {
        if (currentTabIndex < tabLayout.tabCount - 1) {
            currentTabIndex++
            tabLayout.getTabAt(currentTabIndex)?.select()
            updateUI()
        }
    }

    private fun switchToPreviousTab() {
        if (currentTabIndex > 0) {
            currentTabIndex--
            tabLayout.getTabAt(currentTabIndex)?.select()
            updateUI()
        }
    }

    private fun updateUI() {
        val tabName = tabLayout.getTabAt(currentTabIndex)?.text.toString()
        tts.speak(tabName, TextToSpeech.QUEUE_FLUSH, null, null)

        // Only show camera feed when in Ask Surrounding (tab 0)
        if (currentTabIndex == 0) {
            view_finder.visibility = PreviewView.VISIBLE
            overlayText.visibility = TextView.INVISIBLE
        } else {
            view_finder.visibility = PreviewView.GONE
            overlayText.visibility = TextView.VISIBLE
        }
    }

    private fun startActivityForCurrentTab() {
        val intent = when (currentTabIndex) {
            1 -> Intent(this, MakeCallActivity::class.java)
            2 -> Intent(this, SendSMSActivity::class.java)
            3 -> Intent(this, LocationActivity::class.java)
            4 -> Intent(this, NavigationActivity::class.java)
            5 -> Intent(this, DetectObjectsActivity::class.java)
            else -> null
        }
        intent?.let { startActivity(it) }
    }

    // Override key events so that voice and image capture only happen when in tab 0
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentTabIndex == 0 && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!isRecording) {
                isRecording = true
                Log.d("VoiceRecognition", "Starting speech recognition...")
                startListening()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentTabIndex == 0 && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            speechRecognizer.stopListening()
            captureImage() // Capture image and process it along with the voice input
            isRecording = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // Initialize the SpeechRecognizer and its listener
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("SpeechRecognizer", "Error: $error")
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                    recordedText = it[0]
                    Log.d("SpeechRecognizer", "User's Query: $recordedText")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    // Start listening for speech input
    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("VoiceRecognition", "Missing RECORD_AUDIO permission!")
            return
        }
        Log.d("VoiceRecognition", "Listening for speech input...")
        speechRecognizer.startListening(speechIntent)
    }

    // Capture an image using the CameraX ImageCapture use-case
    private fun captureImage() {
        val photoFile = File(cacheDir, "snapshot.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    resizeImage(photoFile)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Photo capture failed: ${exception.message}", exception)
                }
            })
    }

    // Resize and compress the captured image before sending
    private fun resizeImage(imageFile: File) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 640, 480, true)
            val resizedFile = File(cacheDir, "resized_snapshot.jpg")
            val outputStream = FileOutputStream(resizedFile)
            resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            outputStream.close()
            sendToGeminiAPI(resizedFile, recordedText)
        } catch (e: IOException) {
            Log.e("Image Processing", "Resizing failed: ${e.message}")
        }
    }

    // Encode the image file to a Base64 string
    private fun encodeImageToBase64(imageFile: File): String {
        return try {
            val inputStream = FileInputStream(imageFile)
            val bytes = inputStream.readBytes()
            inputStream.close()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    // Send the resized image and voice prompt to the Gemini API
    private fun sendToGeminiAPI(imageFile: File, prompt: String) {
        Executors.newSingleThreadExecutor().execute {
            val base64Image = encodeImageToBase64(imageFile)
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
            }
            val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=AIzaSyCKZL-sZstwIk287r8gFti0_MOtKxRbLYk")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            val client = OkHttpClient()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("Gemini API", "Request failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    val jsonResponse = JSONObject(responseBody ?: "{}")
                    val reply = jsonResponse.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "No response")
                    runOnUiThread {
                        val cleanReply = reply?.replace("*", "") ?: "No response"
                        tts.speak(cleanReply, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        tts.stop()
        tts.shutdown()
    }
}
