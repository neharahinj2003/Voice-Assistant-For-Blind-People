package com.projectech.theblindapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.*

enum class LocationState {
    ASK_TYPE,                              // "current" or "send"
    GET_CURRENT,                           // For current location
    SEND_LOCATION_ASK_DESTINATION_TYPE,    // Ask: number or contact?
    SEND_LOCATION_GET_NUMBER,              // Ask for destination number
    SEND_LOCATION_GET_CONTACT,             // Ask for destination contact name
    SEND_LOCATION_CONFIRM_DESTINATION_NUMBER, // Confirm the provided number
    SEND_LOCATION_CONFIRM_DESTINATION_CONTACT,  // Confirm the provided contact name
    SEND_LOCATION_SEND,                    // Retrieve location and send SMS
    FINISHED
}

class LocationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // Chat container for chat-style UI (instead of a single TextView)
    private lateinit var chatContainer: LinearLayout
    private lateinit var btnSpeak: Button
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var currentState = LocationState.ASK_TYPE
    private var autoListen = true

    // For send location flow:
    private var smsDestination: String = "" // will store either number or contact name
    private var destinationIsNumber: Boolean = false

    companion object {
        const val SPEECH_REQUEST_CODE_LOC = 300
        const val LOCATION_PERMISSION_REQUEST_CODE = 400
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location)

        chatContainer = findViewById(R.id.chatContainer)
        btnSpeak = findViewById(R.id.btnLocationSpeak)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Request location permissions if not granted.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        btnSpeak.setOnClickListener { promptSpeechInput() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            // Start conversation.
            postMessage("App: Do you want to know your current location or send your location? Please say 'current' or 'send'.")
            speakOut("Do you want to know your current location or send your location? Please say current or send") {
                if (currentState != LocationState.FINISHED && autoListen) {
                    promptSpeechInput()
                }
            }
        } else {
            Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptSpeechInput() {
        if (!autoListen) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE_LOC)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        autoListen = false
        if (requestCode == SPEECH_REQUEST_CODE_LOC && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)?.lowercase(Locale.US) ?: ""
            postMessage("User: $spokenText")
            processResponse(spokenText)
        } else {
            autoListen = true
        }
    }

    private fun processResponse(response: String) {
        when (currentState) {
            LocationState.ASK_TYPE -> {
                if (response.contains("current")) {
                    currentState = LocationState.GET_CURRENT
                    postMessage("App: Retrieving your current location...")
                    speakOut("Retrieving your current location") { getCurrentLocation() }
                } else if (response.contains("send")) {
                    currentState = LocationState.SEND_LOCATION_ASK_DESTINATION_TYPE
                    postMessage("App: Do you want to send your location to a number or contact? Please say 'number' or 'contact'.")
                    speakOut("Do you want to send your location to a number or contact? Please say number or contact") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't understand. Please say 'current' or 'send'.")
                    speakOut("I didn't understand. Please say current or send") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.SEND_LOCATION_ASK_DESTINATION_TYPE -> {
                if (response.contains("number")) {
                    destinationIsNumber = true
                    currentState = LocationState.SEND_LOCATION_GET_NUMBER
                    postMessage("App: Please say the number you want to send your location to.")
                    speakOut("Please say the number you want to send your location to") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else if (response.contains("contact")) {
                    destinationIsNumber = false
                    currentState = LocationState.SEND_LOCATION_GET_CONTACT
                    postMessage("App: Please say the contact name you want to send your location to.")
                    speakOut("Please say the contact name you want to send your location to") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't understand. Please say 'number' or 'contact'.")
                    speakOut("I didn't understand. Please say number or contact") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.SEND_LOCATION_GET_NUMBER -> {
                smsDestination = response.replace("\\s+".toRegex(), "")
                if (smsDestination.isNotEmpty() && smsDestination.all { it.isDigit() || it == '+' }) {
                    currentState = LocationState.SEND_LOCATION_CONFIRM_DESTINATION_NUMBER
                    val formattedNumber = formatNumberForSpeech(smsDestination)
                    postMessage("App: You said $formattedNumber. Is that correct? Please say yes or no.")
                    speakOut("You said $formattedNumber. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: That doesn't seem like a valid number. Please try again.")
                    speakOut("That doesn't seem like a valid number. Please say the number again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.SEND_LOCATION_GET_CONTACT -> {
                smsDestination = response.trim()
                if (smsDestination.isNotEmpty()) {
                    currentState = LocationState.SEND_LOCATION_CONFIRM_DESTINATION_CONTACT
                    postMessage("App: You said $smsDestination. Is that correct? Please say yes or no.")
                    speakOut("You said $smsDestination. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't catch a valid contact name. Please try again.")
                    speakOut("I didn't catch a valid contact name. Please say the contact name again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.SEND_LOCATION_CONFIRM_DESTINATION_NUMBER -> {
                if (response.contains("yes")) {
                    currentState = LocationState.SEND_LOCATION_SEND
                    postMessage("App: Sending your location to $smsDestination")
                    speakOut("Sending your location") { getAndSendLocation() }
                } else {
                    currentState = LocationState.SEND_LOCATION_GET_NUMBER
                    postMessage("App: Please say the number you want to send your location to again.")
                    speakOut("Okay, please say the number you want to send your location to again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.SEND_LOCATION_CONFIRM_DESTINATION_CONTACT -> {
                if (response.contains("yes")) {
                    currentState = LocationState.SEND_LOCATION_SEND
                    postMessage("App: Sending your location to $smsDestination")
                    speakOut("Sending your location") { getAndSendLocation() }
                } else {
                    currentState = LocationState.SEND_LOCATION_GET_CONTACT
                    postMessage("App: Please say the contact name you want to send your location to again.")
                    speakOut("Okay, please say the contact name you want to send your location to again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            LocationState.GET_CURRENT -> {
                // No further input expected once location is retrieved.
            }
            else -> { /* no-op */ }
        }
    }

    // Converts digits (and '+' sign) into words so TTS speaks them clearly.
    private fun formatNumberForSpeech(number: String): String {
        val digitMap = mapOf(
            '0' to "zero",
            '1' to "one",
            '2' to "two",
            '3' to "three",
            '4' to "four",
            '5' to "five",
            '6' to "six",
            '7' to "seven",
            '8' to "eight",
            '9' to "nine",
            '+' to "plus"
        )
        return number.toCharArray().map { digitMap[it] ?: it.toString() }.joinToString(" ")
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (currentState != LocationState.GET_CURRENT) return@addOnSuccessListener
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val geocoder = Geocoder(this, Locale.getDefault())
                try {
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    // Use geocoder result to build a relative description.
                    val relativeMessage = if (addresses != null && addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val feature = address.featureName ?: ""
                        val locality = address.locality ?: address.subLocality ?: ""
                        if (feature.isNotEmpty() || locality.isNotEmpty()) {
                            "You're near ${if (feature.isNotEmpty()) feature else locality}."
                        } else {
                            "Your current location has been determined."
                        }
                    } else {
                        "Unable to determine your relative location."
                    }
                    postMessage("App: $relativeMessage")
                    speakOut(relativeMessage)
                    currentState = LocationState.FINISHED
                } catch (e: Exception) {
                    postMessage("App: Unable to determine relative location. ${e.message}")
                    speakOut("Unable to determine your relative location. Please try again later.") {
                        currentState = LocationState.FINISHED
                    }
                }
            } else {
                postMessage("App: Unable to retrieve location. Please try again later.")
                speakOut("Unable to retrieve location. Please try again later.") {
                    currentState = LocationState.FINISHED
                }
            }
        }.addOnFailureListener {
            postMessage("App: Failed to get location: ${it.message}")
            speakOut("Failed to get location. Please try again later.") {
                currentState = LocationState.FINISHED
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getAndSendLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (currentState != LocationState.SEND_LOCATION_SEND) return@addOnSuccessListener
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                // Create a Google Maps link for sending.
                val mapsLink = "https://maps.google.com/?q=$lat,$lon"
                postMessage("App: Your location: $mapsLink")
                speakOut("Sending your location") {
                    if (destinationIsNumber) {
                        val cleaned = smsDestination.replace("\\s+".toRegex(), "")
                        sendSms(cleaned, mapsLink)
                    } else {
                        val contactNumber = lookupContact(smsDestination)
                        if (contactNumber != null) {
                            sendSms(contactNumber.toString(), mapsLink)
                        } else {
                            postMessage("App: No contact found with name $smsDestination.")
                            speakOut("No contact found with name $smsDestination") {
                                currentState = LocationState.SEND_LOCATION_ASK_DESTINATION_TYPE
                                autoListen = true
                                promptSpeechInput()
                            }
                            return@speakOut
                        }
                    }
                    currentState = LocationState.FINISHED
                }
            } else {
                postMessage("App: Unable to retrieve location. Please try again later.")
                speakOut("Unable to retrieve location. Please try again later.") {
                    currentState = LocationState.FINISHED
                }
            }
        }.addOnFailureListener {
            postMessage("App: Failed to get location: ${it.message}")
            speakOut("Failed to get location. Please try again later.") {
                currentState = LocationState.FINISHED
            }
        }
    }

    /**
     * Dynamically adds a chat bubble (App or User) into the chat container.
     */


    // Looks up a contact by name and returns the first matching phone number.
    private fun lookupContact(name: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        var contactNumber: String? = null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                contactNumber = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return contactNumber
    }

    // Sends an SMS using SmsManager.
    private fun sendSms(number: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            Toast.makeText(this, "SMS sent successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun postMessage(message: String) {
        // Determine the bubble layout based on the prefix.
        val layoutRes = when {
            message.startsWith("App:") -> R.layout.item_app_message
            message.startsWith("User:") -> R.layout.item_user_message
            else -> R.layout.item_app_message
        }
        val bubbleView = layoutInflater.inflate(layoutRes, chatContainer, false)
        val tvMessage = bubbleView.findViewById<TextView>(R.id.tvMessage)
        // Remove the "App:" or "User:" prefix for display.
        val displayText = message.replace("App:", "").replace("User:", "").trim()
        tvMessage.text = displayText
        chatContainer.addView(bubbleView)
        // Auto-scroll to the bottom.
        chatContainer.post {
            (chatContainer.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * speakOut accepts an optional onDone callback which is triggered when TTS finishes.
     */
    private fun speakOut(message: String, onDone: (() -> Unit)? = null) {
        val utteranceId = UUID.randomUUID().toString()
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (onDone != null) {
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { onDone() }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { onDone() }
                }
            })
        }
    }

    override fun onBackPressed() {
        autoListen = false
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
