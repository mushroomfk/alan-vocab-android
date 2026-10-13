package com.alanbeidanci.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "QrScanner"
private const val SCAN_COOLDOWN_MS = 700L
private const val STABLE_HITS = 2

private val extractWordRegex = Regex("[A-Za-z][A-Za-z'-]{1,}")

private val COMMON_WORDS = setOf(
    "the", "a", "an", "and", "of", "to", "in", "is", "are", "was", "were",
    "for", "on", "with", "at", "by", "as", "it", "its", "be", "or",
    "that", "this", "from", "not", "but", "you", "your", "we", "our",
    "they", "their", "i", "he", "she", "his", "her", "them", "one",
    "there", "can", "will", "would", "has", "have", "had", "do", "does",
    "did", "so", "if", "then", "than", "all", "any", "some", "no", "yes",
)

private fun extractCandidates(text: String): List<String> =
    extractWordRegex.findAll(text)
        .map { it.value.trim('\'', '-') }
        .filter { it.length >= 2 }
        .distinct()
        .toList()

private fun preferredTarget(candidates: List<String>): String? =
    candidates.firstOrNull { it.lowercase() !in COMMON_WORDS } ?: candidates.firstOrNull()

enum class ScanStatus { IDLE, SCANNING, RECOGNIZED, NOT_FOUND, ERROR }

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var status by remember { mutableStateOf(ScanStatus.IDLE) }
    var busy by remember { mutableStateOf(false) }
    var statusCount by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = remember { CoroutineScope(Dispatchers.Main.immediate) }

    fun present(words: List<String>) {
        busy = false
        if (words.isEmpty()) {
            status = ScanStatus.NOT_FOUND
            statusCount++
            return
        }
        candidates = words.take(8)
        val target = preferredTarget(words)
        if (target != null && words.size <= 3) {
            status = ScanStatus.RECOGNIZED
            statusCount++
            onResult(target)
            return
        }
        status = ScanStatus.RECOGNIZED
        statusCount++
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                busy = true
                status = ScanStatus.SCANNING
                statusCount++
                present(runCatching { recognizeStatic(bitmap) }.getOrDefault(emptyList()))
            }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = ScanStatus.SCANNING
                statusCount++
                val bitmap = withContext(Dispatchers.IO) { decodeImage(context, uri) }
                if (bitmap == null) {
                    busy = false
                    status = ScanStatus.ERROR
                    statusCount++
                } else {
                    present(runCatching { recognizeStatic(bitmap) }.getOrDefault(emptyList()))
                }
            }
        }
    }


    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val previewView = remember {
                PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            }
            val executor = remember { Executors.newSingleThreadExecutor() }
            val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
            val delivered = remember { AtomicBoolean(false) }
            val disposed = remember { AtomicBoolean(false) }
            val analyzing = remember { AtomicBoolean(false) }
            val lastRunAt = remember { AtomicLong(0L) }
            val lastMatched = remember { AtomicReference<String?>(null) }
            val hitCount = remember { AtomicLong(0) }
            val liveRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            DisposableEffect(lifecycleOwner, previewView) {
                providerFuture.addListener(
                    {
                        val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                        if (disposed.get()) {
                            provider.unbindAll()
                            return@addListener
                        }
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            if (disposed.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastRunAt.get() < SCAN_COOLDOWN_MS || !analyzing.compareAndSet(false, true) || delivered.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            lastRunAt.set(now)
                            analyzeFrame(imageProxy, liveRecognizer) { words ->
                                analyzing.set(false)
                                if (delivered.get()) return@analyzeFrame
                                if (words.isEmpty()) {
                                    lastMatched.set(null)
                                    hitCount.set(0)
                                    return@analyzeFrame
                                }
                                candidates = words.take(8)
                                val target = preferredTarget(words) ?: return@analyzeFrame
                                if (target == lastMatched.get()) {
                                    if (hitCount.incrementAndGet() >= STABLE_HITS && delivered.compareAndSet(false, true)) {
                                        status = ScanStatus.RECOGNIZED
                                        statusCount++
                                        onResult(target)
                                    }
                                } else {
                                    lastMatched.set(target)
                                    hitCount.set(1)
                                }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    },
                    mainExecutor,
                )
                onDispose {
                    disposed.set(true)
                    if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
                    liveRecognizer.close()
                    executor.shutdown()
                }
            }

            BoxWithConstraints(Modifier.align(Alignment.Center)) {
                Box(
                    Modifier
                        .width(maxWidth * 0.86f)
                        .height(maxWidth * 0.3f)
                        .border(3.dp, Color.White, RoundedCornerShape(18.dp)),
                )
            }

            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (candidates.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        candidates.forEach { word ->
                            SuggestionChip(
                                onClick = {
                                    if (!delivered.get()) {
                                        delivered.set(true)
                                        onResult(word)
                                    }
                                },
                                label = { Text(word) },
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.68f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text(
                        when (status) {
                            ScanStatus.IDLE -> "将英文单词对准取景框，识别到后自动查询"
                            ScanStatus.SCANNING -> "正在识别…"
                            ScanStatus.RECOGNIZED -> "识别成功，正在查询…"
                            ScanStatus.NOT_FOUND -> "未识别到内容，请对准单词重试，或选择拍照/相册"
                            ScanStatus.ERROR -> "识别失败，请重试"
                        },
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionButton("拍照识别", Icons.Outlined.CameraAlt, enabled = !busy, onClick = {
                        status = ScanStatus.IDLE
                        candidates = emptyList()
                        cameraLauncher.launch(null)
                    })
                    ActionButton("相册识别", Icons.Outlined.PhotoLibrary, enabled = !busy, onClick = {
                        status = ScanStatus.IDLE
                        candidates = emptyList()
                        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限才能识别单词", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("相机画面只在设备本地识别，不会上传。", color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭扫码", tint = Color.White)
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.16f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private suspend fun recognizeStatic(bitmap: Bitmap): List<String> = withContext(Dispatchers.IO) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { result -> cont.resume(extractCandidates(result.text)) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    } finally {
        recognizer.close()
    }
}

private fun decodeImage(context: android.content.Context, uri: Uri): Bitmap? = try {
    val input: InputStream = context.contentResolver.openInputStream(uri) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(input, null, bounds)
    input.close()
    val sample = maxOf(
        1,
        minOf(bounds.outWidth / 2400, bounds.outHeight / 2400, 4),
    )
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val second = context.contentResolver.openInputStream(uri) ?: return null
    val bitmap = BitmapFactory.decodeStream(second, null, options)
    second.close()
    bitmap
} catch (t: Throwable) {
    Log.e(TAG, "decode image failed", t)
    null
}

private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
    if (rotation % 360 == 0) return source
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

@ExperimentalGetImage
private fun analyzeFrame(imageProxy: ImageProxy, recognizer: TextRecognizer, onWords: (List<String>) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    runCatching {
        val frame = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(frame)
            .addOnSuccessListener { result -> onWords(centerBandWords(result, imageProxy.width, imageProxy.height)) }
            .addOnFailureListener { onWords(emptyList()) }
            .addOnCompleteListener { imageProxy.close() }
    }.onFailure {
        imageProxy.close()
    }
}

private fun centerBandWords(text: com.google.mlkit.vision.text.Text, frameWidth: Int, frameHeight: Int): List<String> {
    val minX = frameWidth * 0.05f
    val maxX = frameWidth * 0.95f
    val minY = frameHeight * 0.30f
    val maxY = frameHeight * 0.70f
    val found = mutableListOf<String>()
    for (block in text.textBlocks) {
        for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val centerX = box.centerX().toFloat()
            val centerY = box.centerY().toFloat()
            if (centerX < minX || centerX > maxX || centerY < minY || centerY > maxY) continue
            found += extractCandidates(line.text)
        }
    }
    return found.distinct()
}
