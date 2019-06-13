import com.google.api.gax.rpc.ClientStream
import com.google.api.gax.rpc.ResponseObserver
import com.google.api.gax.rpc.StreamController
import com.google.cloud.dialogflow.v2.*
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.cloud.texttospeech.v1.*
import com.google.protobuf.ByteString
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.*
import javax.sound.sampled.DataLine.Info
import com.google.cloud.dialogflow.v2.QueryInput
import com.google.cloud.dialogflow.v2.SessionName
import com.google.cloud.dialogflow.v2.SessionsClient
import com.google.cloud.dialogflow.v2.EventInput
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.StorageClient
import com.google.firebase.database.*

import java.io.*
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

var firestoreDB: Firestore? = null
var database: FirebaseDatabase? = null
var currentLanguage = "en-US" //or en-US
var sotaIP = "172.20.31.185"

class VocalInterface {
    // Creating shared object
    private val sharedQueue = LinkedBlockingQueue<ByteArray>()
    private var targetDataLine: TargetDataLine? = null
    @Volatile var speech: Boolean = false
    private var clientStream: ClientStream<StreamingRecognizeRequest>? = null
    @Volatile private var textDetected = ""
    private var sessionsClient: SessionsClient? = null
    @Volatile private var textToSpeechClient: TextToSpeechClient? = null
    private var client: SpeechClient? = null
    private var session: SessionName? = null
    private var eventName = ""
    private var userId = "/installation_test_name/5fe6b3ba-2767-4669-ae69-6fdc402e695e"
    private var sotaPort = 9000

    /** Performs infinite streaming speech recognition  */
    @Throws(Exception::class)
    fun infiniteStreamingRecognize() {

        // Microphone Input buffering
        class MicBuffer(var client:Socket?) : Runnable {
            override fun run() {

                // Create the DataInputStream and AudioFormat necessary to acquire the audio from the microphone
                val socketIn = DataInputStream(client?.getInputStream())
                val format = AudioFormat(16000f, 16, 1, true, false)

                // Set the system information to read from the microphone audio stream
                val dataLineInfo = Info(SourceDataLine::class.java, format)
                val speakers = AudioSystem.getLine(dataLineInfo) as SourceDataLine

                // Open the channel with the microphone and start to acquire information from it
                speakers.open(format)
                speakers.start()

                // Now in loop the audio it's catch by the microphone and sent to Sota with a Socke connection
                while (true) {
                    val data = ByteArray(6400)
                    val bais = ByteArrayInputStream(data)
                    val ais = AudioInputStream(bais, format, data.size.toLong())
                    val bytesRead = ais.read(data)
                    if (bytesRead != -1) {
                        socketIn.readFully(data, 0, data.size)
                        speakers.write(data, 0, data.size)
                        sharedQueue.put(data.clone())
                    }
                    ais.close()
                    bais.close()
                }
            }
        }

        // Check Event or response
        class responseReader : Runnable {
            override fun run() {
                println("checking response and event ")
                while (true) {
                    if (!textDetected.isEmpty()) {
                        val textInput = TextInput.newBuilder().setText(textDetected).setLanguageCode(currentLanguage)

                        // Build the query with the TextInput
                        val queryInput = QueryInput.newBuilder().setText(textInput).build()

                        // Performs the detect intent request
                        val responseD = sessionsClient!!.detectIntent(session, queryInput)

                        // Display the query result
                        val queryResult = responseD.queryResult

                        try {
                            printQuery(queryResult)
                            //Query.synthesize(queryResult, textToSpeechClient)
                            synthesizeAndSend(queryResult, textToSpeechClient)
                        } catch (e: Exception) {
                            println(e)
                        }
                        speech = false
                        textDetected = ""

                    } else if (!eventName.isEmpty()) {
                        val event = EventInput.newBuilder().setName(eventName+"_event").setLanguageCode(currentLanguage).build()

                        val queryInput = QueryInput.newBuilder().setEvent(event).build()

                        // Performs the detect intent request
                        val responseD = sessionsClient!!.detectIntent(session, queryInput)

                        // Display the query result
                        val queryResult = responseD.queryResult

                        try {
                            printQuery(queryResult)
                            speech = true
                            //Query.synthesize(queryResult, textToSpeechClient)
                            synthesizeAndSend(queryResult, textToSpeechClient)
                        } catch (e: Exception) {
                            println(e)
                        }
                        speech = false
                        eventName = ""
                    }
                }
            }
        }



        //init
        var noConncted = true
        var clientSocket:Socket? = null


        //wait until the socket connection is established
        while(noConncted) {
            try {
                clientSocket = Socket(sotaIP, sotaPort)
                noConncted = false
                println("Socket connection established")
            } catch (e: Exception) {
                Thread.sleep(2500)
                println("Wainitg for the the socket connection")
            }
        }

        // Creating microphone input buffer thread
        val micrunnable = MicBuffer(clientSocket)
        val micThread = Thread(micrunnable)
        val responsereader = responseReader()
        val readerThread = Thread(responsereader)


        // Credential definition
        /*val serviceAccount = FileInputStream("/Users/tommasaso/Documents/Tesi/IntalliJ/vocalinterface-firebase-adminsdk-3ycvz-afbeeece70.json")
        val options = FirebaseOptions.Builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl("https://vocalinterface.firebaseio.com")
            .build()
        FirebaseApp.initializeApp(options)

        val database = FirebaseDatabase.getInstance()*/
        val myRef = database!!.getReference()

        myRef.child(userId+"/events/confirmMedicineTaken").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() == true && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: confirmMedicineTaken")
            }
        })
        myRef.child(userId+"/events/drugReminderFullStomach").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() == true && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: drugReminderFullStomach")
            }
        })
        myRef.child(userId+"/events/proposingActivity").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.getValue() != "" && eventName == ""){
                    eventName = dataSnapshot.key;
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: proposingActivity")
            }
        })
        myRef.child(userId+"/events/proposingNewActivity/changeIdea").addValueEventListener(object :ValueEventListener{
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                //if (dataSnapshot.getValue().toString().toInt() > 0 && eventName == ""){
                if (dataSnapshot.getValue() == true && eventName == ""){
                    eventName = dataSnapshot.key;
                    println("eventName: "+eventName);
                }
            }
            override fun onCancelled(error: DatabaseError) {
                println("Error on event listener: changeIdea")
            }
        })

        var responseObserver: ResponseObserver<StreamingRecognizeResponse>?
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
                    //println("pingu")
                    println(t)
                }
            }



            clientStream = client!!.streamingRecognizeCallable().splitCall(responseObserver)

            val recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setLanguageCode(currentLanguage)
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


