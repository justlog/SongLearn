package com.example.songlearncompose

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.media.*
import android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.platform.LocalDensity
import com.example.songlearncompose.ui.theme.SongLearnComposeTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class PCMByteArray(var rawPCM: ByteArray, var PCMFormat: Int, var sampleRate: Int)

class NormalizedAudioTrack {
    lateinit var audio: FloatArray;
    var duration: Int
    var sampleRate: Int
    var sizeInSamples: Int

    constructor(pcmArr: PCMByteArray, duration: Int) {
        this.duration = duration;
        this.sampleRate = pcmArr.sampleRate;
        this.sizeInSamples = this.sampleRate*this.duration;
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
    var isLoaded: Boolean = false;

    private fun ParseAudioFile(fileId: Int, res: Resources): PCMByteArray
    {

        var PCMArray: PCMByteArray = PCMByteArray(ByteArray(0),0, 0);

        var fd = res.openRawResourceFd(fileId);

        var mmr: MediaMetadataRetriever = MediaMetadataRetriever();
        mmr.setDataSource(fd.fileDescriptor);
        val sr = mmr.extractMetadata(METADATA_KEY_SAMPLERATE)?.toInt();
        if(sr != null){
            PCMArray.sampleRate = sr;
        }
        mmr.close();



        var extractor: MediaExtractor = MediaExtractor();
        extractor.setDataSource(fd);

        var decoder: MediaCodec? = null;
        var format: MediaFormat? = null;
        for(i in 0..extractor.trackCount){
            format = extractor.getTrackFormat(i);
            var mime: String? = format.getString(MediaFormat.KEY_MIME);
            if(mime?.startsWith("audio/") == true){
                extractor.selectTrack(i);
                decoder = MediaCodec.createDecoderByType(mime);
                PCMArray.PCMFormat = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
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
                        assert(bufferInfo.size > 0);
                        val pcmData: ByteArray = ByteArray(bufferInfo.size);
                        outputBuffer.get(pcmData);
                        PCMArray.rawPCM = PCMArray.rawPCM.plus(pcmData);
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false);
                }
            }


            decoder.release();
            extractor.release();
            fd.close();
        }

        else{
            Log.e("Parse", "Could not create decoder!");
            assert(false);
        }

        return PCMArray;
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SongLearnComposeTheme {
                var width by remember { mutableStateOf(0) }
                var height by remember { mutableStateOf(0) }
                var loaded by remember {mutableStateOf(false);}
                val density = LocalDensity.current;
                val activity = LocalContext.current as Activity;
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                if(!loaded){
                    Column(modifier = Modifier.fillMaxSize(),horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(modifier=Modifier.fillMaxSize(),onClick = {
                            //TODO: Handle different PCM encoding (currently make 16 bit work per sine.wav)
                            val fileHandle = R.raw.plymouth;
                            var player: MediaPlayer = MediaPlayer.create(applicationContext, fileHandle);
                            //player.setlis
                            PCMArray = ParseAudioFile(fileHandle, resources);
                            assert(PCMArray != null);
                            normalizedAudio = NormalizedAudioTrack(PCMArray!!, player.duration);
                            assert(normalizedAudio != null);
                            loaded = true;
                            //NOTE: Just in place for later reference when we want to play audio.
                            player.start();
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
                        var verticalOffset by remember { mutableStateOf(0f) };
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
                                var windowSize = ((normalizedAudio.sampleRate.toFloat()*(1f/windowScale)).toInt()).coerceIn(0, normalizedAudio.sampleRate*3);
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
