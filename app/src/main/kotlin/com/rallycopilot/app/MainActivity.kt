package com.rallycopilot.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rallycopilot.app.data.AppDb
import com.rallycopilot.app.drive.DriveScreen
import com.rallycopilot.app.drive.DriveService
import com.rallycopilot.core.model.DriverProfile
import com.rallycopilot.core.model.SeverityBand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    var driveService: DriveService? = null
        private set
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            driveService = (service as DriveService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { driveService = null }
    }

    /** Location grant state, observed by the UI so DRIVE can gate on it. */
    val locationGranted = androidx.compose.runtime.mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationGranted.value = hasLocationPermission()
        }

    fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )
    }

    /** Set when a newer release has been downloaded and is ready to install. */
    val updateReady = androidx.compose.runtime.mutableStateOf<com.rallycopilot.app.update.Updater.Available?>(null)

    /** Human-readable outcome of the last manual check, for the settings screen. */
    val updateStatus = androidx.compose.runtime.mutableStateOf<String?>(null)
    val updateChecking = androidx.compose.runtime.mutableStateOf(false)

    /**
     * [manual] checks report their outcome; the silent check at launch does not,
     * because "couldn't reach GitHub" is not worth interrupting a drive over.
     */
    fun checkForUpdates(manual: Boolean = false) {
        if (updateChecking.value) return
        updateChecking.value = true
        if (manual) updateStatus.value = "checking…"
        lifecycleScope.launch {
            val result = com.rallycopilot.app.update.Updater.checkNow(
                this@MainActivity, BuildConfig.VERSION_NAME
            )
            when (result) {
                is com.rallycopilot.app.update.Updater.Result.Update -> {
                    updateReady.value = result.available
                    updateStatus.value = "v${result.available.version} downloaded — tap to install"
                }
                is com.rallycopilot.app.update.Updater.Result.UpToDate -> {
                    updateReady.value = null
                    if (manual) updateStatus.value = "up to date (v${result.version})"
                }
                is com.rallycopilot.app.update.Updater.Result.Failed ->
                    if (manual) updateStatus.value = "couldn't check: ${result.reason}"
            }
            updateChecking.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationGranted.value = hasLocationPermission()
        // Ask only when something is actually missing — re-firing dialogs on every
        // recreation burns through the system's "don't ask again" budget.
        val missing = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) requestPermissions()
        checkForUpdates()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppNav(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A drive may already be running (activity recreated at dusk by the theme
        // flip, or relaunched from the notification): reconnect to it, or END would
        // be a silent no-op and the drive unreachable.
        if (!bound) {
            DriveService.instance?.let {
                bindService(Intent(this, DriveService::class.java), connection, 0)
                bound = true
            }
        }
    }

    fun startDrive(wet: Boolean, calibration: Boolean, demo: Boolean = false) {
        val i = Intent(this, DriveService::class.java)
            .putExtra(DriveService.EXTRA_WET, wet)
            .putExtra(DriveService.EXTRA_CALIBRATION, calibration)
            .putExtra(DriveService.EXTRA_DEMO, demo)
        startForegroundService(i)
        if (!bound) { bindService(i, connection, Context.BIND_AUTO_CREATE); bound = true }
    }

    fun stopDrive() {
        (driveService ?: DriveService.instance)?.stopDrive()
        if (bound) { runCatching { unbindService(connection) }; bound = false }
        driveService = null
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(connection) }
        super.onDestroy()
    }
}

@Composable
fun AppNav(activity: MainActivity) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(activity, onNav = { nav.navigate(it) }) }
        composable("drive") { DriveScreen(activity, onExit = { nav.popBackStack() }) }
        composable("profile") { ProfileScreen(activity) }
        composable("runs") { RunsScreen(activity) }
        composable("roads") { com.rallycopilot.app.extras.RoadFinderScreen(activity) }
        composable("settings") { SettingsScreen(activity) }
    }
}

