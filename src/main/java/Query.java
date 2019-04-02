import com.google.cloud.dialogflow.v2.QueryResult;
import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;

import javax.sound.sampled.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class Query {
    private static TextToSpeechClient textToSpeechClient;

    public static void print(QueryResult queryResult){
        System.out.println("====================");
        System.out.format("Query Text: '%s'\n", queryResult.getQueryText());
        System.out.format("Detected Intent: %s (confidence: %f)\n",
                queryResult.getIntent().getDisplayName(), queryResult.getIntentDetectionConfidence());
        System.out.format("Fulfillment Text: '%s'\n", queryResult.getFulfillmentText());
    }
    public static void synthesize(QueryResult queryResult, TextToSpeechClient textToSpeechClient){
        try {
            // Set the text input to be synthesized
            SynthesisInput input = SynthesisInput.newBuilder()
                    .setText(queryResult.getFulfillmentText())
                    .build();

            // Build the voice request, select the language code ("en-US") and the ssml voice gender
            // ("neutral")
            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode("en-GB")
                    .setName("en-GB-Wavenet-C")
                    .setSsmlGender(SsmlVoiceGender.FEMALE)
                    .build();

            // Select the type of audio file you want returned
            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.LINEAR16)
                    .build();

            // Perform the text-to-speech request on the text input with the selected voice parameters and
            // audio file type
            SynthesizeSpeechResponse responseTTS = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);

            // Get the audio contents from the response

            ByteString audioContents = responseTTS.getAudioContent();

            OutputStream out = new FileOutputStream("output.wav");
            out.write(audioContents.toByteArray());
            File file = new File(System.getProperty("user.dir")+"/output.wav");

            AudioInputStream stream = AudioSystem.getAudioInputStream(file);
            AudioFormat format = stream.getFormat();
            DataLine.Info info = new DataLine.Info(Clip.class, format);
            Clip clip = (Clip) AudioSystem.getLine(info);
            clip.open(stream);
            clip.start();
            while(clip.getMicrosecondLength() != clip.getMicrosecondPosition()) { }

        }
        catch (Exception e) {
            System.out.println(e);
        }
    }
}
