package com.projectech.theblindapp

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var overlayText: TextView
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var tts: TextToSpeech
    private var currentTabIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tabLayout)
        overlayText = findViewById(R.id.overlayText)

        // Add tabs to TabLayout
        tabLayout.addTab(tabLayout.newTab().setText("Ask Surrounding"))
        tabLayout.addTab(tabLayout.newTab().setText("Make Call"))
        tabLayout.addTab(tabLayout.newTab().setText("Send SMS"))
        tabLayout.addTab(tabLayout.newTab().setText("Location"))
        tabLayout.addTab(tabLayout.newTab().setText("Navigation"))
        tabLayout.addTab(tabLayout.newTab().setText("Detect Objects"))

        // Initialize Text-to-Speech
        tts = TextToSpeech(this) {}

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

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
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

        if (currentTabIndex == 0) {
            overlayText.visibility = TextView.GONE // Show camera feed
        } else {
            overlayText.visibility = TextView.VISIBLE // Show black overlay
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
}
