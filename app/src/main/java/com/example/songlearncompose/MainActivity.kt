package com.example.songlearncompose

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.*
import android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.example.songlearncompose.ui.theme.SongLearnComposeTheme
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class PCMByteArray(var rawPCM: ByteArray, var PCMFormat: Int, var sampleRate: Int)

class NormalizedAudioTrack {
    lateinit var audio: FloatArray;
    var durationInMs: Int
    var sampleRate: Int
    var sizeInSamples: Int

    constructor(pcmArr: PCMByteArray, duration: Int) {
        this.durationInMs = duration;
        this.sampleRate = pcmArr.sampleRate;
//        assert(this.sampleRate.toFloat()/1000f == (this.sampleRate/1000).toFloat());//Make sure samplesPerMs makes sense
        this.sizeInSamples = (this.sampleRate.toFloat()*(this.durationInMs.toFloat()/1000f)).toInt();
        when (pcmArr.PCMFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shortOut = ShortArray(pcmArr.rawPCM.size/2);
                //TODO: Try to write the PCM conversion yourself to make sure you understand how it works.
                ByteBuffer.wrap(pcmArr.rawPCM).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortOut);
                val maxPos = shortOut.max();
                val min = shortOut.min();
                val maxValue = max(maxPos.toDouble(), abs(min.toInt()).toDouble()).toFloat() + 1000;
                audio = FloatArray(shortOut.size/2);
                for (i in 0 until shortOut.size step 2) {
                    audio[i - i/2] = shortOut[i]/maxValue;
//                    audio[i - i/2] = shortOut[i]/32767.0f;
                }
            }
            //TODO: Add the rest...
        }
    }
}

class MainActivity : ComponentActivity() {

    lateinit var PCMArray: PCMByteArray;
    lateinit var normalizedAudio: NormalizedAudioTrack;
    var isLoaded: Boolean by mutableStateOf(false);
    lateinit var audioPlayer: MediaPlayer

