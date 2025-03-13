package com.projectech.theblindapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.*

enum class NavigationState {
    NO_SAVED_FOUND_PROMPT,      // No saved destinations exist.
    CHOOSE_SAVED_OR_NEW,        // Ask: "Where do you want to go? Your saved addresses are ... "
    CONFIRM_SAVED_DESTINATION,  // Confirm a chosen saved destination.
    GET_DIRECTIONS,             // Retrieve current location and launch maps.
    FINISHED
}

class NavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var chatContainer: LinearLayout
    private lateinit var btnSpeak: Button
    private lateinit var btnGps: ImageButton
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences

    private var currentState = NavigationState.NO_SAVED_FOUND_PROMPT
    private var destinationName: String = ""
    private var autoListen = true

    companion object {
        const val SPEECH_REQUEST_CODE_NAV = 500
        const val LOCATION_PERMISSION_REQUEST_CODE_NAV = 600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        chatContainer = findViewById(R.id.chatContainer)
        btnSpeak = findViewById(R.id.btnNavigationSpeak)
        btnGps = findViewById(R.id.btnGps)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("Destinations", MODE_PRIVATE)

        // Determine initial state based on saved destinations.
        val savedDestinations = getSavedDestinations()
        currentState = if (savedDestinations.isEmpty()) {
            NavigationState.NO_SAVED_FOUND_PROMPT
        } else {
            NavigationState.CHOOSE_SAVED_OR_NEW
        }

        // Request location permissions if not granted.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE_NAV
            )
        }

        btnSpeak.setOnClickListener { promptSpeechInput() }
        btnGps.setOnClickListener { openSavedAddressesDialog() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US

            if (currentState == NavigationState.NO_SAVED_FOUND_PROMPT) {
                // No addresses found. Prompt user to add via the dialog (not voice).
                postMessage("App: No saved address found. Please add new addresses by clicking the top right Button.")
                speakOut("No saved address found. Please add new addresses by clicking the top right Button.") {
                    currentState = NavigationState.FINISHED
                }
            } else {
                // Some addresses exist; prompt user to choose one.
                val saved = getSavedDestinations().joinToString(", ") { it.capitalize(Locale.US) }
                postMessage("App: Where do you want to go? Your saved addresses are $saved.")
                speakOut("Where do you want to go? Your saved addresses are $saved.") {
                    if (currentState != NavigationState.FINISHED && autoListen) {
                        promptSpeechInput()
                    }
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
            startActivityForResult(intent, SPEECH_REQUEST_CODE_NAV)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        autoListen = false
        if (requestCode == SPEECH_REQUEST_CODE_NAV && resultCode == RESULT_OK) {
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
            NavigationState.NO_SAVED_FOUND_PROMPT -> {
                // We no longer allow adding by voice, so just end or do nothing.
                postMessage("App: No saved addresses found. Please add a new one from the Saved Addresses.")
                speakOut("No saved addresses found. Please add a new one from the Saved Addresses.") {
                    currentState = NavigationState.FINISHED
                }
            }

            NavigationState.CHOOSE_SAVED_OR_NEW -> {
                // User must say one of the existing addresses
                destinationName = response.trim()
                val saved = getSavedDestinations()

                // CASE-INSENSITIVE SEARCH
                val matchedName = saved.firstOrNull { it.equals(destinationName, ignoreCase = true) }

                if (matchedName != null) {
                    // Found a saved address
                    currentState = NavigationState.CONFIRM_SAVED_DESTINATION
                    postMessage("App: You chose $matchedName. Is that correct? Please say yes or no.")
                    speakOut("You chose $matchedName. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    // No match
                    postMessage("App: No saved destination found for $destinationName. Please add a new one from the Saved Addresses.")
                    speakOut("No saved destination found for $destinationName. Please add a new one from the Saved Addresses.") {
                        currentState = NavigationState.FINISHED
                    }
                }
            }

            NavigationState.CONFIRM_SAVED_DESTINATION -> {
                if (response.contains("yes")) {
                    currentState = NavigationState.GET_DIRECTIONS
                    postMessage("App: Getting directions to $destinationName...")
                    speakOut("Getting directions to $destinationName") {
                        getCurrentLocationAndNavigate(destinationName)
                    }
                } else {
                    // Let user try again or just finish
                    val savedList = getSavedDestinations().joinToString(", ") { it.capitalize(Locale.US) }
                    postMessage("App: Please say the destination name again. Your saved addresses are: $savedList.")
                    speakOut("Please say the destination name again. Your saved addresses are: $savedList.") {
                        autoListen = true
                        promptSpeechInput()
                    }
                    currentState = NavigationState.CHOOSE_SAVED_OR_NEW
                }
            }

            else -> {
                // GET_DIRECTIONS or FINISHED, no-op
            }
        }
    }

    // Retrieves saved destination names from SharedPreferences.
    private fun getSavedDestinations(): Set<String> {
        return prefs.getStringSet("destination_names", emptySet()) ?: emptySet()
    }

    // Save current location as the given destination name.
    @SuppressLint("MissingPermission")
    private fun saveCurrentLocationAsDestination(name: String) {
        // You can still keep this function if you want to save from the UI (dialog),
        // but it's no longer called via voice flow.
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val editor = prefs.edit()
                editor.putFloat("${name}_lat", location.latitude.toFloat())
                editor.putFloat("${name}_lon", location.longitude.toFloat())
                val currentNames = prefs.getStringSet("destination_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                currentNames.add(name)
                editor.putStringSet("destination_names", currentNames)
                editor.apply()
                postMessage("App: Saved your current location as $name.")
                speakOut("Saved your current location as $name.") {
                    currentState = NavigationState.GET_DIRECTIONS
                    getCurrentLocationAndNavigate(name)
                }
            } else {
                postMessage("App: Unable to retrieve location for saving.")
                speakOut("Unable to retrieve location for saving.") {
                    currentState = NavigationState.FINISHED
                }
            }
        }.addOnFailureListener {
            postMessage("App: Failed to get location: ${it.message}")
            speakOut("Failed to get location. Please try again later.") {
                currentState = NavigationState.FINISHED
            }
        }
    }

    // Flag to prevent multiple maps launches.
    private var navigationLaunched = false

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndNavigate(name: String) {
        if (navigationLaunched) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (currentState != NavigationState.GET_DIRECTIONS) return@addOnSuccessListener
            if (location != null) {
                val destLat = prefs.getFloat("${name}_lat", 0f).toDouble()
                val destLon = prefs.getFloat("${name}_lon", 0f).toDouble()
                if (destLat == 0.0 || destLon == 0.0) {
                    postMessage("App: Saved destination coordinates not found.")
                    speakOut("Saved destination coordinates not found.")
                    currentState = NavigationState.FINISHED
                    navigationLaunched = true
                    return@addOnSuccessListener
                }
                val gmmIntentUri = Uri.parse("google.navigation:q=$destLat,$destLon&mode=w")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                    postMessage("App: Launching Google Maps for navigation.")
                    speakOut("Launching Google Maps for navigation.")
                } else {
                    postMessage("App: Google Maps is not installed.")
                    speakOut("Google Maps is not installed.")
                }
                currentState = NavigationState.FINISHED
                navigationLaunched = true
            } else {
                postMessage("App: Unable to retrieve your current location.")
                speakOut("Unable to retrieve your current location.") {
                    currentState = NavigationState.FINISHED
                }
            }
        }.addOnFailureListener {
            postMessage("App: Failed to get location: ${it.message}")
            speakOut("Failed to get location. Please try again later.") {
                currentState = NavigationState.FINISHED
            }
        }
    }

    /**
     * Dynamically adds a chat bubble (App or User) into chatContainer.
     */
    private fun postMessage(message: String) {
        val layoutRes = when {
            message.startsWith("App:") -> R.layout.item_app_message
            message.startsWith("User:") -> R.layout.item_user_message
            else -> R.layout.item_app_message
        }
        val bubbleView = layoutInflater.inflate(layoutRes, chatContainer, false)
        val tvMessage = bubbleView.findViewById<TextView>(R.id.tvMessage)
        val displayText = message.replace("App:", "").replace("User:", "").trim()
        tvMessage.text = displayText
        chatContainer.addView(bubbleView)
        chatContainer.post {
            (chatContainer.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * speakOut accepts an optional onDone callback triggered when TTS finishes.
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

    // Opens a dialog showing saved addresses and options to add new or remove.
    private fun openSavedAddressesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_saved_addresses, null)
        val listView = dialogView.findViewById<ListView>(R.id.listSavedAddresses)
        val btnAddNew = dialogView.findViewById<Button>(R.id.btnAddNew)
        val btnRemove = dialogView.findViewById<Button>(R.id.btnRemove)

        val savedList = getSavedDestinations().toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedList)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Saved Addresses")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        // "Add New" uses the custom dialog, not voice
        btnAddNew.setOnClickListener {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val dialogView = layoutInflater.inflate(R.layout.dialog_add_address, null)
                val etName = dialogView.findViewById<EditText>(R.id.etName)
                val etCoordinates = dialogView.findViewById<EditText>(R.id.etCoordinates)

                val alertDialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .create()

                dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
                    alertDialog.dismiss()
                }

                dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
                    val name = etName.text.toString().trim().lowercase(Locale.US)
                    val coordsString = etCoordinates.text.toString().trim()

                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Parse the typed coordinates
                    val latLon = coordsString.split(",")
                    if (latLon.size == 2) {
                        val lat = latLon[0].trim().toDoubleOrNull()
                        val lon = latLon[1].trim().toDoubleOrNull()
                        if (lat != null && lon != null) {
                            // Store the user-typed coordinates
                            val editor = prefs.edit()
                            editor.putFloat("${name}_lat", lat.toFloat())
                            editor.putFloat("${name}_lon", lon.toFloat())

                            val currentNames = prefs.getStringSet("destination_names", mutableSetOf())
                                ?.toMutableSet() ?: mutableSetOf()
                            currentNames.add(name)
                            editor.putStringSet("destination_names", currentNames)
                            editor.apply()

                            Toast.makeText(this, "Saved $name with custom coordinates", Toast.LENGTH_SHORT).show()
                            alertDialog.dismiss()
                        } else {
                            Toast.makeText(this, "Invalid coordinates format", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "Please enter coordinates in the format: lat, lon", Toast.LENGTH_SHORT).show()
                    }
                }
                alertDialog.show()
            }
        }


        // "Remove" an existing destination
        btnRemove.setOnClickListener {
            if (savedList.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Remove Destination")
                    .setItems(savedList.toTypedArray()) { _, which ->
                        val nameToRemove = savedList[which]
                        val editor = prefs.edit()
                        val currentNames = prefs.getStringSet("destination_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        currentNames.remove(nameToRemove)
                        editor.putStringSet("destination_names", currentNames)
                        editor.remove("${nameToRemove}_lat")
                        editor.remove("${nameToRemove}_lon")
                        editor.apply()
                        Toast.makeText(this, "Removed $nameToRemove", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No saved destinations to remove", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
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
