package com.projectech.theblindapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

enum class CallState {
    ASK_TYPE, ASK_NUMBER, CONFIRM_NUMBER, ASK_CONTACT, CONFIRM_CONTACT, FINISHED
}

class MakeCallActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var chatContainer: LinearLayout
    private lateinit var btnSpeak: Button
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    private var currentState = CallState.ASK_TYPE
    private var tempNumber: String = ""
    private var tempContact: String = ""

    // Use this flag to control auto-listening (only once per reply)
    private var autoListen: Boolean = true

    companion object {
        const val SPEECH_REQUEST_CODE = 100
        const val CONTACT_PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_make_call)

        // Find views
        chatContainer = findViewById(R.id.chatContainer)
        btnSpeak = findViewById(R.id.btnSpeak)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // Ensure READ_CONTACTS permission is granted
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACT_PERMISSION_REQUEST_CODE
            )
        }

        btnSpeak.setOnClickListener {
            promptSpeechInput()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            // Once TTS is ready, start the conversation.
            postMessage("App: Do you wanna call on a number or saved contact? Reply with either \"number\" or \"contact\"")
            speakOut("Do you wanna call on a number or saved contact? Reply with either number or contact") {
                if (currentState != CallState.FINISHED && autoListen) {
                    promptSpeechInput()
                }
            }
        } else {
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show()
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
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Disable auto prompt until this reply is processed
        autoListen = false

        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)?.lowercase(Locale.US) ?: ""
            postMessage("User: $spokenText")
            processUserResponse(spokenText)
        } else {
            autoListen = true
        }
    }

    private fun processUserResponse(response: String) {
        when (currentState) {
            CallState.ASK_TYPE -> {
                if (response.contains("number")) {
                    currentState = CallState.ASK_NUMBER
                    postMessage("App: Please say the number you wanna call.")
                    speakOut("Please say the number you wanna call") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else if (response.contains("contact")) {
                    currentState = CallState.ASK_CONTACT
                    postMessage("App: Please say the name of the person you wanna call.")
                    speakOut("Please say the name of the person you wanna call") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't understand. Please reply with either number or contact.")
                    speakOut("I didn't understand. Please reply with either number or contact") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            CallState.ASK_NUMBER -> {
                // Format the number so that each digit is read separately.
                tempNumber = response.filter { it.isDigit() || it == '+' }
                if (tempNumber.isNotEmpty()) {
                    currentState = CallState.CONFIRM_NUMBER
                    val formattedNumber = formatNumberForSpeech(tempNumber)
                    postMessage("App: You said $formattedNumber. Is that correct? Please say yes or no.")
                    speakOut("You said $formattedNumber. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't catch a valid number. Please try again.")
                    speakOut("I didn't catch a valid number. Please say the number you wanna call") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            CallState.CONFIRM_NUMBER -> {
                if (response.contains("yes")) {
                    postMessage("App: Calling $tempNumber")
                    speakOut("Calling $tempNumber") {
                        autoListen = false
                        initiateCall(tempNumber)
                        currentState = CallState.FINISHED
                    }
                } else {
                    currentState = CallState.ASK_NUMBER
                    postMessage("App: Please say the number you wanna call again.")
                    speakOut("Okay, please say the number you wanna call again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            CallState.ASK_CONTACT -> {
                tempContact = response.trim()
                if (tempContact.isNotEmpty()) {
                    currentState = CallState.CONFIRM_CONTACT
                    postMessage("App: You said $tempContact. Is that correct? Please say yes or no.")
                    speakOut("You said $tempContact. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postMessage("App: I didn't catch a valid contact name. Please try again.")
                    speakOut("I didn't catch a valid contact name. Please say the name of the person you wanna call") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            CallState.CONFIRM_CONTACT -> {
                if (response.contains("yes")) {
                    // Lookup the contact's number
                    val contactNumber = lookupContact(tempContact)
                    if (contactNumber != null) {
                        postMessage("App: Calling $tempContact")
                        speakOut("Calling $tempContact") {
                            autoListen = false
                            initiateCall(contactNumber)
                            currentState = CallState.FINISHED
                        }
                    } else {
                        postMessage("App: No contact found with name $tempContact.")
                        speakOut("No contact found with name $tempContact") {
                            // Restart contact input
                            currentState = CallState.ASK_CONTACT
                            autoListen = true
                            promptSpeechInput()
                        }
                    }
                } else {
                    currentState = CallState.ASK_CONTACT
                    postMessage("App: Please say the name of the person you wanna call again.")
                    speakOut("Okay, please say the name of the person you wanna call again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }
            CallState.FINISHED -> {
                postMessage("App: Conversation finished.")
                // No auto-prompt when finished.
            }
        }
    }

    // Helper function: Formats a number so that each digit is separated by a space.
    private fun formatNumberForSpeech(number: String): String {
        return number.toCharArray().joinToString(" ") { it.toString() }
    }

    // Looks up a contact by name in the user's contacts and returns the first matching phone number.
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

    // Initiates a phone call using ACTION_CALL.
    private fun initiateCall(number: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            startActivity(callIntent)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Call permission not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dynamically adds a chat bubble (App or User) into chatContainer.
     */
    private fun postMessage(message: String) {
        // 1. Decide if it's an App or User bubble
        val layoutRes = when {
            message.startsWith("App:") -> R.layout.item_app_message
            message.startsWith("User:") -> R.layout.item_user_message
            else -> R.layout.item_app_message // fallback if needed
        }

        // 2. Inflate the correct bubble layout
        val bubbleView = layoutInflater.inflate(layoutRes, chatContainer, false)

        // 3. Get the TextView inside the bubble and set the text
        val tvMessage = bubbleView.findViewById<TextView>(R.id.tvMessage)
        // Remove "App:" or "User:" prefix for display
        val displayText = message
            .replace("App:", "")
            .replace("User:", "")
            .trim()
        tvMessage.text = displayText

        // 4. Add the bubble to chatContainer
        chatContainer.addView(bubbleView)

        // 5. Auto-scroll to the bottom
        chatContainer.post {
            (chatContainer.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * speakOut with optional onDone callback. Uses an utterance listener
     * to trigger the callback once speaking is complete.
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
        // Disable auto-listen on back press.
        autoListen = false
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