    private fun ParseAudioFile(fileDesc: FileDescriptor): PCMByteArray
    {

        var PCMArray: PCMByteArray = PCMByteArray(ByteArray(0),0, 0);



        var mmr: MediaMetadataRetriever = MediaMetadataRetriever();
        mmr.setDataSource(fileDesc);
        val sr = mmr.extractMetadata(METADATA_KEY_SAMPLERATE)?.toInt();
        if(sr != null){
            PCMArray.sampleRate = sr;
        }
        mmr.close();



        var extractor: MediaExtractor = MediaExtractor();
        extractor.setDataSource(fileDesc);

        var decoder: MediaCodec? = null;
        var format: MediaFormat? = null;
        for(i in 0..extractor.trackCount){
            format = extractor.getTrackFormat(i);
            var mime: String? = format.getString(MediaFormat.KEY_MIME);
            if(mime?.startsWith("audio/") == true){
                extractor.selectTrack(i);
                decoder = MediaCodec.createDecoderByType(mime);
                try{
                    PCMArray.PCMFormat = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
                catch (e: Throwable){
//                    PCMArray.PCMFormat = AudioFormat.ENCODING_MP3;
                    PCMArray.PCMFormat = AudioFormat.ENCODING_PCM_16BIT;
                }
                break;
            }
        }

        if(decoder != null){
            decoder.configure(format, null, null, 0);
            decoder.start();

            while(true){
                val inputBufferIndex = decoder.dequeueInputBuffer(-1);
                if(inputBufferIndex >= 0){
                    val readBuffer: ByteBuffer? = decoder.getInputBuffer(inputBufferIndex);
                    if(readBuffer != null){
                        val samplesRead = extractor.readSampleData(readBuffer, 0);
                        if(samplesRead < 0){//No more samples to read.
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0,0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            break;
                        }
                        decoder.queueInputBuffer(inputBufferIndex, 0, samplesRead, extractor.sampleTime, 0);
                        extractor.advance();
                    }
                }

                val bufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo();
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, -1);
                if(outputBufferIndex >= 0){
                    val outputBuffer: ByteBuffer? = decoder.getOutputBuffer(outputBufferIndex);
                    if(outputBuffer != null){
                        if(bufferInfo.size == 0){
                            Log.w("decoding", "bufferInfo.size=0");
                        }
                        else{
//                            Log.i("decoding", "found data!");
                            val pcmData: ByteArray = ByteArray(bufferInfo.size);
                            outputBuffer.get(pcmData);
                            PCMArray.rawPCM = PCMArray.rawPCM.plus(pcmData);
                        }
//                        assert(bufferInfo.size > 0);
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            }


            decoder.release();
            extractor.release();
            Log.i("decoding", "done!");
//            fd.close();
        }

        else{
            Log.e("Parse", "Could not create decoder!");
            assert(false);
        }

        return PCMArray;
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) {uri: Uri? ->
            if(uri != null){
                var uriName = uri;
                //NOTE: Possibly totally useless. decoding the mp3 seems to work fine. now just need to get it fast.
//                if(uri.path?.contains("mp3") == true){//convert first
//                    val string = FFmpegKitConfig.getSafParameterForRead(this.applicationContext, uri);
//                    val outputFile = File("${this.applicationContext.externalCacheDir}/temp.wav");
//                    val command = "-y -i ${string} -acodec pcm_s16le -c copy ${outputFile.path}";
//                    val session: FFmpegSession = FFmpegKit.execute(command);
//                    if(ReturnCode.isSuccess(session.returnCode)){
//                        uriName = outputFile.toUri();
//                    }
//                    else{
//                        Log.w("", String.format("Command failed with state %s and rc %s.%s", session.getState(), session.getReturnCode(), session.getFailStackTrace()));
//                        assert(false);
//                    }
//                }
                val fileDescriptor = contentResolver.openFileDescriptor(uriName, "r")
                val fileHandle = fileDescriptor?.fd;
                if(fileHandle != null){
                    audioPlayer = MediaPlayer.create(applicationContext, uriName);
                    PCMArray = ParseAudioFile(fileDescriptor.fileDescriptor);
                    Log.i("main", "parsedaudiofile");
                    assert(PCMArray != null);
                    normalizedAudio = NormalizedAudioTrack(PCMArray!!, audioPlayer.duration);
                    Log.i("main", "normalized audio");
                    assert(normalizedAudio != null);
                    isLoaded = true;
                    audioPlayer.start();
                    fileDescriptor.close()
                }
            }
        }
        setContent {
            SongLearnComposeTheme {
//                var width by remember { mutableStateOf(0) }
//                var height by remember { mutableStateOf(0) }
//                var loaded by remember {mutableStateOf(false);}
//                val density = LocalDensity.current;
                val activity = LocalContext.current as Activity;
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                if(!isLoaded){
                    Column(modifier = Modifier.fillMaxSize(),horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(modifier=Modifier.fillMaxSize(),onClick = {
                            //TODO: Handle different PCM encoding (currently make 16 bit work per sine.wav)
                            getContent.launch("audio/*");
                        }) {
                            Text(text = "Click to load file");
                        }
                    }
                }
                else{
                    Box(modifier=Modifier.fillMaxSize(), contentAlignment = Alignment.Center)
                    {
                        //TODO: Last time you stopped trying to implement input onto the drawing.
                        //I think what I want to do is to try and have a lien go across the waveform as the
                        //audio file is playing just to see if it works. Afterwards, I can try
                        //to implement a click event where the audio file seeks to the corresponding point
                        //on the waveform.
                        var horizontalOffset by remember { mutableStateOf(0f) };
                        var windowScale by remember { mutableStateOf(1f)};
                        var lastMiddleSample: Int = 0;
                        var lastStartIdx: Int = 0;
                        var scaleChanged: Boolean = false;
                        Canvas(modifier= Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .background(Color.White)
                            .pointerInput(Unit){
                                               detectTransformGestures { centroid, pan, zoom, rotation ->
                                                   horizontalOffset = pan.x;
                                                   windowScale *= zoom;
                                                   windowScale = windowScale.coerceIn(0.1f, 100f);
                                                   if(zoom != 1.0f) scaleChanged = true;
                                               }
                            }
                            ,
                            onDraw = {
                                val width = size.width;
                                val height = size.height;
                                val MAX_WINDOW = normalizedAudio.sampleRate*3;
                                var windowSize = ((normalizedAudio.sampleRate.toFloat()*(1f/windowScale)).toInt()).coerceIn(0, MAX_WINDOW);
                                var localOffset = -horizontalOffset;
                                var startIdx: Int;
                                if(scaleChanged) {
                                    startIdx = lastMiddleSample - (windowSize/2);
                                    scaleChanged = false;
                                }
                                else{
                                    startIdx = lastStartIdx + (windowSize.toFloat() * (localOffset.toFloat()/width.toFloat())).toInt();
                                }
                                val size = windowSize;
                                val recp: Float = 1.0f/size;
                                if(startIdx+size >= normalizedAudio.sizeInSamples){
                                    startIdx = normalizedAudio.sizeInSamples-size-1;
                                }
                                else if(startIdx < 0){
                                    startIdx = 0;
                                }
                                lastMiddleSample = (startIdx+(startIdx+size)) / 2;
                                lastStartIdx = startIdx;

                                var windowToMaxRatio = 1.0f-windowSize.toFloat()/MAX_WINDOW.toFloat();
                                if(windowSize > 24000){//TEMPORARY: above half a second of 48,000khz file
//                                    Log.i("", "windowSize:${windowSize}");
                                    val stepSize = 400;
                                    for(i in startIdx .. startIdx+size step stepSize){
                                        var endIdx = i+stepSize;
                                        if(endIdx >= startIdx+size){
                                            endIdx = startIdx+size - i;
                                        }
//                                        Log.i("canvas", "i:${i} endIdx:${endIdx}");
                                        val sliced = normalizedAudio.audio.slice(IntRange(i, endIdx));
                                        var avg = 0.0f;
                                        for(sample in sliced){
                                            avg += abs(sample);
                                        }
//                                        val avg = normalizedAudio.audio.slice(IntRange(i, endIdx)).sum()/(endIdx-i).toFloat();
                                        avg /= (endIdx-i).toFloat();
                                        val x: Float = (i-startIdx).toFloat() * recp.toFloat() * width;
                                        if(i >= normalizedAudio.audio.size){
                                            Log.i("", "startIdx:${startIdx}, end:${startIdx+size}");
                                        }
                                        else if(i < 0){
                                            Log.i("", "idx negative:${i}, startIdx:${startIdx}");
                                        }
//                                        val barHeight: Float = -normalizedAudio.audio[i]*(height/2);
                                        val barHeight: Float = -avg*(height/2);
                                        drawLine(Color.Black, Offset(x, this.center.y), Offset(x, this.center.y+barHeight), 1.0f);
                                        drawLine(Color.Black, Offset(x, this.center.y), Offset(x, this.center.y-barHeight), 1.0f);
                                    }
                                }
                                else{
                                    for(i in startIdx .. startIdx+size){
                                        val x: Float = (i-startIdx).toFloat() * recp.toFloat() * width;
                                        if(i >= normalizedAudio.audio.size){
                                            Log.i("", "startIdx:${startIdx}, end:${startIdx+size}");
                                        }
                                        else if(i < 0){
                                            Log.i("", "idx negative:${i}, startIdx:${startIdx}");
                                        }
                                        val barHeight: Float = -normalizedAudio.audio[i]*(height/2);
                                        drawLine(Color.Black, Offset(x, this.center.y), Offset(x, this.center.y+barHeight), 1.0f);
                                    }
                                }
                        })
                    }
                }
            }
        }
    }
}

/*
@Composable
fun CustomCanvas(modifier: Modifier, audio: FloatArray){
    var width by remember { mutableStateOf(0) }
    var height by remember { mutableStateOf(0) }
    val density = LocalDensity.current;
        Canvas(modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val d = density.density;
                width = (coordinates.size.width / d).toInt();
                height = (coordinates.size.height / d).toInt();
            }
            , onDraw = {
                try{
                    val mid = Offset(this.center.x, this.center.y);
                    for(i in 0 .. audio.size){
                        val x = i.toFloat()/audio.size * width.toFloat();
                        val barHeight = audio[i]*height.toFloat();
                        drawLine(Color.Black, Offset(x, mid.y), Offset(x, barHeight), 1.0f);
                    }
                }
                catch(e: Exception){
                    Log.e("draw fuck", e.toString());
                }
            })
}
 */
