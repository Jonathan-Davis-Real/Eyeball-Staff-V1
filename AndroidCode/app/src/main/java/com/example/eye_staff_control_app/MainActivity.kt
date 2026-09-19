package com.example.eye_staff_control_app

import android.os.Bundle
import android.os.SystemClock
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.eye_staff_control_app.ui.theme.EyeStaffControlAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch


object stepperMotor {
    var rotatoinPos = 0f
    var pixelsToStepMult = 1.0f
    var invertStepDirection = false
    var cameraHost = "192.168.1.100"

    private const val PREFS_NAME = "stepper_motor_settings"
    private const val PIXELS_TO_STEP_MULT_KEY = "pixels_to_step_mult"
    private const val INVERT_STEP_DIRECTION_KEY = "invert_step_direction"
    private const val CAMERA_HOST_KEY = "camera_host"
    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pixelsToStepMult = preferences?.getFloat(PIXELS_TO_STEP_MULT_KEY, pixelsToStepMult)
            ?: pixelsToStepMult
        invertStepDirection = preferences?.getBoolean(
            INVERT_STEP_DIRECTION_KEY,
            invertStepDirection
        ) ?: invertStepDirection
        cameraHost = preferences?.getString(CAMERA_HOST_KEY, cameraHost)
            ?: cameraHost
    }

    fun saveSettings() {
        preferences?.edit()
            ?.putFloat(PIXELS_TO_STEP_MULT_KEY, pixelsToStepMult)
            ?.putBoolean(INVERT_STEP_DIRECTION_KEY, invertStepDirection)
            ?.putString(CAMERA_HOST_KEY, cameraHost)
            ?.apply()
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepperMotor.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            EyeStaffControlAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GetCameraFrame(host = stepperMotor.cameraHost, port = 81)
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Cyan) {
        Text(
            text = "Hi, my name is $name!",
            modifier = modifier.padding(24.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EyeStaffControlAppTheme {
        Greeting("Meghan")
    }
}

@Composable
fun CameraFaceTrack(
    host: String,
    faceOffset: Float,
    pixelsToStepMult: Float
) {
    LaunchedEffect(host, faceOffset, pixelsToStepMult) {
        val result = sendStepCommand(host, faceOffset, pixelsToStepMult, false)
        println(result)
    }
}

private suspend fun sendStepCommand(
    host: String,
    faceOffset: Float,
    pixelsToStepMult: Float,
    invertStepDirection: Boolean
): String =
    withContext(Dispatchers.IO) {
        val direction = if (invertStepDirection) -1 else 1
        val stepAmount = (faceOffset * pixelsToStepMult * direction).toInt()
        if (kotlin.math.abs(stepAmount) <= 5) {
            return@withContext "Skipped: step amount $stepAmount is within the dead zone"
        }

        val command = "http://$host/cmd?value=STEP%20$stepAmount"
        try {
            val connection = URL(command)
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.requestMethod = "GET"
            try {
                "Sent: $command (HTTP ${connection.responseCode})"
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            "Failed: $command (${error.message ?: "network error"})"
        }
    }

@Composable
fun GetCameraFrame(host: String, port: Int) {
    var cameraHostText by remember(host) { mutableStateOf(host) }
    var activeHost by remember(host) { mutableStateOf(host) }
    var faceCount by remember { mutableIntStateOf(0) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var commandStatus by remember { mutableStateOf<String?>(null) }
    var commandHistory by remember { mutableStateOf(emptyList<String>()) }
    var streamStatus by remember { mutableStateOf("Connecting to $activeHost:$port") }
    var pixelsToStepMultText by remember {
        mutableStateOf(stepperMotor.pixelsToStepMult.toString())
    }
    var invertStepDirection by remember { mutableStateOf(stepperMotor.invertStepDirection) }
    val latestFrames = remember(activeHost, port) {
        Channel<ByteArray>(capacity = Channel.CONFLATED)
    }
    val commandScope = rememberCoroutineScope()
    val detectorBusy = remember { AtomicBoolean(false) }
    val lastAutoCommandAt = remember { AtomicLong(0L) }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking()
                .build()
        )
    }

    DisposableEffect(detector) {
        onDispose { detector.close() }
    }

    LaunchedEffect(activeHost, port) {
        streamStatus = "Connecting to $activeHost:$port"
        while (isActive) {
            try {
                withContext(Dispatchers.IO) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(activeHost, port), 5_000)
                        socket.tcpNoDelay = true
                        withContext(Dispatchers.Main.immediate) {
                            streamStatus = "Connected to $activeHost:$port; waiting for frames"
                        }
                        socket.getInputStream().use { input ->
                            var receivedFrameCount = 0L
                            var receivedByteCount = 0L
                            var lastReceiveReportAt = SystemClock.elapsedRealtime()
                            var lastReportedFrameCount = 0L
                            var lastReportedByteCount = 0L
                            readJpegFrames(input) { jpeg ->
                                receivedFrameCount++
                                receivedByteCount += jpeg.size
                                if (DROP_RECEIVED_FRAMES_FOR_TEST) {
                                    dumpReceivedFrameForTest(
                                        receivedFrameCount,
                                        receivedByteCount,
                                        lastReceiveReportAt,
                                        lastReportedFrameCount,
                                        lastReportedByteCount
                                    )
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastReceiveReportAt >= 1_000) {
                                        lastReceiveReportAt = now
                                        lastReportedFrameCount = receivedFrameCount
                                        lastReportedByteCount = receivedByteCount
                                    }
                                } else {
                                    latestFrames.trySend(jpeg)
                                }
                                if (!DROP_RECEIVED_FRAMES_FOR_TEST) {
                                    withContext(Dispatchers.Main.immediate) {
                                        streamStatus = "Receiving video from $activeHost:$port"
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                streamStatus = "Stream error: ${error.message ?: "connection failed"}"
                delay(1_000)
            }
        }
    }

    LaunchedEffect(latestFrames) {
        for (jpeg in latestFrames) {
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } ?: continue
            frameBitmap = bitmap
        }
    }

    LaunchedEffect(frameBitmap) {
        val bitmap = frameBitmap ?: return@LaunchedEffect
        if (!detectorBusy.compareAndSet(false, true)) return@LaunchedEffect

        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                faceCount = faces.size
                var lastFacePos = 0.0f

                if (faces.isNotEmpty()){
                    var closestFace = faces[0]
                    var maxArea = 0.0

                    for (face in faces){
                        val box = face.boundingBox
                        val area = box.width().toDouble() * box.height().toDouble()
                        if (area > maxArea) {
                            maxArea = area
                            closestFace = face
                        }
                    }

                    val cameraCenter = bitmap.width / 2f
                    val faceCenter = closestFace.boundingBox.centerX().toFloat()
                    lastFacePos = faceCenter
                    val faceOffset = cameraCenter - faceCenter

                    val now = System.currentTimeMillis()
                    if (now - lastAutoCommandAt.get() >= AUTO_COMMAND_INTERVAL_MS &&
                        lastAutoCommandAt.compareAndSet(
                            lastAutoCommandAt.get(),
                            now
                        )
                    ) {
                        commandScope.launch {
                            val result = sendStepCommand(
                                activeHost,
                                faceOffset,
                                stepperMotor.pixelsToStepMult,
                                stepperMotor.invertStepDirection
                            )
                            commandStatus = result
                            val direction = if (stepperMotor.invertStepDirection) -1 else 1
                            stepperMotor.rotatoinPos += (
                                faceOffset * stepperMotor.pixelsToStepMult * direction
                            )
                            commandHistory = (commandHistory + result).takeLast(5)
                        }
                    }
                }
                else if ((lastFacePos > (bitmap.width / 3f) * 2f  || lastFacePos < bitmap.width / 3f)){
                    val now = System.currentTimeMillis()
                    if (now - lastAutoCommandAt.get() >= AUTO_COMMAND_INTERVAL_MS &&
                        lastAutoCommandAt.compareAndSet(
                            lastAutoCommandAt.get(),
                            now
                        )
                    ) {
                        commandScope.launch {
                            val result = sendStepCommand(
                                activeHost,
                                lastFacePos,
                                stepperMotor.pixelsToStepMult,
                                stepperMotor.invertStepDirection
                            )
                            commandStatus = result
                            commandHistory = (commandHistory + result).takeLast(5)
                        }
                    }
                }
            }
            .addOnFailureListener { faceCount = 0 }
            .addOnCompleteListener { detectorBusy.set(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        frameBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Camera frame",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                contentScale = ContentScale.Fit
            )
        }

        Text(
            text = "Faces detected: $faceCount",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = streamStatus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                commandScope.launch {
                    val result = sendStepCommand(
                        activeHost,
                        20.0f,
                        stepperMotor.pixelsToStepMult,
                        stepperMotor.invertStepDirection
                    )
                    commandStatus = result
                    commandHistory = (commandHistory + result).takeLast(5)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Send STEP 20")
        }

        Button(
            onClick = {
                invertStepDirection = !invertStepDirection
                stepperMotor.invertStepDirection = invertStepDirection
                stepperMotor.saveSettings()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 184.dp)
        ) {
            Text(
                if (stepperMotor.invertStepDirection) {
                    "Direction: Inverted"
                } else {
                    "Direction: Normal"
                }
            )
        }

        OutlinedTextField(
            value = cameraHostText,
            onValueChange = { cameraHostText = it },
            label = { Text("Camera IP address") },
            singleLine = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 304.dp)
        )

        Button(
            onClick = {
                val newHost = cameraHostText.trim()
                if (newHost.isNotEmpty()) {
                    stepperMotor.cameraHost = newHost
                    stepperMotor.saveSettings()
                    activeHost = newHost
                    commandStatus = "Connecting to $newHost"
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 240.dp)
        ) {
            Text("Apply camera IP")
        }

        OutlinedTextField(
            value = pixelsToStepMultText,
            onValueChange = { value ->
                pixelsToStepMultText = value
                value.toFloatOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.let {
                        stepperMotor.pixelsToStepMult = it
                        stepperMotor.saveSettings()
                    }
            },
            label = { Text("Pixels to step multiplier") },
            singleLine = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
        )

        commandStatus?.let { status ->
            Text(
                text = status,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 136.dp),
                color = Color.White
            )
        }

        if (commandHistory.isNotEmpty()) {
            Text(
                text = commandHistory.joinToString("\n"),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                color = Color.White
            )
        }
    }
}

private fun dumpReceivedFrameForTest(
    frameCount: Long,
    byteCount: Long,
    lastReportAt: Long,
    lastReportedFrameCount: Long,
    lastReportedByteCount: Long
) {
    val elapsedMs = SystemClock.elapsedRealtime() - lastReportAt
    if (elapsedMs >= 1_000) {
        val framesPerSecond = (frameCount - lastReportedFrameCount) * 1_000 / elapsedMs
        val bytesPerSecond = (byteCount - lastReportedByteCount) * 1_000 / elapsedMs
        println(
            "Receive-only test: $framesPerSecond frames/s, " +
                "$bytesPerSecond bytes/s"
        )
    }
}

private suspend fun readJpegFrames(
    input: java.io.InputStream,
    onFrame: suspend (ByteArray) -> Unit
) = withContext(Dispatchers.IO) {
    val lengthHeader = ByteArray(4)

    while (isActive) {
        if (!readFully(input, lengthHeader)) return@withContext
        val frameLength =
            ((lengthHeader[0].toInt() and 0xFF) shl 24) or
                ((lengthHeader[1].toInt() and 0xFF) shl 16) or
                ((lengthHeader[2].toInt() and 0xFF) shl 8) or
                (lengthHeader[3].toInt() and 0xFF)
        require(frameLength in 1..MAX_JPEG_BYTES) {
            "Invalid JPEG frame length: $frameLength"
        }
        val jpeg = ByteArray(frameLength)
        if (!readFully(input, jpeg)) return@withContext
        onFrame(jpeg)
    }
}

private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val bytesRead = input.read(buffer, offset, buffer.size - offset)
        if (bytesRead == -1) return false
        if (bytesRead == 0) continue
        offset += bytesRead
    }
    return true
}
private const val MAX_JPEG_BYTES = 20 * 1024 * 1024
private const val DROP_RECEIVED_FRAMES_FOR_TEST = false
private const val AUTO_COMMAND_INTERVAL_MS = 250L
