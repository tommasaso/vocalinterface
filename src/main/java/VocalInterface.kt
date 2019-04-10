import com.google.api.gax.rpc.ClientStream
import com.google.api.gax.rpc.ResponseObserver
import com.google.api.gax.rpc.StreamController
import com.google.cloud.dialogflow.v2.*
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechRecognitionAlternative
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognitionResult
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.cloud.texttospeech.v1.*
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.protobuf.ByteString
import java.util.ArrayList
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.*
import javax.sound.sampled.DataLine.Info

import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.core.*
import com.google.cloud.dialogflow.v2.QueryInput
import com.google.cloud.dialogflow.v2.QueryResult
import com.google.cloud.dialogflow.v2.SessionName
import com.google.cloud.dialogflow.v2.SessionsClient
import com.google.cloud.dialogflow.v2.TextInput.Builder
import com.google.cloud.dialogflow.v2.EventInput
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.cloud.dialogflow.v2beta1.EventInputOrBuilder

// Imports the Google Cloud client library
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.*

class VocalInterface {
    // Creating shared object
    private val sharedQueue = LinkedBlockingQueue<ByteArray>()
    private var targetDataLine: TargetDataLine? = null
    private val BYTES_PER_BUFFER = 6400 // buffer size in bytes
    @Volatile var speech: Boolean = false
    private var clientStream: ClientStream<StreamingRecognizeRequest>? = null
    @Volatile private var textDetected = ""
    private var sessionsClient: SessionsClient? = null
    @Volatile private var textToSpeechClient: TextToSpeechClient? = null
    private var client: SpeechClient? = null
    private var session: SessionName? = null
    private var eventName = ""

