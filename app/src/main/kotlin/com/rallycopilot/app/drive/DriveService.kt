package com.rallycopilot.app.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Foreground service that owns a drive: GPS in, engine in the middle, voice out.
 * The activity binds to read HudState; killing the activity does not kill the drive.
 *
 * THREADING: the DriveEngine has no internal locking, so EVERYTHING that mutates it
 * (fixes, ticks, prompt answers) is funnelled onto one single-threaded dispatcher.
 * The HUD reads engine.hud (a StateFlow) from anywhere — that is the one safe surface.
 */
class DriveService : Service() {

    inner class LocalBinder : Binder() {
        val service: DriveService get() = this@DriveService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "drive-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    lateinit var engine: DriveEngine
        private set
    private lateinit var voice: VoicePack
    private lateinit var db: AppDb
    private var collector: ObservationCollector? = null
    private var fused: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    val obd = ObdClient(scope)
    /** True from DRIVE tap to END; runId is assigned asynchronously during init. */
    @Volatile var driveActive = false
        private set
    @Volatile var runId: Long = -1
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
    @Volatile private var distanceM = 0.0
    private var lastFix: Fix? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb.get(this)
        voice = VoicePack(this)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopDrive(); return START_NOT_STICKY }
        // Null intent = START_STICKY restart after process death. Never silently start
        // a phantom drive with default settings — just go away.
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (driveActive) return START_NOT_STICKY // already driving