@Composable
fun HomeScreen(activity: MainActivity, onNav: (String) -> Unit) {
    // Saveable: the dusk theme flip recreates the activity and must not silently
    // reset a deliberately-chosen WET back to DRY.
    var wet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val locationGranted by activity.locationGranted
    // Real drives need location before the service starts: on Android 14+ a
    // location-type foreground service CRASHES if started without the grant.
    fun startRealDrive(calibration: Boolean) {
        if (locationGranted) {
            activity.startDrive(wet, calibration); onNav("drive")
        } else {
            activity.requestPermissions()
        }
    }
    val bg = Color(0xFF06080B)
    val panel = Color(0xFF11161D)
    val ink = Color(0xFFEAF0F6)
    val inkDim = Color(0xFF7C8B9A)
    val green = Color(0xFF2EE06B)

    Column(
        Modifier.fillMaxSize().background(bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(46.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("RALLY", color = ink, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(" COPILOT", color = green, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Text(
            "STROUD REGION · NO RECCE NEEDED",
            color = inkDim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(26.dp))

        // OTA update banner: appears only when a newer release is downloaded and ready.
        activity.updateReady.value?.let { update ->
            Button(
                onClick = { com.rallycopilot.app.update.Updater.install(activity, update) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6BB8FF)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "UPDATE v${update.version} READY — TAP TO INSTALL",
                        fontSize = 14.sp, color = Color(0xFF06080B), fontWeight = FontWeight.Black,
                    )
                    Text("downloaded · one tap · data survives", fontSize = 10.sp, color = Color(0xCC06080B))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = { startRealDrive(calibration = false) },
            modifier = Modifier.fillMaxWidth().height(92.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = green),
        ) {
            Text("DRIVE", fontSize = 30.sp, color = Color(0xFF06080B), fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
        if (!locationGranted) {
            Text(
                "location permission needed for a real drive — tap DRIVE to grant",
                color = Color(0xFFFFB74D), fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { startRealDrive(calibration = true) },
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = panel),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CALIBRATE", fontSize = 15.sp, color = ink, fontWeight = FontWeight.Bold)
                    Text("learn my pace", fontSize = 10.sp, color = inkDim)
                }
            }
            Button(
                onClick = { activity.startDrive(wet = false, calibration = false, demo = true); onNav("drive") },
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = panel),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DEMO", fontSize = 15.sp, color = ink, fontWeight = FontWeight.Bold)
                    Text("no GPS needed", fontSize = 10.sp, color = inkDim)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Conditions: segmented dry/wet
        Row(
            Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(4.dp),
        ) {
            for ((isWet, label) in listOf(false to "DRY", true to "WET")) {
                val selected = wet == isWet
                Button(
                    onClick = { wet = isWet },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF232D38) else Color.Transparent,
                    ),
                    elevation = null,
                ) {
                    Text(
                        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (selected) (if (isWet) Color(0xFF6BB8FF) else ink) else inkDim,
                    )
                }
            }
        }
        if (wet) {
            Text(
                "wet: suggested speeds reduced 20%",
                color = Color(0xFF6BB8FF), fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
        Spacer(Modifier.height(22.dp))

        Text("MORE", color = inkDim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val entries = listOf(
            "roads" to Pair("Road finder", "twisty & quiet near you"),
            "profile" to Pair("Driver profile", "your learned pace"),
            "runs" to Pair("Run log", "reports · share · export"),
            "settings" to Pair("Settings", "voice · verbosity · caps"),
        )
        for (row in entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((route, meta) in row) {
                    Button(
                        onClick = { onNav(route) },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = panel),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(meta.first, fontSize = 14.sp, color = ink, fontWeight = FontWeight.SemiBold)
                            Text(meta.second, fontSize = 10.sp, color = inkDim, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ProfileScreen(activity: MainActivity) {
    val db = remember { AppDb.get(activity) }
    var cond by remember { mutableStateOf(com.rallycopilot.core.model.Conditions.DRY) }
    var profile by remember(cond) { mutableStateOf(db.loadProfile(cond)) }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(20.dp)) {
        Text("DRIVER PROFILE", color = Color.White, fontSize = 24.sp)
        Row(
            Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in com.rallycopilot.core.model.Conditions.entries) {
                val selected = cond == c
                Button(
                    onClick = { cond = c; profile = db.loadProfile(c) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2EE06B) else Color(0xFF141C24),
                    ),
                ) {
                    Text(
                        c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (selected) Color.Black else Color(0xFFB8C4D0),
                    )
                }
            }
        }
        Text(
            "push factor ${"%.2f".format(profile.pushFactor)} · separate model per conditions",
            color = Color(0xFF8899AA), fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SeverityBand.entries.filter { it != SeverityBand.FLAT }) { band ->
                val g = profile.aLatFor(band) / 9.81
                val n = profile.sampleCountByBand[band] ?: 0
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(band.name.lowercase(), fontSize = 16.sp)
                        Text("%.2f g   ·   %d corners".format(g, n), fontSize = 16.sp)
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val fresh = if (cond == com.rallycopilot.core.model.Conditions.WET)
                            DriverProfile(emptyMap(), emptyMap(), seedALat = 0.4 * 9.81)
                        else DriverProfile.COLD_START
                        db.saveProfile(fresh, cond)
                        profile = fresh
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2530)),
                ) { Text("Reset ${cond.name.lowercase()} profile to default") }
            }
        }
    }
}

@Composable
fun RunsScreen(activity: MainActivity) {
    val db = remember { AppDb.get(activity) }
    val runs = remember { db.runs() }
    val fmt = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale.UK) }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(20.dp)) {
        Text("RUN LOG", color = Color.White, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs) { run ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(fmt.format(Date(run.startedAt)), fontSize = 16.sp)
                        val mins = run.endedAt?.let { (it - run.startedAt) / 60000 } ?: 0
                        Text(
                            "%.1f miles · %d min · %s".format(
                                run.distanceM / 1609.34, mins, run.feedback ?: "no feedback"
                            ),
                            fontSize = 13.sp, color = Color(0xFF8899AA),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                // Card rendering reads the whole run log — off the main thread.
                                onClick = {
                                    Thread {
                                        runCatching { com.rallycopilot.app.extras.DriveReport.share(activity, run.id) }
                                    }.start()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            ) { Text("Share card", color = Color.Black, fontSize = 13.sp) }
                            Button(
                                // runCatching: an encoder/MediaStore failure must fail
                                // one export, not take down the whole process.
                                onClick = {
                                    Thread {
                                        runCatching { com.rallycopilot.app.extras.OverlayExporter.export(activity, run.id) }
                                    }.start()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3540)),
                            ) { Text("Export overlay video", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(activity: MainActivity) {
    val db = remember { AppDb.get(activity) }
    var verbosity by remember { mutableStateOf(db.kvGet("verbosity") ?: "all") }
    var cap by remember { mutableStateOf(db.kvGet("capG")?.toFloatOrNull() ?: 0f) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0F14))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SETTINGS", color = Color.White, fontSize = 24.sp)

        // ---- updates ----
        val checking by activity.updateChecking
        val status by activity.updateStatus
        val ready by activity.updateReady
        Text("App version ${BuildConfig.VERSION_NAME}", color = Color(0xFFB8C4D0))
        Button(
            onClick = {
                if (ready != null) com.rallycopilot.app.update.Updater.install(activity, ready!!)
                else activity.checkForUpdates(manual = true)
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ready != null) Color(0xFF6BB8FF) else Color(0xFF232D38),
                disabledContainerColor = Color(0xFF1A222B),
            ),
        ) {
            Text(
                when {
                    checking -> "CHECKING…"
                    ready != null -> "INSTALL v${ready!!.version}"
                    else -> "CHECK FOR UPDATES"
                },
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (ready != null) Color(0xFF06080B) else Color(0xFFEAF0F6),
            )
        }
        status?.let {
            Text(it, color = if (ready != null) Color(0xFF6BB8FF) else Color(0xFF667788), fontSize = 12.sp)
        }

        // ---- when does the co-driver talk? ----
        var speakMode by remember { mutableStateOf(db.kvGet("speak_mode") ?: "smart") }
        Text("When to call", color = Color(0xFFB8C4D0))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, title, sub) in listOf(
                Triple("smart", "Only when I'm pressing on",
                    "cruising stays quiet — speed cameras still called"),
                Triple("always", "Every drive, all the time",
                    "corners, hazards and cameras whenever you're moving"),
            )) {
                val selected = speakMode == key
                Button(
                    onClick = {
                        speakMode = key
                        db.kvPut("speak_mode", key)
                        activity.driveService?.setAlwaysSpeak(key == "always")
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2EE06B) else Color(0xFF141C24),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) Color.Black else Color(0xFFEAF0F6),
                        )
                        Text(
                            sub, fontSize = 10.sp, maxLines = 1,
                            color = if (selected) Color(0xCC06080B) else Color(0xFF7C8B9A),
                        )
                    }
                }
            }
        }
        Text(
            "Speed cameras are always announced either way — an alert you only get " +
                "sometimes is one you can't rely on.",
            color = Color(0xFF667788), fontSize = 11.sp,
        )

        // ---- voice level and where it comes out ----
        var vol by remember { mutableStateOf(db.kvGet("voice_volume")?.toFloatOrNull() ?: 1.0f) }
        var bal by remember { mutableStateOf(db.kvGet("voice_balance")?.toFloatOrNull() ?: 0.0f) }

        Text("Voice volume: ${(vol * 100).toInt()}%", color = Color(0xFFB8C4D0))
        Slider(
            value = vol,
            onValueChange = { vol = it; activity.driveService?.setVoiceVolume(it) },
            onValueChangeFinished = {
                db.kvPut("voice_volume", vol.toString())
                activity.driveService?.previewVoice()
            },
            valueRange = 0.1f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF2EE06B),
                activeTrackColor = Color(0xFF2EE06B),
                inactiveTrackColor = Color(0xFF232D38),
            ),
        )

        Text(
            when {
                bal <= -0.95f -> "Speaker: hard LEFT (driver's side)"
                bal < -0.1f -> "Speaker: biased left ${(-bal * 100).toInt()}%"
                bal > 0.95f -> "Speaker: hard RIGHT"
                bal > 0.1f -> "Speaker: biased right ${(bal * 100).toInt()}%"
                else -> "Speaker: centre (both sides)"
            },
            color = Color(0xFFB8C4D0),
        )
        Slider(
            value = bal,
            onValueChange = { bal = it; activity.driveService?.setVoiceBalance(it) },
            onValueChangeFinished = {
                db.kvPut("voice_balance", bal.toString())
                activity.driveService?.previewVoice()
            },
            valueRange = -1f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF6BB8FF),
                activeTrackColor = Color(0xFF6BB8FF),
                inactiveTrackColor = Color(0xFF232D38),
            ),
        )
        Text(
            "Over Bluetooth the car owns its speakers — Android can only pan the voice " +
                "left or right, not pick one physical speaker. Hard left puts it in the " +
                "driver's-side speakers; use the car's own fader to push it forward. " +
                "Slide during a drive to hear a sample.",
            color = Color(0xFF667788), fontSize = 11.sp,
        )

        Text("Verbosity", color = Color(0xFFB8C4D0))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("all" to "Everything", "tight" to "4+ only", "min" to "Min")) {
                Button(
                    onClick = {
                        verbosity = key
                        db.kvPut("verbosity", key)
                        activity.driveService?.setVerbosity(key)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (verbosity == key) Color(0xFF1DB954) else Color(0xFF141C24)
                    ),
                ) { Text(label, color = if (verbosity == key) Color.Black else Color.White) }
            }
        }

        Text(
            if (cap <= 0f) "Lateral-g cap: OFF (learning unbounded — your choice)"
            else "Lateral-g cap: %.2f g".format(cap),
            color = Color(0xFFB8C4D0),
        )
        Slider(
            value = cap, onValueChange = { cap = it },
            onValueChangeFinished = { db.kvPut("capG", cap.toString()) },
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF2EE06B),
                activeTrackColor = Color(0xFF2EE06B),
                inactiveTrackColor = Color(0xFF232D38),
            ),
        )
        // ---- speed source ----
        var speedSrc by remember { mutableStateOf(db.kvGet("speed_source") ?: "auto") }
        Text("Speed source", color = Color(0xFFB8C4D0))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf(
                "auto" to "Auto", "obd" to "OBD", "gps" to "GPS",
            )) {
                val selected = speedSrc == key
                Button(
                    onClick = {
                        speedSrc = key
                        db.kvPut("speed_source", key)
                        activity.driveService?.setSpeedSource(key)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2EE06B) else Color(0xFF141C24),
                    ),
                ) {
                    Text(
                        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (selected) Color.Black else Color(0xFFB8C4D0),
                    )
                }
            }
        }
        Text(
            when (speedSrc) {
                "gps" -> "GPS only — Bluetooth is never opened, so no OBD, no gear calls."
                "obd" -> "Car speed preferred. GPS still covers you while the dongle " +
                    "connects or if it drops, and the OBD/GPS lights show which is live."
                else -> "Uses the car's own speed when the dongle is live, GPS otherwise. " +
                    "The OBD and GPS lights on the drive screen show which one you're on."
            },
            color = Color(0xFF667788), fontSize = 11.sp,
        )

        // ---- OBD dongle selection ----
        Text("OBD dongle", color = Color(0xFFB8C4D0))
        // What the link is actually doing right now — connecting, retrying, why it
        // failed. Polled so you can watch it come up while sitting in the car.
        val obdLive by androidx.compose.runtime.produceState<String?>(initialValue = null) {
            while (true) {
                value = activity.driveService?.obdStatusText()
                kotlinx.coroutines.delay(1000)
            }
        }
        Text(
            "Status: " + (obdLive ?: "no drive running — start one to connect"),
            color = if (obdLive?.startsWith("live") == true) Color(0xFF2EE06B) else Color(0xFF8899AA),
            fontSize = 12.sp,
        )
        val selectedMac = remember { mutableStateOf(db.kvGet("obd_mac")) }
        val bonded = remember {
            // BLUETOOTH_CONNECT (API 31+) may be denied — check first instead of
            // letting a SecurityException decide the outcome.
            val btAllowed = android.os.Build.VERSION.SDK_INT < 31 ||
                activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!btAllowed) emptyList()
            else runCatching {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?.bondedDevices?.map { (it.name ?: "unknown") to it.address }.orEmpty()
            }.getOrDefault(emptyList())
        }
        if (bonded.isEmpty()) {
            Text(
                "No paired Bluetooth devices visible — pair the ELM327 in system Bluetooth " +
                    "settings first (or grant the Nearby devices permission).",
                color = Color(0xFF667788), fontSize = 12.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Auto option
            Button(
                onClick = {
                    selectedMac.value = null
                    db.writableDatabase.execSQL("DELETE FROM kv WHERE key='obd_mac'")
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedMac.value == null) Color(0xFF2EE06B) else Color(0xFF141C24),
                ),
            ) {
                Text(
                    "Auto-detect (by device name)", fontSize = 13.sp,
                    color = if (selectedMac.value == null) Color.Black else Color(0xFFB8C4D0),
                )
            }
            for ((name, mac) in bonded) {
                val selected = selectedMac.value == mac
                Button(
                    onClick = { selectedMac.value = mac; db.kvPut("obd_mac", mac) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF2EE06B) else Color(0xFF141C24),
                    ),
                ) {
                    Text(
                        "$name · $mac", fontSize = 13.sp,
                        color = if (selected) Color.Black else Color(0xFFB8C4D0),
                    )
                }
            }
            Button(
                onClick = {
                    db.writableDatabase.execSQL("DELETE FROM kv WHERE key LIKE 'obd_pids_%'")
                    db.writableDatabase.execSQL("DELETE FROM kv WHERE key='car_vin'")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232D38)),
            ) { Text("Rescan car PIDs on next connect", fontSize = 12.sp, color = Color(0xFFB8C4D0)) }
            val vin = db.kvGet("car_vin")
            Text(
                when {
                    vin != null -> "Car remembered by VIN $vin — PIDs cached, connects instantly, " +
                        "and the cache follows the car even if you swap dongles"
                    else -> "PIDs scan once on first connect, then are remembered — keyed by the " +
                        "car's VIN when the ECU reports one, otherwise by the dongle"
                },
                color = Color(0xFF667788), fontSize = 11.sp,
            )
        }

        Text(
            "Suggested speeds are advisory. They come from map geometry that can be wrong, " +
                "and know nothing about surface, camber, weather or traffic. Your eyes win.",
            color = Color(0xFF667788), fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}
