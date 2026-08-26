package com.rallycopilot.app.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rallycopilot.app.audio.VoicePack
import com.rallycopilot.app.data.AppDb
import com.rallycopilot.app.data.KnowledgeDb
import com.rallycopilot.app.data.SqliteMapStore
import com.rallycopilot.app.obd.ObdClient
import com.rallycopilot.core.advisor.Advisor
import com.rallycopilot.core.engine.Clock
import com.rallycopilot.core.engine.DriveEngine
import com.rallycopilot.core.horizon.HorizonBuilder
import com.rallycopilot.core.matcher.MapMatcher
import com.rallycopilot.core.model.Conditions
import com.rallycopilot.core.model.Fix
import com.rallycopilot.core.profile.Learning
import com.rallycopilot.core.profile.ObservationCollector
import com.rallycopilot.core.profile.StyleDetector
import com.rallycopilot.core.report.IncidentDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a drive: GPS in, engine in the middle, voice out.
 * The activity binds to read HudState; killing the activity does not kill the drive.
 */
class DriveService : Service() {

    inner class LocalBinder : Binder() {
        val service: DriveService get() = this@DriveService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var engine: DriveEngine
        private set
    private lateinit var voice: VoicePack
    private lateinit var db: AppDb
    private var collector: ObservationCollector? = null
    private var fused: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    val obd = ObdClient(scope)
    var runId: Long = -1
        private set
    var isCalibration = false
        private set
    var isDemo = false
        private set
    var conditions: Conditions = Conditions.DRY
        private set
    var map: SqliteMapStore? = null
        private set
    private var knowledge: KnowledgeDb? = null
    private var imu: ImuMonitor? = null
    private var slowdownMonitor: com.rallycopilot.core.knowledge.SlowdownMonitor? = null
    private var distanceM = 0.0
    private var lastFix: Fix? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        db = AppDb(this)
        voice = VoicePack(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopDrive(); return START_NOT_STICKY }
        if (runId >= 0) return START_STICKY // already driving

        startForeground(NOTIF_ID, buildNotification())

        conditions = if (intent?.getBooleanExtra(EXTRA_WET, false) == true) Conditions.WET else Conditions.DRY
        isCalibration = intent?.getBooleanExtra(EXTRA_CALIBRATION, false) == true
        isDemo = intent?.getBooleanExtra(EXTRA_DEMO, false) == true
        runId = db.startRun(conditions, isCalibration)

        val map = SqliteMapStore(this)
        this.map = map
        val know = KnowledgeDb(db)
        knowledge = know
        val slowMon = com.rallycopilot.core.knowledge.SlowdownMonitor()
        slowdownMonitor = slowMon
        // Separate learned profiles per conditions: wet drives use and train the wet model.
        val profile = db.loadProfile(conditions)
        collector = ObservationCollector(runId, conditions)
        val clock = object : Clock { override fun nowMs() = System.currentTimeMillis() }
        val advisor = Advisor(profile, conditions = conditions)
        // Your road history trims suggestions where trouble was learned.
        advisor.speedFactorLookup = { e, a, b -> know.factorFor(e, a, b) }
        engine = DriveEngine(
            matcher = MapMatcher(map),
            horizonBuilder = HorizonBuilder(map, know),
            advisor = advisor,
            audio = voice,
            runLog = db.runLogFor(runId),
            clock = clock,
            vehicle = obd,
            collector = collector,
            incidentDetector = IncidentDetector(),
            styleDetector = StyleDetector(),
            healthWatch = com.rallycopilot.core.obd.HealthWatch(),
            knowledge = know,
            slowdown = slowMon,
        )

        // IMU: surface roughness + pothole spikes, tagged to the matched road bucket.
        imu = ImuMonitor(
            this,
            onRoughness = { rms ->
                engine.hud.value.matched?.let { m ->
                    if (engine.hud.value.speedMps > 4.0) know.addRoughness(m.edgeId, m.offsetM, rms)
                }
            },
            onBump = { slowMon.reportBump(System.currentTimeMillis()) },
        ).also { it.start() }

        voice.requestFocus()
        voice.startKeepAlive()
        if (isDemo) startDemo(map) else {
            startGps()
            // Bonded-device access needs BLUETOOTH_CONNECT; never let a denial kill the drive.
            runCatching {
                // User-selected dongle wins; otherwise guess by name.
                val mac = db.kvGet("obd_mac") ?: obd.findBonded()
                if (mac != null) {
                    // PID cache keyed by VIN when readable (follows the car), else MAC.
                    obd.connect(
                        mac,
                        loadCache = { key ->
                            db.kvGet("obd_pids_" + key)
                                ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet()
                        },
                        saveCache = { key, scanned ->
                            db.kvPut("obd_pids_" + key, scanned.joinToString(","))
                            if (key.startsWith("vin:")) db.kvPut("car_vin", key.removePrefix("vin:"))
                        },
                    )
                }
            }
        }

        // Tick loop at 10 Hz: dead reckoning, trigger checks, HUD state.
        scope.launch {
            while (isActive && runId >= 0) {
                engine.onTick()
                delay(100)
            }
        }
        return START_STICKY
    }

