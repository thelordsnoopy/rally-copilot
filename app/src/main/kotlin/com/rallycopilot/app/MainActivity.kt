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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Set when a newer release has been downloaded and is ready to install. */
    val updateReady = androidx.compose.runtime.mutableStateOf<com.rallycopilot.app.update.Updater.Available?>(null)

    private fun checkForUpdates() {
        lifecycleScope.launch {
            updateReady.value = com.rallycopilot.app.update.Updater.check(
                this@MainActivity, BuildConfig.VERSION_NAME
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )
        checkForUpdates()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppNav(this)
            }
        }
    }

    fun startDrive(wet: Boolean, calibration: Boolean, demo: Boolean = false) {
        val i = Intent(this, DriveService::class.java)
            .putExtra(DriveService.EXTRA_WET, wet)
            .putExtra(DriveService.EXTRA_CALIBRATION, calibration)
            .putExtra(DriveService.EXTRA_DEMO, demo)
        startForegroundService(i)
        bindService(i, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    fun stopDrive() {
        driveService?.stopDrive()
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
    var wet by remember { mutableStateOf(false) }
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
            onClick = { activity.startDrive(wet, calibration = false); onNav("drive") },
            modifier = Modifier.fillMaxWidth().height(92.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = green),
        ) {
            Text("DRIVE", fontSize = 30.sp, color = Color(0xFF06080B), fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { activity.startDrive(wet, calibration = true); onNav("drive") },
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
    val db = remember { AppDb(activity) }
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
    val db = remember { AppDb(activity) }
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
                                onClick = { com.rallycopilot.app.extras.DriveReport.share(activity, run.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            ) { Text("Share card", color = Color.Black, fontSize = 13.sp) }
                            Button(
                                onClick = {
                                    Thread {
                                        com.rallycopilot.app.extras.OverlayExporter.export(activity, run.id)
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
    val db = remember { AppDb(activity) }
    var verbosity by remember { mutableStateOf(db.kvGet("verbosity") ?: "all") }
    var cap by remember { mutableStateOf(db.kvGet("capG")?.toFloatOrNull() ?: 0f) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0F14))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SETTINGS", color = Color.White, fontSize = 24.sp)

        Text("Verbosity", color = Color(0xFFB8C4D0))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("all" to "Everything", "tight" to "4+ only", "min" to "Min")) {
                Button(
                    onClick = { verbosity = key; db.kvPut("verbosity", key) },
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
        // ---- OBD dongle selection ----
        Text("OBD dongle", color = Color(0xFFB8C4D0))
        val selectedMac = remember { mutableStateOf(db.kvGet("obd_mac")) }
        val bonded = remember {
            runCatching {
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
            selectedMac.value?.let { mac ->
                Button(
                    onClick = {
                        db.writableDatabase.execSQL("DELETE FROM kv WHERE key=?", arrayOf("obd_pids_$mac"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232D38)),
                ) { Text("Rescan car PIDs on next connect", fontSize = 12.sp, color = Color(0xFFB8C4D0)) }
                Text(
                    if (db.kvGet("obd_pids_$mac") != null)
                        "PIDs remembered for this car — connects instantly"
                    else "PIDs will be scanned once on next connect, then remembered",
                    color = Color(0xFF667788), fontSize = 11.sp,
                )
            }
        }

        Text(
            "Suggested speeds are advisory. They come from map geometry that can be wrong, " +
                "and know nothing about surface, camber, weather or traffic. Your eyes win.",
            color = Color(0xFF667788), fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}
