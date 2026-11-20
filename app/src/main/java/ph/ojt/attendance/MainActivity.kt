package ph.ojt.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private fun hasPerms(ctx: Context) =
    REQUIRED.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { AttendanceApp() } } }
    }
}

@Composable
fun AttendanceApp() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(REQUIRED) }
    if (granted) {
        MainContent()
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Camera and precise location permissions are required to record attendance.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(REQUIRED) }) { Text("Grant permissions") }
        }
    }
}

@Composable
fun MainContent() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var records by remember { mutableStateOf(store.all()) }
    var status by remember { mutableStateOf(TimeStatus.NEEDS_SYNC) }
    var nowMs by remember { mutableStateOf<Long?>(null) }
    var tab by remember { mutableStateOf(0) }
    val syncing = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        var lastTry = -100_000L
        while (true) {
            val el = SystemClock.elapsedRealtime()
            val s = TimeAuthority.status(ctx)
            if ((s != TimeStatus.OK || el - lastTry > 900_000) && el - lastTry > 10_000 &&
                syncing.compareAndSet(false, true)
            ) {
                lastTry = el
                launch(Dispatchers.IO) {
                    try { TimeAuthority.sync(ctx) } finally { syncing.set(false) }
                }
            }
            status = TimeAuthority.status(ctx)
            nowMs = TimeAuthority.trustedNowMs(ctx)
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            nowMs?.let { PH.full(it) } ?: "Waiting for time sync...",
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Record") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) {
            if (status == TimeStatus.OK) {
                RecordScreen(store, records, nowMs) { records = store.all() }
            } else {
                LockedScreen(status)
            }
        } else {
            HistoryScreen(store, records)
        }
    }
}

@Composable
fun LockedScreen(status: TimeStatus) {
    val msg = when (status) {
        TimeStatus.NEEDS_SYNC ->
            "Connect to the internet once so the app can sync Philippine Time. This is also needed after every phone restart."
        TimeStatus.AUTO_TIME_OFF ->
            "Turn ON 'Automatic date & time' in your phone settings to continue."
        TimeStatus.CLOCK_MISMATCH ->
            "Your phone's date/time was changed. Set it back to automatic. Attendance is locked until the clock matches Philippine Time."
        TimeStatus.OK -> ""
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Attendance locked", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB00020))
        Spacer(Modifier.height(12.dp))
        Text(msg)
    }
}

@Composable
fun RecordScreen(store: Store, records: List<Rec>, nowMs: Long?, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val prefs = remember { ctx.getSharedPreferences("profile", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val today = nowMs?.let { PH.day(it) }
    val todays = records.filter { it.day == today }
    val hasIn = todays.any { it.type == "IN" }
    val hasOut = todays.any { it.type == "OUT" }
    val nextType = if (!hasIn) "IN" else "OUT"

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Full name") }, singleLine = true,
            enabled = records.isEmpty(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                PreviewView(c).also { pv ->
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, selector, preview, imageCapture)
                    }, ContextCompat.getMainExecutor(c))
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        if (message.isNotEmpty()) Text(message)
        Text(
            when {
                !hasIn -> "Today: not timed in yet"
                !hasOut -> "Today: timed in at ${PH.time(todays.first { it.type == "IN" }.epochMs)}"
                else -> "Today: complete (in and out recorded)"
            }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !busy && !(hasIn && hasOut),
            onClick = {
                if (name.isBlank()) {
                    message = "Enter your full name first."
                } else {
                    if (records.isEmpty()) prefs.edit().putString("name", name.trim()).apply()
                    scope.launch {
                        busy = true
                        message = "Getting location and taking photo..."
                        message = try {
                            doCapture(ctx, store, imageCapture, executor, nextType, name.trim())
                        } catch (e: Exception) {
                            e.message ?: "Failed. Try again."
                        }
                        busy = false
                        onChanged()
                    }
                }
            }
        ) {
            Text(
                when {
                    hasIn && hasOut -> "DONE FOR TODAY"
                    !hasIn -> "TAKE PHOTO - TIME IN"
                    else -> "TAKE PHOTO - TIME OUT"
                },
                fontSize = 16.sp
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
suspend fun doCapture(
    ctx: Context, store: Store, ic: ImageCapture, ex: Executor, type: String, name: String
): String {
    if (TimeAuthority.status(ctx) != TimeStatus.OK) return "Time check failed. Fix your time settings."
    val loc = currentLocation(ctx) ?: return "Cannot get your location. Turn on GPS/location and try again."
    val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
    if (mock) return "Fake/mock location detected. Attendance rejected."
    val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
    if (ageMs > 30_000) return "Location is outdated. Try again."
    if (loc.accuracy > 50f) return "GPS accuracy too low (+/-${loc.accuracy.toInt()} m). Move outdoors or near a window and retry."
    val bmp = capturePhoto(ic, ex)
    if (TimeAuthority.status(ctx) != TimeStatus.OK) return "Time check failed. Fix your time settings."
    val now = TimeAuthority.trustedNowMs(ctx) ?: return "Time not synced."
    withContext(Dispatchers.IO) { store.save(type, bmp, now, loc, name) }
    return "Time ${if (type == "IN") "In" else "Out"} saved: ${PH.full(now)}"
}

@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Location? = suspendCancellableCoroutine { cont ->
    val client = LocationServices.getFusedLocationProviderClient(ctx)
    val cts = CancellationTokenSource()
    cont.invokeOnCancellation { cts.cancel() }
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
        .addOnSuccessListener { cont.resume(it) }
        .addOnFailureListener { cont.resume(null) }
}

suspend fun capturePhoto(ic: ImageCapture, ex: Executor): Bitmap = suspendCancellableCoroutine { cont ->
    ic.takePicture(ex, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                val out = if (rot == 0) bmp else Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true
                )
                cont.resume(out)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            cont.resumeWithException(exception)
        }
    })
}

@Composable
fun HistoryScreen(store: Store, records: List<Rec>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf("") }
    val name = remember { ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("name", "") ?: "" }
    val grouped = records.groupBy { it.day }.toSortedMap(reverseOrder()).entries.toList()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { store.verify() }
                    info = if (p.isEmpty()) "All records verified. No tampering found." else p.joinToString("\n")
                }
            }) { Text("Verify records") }
            Button(onClick = {
                scope.launch {
                    try {
                        val f = withContext(Dispatchers.IO) { PdfExport.create(ctx, records, name) }
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(i, "Share attendance PDF"))
                    } catch (e: Exception) {
                        info = e.message ?: "Export failed."
                    }
                }
            }) { Text("Export PDF") }
        }
        if (info.isNotEmpty()) Text(info, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(grouped) { entry -> DayCard(entry.key, entry.value) }
        }
    }
}

@Composable
fun DayCard(day: String, list: List<Rec>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(PH.dayLabel(day), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (type in listOf("IN", "OUT")) {
                    val r = list.find { it.type == type }
                    Column(Modifier.weight(1f)) {
                        Text(if (type == "IN") "Time In" else "Time Out", fontWeight = FontWeight.SemiBold)
                        if (r == null) {
                            Text("-")
                        } else {
                            Thumb(r.photo)
                            Text(PH.time(r.epochMs), fontSize = 13.sp)
                            Text("${fmt(r.lat)}, ${fmt(r.lng)}", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Thumb(photo: String) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, photo) {
        value = withContext(Dispatchers.IO) { loadScaled(java.io.File(java.io.File(ctx.filesDir, "photos"), photo), 300) }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
}
