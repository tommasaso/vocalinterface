import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.dialogflow.v2.*;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.StreamingRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.cloud.texttospeech.v1.*;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.sound.sampled.*;
import javax.sound.sampled.DataLine.Info;

import com.google.api.gax.rpc.ApiStreamObserver;
import com.google.api.gax.core.*;
import com.google.cloud.dialogflow.v2.QueryInput;
import com.google.cloud.dialogflow.v2.QueryResult;
import com.google.cloud.dialogflow.v2.SessionName;
import com.google.cloud.dialogflow.v2.SessionsClient;
import com.google.cloud.dialogflow.v2.TextInput.Builder;
import com.google.cloud.dialogflow.v2.EventInput;
import com.google.cloud.dialogflow.v2beta1.EventInputOrBuilder;

import java.util.concurrent.LinkedBlockingQueue;
// Imports the Google Cloud client library
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.*;
import java.io.*;
import javax.sound.sampled.*;

public class vocalinterface {
    // Creating shared object
    private static volatile BlockingQueue<byte[]> sharedQueue = new LinkedBlockingQueue();
    private static TargetDataLine targetDataLine;
    private static int BYTES_PER_BUFFER = 6400; // buffer size in bytes
    public static volatile boolean speech;
    private static ClientStream<StreamingRecognizeRequest> clientStream;
    private static volatile String textDetected = "";
    private static SessionsClient sessionsClient;
    private static TextToSpeechClient textToSpeechClient;
    private static SpeechClient client;
    private static SessionName session;
    private static String eventName = "";