fun main(){
    try {
        initDB()
        initIP()
        VocalInterface().infiniteStreamingRecognize()
    } catch (e: Exception) {
        println("Exception caught: $e")
    }
}

fun synthesizeAndSend(queryResult: QueryResult, textToSpeechClient: TextToSpeechClient?) {
    try {
    if(queryResult.fulfillmentText != "") {
        // Send to Sota if DialogFlow found an intent
        val understood = !queryResult.intent.isFallback


        // Set the text input to be synthesized
        val input = SynthesisInput.newBuilder()
            .setText(queryResult.fulfillmentText)
            .build()

        // Build the voice request, select the language code ("en-US") and the ssml voice gender
        // ("neutral")
        val voice = VoiceSelectionParams.newBuilder()
            //.setLanguageCode("en-GB")
            //.setName("en-GB-Wavenet-C")
            .setLanguageCode(currentLanguage)
            .setName(currentLanguage + "-Wavenet-F")
            .setSsmlGender(SsmlVoiceGender.FEMALE)
            .build()

        // Select the type of audio file you want returned
        val audioConfig = AudioConfig.newBuilder()
            .setAudioEncoding(AudioEncoding.LINEAR16)
            .setPitch(5.5)
            .build()

        // Perform the text-to-speech request on the text input with the selected voice parameters and
        // audio file type
        val responseTTS = textToSpeechClient?.synthesizeSpeech(input, voice, audioConfig)

        // Get the audio contents from the response
        val audioContents = responseTTS?.audioContent

        // Open the socket connection
        val serverSocket = ServerSocket(8450)

        //Wait until the the connection is established
        val socketSota = serverSocket.accept()

        //Generate the Output Streaming
        val socketOut = DataOutputStream(socketSota.getOutputStream())

        //Send via socket protocol the size of the Audio Synthesized
        socketOut.writeInt(audioContents?.toByteArray()!!.size)

        //Send via socket protocol the audio file
        socketOut.write(audioContents.toByteArray(), 0, audioContents.toByteArray()!!.size)

        //Send via socket protocol if the sentence was understood
        socketOut.writeBoolean(understood)

        //Close the socket connection
        serverSocket.close()

        /**To reproduce the Synthesized Speech also Here*/
        //TODO("TO BE REMOVED - USELESS")
        val out = FileOutputStream("output.wav")
        out.write(audioContents.toByteArray())
        val file = File(System.getProperty("user.dir") + "/output.wav")

        val stream = AudioSystem.getAudioInputStream(file)
        val format = stream.format
        val info = DataLine.Info(Clip::class.java, format)
        val clip = AudioSystem.getLine(info) as Clip
        clip.open(stream)
        clip.start()
        while (clip.microsecondLength != clip.microsecondPosition) {
        }
    }

    } catch (e: Exception) {
        println(e)
    }
}