    /** Performs infinite streaming speech recognition  */
    @Throws(Exception::class)
    fun infiniteStreamingRecognize() {

        // Microphone Input buffering
        class MicBuffer : Runnable {
            override fun run() {
                println("Start speaking...Press Ctrl-C to stop")
                targetDataLine!!.start()
                val data = ByteArray(BYTES_PER_BUFFER)
                while (targetDataLine!!.isOpen) {
                    try {
                        val numBytesRead = targetDataLine!!.read(data, 0, data.size)
                        if (numBytesRead <= 0 && targetDataLine!!.isOpen) {
                            continue
                        }
                        sharedQueue.put(data.clone())
                        //System.out.println("data = "+data.clone());
                    } catch (e: InterruptedException) {
                        println("Microphone input buffering interrupted : " + e.message)
                    }
                }
            }
        }

        // Check Event or response
        class responseReader : Runnable {
            override fun run() {
                println("checking response and event ")
                while (true) {
                    if (!textDetected.isEmpty()) {
                        val textInput = TextInput.newBuilder().setText(textDetected).setLanguageCode("en-US")

                        // Build the query with the TextInput
                        val queryInput = QueryInput.newBuilder().setText(textInput).build()

                        // Performs the detect intent request
                        val responseD = sessionsClient!!.detectIntent(session, queryInput)

                        // Display the query result
                        val queryResult = responseD.queryResult

                        try {
                            Query.print(queryResult)
                            Query.synthesize(queryResult, textToSpeechClient)
                        } catch (e: Exception) {
                            println(e)
                        }
                        speech = false
                        textDetected = ""

                    } else if (!eventName.isEmpty()) {
                        val event = EventInput.newBuilder().setName(eventName+"_event").setLanguageCode("en-US").build()

                        val queryInput = QueryInput.newBuilder().setEvent(event).build()

                        // Performs the detect intent request
                        val responseD = sessionsClient!!.detectIntent(session, queryInput)

                        // Display the query result
                        val queryResult = responseD.queryResult

                        try {
                            Query.print(queryResult)
                            speech = true
                            Query.synthesize(queryResult, textToSpeechClient)
                        } catch (e: Exception) {
                            println(e)
                        }
                        speech = false
                        eventName = ""
                    }
                }
            }
        }
        // Creating microphone input buffer thread
        val micrunnable = MicBuffer()
        val micThread = Thread(micrunnable)
        val responsereader = responseReader()
        val readerThread = Thread(responsereader)

        val serviceAccount = FileInputStream("/Users/tommasaso/Documents/Tesi/IntalliJ/vocalinterface-firebase-adminsdk-3ycvz-afbeeece70.json")
        val options = FirebaseOptions.Builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl("https://vocalinterface.firebaseio.com")
            .build()
        FirebaseApp.initializeApp(options)

        val database = FirebaseDatabase.getInstance()
        val myRef = database.getReference()


        myRef.child("/events/confirmMedicineTaken").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() == true && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: confirmMedicineTaken")
            }
        })
        myRef.child("/events/drugReminderFullStomach").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() == true && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: drugReminderFullStomach")
            }
        })
        myRef.child("/events/proposingActivity").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() != "" && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: proposingActivity")
            }
        })
        myRef.child("/events/proposingNewActivity").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() != "" && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: proposingActivity")
            }
        })


        var responseObserver: ResponseObserver<StreamingRecognizeResponse>? = null
        try {
            client = SpeechClient.create()
            sessionsClient = SessionsClient.create()
            textToSpeechClient = TextToSpeechClient.create()
            // Set the session name using the sessionId (UUID) and projectID (my-project-id)
            session = SessionName.of("vocalinterface", "123456789098765")
            println("Session Path: " + session!!.toString())

            responseObserver = object : ResponseObserver<StreamingRecognizeResponse> {

                override fun onStart(controller: StreamController) {}

                override fun onResponse(response: StreamingRecognizeResponse) {
                    try {
                        speech = true

                        val result = response.resultsList[0]
                        // There can be several alternative transcripts for a given chunk of speech. Just
                        // use the first (most likely) one here.
                        val alternative = result.alternativesList[0]
                        println("transcript: " + alternative.transcript + "\nconfindece: " + alternative.confidence)

                        if (alternative.confidence > 0.5) {
                            textDetected = alternative.transcript

                        }
                    } catch (e: Exception) {
                        println(e)
                    }

                }

                override fun onComplete() {
                    println("Done")
                }

                override fun onError(t: Throwable) {
                    println("pingu")
                    println(t)
                }
            }

            clientStream = client!!.streamingRecognizeCallable().splitCall(responseObserver)

            val recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setLanguageCode("en-US")
                .setSampleRateHertz(16000)
                .build()
            val streamingRecognitionConfig =
                StreamingRecognitionConfig.newBuilder().setConfig(recognitionConfig).build()

            var request = StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingRecognitionConfig)
                .build() // The first request in a streaming call has to be a config

            clientStream!!.send(request)

            try {
                // SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true, bigEndian: false
                val audioFormat = AudioFormat(16000f, 16, 1, true, false)
                val targetInfo = Info(
                    TargetDataLine::class.java,
                    audioFormat
                ) // Set the system information to read from the microphone audio stream

                if (!AudioSystem.isLineSupported(targetInfo)) {
                    println("Microphone not supported")
                    System.exit(0)
                }
                // Target data line captures the audio stream the microphone produces.
                targetDataLine = AudioSystem.getLine(targetInfo) as TargetDataLine
                targetDataLine!!.open(audioFormat)
                micThread.start()
                readerThread.start()

                var startTime = System.currentTimeMillis()
                speech = false

                while (true) {
                    val estimatedTime = System.currentTimeMillis() - startTime
                    if (estimatedTime >= 55000) {
                        clientStream!!.closeSend()
                        clientStream = client!!.streamingRecognizeCallable().splitCall(responseObserver)

                        request = StreamingRecognizeRequest.newBuilder()
                            .setStreamingConfig(streamingRecognitionConfig)
                            .build()

                        startTime = System.currentTimeMillis()
                        clientStream!!.send(request)
                    } else {
                        request = StreamingRecognizeRequest.newBuilder()
                            .setAudioContent(ByteString.copyFrom(sharedQueue.take()))
                            .build()
                        if (speech == false) {
                            clientStream!!.send(request)
                        }
                    }
                }
            } catch (e: Exception) {
                println(e)
            }

        } catch (e: Exception) {
            println(e)
        }

    }
}


fun main(args : Array<String>){
    try {
        VocalInterface().infiniteStreamingRecognize();
    } catch (e: Exception) {
        println("Exception caught: $e")
    }

}