    public static void main(String... args) {
        try {
            infiniteStreamingRecognize();
        } catch (Exception e) {
            System.out.println("Exception caught: " + e);
        }
    }
    /** Performs infinite streaming speech recognition */
    static public void infiniteStreamingRecognize() throws Exception {

        // Microphone Input buffering
        class MicBuffer implements Runnable {
            @Override
            public void run() {
                System.out.println("Start speaking...Press Ctrl-C to stop");
                targetDataLine.start();
                byte[] data = new byte[BYTES_PER_BUFFER];
                while (targetDataLine.isOpen()) {
                    try {
                        int numBytesRead = targetDataLine.read(data, 0, data.length);
                        if ((numBytesRead <= 0) && (targetDataLine.isOpen())) {
                            continue;
                        }
                        sharedQueue.put(data.clone());
                        //System.out.println("data = "+data.clone());
                    } catch (InterruptedException e) {
                        System.out.println("Microphone input buffering interrupted : " + e.getMessage());
                    }
                }
            }
        }
        // Check Event or response
        class responseReader implements Runnable {
            @Override
            public void run() {
                System.out.println("checking response and event ");
                while (true){
                    if(!textDetected.isEmpty()){
                        Builder textInput = TextInput.newBuilder().setText(textDetected).setLanguageCode("en-US");

                        // Build the query with the TextInput
                        QueryInput queryInput = QueryInput.newBuilder().setText(textInput).build();
                        System.out.println("queryInput= "+ queryInput);

                        // Performs the detect intent request
                        DetectIntentResponse responseD = sessionsClient.detectIntent(session, queryInput);

                        // Display the query result
                        QueryResult queryResult = responseD.getQueryResult();

                        try{
                            Query.print(queryResult);
                            Query.synthesize(queryResult,textToSpeechClient);
                        }catch (Exception e) {
                            System.out.println(e);
                        }
                        speech = false;

                        textDetected = "";
                    }
                    else if(!eventName.isEmpty()){
                        EventInput event = EventInput.newBuilder().setName("test_event").setLanguageCode("en-US").build();

                        QueryInput queryInput = QueryInput.newBuilder().setEvent(event).build();

                        // Performs the detect intent request
                        DetectIntentResponse responseD = sessionsClient.detectIntent(session, queryInput);

                        // Display the query result
                        QueryResult queryResult = responseD.getQueryResult();

                        try{
                            Query.print(queryResult);
                            speech = true;
                            Query.synthesize(queryResult,textToSpeechClient);
                        }catch (Exception e) {
                            System.out.println(e);
                        }
                        speech = false;
                        eventName = "";
                    }
                }
            }
        }
        // Creating microphone input buffer thread
        MicBuffer micrunnable = new MicBuffer();
        Thread micThread = new Thread(micrunnable);
        responseReader responsereader = new responseReader();
        Thread readerThread = new Thread(responsereader);

        ResponseObserver<StreamingRecognizeResponse> responseObserver = null;
        try {
            client = SpeechClient.create();
            sessionsClient = SessionsClient.create();
            textToSpeechClient = TextToSpeechClient.create();
            // Set the session name using the sessionId (UUID) and projectID (my-project-id)
            session = SessionName.of("vocalinterface", "123456789098765");
            System.out.println("Session Path: " + session.toString());

            responseObserver =
                    new ResponseObserver<StreamingRecognizeResponse>() {

                        public void onStart(StreamController controller) {}

                        public void onResponse(StreamingRecognizeResponse response) {
                            try {
                                speech = true;

                                StreamingRecognitionResult result = response.getResultsList().get(0);
                                // There can be several alternative transcripts for a given chunk of speech. Just
                                // use the first (most likely) one here.
                                SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
                                System.out.println("transcript: " + alternative.getTranscript() + "\nconfindece: " + alternative.getConfidence());

                                if (alternative.getConfidence() > 0.5) {
                                    textDetected = alternative.getTranscript();
//                                Builder textInput = TextInput.newBuilder().setText(alternative.getTranscript()).setLanguageCode("en-US");
//
//                                // Build the query with the TextInput
//                                QueryInput queryInput = QueryInput.newBuilder().setText(textInput).build();
//
//                                // Performs the detect intent request
//                                DetectIntentResponse responseD = sessionsClient.detectIntent(session, queryInput);
//
//                                // Display the query result
//                                QueryResult queryResult = responseD.getQueryResult();
//
//                                System.out.println("====================");
//                                System.out.format("Query Text: '%s'\n", queryResult.getQueryText());
//                                System.out.format("Detected Intent: %s (confidence: %f)\n",
//                                        queryResult.getIntent().getDisplayName(), queryResult.getIntentDetectionConfidence());
//                                System.out.format("Fulfillment Text: '%s'\n", queryResult.getFulfillmentText());
//                                System.out.printf("Transcript : %s\n", alternative);
//
//                                //////////////
//                                // Set the text input to be synthesized
//                                SynthesisInput input = SynthesisInput.newBuilder()
//                                        .setText(queryResult.getFulfillmentText())
//                                        .build();
//
//                                // Build the voice request, select the language code ("en-US") and the ssml voice gender
//                                // ("neutral")
//                                VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
//                                        .setLanguageCode("en-GB")
//                                        .setName("en-GB-Wavenet-C")
//                                        .setSsmlGender(SsmlVoiceGender.FEMALE)
//                                        .build();
//
//                                // Select the type of audio file you want returned
//                                AudioConfig audioConfig = AudioConfig.newBuilder()
//                                        .setAudioEncoding(AudioEncoding.LINEAR16)
//                                        .build();
//
//                                // Perform the text-to-speech request on the text input with the selected voice parameters and
//                                // audio file type
//                                SynthesizeSpeechResponse responseTTS = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
//
//                                // Get the audio contents from the response
//
//                                ByteString audioContents = responseTTS.getAudioContent();
//
//                                try (OutputStream out = new FileOutputStream("output.wav")) {
//                                    out.write(audioContents.toByteArray());
//                                    System.out.println("Audio content written to file \"output.wav\"");
//                                    File file = new File(System.getProperty("user.dir")+"/output.wav");
//
//                                    AudioInputStream stream = AudioSystem.getAudioInputStream(file);
//                                    AudioFormat format = stream.getFormat();
//                                    DataLine.Info info = new DataLine.Info(Clip.class, format);
//                                    Clip clip = (Clip) AudioSystem.getLine(info);
//                                    clip.open(stream);
//                                    clip.start();
//                                    while(clip.getMicrosecondLength() != clip.getMicrosecondPosition()) { }
//
//                                    speech = false;
//                                }
//                                catch (Exception e) {
//                                    System.out.println("3");
//                                    System.out.println(e);
//                                }
                                }
                            } catch (Exception e) {
                                System.out.println(e);
                            }
                        }
                        public void onComplete() {
                            System.out.println("Done");
                        }
                        public void onError(Throwable t) {
                            System.out.println("pingu");
                            System.out.println(t);
                        }
                    };

            clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

            RecognitionConfig recognitionConfig =
                    RecognitionConfig.newBuilder()
                            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                            .setLanguageCode("en-US")
                            .setSampleRateHertz(16000)
                            .build();
            StreamingRecognitionConfig streamingRecognitionConfig =
                    StreamingRecognitionConfig.newBuilder().setConfig(recognitionConfig).build();

            StreamingRecognizeRequest request =
                    StreamingRecognizeRequest.newBuilder()
                            .setStreamingConfig(streamingRecognitionConfig)
                            .build(); // The first request in a streaming call has to be a config

            clientStream.send(request);

            try {
                // SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true, bigEndian: false
                AudioFormat audioFormat = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info targetInfo =
                        new Info(
                                TargetDataLine.class,
                                audioFormat); // Set the system information to read from the microphone audio stream

                if (!AudioSystem.isLineSupported(targetInfo)) {
                    System.out.println("Microphone not supported");
                    System.exit(0);
                }
                // Target data line captures the audio stream the microphone produces.
                targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
                targetDataLine.open(audioFormat);
                micThread.start();
                readerThread.start();

                long startTime = System.currentTimeMillis();
                speech = false;

                while (true) {
                    long estimatedTime = System.currentTimeMillis() - startTime;
                    if (estimatedTime >= 55000) {
                        clientStream.closeSend();
                        clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

                        request =
                                StreamingRecognizeRequest.newBuilder()
                                        .setStreamingConfig(streamingRecognitionConfig)
                                        .build();

                        startTime = System.currentTimeMillis();
                        clientStream.send(request);
                    } else {
                        request =
                                StreamingRecognizeRequest.newBuilder()
                                        .setAudioContent(ByteString.copyFrom(sharedQueue.take()))
                                        .build();
                        if(speech == false){
                            clientStream.send(request);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