fun printQuery(queryResult: QueryResult) {
    println("====================")
    System.out.format("Query Text: '%s'\n", queryResult.queryText)
    System.out.format( "Detected Intent: %s (confidence: %f)\n",
        queryResult.intent.displayName, queryResult.intentDetectionConfidence )
    System.out.format("Fulfillment Text: '%s'\n", queryResult.fulfillmentText)
}


fun publicIP() {
    val url = URL("http://checkip.amazonaws.com/")
    val br = BufferedReader(InputStreamReader(url.openStream()))
    val docRef = firestoreDB!!.collection("Home").document("VocalInterface")
    val future = docRef.update("hasIP", br.readLine().toString())
    val result = future.get()
}


fun initDB() {

    // Fetch the service account key JSON file contents
    val serviceAccount = FileInputStream("/Users/tommasaso/Documents/Tesi/IntalliJ/vocalinterface-firebase-adminsdk-3ycvz-8068c39321.json")

    // Initialize the app with a service account, granting admin privileges
    val options = FirebaseOptions.Builder()
        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
        .setDatabaseUrl("https://vocalinterface.firebaseio.com")
        .setStorageBucket("vocalinterface.appspot.com")
        .build()
    // Initialize app for RealtimeDB
    FirebaseApp.initializeApp(options)

    val optionsFirestore = FirestoreOptions
        .newBuilder()
        .setTimestampsInSnapshotsEnabled(true)
        .build()

    database = FirebaseDatabase.getInstance()

    firestoreDB = optionsFirestore.service
}
fun initIP(){
    var localSotaIP = ""
    val myIP = getIP()
    if (myIP != ""){
        database!!.getReference().child("installation_test_name/VocalInterface/ViHasIP").setValueAsync(myIP)
    }else{
        println("Error int the IP acquisition")
    }

    database!!.getReference().child("installation_test_name/VocalInterface/SotaHasIP").addListenerForSingleValueEvent(object :ValueEventListener{
        override fun onDataChange(dataSnapshot: DataSnapshot) {
            val end = System.currentTimeMillis() + 10000

            do {
                localSotaIP = dataSnapshot.value.toString()
                if (System.currentTimeMillis() > end) {
                    break
                }
            }while (sotaIP.equals(""))
            println("SotaIP: $sotaIP")
            sotaIP = localSotaIP
        }
        override fun onCancelled(error: DatabaseError) {
            println("Error in the Sota's IP acquisition")
        }
    })
}

fun getIP():String{
    val n = NetworkInterface.getNetworkInterfaces()
    while (n.hasMoreElements()) { //for each interface
        val e = n.nextElement()
        val a = e.inetAddresses
        while (a.hasMoreElements()) {
            val addr = a.nextElement()
            val add = addr.hostAddress.toString()
            if(e.name == "en0" && add.length < 17){
                println("IPv4 Address: $add")
                return add
            }
        }
    }
    return ""
}



