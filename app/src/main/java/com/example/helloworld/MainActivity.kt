package com.example.helloworld

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isRecording = mutableStateOf(false)
    private var recordingThread: Thread? = null
    private val fullTranscript = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val transcripts = remember { mutableStateListOf<String>() }
            var interimTranscript by remember { mutableStateOf("") }
            var connectionStatus by remember { mutableStateOf("Disconnected") }
            val context = LocalContext.current

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    toggleRecording(transcripts)
                }
            }

            LaunchedEffect(Unit) {
                connectWebSocket(
                    onMessage = { transcript, isFinal -> 
                        if (transcript.isNotBlank()) {
                            if (isFinal) {
                                interimTranscript = ""
                                // Basic de-duplication: don't append if it's exactly what we already have at the end
                                val textToAppend = transcript.trim()
                                if (!fullTranscript.toString().trim().endsWith(textToAppend)) {
                                    fullTranscript.append(textToAppend).append(" ")
                                }
                                transcripts.clear()
                                transcripts.add(fullTranscript.toString())
                            } else {
                                interimTranscript = transcript
                            }
                        }
                    },
                    onStatusChange = { status -> connectionStatus = status }
                )
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TranscriptionScreen(
                        transcripts = transcripts,
                        interim = interimTranscript,
                        status = connectionStatus,
                        recording = isRecording.value,
                        onToggleRecording = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                toggleRecording(transcripts)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun toggleRecording(transcripts: SnapshotStateList<String>) {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(sampleRate * 2)
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return

        isRecording.value = true
        audioRecord.startRecording()

        recordingThread = thread {
            val firstChunkSize = sampleRate * 2 * 2 // 2s (16-bit mono = 2 bytes per sample)
            val subsequentChunkSize = sampleRate * 2 * 5 // 5s
            var chunkIndex = 0
            val outputStream = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)

            while (isRecording.value) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                    val targetSize = if (chunkIndex == 0) firstChunkSize else subsequentChunkSize
                    if (outputStream.size() >= targetSize) {
                        webSocket?.send(ByteString.of(*outputStream.toByteArray()))
                        outputStream.reset()
                        chunkIndex++
                    }
                }
            }
            
            // Send leftovers
            if (outputStream.size() > 0) {
                webSocket?.send(ByteString.of(*outputStream.toByteArray()))
            }
            
            try {
                audioRecord.stop()
            } catch (e: Exception) {
                // Ignore stop error if already released
            }
            audioRecord.release()
        }
    }

    private fun stopRecording() {
        isRecording.value = false
        recordingThread?.join()
        recordingThread = null
    }

    private fun connectWebSocket(onMessage: (String, Boolean) -> Unit, onStatusChange: (String) -> Unit) {
        val request = Request.Builder()
            .url("ws://74.208.166.203:5050/ws/transcribe")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatusChange("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val result = results.getJSONObject(0)
                        val alternatives = result.optJSONArray("alternatives")
                        val isFinal = result.optBoolean("is_final", result.optBoolean("final", true))
                        
                        if (alternatives != null && alternatives.length() > 0) {
                            val transcript = alternatives.getJSONObject(0).optString("transcript", "")
                            onMessage(transcript, isFinal)
                        }
                    }
                } catch (e: Exception) {
                    onMessage(text, true)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChange("Closing...")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChange("Disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatusChange("Error: ${t.message}")
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        webSocket?.close(1000, "Activity destroyed")
    }
}

@Composable
fun TranscriptionScreen(
    transcripts: List<String>,
    interim: String,
    status: String,
    recording: Boolean,
    onToggleRecording: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(transcripts.size, interim) {
        if (transcripts.isNotEmpty() || interim.isNotEmpty()) {
            listState.animateScrollToItem(if (interim.isNotEmpty()) transcripts.size else transcripts.size.coerceAtMost(0)) // Basic scroll logic
            // More robust: scroll to bottom
            if (transcripts.isNotEmpty()) {
                listState.animateScrollToItem(transcripts.size - 1)
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Status: $status", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onToggleRecording,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (recording) "Stop Recording" else "Start Recording")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            items(transcripts) { transcript ->
                Text(
                    text = transcript,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (interim.isNotBlank()) {
                item {
                    Text(
                        text = interim,
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
