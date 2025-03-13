package com.projectech.theblindapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.*

enum class SmsState {
    ASK_TYPE,
    ASK_DESTINATION,
    CONFIRM_DESTINATION_NUMBER,
    CONFIRM_DESTINATION_CONTACT,
    ASK_MESSAGE,
    CONFIRM_MESSAGE,
    FINISHED
}

class SendSMSActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var btnSmsSpeak: Button
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    // We'll dynamically insert chat bubbles into this container
    private lateinit var chatContainer: LinearLayout

    private var currentState = SmsState.ASK_TYPE
    private var smsDestination: String = "" // either the number or contact name
    private var smsMessage: String = ""
    // Flag to remember if the destination is a number
    private var destinationIsNumber: Boolean = false

    // Controls auto prompt so that mic opens only once per reply
    private var autoListen: Boolean = true

    companion object {
        const val SPEECH_REQUEST_CODE_SMS = 200
        const val CONTACT_PERMISSION_REQUEST_CODE_SMS = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_sms)

        // Find views
        chatContainer = findViewById(R.id.chatContainer)
        btnSmsSpeak = findViewById(R.id.btnSmsSpeak)

        // Init TTS and SpeechRecognizer
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // Request necessary permissions
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS),
                CONTACT_PERMISSION_REQUEST_CODE_SMS
            )
        }

        // Speak button
        btnSmsSpeak.setOnClickListener {
            promptSpeechInput()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            // Start conversation.
            postSmsMessage("App: Do you want to send an SMS to a number or saved contact? Reply with either \"number\" or \"contact\"")
            speakOut("Do you want to send an SMS to a number or saved contact? Reply with either number or contact") {
                if (currentState != SmsState.FINISHED && autoListen) {
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
            startActivityForResult(intent, SPEECH_REQUEST_CODE_SMS)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Disable auto prompt until reply is processed.
        autoListen = false

        if (requestCode == SPEECH_REQUEST_CODE_SMS && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)?.toLowerCase(Locale.US) ?: ""
            postSmsMessage("User: $spokenText")  // <--- Now posts a user bubble
            processSmsResponse(spokenText)
        } else {
            autoListen = true
        }
    }

    private fun processSmsResponse(response: String) {
        when (currentState) {
            SmsState.ASK_TYPE -> {
                if (response.contains("number")) {
                    currentState = SmsState.ASK_DESTINATION
                    postSmsMessage("App: Please say the number you want to text.")
                    speakOut("Please say the number you want to text") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else if (response.contains("contact")) {
                    currentState = SmsState.ASK_DESTINATION
                    postSmsMessage("App: Please say the name of the contact you want to text.")
                    speakOut("Please say the name of the contact you want to text") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postSmsMessage("App: I didn't understand. Please reply with either number or contact.")
                    speakOut("I didn't understand. Please reply with either number or contact") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.ASK_DESTINATION -> {
                smsDestination = response.trim()
                if (smsDestination.isNotEmpty()) {
                    val cleanedDestination = smsDestination.replace("\\s+".toRegex(), "")
                    if (cleanedDestination.all { it.isDigit() || it == '+' }) {
                        destinationIsNumber = true
                        currentState = SmsState.CONFIRM_DESTINATION_NUMBER
                        val formattedNumber = formatNumberForSpeech(cleanedDestination)
                        postSmsMessage("App: You said $formattedNumber. Is that correct? Please say yes or no.")
                        speakOut("You said $formattedNumber. Is that correct? Please say yes or no") {
                            autoListen = true
                            promptSpeechInput()
                        }
                    } else {
                        destinationIsNumber = false
                        currentState = SmsState.CONFIRM_DESTINATION_CONTACT
                        postSmsMessage("App: You said $smsDestination. Is that correct? Please say yes or no.")
                        speakOut("You said $smsDestination. Is that correct? Please say yes or no") {
                            autoListen = true
                            promptSpeechInput()
                        }
                    }
                } else {
                    postSmsMessage("App: I didn't catch a valid destination. Please try again.")
                    speakOut("I didn't catch a valid destination. Please say the number or contact name") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.CONFIRM_DESTINATION_NUMBER -> {
                if (response.contains("yes")) {
                    currentState = SmsState.ASK_MESSAGE
                    postSmsMessage("App: Please say the message you want to send to this number.")
                    speakOut("Please say the message you want to send to this number") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    currentState = SmsState.ASK_DESTINATION
                    postSmsMessage("App: Please say the number you want to text again.")
                    speakOut("Okay, please say the number you want to text again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.CONFIRM_DESTINATION_CONTACT -> {
                if (response.contains("yes")) {
                    currentState = SmsState.ASK_MESSAGE
                    postSmsMessage("App: Please say the message you want to send to this contact.")
                    speakOut("Please say the message you want to send to this contact") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    currentState = SmsState.ASK_DESTINATION
                    postSmsMessage("App: Please say the contact name you want to text again.")
                    speakOut("Okay, please say the contact name you want to text again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.ASK_MESSAGE -> {
                smsMessage = response.trim()
                if (smsMessage.isNotEmpty()) {
                    currentState = SmsState.CONFIRM_MESSAGE
                    postSmsMessage("App: You said: \"$smsMessage\". Is that correct? Please say yes or no.")
                    speakOut("You said: $smsMessage. Is that correct? Please say yes or no") {
                        autoListen = true
                        promptSpeechInput()
                    }
                } else {
                    postSmsMessage("App: I didn't catch a message. Please try again.")
                    speakOut("I didn't catch a message. Please say the message you want to send") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.CONFIRM_MESSAGE -> {
                if (response.contains("yes")) {
                    if (destinationIsNumber) {
                        postSmsMessage("App: Sending SMS to $smsDestination")
                        speakOut("Sending SMS to $smsDestination") {
                            autoListen = false
                            val cleaned = smsDestination.replace("\\s+".toRegex(), "")
                            sendSms(cleaned, smsMessage)
                            currentState = SmsState.FINISHED
                        }
                    } else {
                        val contactNumber = lookupContact(smsDestination)
                        if (contactNumber != null) {
                            postSmsMessage("App: Sending SMS to $smsDestination")
                            speakOut("Sending SMS to $smsDestination") {
                                autoListen = false
                                sendSms(contactNumber, smsMessage)
                                currentState = SmsState.FINISHED
                            }
                        } else {
                            postSmsMessage("App: No contact found with name $smsDestination.")
                            speakOut("No contact found with name $smsDestination") {
                                currentState = SmsState.ASK_DESTINATION
                                autoListen = true
                                promptSpeechInput()
                            }
                        }
                    }
                } else {
                    currentState = SmsState.ASK_MESSAGE
                    postSmsMessage("App: Please say the message you want to send again.")
                    speakOut("Okay, please say the message you want to send again") {
                        autoListen = true
                        promptSpeechInput()
                    }
                }
            }

            SmsState.FINISHED -> {
                postSmsMessage("App: SMS process finished.")
                // End conversation; do not prompt further.
            }
        }
    }

    /**
     * Dynamically adds a chat bubble (App or User) into chatContainer.
     */
    private fun postSmsMessage(message: String) {
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

    // Helper: Formats a number so each digit is spoken clearly.
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
                contactNumber = it.getString(
                    it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                )
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