    /** Demo: replay a synthesised drive along a real Stroud road at wall-clock pace. */
    private fun startDemo(map: SqliteMapStore) {
        scope.launch(Dispatchers.Default) {
            val samples = DemoDrive.route(map)
            if (samples.isEmpty()) return@launch
            val fixes = DemoDrive.fixes(samples, System.currentTimeMillis() + 1500)
            var i = 0
            while (isActive && i < fixes.size && runId >= 0) {
                val now = System.currentTimeMillis()
                val f = fixes[i]
                if (f.tMs <= now) {
                    lastFix?.let { prev ->
                        distanceM += com.rallycopilot.core.geo.Geo.haversineM(
                            com.rallycopilot.core.model.LatLon(prev.lat, prev.lon),
                            com.rallycopilot.core.model.LatLon(f.lat, f.lon),
                        )
                    }
                    lastFix = f
                    engine.onFix(f)
                    i++
                } else {
                    delay((f.tMs - now).coerceAtMost(200))
                }
            }
        }
    }

    private fun startGps() {
        fused = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L)
            .setMinUpdateIntervalMillis(100L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val fix = Fix(
                        tMs = loc.time,
                        lat = loc.latitude, lon = loc.longitude,
                        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                        bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 99.0,
                    )
                    lastFix?.let { prev ->
                        distanceM += com.rallycopilot.core.geo.Geo.haversineM(
                            com.rallycopilot.core.model.LatLon(prev.lat, prev.lon),
                            com.rallycopilot.core.model.LatLon(fix.lat, fix.lon),
                        )
                    }
                    lastFix = fix
                    engine.onFix(fix)
                }
            }
        }
        locationCallback = cb
        try {
            fused?.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked mid-run; HUD shows GPS lost.
        }
    }

    fun stopDrive() {
        if (runId >= 0) {
            db.endRun(runId, distanceM)
            collector?.observations?.let { obs ->
                db.saveObservations(obs)
                // Learning pass: calibration runs cut everything before the onset.
                val useObs = if (isCalibration) {
                    val onset = Learning.onsetIndex(obs)
                    obs.drop(onset)
                } else obs
                if (useObs.isNotEmpty()) {
                    // Learn only from history matching this run's conditions.
                    val history = db.allObservations().filter { it.conditions == conditions }
                    val updated = Learning.applySession(db.loadProfile(conditions), history)
                    db.saveProfile(updated, conditions)
                }
            }
        }
        runId = -1
        locationCallback?.let { fused?.removeLocationUpdates(it) }
        locationCallback = null
        imu?.stop(); imu = null
        obd.disconnect()
        voice.release()
        // Do NOT cancel the scope here: Android may reuse this service instance for the
        // next drive, and a cancelled scope silently drops every future launch — which
        // presents as a dead HUD stuck on STARTING. The tick/demo loops exit on their
        // own via the runId guard; the scope dies with the instance in onDestroy.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (runId >= 0) stopDrive()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Drive", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Rally Copilot")
            .setContentText("Drive in progress")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "drive"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.rallycopilot.STOP"
        const val EXTRA_WET = "wet"
        const val EXTRA_CALIBRATION = "calibration"
        const val EXTRA_DEMO = "demo"
    }
}