        isDemo = intent.getBooleanExtra(EXTRA_DEMO, false)
        // On API 34+ a location-type startForeground THROWS unless location is actually
        // granted right now. The activity gates DRIVE on the grant; this is the belt to
        // that brace — and DEMO needs no location at all, so it runs as media playback.
        val useLocationType = hasLocationPermission() && !isDemo
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                if (useLocationType) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        driveActive = true
        conditions = if (intent.getBooleanExtra(EXTRA_WET, false)) Conditions.WET else Conditions.DRY
        isCalibration = intent.getBooleanExtra(EXTRA_CALIBRATION, false)
        // Fresh per-run state: a reused service instance must not inherit the previous
        // run's distance or a stale lastFix (one giant haversine jump).
        distanceM = 0.0
        lastFix = null

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rallycopilot:drive")
            .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }

        // Heavy lifting (48 MB first-run map copy, DB DDL, profile load) runs OFF the
        // main thread, on the engine's own thread so all engine state is born there.
        scope.launch(engineDispatcher) { initDrive() }
        return START_NOT_STICKY
    }

    private fun initDrive() {
        if (!driveActive) return
        val map = SqliteMapStore(this)
        this.map = map
        val know = KnowledgeDb(db)
        knowledge = know
        val slowMon = com.rallycopilot.core.knowledge.SlowdownMonitor()
        slowdownMonitor = slowMon
        runId = db.startRun(conditions, isCalibration)
        // Separate learned profiles per conditions: wet drives use and train the wet model.
        val profile = db.loadProfile(conditions)
        collector = ObservationCollector(runId, conditions)
        val clock = object : Clock { override fun nowMs() = System.currentTimeMillis() }
        val advisor = Advisor(profile, conditions = conditions)
        // Your road history trims suggestions where trouble was learned.
        advisor.speedFactorLookup = { e, a, b -> know.factorFor(e, a, b) }
        advisor.camberLookup = { e, a, b -> know.camberFor(e, a, b) }
        // Gear calls from the learned ratio table, aiming at the exit revs learned
        // from how THIS driver actually shifts (see GearInference.exitRpm).
        advisor.gearLookup = { v -> if (obd.connected) obd.gearInference.gearForSpeed(v) else null }
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
        // "always" = call everything whenever driving; default = quiet unless pressing on.
        engine.alwaysSpeak = db.kvGet("speak_mode") == "always"
        engine.maxSpokenBandOrdinal = verbosityOrdinal(db.kvGet("verbosity"))
        engine.speedSource = speedSourceOf(db.kvGet("speed_source"))

        // IMU: surface roughness, pothole spikes, mount self-alignment and camber —
        // all tagged to the matched road bucket. No calibration step anywhere: the
        // forward axis is learned from firm accelerate/brake events automatically.
        val mount = com.rallycopilot.core.imu.MountAlignment()
        val camber = com.rallycopilot.core.imu.CamberEstimator(mount)
        var lastSpeed = 0.0
        var lastSpeedT = 0L
        var currentDvdt = 0.0
        var lastCamberWriteT = 0L
        imu = ImuMonitor(
            this,
            onRoughness = { rms ->
                engine.hud.value.matched?.let { m ->
                    if (engine.hud.value.speedMps > 4.0) {
                        scope.launch(engineDispatcher) { know.addRoughness(m.edgeId, m.offsetM, rms) }
                    }
                }
            },
            onBump = { slowMon.reportBump(System.currentTimeMillis()) },
            onSample = { accel, gravRaw ->
                // Android's TYPE_GRAVITY points UP (reaction force); the core maths
                // expects the physical gravity vector, pointing DOWN.
                val grav = gravRaw * -1.0
                val hudNow = engine.hud.value
                val now = System.currentTimeMillis()
                // dv/dt over a >=300 ms window of HUD speed — NOT per-IMU-sample deltas.
                // Integer-km/h OBD speed over a 20 ms gap reads ~14 m/s² of pure
                // quantisation noise and would flood the alignment event gate.
                if (lastSpeedT == 0L) {
                    lastSpeed = hudNow.speedMps; lastSpeedT = now
                } else if (now - lastSpeedT >= 300) {
                    currentDvdt = (hudNow.speedMps - lastSpeed) / ((now - lastSpeedT) / 1000.0)
                    lastSpeed = hudNow.speedMps; lastSpeedT = now
                }
                mount.tick(accel, grav, currentDvdt)
                val deg = camber.tick(accel, grav, hudNow.speedMps)
                // Persist camber at ~1 Hz against the current bucket, normalised to the
                // edge's FORWARD frame — the same crown leans the other way when the
                // edge is driven against its node order, and mixing frames cancels the
                // learned value on every two-way road.
                if (deg != null && now - lastCamberWriteT > 1000) {
                    lastCamberWriteT = now
                    hudNow.matched?.let { m ->
                        val forwardFrameDeg = if (m.forward) deg else -deg
                        scope.launch(engineDispatcher) { know.addCamber(m.edgeId, m.offsetM, forwardFrameDeg) }
                    }
                }
            },
        ).also { it.start() }

        voice.setVolume(db.kvGet("voice_volume")?.toFloatOrNull() ?: 1.0f)
        voice.setBalance(db.kvGet("voice_balance")?.toFloatOrNull() ?: 0.0f)
        voice.requestFocus()
        voice.startKeepAlive()
        if (isDemo) startDemo(map) else {
            startGps()
            // Bonded-device access needs BLUETOOTH_CONNECT; never let a denial kill the drive.
            runCatching {
                // GPS-only: do not touch Bluetooth at all, so the OBD chip stays dark
                // and there is no ambiguity about where the speed came from.
                val mac = resolveObdMac()
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
                        // Gear calibration follows the car, same key as the PID cache.
                        loadGears = { key -> db.kvGet("obd_gears_" + key) },
                        saveGears = { key, data -> db.kvPut("obd_gears_" + key, data) },
                        // Remember which protocol the car answered on, so the sweep
                        // is paid for once rather than on every connect.
                        loadProto = { key -> db.kvGet("obd_proto_" + key) },
                        saveProto = { key, cmd -> db.kvPut("obd_proto_" + key, cmd) },
                    )
                }
            }
        }

        // Tick loop at 10 Hz on the engine thread: dead reckoning, triggers, HUD state.
        scope.launch(engineDispatcher) {
            while (isActive && driveActive) {
                engine.onTick()
                delay(100)
            }
        }
    }

    /** Live change of the quiet-mode setting; takes effect on the next tick. */
    fun setAlwaysSpeak(always: Boolean) {
        if (::engine.isInitialized) engine.alwaysSpeak = always
    }

    /** Live change of verbosity; takes effect on the next tick. */
    fun setVerbosity(key: String?) {
        if (::engine.isInitialized) engine.maxSpokenBandOrdinal = verbosityOrdinal(key)
    }

    /** Live change of the speed source; takes effect on the next tick. */
    fun setSpeedSource(key: String?) {
        if (::engine.isInitialized) engine.speedSource = speedSourceOf(key)
    }

    /** One-line description of what the OBD link is doing, for the settings screen. */
    fun obdStatusText(): String = obd.statusText

    /** Current link state, for the drive-screen overlay. */
    fun obdState(): ObdClient.State = obd.state

    /** Raw dongle conversation, for diagnosing a car that will not answer. */
    fun obdLog(): List<String> = obd.diagnosticLog()

    /**
     * Which dongle to talk to — and, when the answer is "none", WHY. Every branch
     * here used to fall out as a silent null, so a denied permission, Bluetooth
     * being switched off and no dongle being paired were indistinguishable from
     * the app simply not bothering.
     */
    private fun resolveObdMac(): String? {
        if (db.kvGet("speed_source") == "gps") {
            obd.reportUnavailable("speed source is set to GPS only"); return null
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            obd.reportUnavailable("Nearby devices permission not granted"); return null
        }
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { obd.reportUnavailable("no Bluetooth on this phone"); return null }
        if (!adapter.isEnabled) { obd.reportUnavailable("Bluetooth is switched off"); return null }
        db.kvGet("obd_mac")?.let { return it }
        val found = runCatching { obd.findBonded() }.getOrNull()
        if (found == null) {
            val paired = runCatching { adapter.bondedDevices.size }.getOrDefault(0)
            obd.reportUnavailable(
                if (paired == 0) "no paired Bluetooth devices - pair the ELM327 first"
                else "none of your $paired paired devices looks like an OBD dongle - pick it in Settings"
            )
        }
        return found
    }

    /** Live voice level/balance changes from the settings screen. */
    fun setVoiceVolume(v: Float) { if (::voice.isInitialized) voice.setVolume(v) }
    fun setVoiceBalance(b: Float) { if (::voice.isInitialized) voice.setBalance(b) }

    /** Play a sample so the driver can set level and balance with the engine running. */
    fun previewVoice() {
        if (!::voice.isInitialized) return
        voice.play(
            com.rallycopilot.core.model.Utterance(
                clipKeys = listOf("left_four"), urgent = false,
                deadlineDistanceM = 0.0, forCornerId = null,
            )
        )
    }

    /** UI answer to the hazard prompt, marshalled onto the engine thread. */
    fun answerHazard(yes: Boolean) {
        scope.launch(engineDispatcher) {
            if (driveActive && ::engine.isInitialized) engine.answerHazardPrompt(yes)
        }
    }

    /** Demo: replay a synthesised drive along a real Stroud road at wall-clock pace. */
    private fun startDemo(map: SqliteMapStore) {
        scope.launch(engineDispatcher) {
            val samples = DemoDrive.route(map)
            if (samples.isEmpty()) return@launch
            val fixes = DemoDrive.fixes(samples, System.currentTimeMillis() + 1500)
            var i = 0
            while (isActive && i < fixes.size && driveActive) {
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
                        // Stamp with the engine's own clock domain. loc.time is GNSS/UTC
                        // time — a device clock a few seconds off would otherwise fail
                        // the 3 s freshness check forever and report "GPS lost".
                        tMs = System.currentTimeMillis(),
                        lat = loc.latitude, lon = loc.longitude,
                        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                        // NaN = "no bearing", which the matcher treats as neutral —
                        // 0.0 is indistinguishable from genuinely heading north.
                        bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 99.0,
                    )
                    lastFix?.let { prev ->
                        distanceM += com.rallycopilot.core.geo.Geo.haversineM(
                            com.rallycopilot.core.model.LatLon(prev.lat, prev.lon),
                            com.rallycopilot.core.model.LatLon(fix.lat, fix.lon),
                        )
                    }
                    lastFix = fix
                    scope.launch(engineDispatcher) { if (driveActive) engine.onFix(fix) }
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
        if (!driveActive) return
        driveActive = false
        val rid = runId
        runId = -1
        // Cheap teardown immediately, on whichever thread called us.
        locationCallback?.let { fused?.removeLocationUpdates(it) }
        locationCallback = null
        imu?.stop(); imu = null
        obd.disconnect()
        voice.release()
        wakeLock?.let { runCatching { it.release() } }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Persistence + the learning pass go to the engine thread — after months of
        // observations this is real work and has no place on a button's onClick.
        val obs = collector?.observations
        scope.launch(engineDispatcher) {
            if (rid >= 0) {
                db.endRun(rid, distanceM)
                if (obs != null) {
                    // Calibration runs cut everything before the detected onset — the
                    // warm-up corners never enter the learning history at all.
                    val useObs = if (isCalibration) obs.drop(Learning.onsetIndex(obs)) else obs
                    db.saveObservations(useObs)
                    if (useObs.isNotEmpty()) {
                        // Learn only from history matching this run's conditions.
                        val history = db.allObservations().filter { it.conditions == conditions }
                        val updated = Learning.applySession(db.loadProfile(conditions), history)
                        db.saveProfile(updated, conditions)
                    }
                }
            }
            stopSelf()
        }
        // Scope stays alive: Android may reuse this instance for the next drive.
        // The loops above exit via the driveActive guard; the scope dies in onDestroy.
    }

    override fun onDestroy() {
        if (driveActive) stopDrive()
        instance = null
        voice.shutdown()
        scope.cancel()
        engineDispatcher.close()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Drive", NotificationManager.IMPORTANCE_LOW)
        )
        // The notification must always be a way back in AND a way OUT: if the activity
        // is recreated mid-drive and loses its binding, this is the only handle left.
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Rally Copilot")
            .setContentText("Drive in progress")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "End drive", stop).build())
            .build()
    }

    companion object {
        /** Settings verbosity key → gentlest severity band still spoken. */
        fun verbosityOrdinal(key: String?): Int = when (key) {
            "tight" -> com.rallycopilot.core.model.SeverityBand.FOUR.ordinal
            "min" -> com.rallycopilot.core.model.SeverityBand.TWO.ordinal
            // Default stays "call everything" — the user's standing choice.
            else -> com.rallycopilot.core.model.SeverityBand.SIX.ordinal
        }

        fun speedSourceOf(key: String?): com.rallycopilot.core.engine.DriveEngine.SpeedSource = when (key) {
            "gps" -> com.rallycopilot.core.engine.DriveEngine.SpeedSource.GPS_ONLY
            "obd" -> com.rallycopilot.core.engine.DriveEngine.SpeedSource.OBD_ONLY
            else -> com.rallycopilot.core.engine.DriveEngine.SpeedSource.AUTO
        }

        const val CHANNEL = "drive"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.rallycopilot.STOP"
        const val EXTRA_WET = "wet"
        const val EXTRA_CALIBRATION = "calibration"
        const val EXTRA_DEMO = "demo"

        /** The live service instance, for the activity to rebind after recreation. */
        @Volatile var instance: DriveService? = null
            private set
    }
}
