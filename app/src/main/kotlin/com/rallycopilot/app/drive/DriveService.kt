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
    /** Why the conditions are what they are ("raining now", "manual"), for the UI. */
    @Volatile var conditionsWhy: String = "manual"
        private set
    var map: SqliteMapStore? = null
        private set
    private var knowledge: KnowledgeDb? = null
    private var imu: ImuMonitor? = null
    private var slowdownMonitor: com.rallycopilot.core.knowledge.SlowdownMonitor? = null
    private var radiusAuditor: com.rallycopilot.core.knowledge.RadiusAuditor? = null
    private var blackBox: com.rallycopilot.app.debug.BlackBox? = null
    private var voiceCommands: com.rallycopilot.app.audio.VoiceCommands? = null
    /** Is the car going where it is pointing? Gyro vs GNSS course. */
    val slip = com.rallycopilot.core.imu.SlipEstimator()
    @Volatile private var yawRateRadS = 0.0
    @Volatile private var courseRateRadS = 0.0

    /** Phone-in-mount wobble, degrees — the DriveScreen warns above 8. */
    @Volatile var mountWobbleDeg: Double = 0.0
        private set
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
        conditionsWhy = "manual"
        conditionsAuto = intent.getBooleanExtra(EXTRA_COND_AUTO, false)
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

    @Volatile private var conditionsAuto = false

    /**
     * Auto wet/dry: ask Open-Meteo about rain at (roughly) here. Must run BEFORE
     * the profile load — wet and dry are separate learned models. Offline falls
     * back to whatever auto decided last time; auto is a default, never a lock.
     */
    private fun resolveAutoConditions() {
        val loc = runCatching {
            val task = LocationServices.getFusedLocationProviderClient(this).lastLocation
            com.google.android.gms.tasks.Tasks.await(task, 2, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull()
        val r = WeatherCheck.check(loc?.latitude ?: 51.746, loc?.longitude ?: -2.218)
        if (r != null) {
            conditions = r.conditions
            conditionsWhy = "auto: " + r.why
            db.kvPut("last_auto_conditions", r.conditions.name)
        } else {
            conditions = db.kvGet("last_auto_conditions")
                ?.let { runCatching { Conditions.valueOf(it) }.getOrNull() } ?: Conditions.DRY
            conditionsWhy = "auto: offline, using last known"
        }
    }

    private fun initDrive() {
        if (!driveActive) return
        if (conditionsAuto) resolveAutoConditions()
        val map = SqliteMapStore(this)
        this.map = map
        val know = KnowledgeDb(db)
        knowledge = know
        val slowMon = com.rallycopilot.core.knowledge.SlowdownMonitor()
        slowdownMonitor = slowMon
        runId = db.startRun(conditions, isCalibration)
        // The black box opens first: a crash during setup is exactly the kind of
        // thing there is otherwise no record of.
        val bb = if (db.kvGet("blackbox") != "off")
            com.rallycopilot.app.debug.BlackBox(this, runId) else null
        blackBox = bb
        bb?.log("drive_start", mapOf(
            "app" to com.rallycopilot.app.BuildConfig.VERSION_NAME,
            "versionCode" to com.rallycopilot.app.BuildConfig.VERSION_CODE,
            "device" to (android.os.Build.MODEL ?: "?"),
            "android" to android.os.Build.VERSION.SDK_INT,
            "conditions" to conditions.name, "why" to conditionsWhy,
            "calibration" to isCalibration, "demo" to isDemo,
            "car" to db.activeCarKey(),
            "speakMode" to (db.kvGet("speak_mode") ?: "smart"),
            "verbosity" to (db.kvGet("verbosity") ?: "all"),
            "speedSource" to (db.kvGet("speed_source") ?: "auto"),
            "audioFocus" to (db.kvGet("audio_focus") ?: "pause"),
            "keepAlive" to (db.kvGet("bt_keepalive") != "off"),
            "voiceCommands" to (db.kvGet("voice_commands") == "on"),
            "coaching" to (db.kvGet("coaching") != "off"),
            "audioLatencyMs" to (db.kvGet("audio_latency_ms") ?: "unmeasured"),
        ))
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
        // Radius audit: your measured cornering vs the map's radius, per corner.
        // Wiped automatically when the map data changes (corner ids are unstable).
        val auditor = com.rallycopilot.core.knowledge.RadiusAuditor(
            com.rallycopilot.app.data.AuditDb(db, map.fingerprint)
        )
        radiusAuditor = auditor
        advisor.radiusAuditLookup = { id -> auditor.adviceFor(id) }
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
            coach = if (db.kvGet("coaching") != "off") com.rallycopilot.core.advisor.Coach() else null,
            radiusAuditor = auditor,
            telemetry = bb ?: com.rallycopilot.core.engine.NullTelemetry,
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
        var lastSpeedSrc = false
        var currentDvdt = 0.0
        var lastCamberWriteT = 0L
        var lastImuLogT = 0L
        imu = ImuMonitor(
            this,
            onRoughness = { rms ->
                engine.hud.value.matched?.let { m ->
                    blackBox?.log("rough", mapOf(
                        "rms" to rms, "edge" to m.edgeId, "off" to m.offsetM,
                        "speed" to engine.hud.value.speedMps))
                    if (engine.hud.value.speedMps > 4.0) {
                        scope.launch(engineDispatcher) { know.addRoughness(m.edgeId, m.offsetM, rms) }
                    }
                }
            },
            onYawRate = { rad ->
                yawRateRadS = rad
                val hud = engine.hud.value
                val st = slip.tick(System.currentTimeMillis(), rad, courseRateRadS, hud.speedMps)
                engine.slipping = st.sliding
                engine.drivenRadiusM = slip.drivenRadiusM(hud.speedMps)
            },
            onBump = {
                slowMon.reportBump(System.currentTimeMillis())
                blackBox?.log("bump", mapOf(
                    "edge" to engine.hud.value.matched?.edgeId,
                    "off" to engine.hud.value.matched?.offsetM,
                    "speed" to engine.hud.value.speedMps))
            },
            onSample = { accel, gravRaw ->
                // Android's TYPE_GRAVITY points UP (reaction force); the core maths
                // expects the physical gravity vector, pointing DOWN.
                val grav = gravRaw * -1.0
                val hudNow = engine.hud.value
                val now = System.currentTimeMillis()
                // dv/dt over a >=300 ms window of HUD speed — NOT per-IMU-sample deltas.
                // Integer-km/h OBD speed over a 20 ms gap reads ~14 m/s² of pure
                // quantisation noise and would flood the alignment event gate.
                // dv/dt must be differentiated from ONE speed source. OBD answered on
                // 88% of ticks in the first real trace, and every drop back to GPS —
                // and every return — put a step change into the fused speed that is
                // not the car accelerating. Differentiating across that boundary
                // manufactured events with the wrong SIGN as often as the right one,
                // which is why 344 alignment events averaged out to a coherence of
                // 0.087 and the mount never aligned.
                val src = hudNow.speedFromObd
                if (lastSpeedT == 0L || src != lastSpeedSrc) {
                    lastSpeed = hudNow.speedMps; lastSpeedT = now
                    lastSpeedSrc = src; currentDvdt = 0.0
                } else if (now - lastSpeedT >= 300) {
                    val d = (hudNow.speedMps - lastSpeed) / ((now - lastSpeedT) / 1000.0)
                    // Belt to the core's brace: anything beyond road-car physics is
                    // an artefact and must not become an alignment event.
                    currentDvdt = if (kotlin.math.abs(d) <= 8.0) d else 0.0
                    lastSpeed = hudNow.speedMps; lastSpeedT = now
                }
                mount.tick(accel, grav, currentDvdt)
                // Car-frame lateral acceleration for the radius audit: the component
                // of accel along car-LEFT (up × forward). Null until the mount is
                // aligned — an unaligned axis would feed the audit garbage radii.
                engine.imuLateralMps2 = mount.forward?.let { fwd ->
                    val up = grav.unit() * -1.0
                    val left = up.cross(fwd).unit()
                    kotlin.math.abs(accel.dot(left)).takeIf { it.isFinite() }
                }
                val deg = camber.tick(accel, grav, hudNow.speedMps)
                // Black box: the raw IMU stream, thinned to ~10 Hz. Full sensor rate
                // is 50-100 Hz and would be most of the file for little extra truth.
                mountWobbleDeg = mount.wobbleDeg
                if (now - lastImuLogT > 100) {
                    lastImuLogT = now
                    val fwd = mount.forward
                    blackBox?.log("imu", mapOf(
                        "ax" to accel.x, "ay" to accel.y, "az" to accel.z,
                        "gx" to grav.x, "gy" to grav.y, "gz" to grav.z,
                        "dvdt" to currentDvdt,
                        "aligned" to mount.isAligned,
                        "wobbleDeg" to mount.wobbleDeg,
                        "stable" to mount.isStable,
                        "alignEvents" to mount.eventCount,
                        "coherence" to mount.coherence,
                        "fx" to fwd?.x, "fy" to fwd?.y, "fz" to fwd?.z,
                        "camberDeg" to deg,
                        "yawRate" to slip.state.yawRateRadS,
                        "courseRate" to slip.state.courseRateRadS,
                        "slipRatio" to slip.state.ratio,
                        "slipVerdict" to slip.state.verdict.name,
                        "sliding" to slip.state.sliding,
                        "drivenR" to slip.drivenRadiusM(hudNow.speedMps),
                        "aLat" to engine.imuLateralMps2,
                        "speed" to hudNow.speedMps,
                        "edge" to hudNow.matched?.edgeId, "off" to hudNow.matched?.offsetM,
                    ))
                }
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

        bb?.log("profile", mapOf(
            "push" to profile.pushFactor,
            "bands" to com.rallycopilot.core.model.SeverityBand.entries.joinToString(";") { b ->
                "${b.name}:${"%.2f".format(profile.aLatFor(b) / 9.81)}g:n${profile.sampleCountByBand[b] ?: 0}"
            },
        ))

        // Audio latency: use the remembered measurement immediately, then re-measure
        // in the background (head unit, codec and phone can all change between drives).
        // Reuse a stored measurement only if it was taken on the SAME audio route.
        // A 488 ms figure measured against who-knows-what was timing every call in
        // the first real trace, because a failed re-measurement silently falls back
        // to whatever was remembered. Timing is the one number worth being fussy
        // about: it is subtracted from every braking point.
        val storedRoute = db.kvGet("audio_latency_route")
        val stored = db.kvGet("audio_latency_ms")?.toLongOrNull()
        if (stored != null && storedRoute == audioRoute()) engine.audioLatencyMs = stored
        scope.launch(Dispatchers.IO) {
            // Give Bluetooth a moment to take the stream: the drive often starts
            // before the head unit has finished connecting.
            var waited = 0
            while (driveActive && waited < 20_000 && audioRoute() != "bluetooth") {
                delay(1000); waited += 1000
            }
            calibrateAudio()
            // Hands-free voice starts AFTER the chirp measurement: two owners of the
            // microphone at once and the calibrator hears the recogniser's silence.
            // OPT-IN, and off by default. Android's SpeechRecognizer grabs the
            // microphone and, on a lot of devices, makes the system duck or pause
            // media every time it starts listening — and this loop restarts it
            // continuously for the whole drive. That is the shape of "my music keeps
            // pausing at random", unconnected to any corner call.
            if (driveActive && db.kvGet("voice_commands") == "on") {
                voiceCommands = com.rallycopilot.app.audio.VoiceCommands(this@DriveService) { cmd ->
                    onVoiceCommand(cmd)
                }.also { it.start() }
            }
        }

        voice.setVolume(db.kvGet("voice_volume")?.toFloatOrNull() ?: 1.0f)
        voice.boostDb = db.kvGet("voice_boost")?.toIntOrNull() ?: 6
        voice.setBalance(db.kvGet("voice_balance")?.toFloatOrNull() ?: 0.0f)
        voice.muted = false // a "quiet" from last drive must not silence this one
        // Focus is taken per utterance from here on, never held across the drive.
        voice.focusMode = when (db.kvGet("audio_focus")) {
            "duck" -> VoicePack.FocusMode.DUCK
            "pause" -> VoicePack.FocusMode.PAUSE
            else -> VoicePack.FocusMode.NONE
        }
        if (db.kvGet("bt_keepalive") != "off") voice.startKeepAlive()
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

    /** Last audio-calibration outcome, for the settings screen. */
    @Volatile var audioCalibration: String = "not measured yet"
        private set

    /**
     * Play the chirp, listen for it, and use the measured delay for note timing.
     * Runs once at drive start, before you have set off.
     */
    private fun calibrateAudio() {
        if (!com.rallycopilot.app.audio.LatencyCalibrator.hasMic(this)) {
            audioCalibration = "microphone permission not granted - using ${engine.audioLatencyMs} ms"
            return
        }
        // Only worth measuring once audio is actually going to the car. The first
        // real trace measured 9 ms — the microphone hearing the phone's own speaker,
        // because the chirp ran before Bluetooth had the stream. A number that small
        // is not a fast car, it is proof the sound never left the phone, so the
        // result was binned and a stale 488 ms carried the whole drive's timing.
        val route = audioRoute()
        blackBox?.log("audio_route", mapOf("route" to route))
        if (route != "bluetooth") {
            audioCalibration = "not measured — audio is going to the $route, not the car"
            blackBox?.log("audio_cal", mapOf("ms" to null, "why" to "route=$route"))
            return
        }
        audioCalibration = "measuring..."
        // Hold the music off for the measurement, then hand it straight back. A song
        // playing across the chirp is the one thing that reliably breaks it.
        voice.beginMeasurement()
        val r = try {
            com.rallycopilot.app.audio.LatencyCalibrator.measure(this, voice.attributes)
        } finally {
            voice.endMeasurement()
        }
        val ms = r.latencyMs
        if (ms != null) {
            engine.audioLatencyMs = ms
            db.kvPut("audio_latency_ms", ms.toString())
            db.kvPut("audio_latency_route", route)
            audioCalibration = "audio delay ${ms} ms (measured)"
            blackBox?.log("audio_cal", mapOf("ms" to ms, "noise" to r.noiseLevel))
        } else {
            audioCalibration = "${r.message} - using ${engine.audioLatencyMs} ms"
            blackBox?.log("audio_cal", mapOf("ms" to null, "why" to r.message, "noise" to r.noiseLevel))
        }
    }

    /** Where the co-driver's voice is actually coming out right now. */
    private fun audioRoute(): String = runCatching {
        val am = getSystemService(android.media.AudioManager::class.java)
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        when {
            outs.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } -> "bluetooth"
            outs.any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "headphones"
            else -> "phone speaker"
        }
    }.getOrDefault("unknown")

    /** Map view telemetry: what the HUD actually had to draw, and from where. */
    fun logMapFetch(lat: Double, lon: Double, edgeId: Long, offsetM: Double, edges: Int, ms: Long) {
        blackBox?.log("map_fetch", mapOf(
            "lat" to lat, "lon" to lon, "edge" to edgeId, "off" to offsetM,
            "edges" to edges, "ms" to ms,
        ))
    }

    /** Hands-free commands. Every one is harmless to miss or to false-trigger. */
    private fun onVoiceCommand(cmd: com.rallycopilot.app.audio.VoiceCommands.Command) {
        if (!driveActive || !::voice.isInitialized) return
        when (cmd) {
            com.rallycopilot.app.audio.VoiceCommands.Command.AGAIN -> voice.repeatLast()
            com.rallycopilot.app.audio.VoiceCommands.Command.QUIET -> voice.muted = true
            com.rallycopilot.app.audio.VoiceCommands.Command.TALK -> voice.muted = false
            com.rallycopilot.app.audio.VoiceCommands.Command.LOUDER -> nudgeVolume(+0.15f)
            com.rallycopilot.app.audio.VoiceCommands.Command.QUIETER -> nudgeVolume(-0.15f)
            com.rallycopilot.app.audio.VoiceCommands.Command.WRONG ->
                scope.launch(engineDispatcher) {
                    if (driveActive && ::engine.isInitialized) engine.flagLastCall()
                }
        }
    }

    private fun nudgeVolume(delta: Float) {
        val v = (voice.volume + delta).coerceIn(0.1f, 1.0f)
        voice.setVolume(v)
        db.kvPut("voice_volume", v.toString())
    }

    /** Live voice level/balance changes from the settings screen. */
    fun setVoiceVolume(v: Float) { if (::voice.isInitialized) voice.setVolume(v) }
    fun setVoiceBoost(db_: Int) { if (::voice.isInitialized) voice.boostDb = db_ }
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
                        // How fast the VELOCITY VECTOR is turning, from successive
                        // GNSS bearings. Paired against the gyro's body yaw rate,
                        // the difference is sideslip — the car not going where it
                        // is pointing. Bearing is only meaningful while moving, and
                        // is NaN when the fix has none.
                        val dt = (fix.tMs - prev.tMs) / 1000.0
                        if (dt in 0.05..2.0 && fix.speedMps > 3.0 &&
                            !fix.bearingDeg.isNaN() && !prev.bearingDeg.isNaN()
                        ) {
                            var d = fix.bearingDeg - prev.bearingDeg
                            while (d > 180) d -= 360
                            while (d < -180) d += 360
                            // Bearing grows clockwise; yaw is positive anticlockwise.
                            courseRateRadS = -Math.toRadians(d) / dt
                        }
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
        voiceCommands?.stop(); voiceCommands = null
        obd.disconnect()
        voice.release()
        wakeLock?.let { runCatching { it.release() } }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Persistence + the learning pass go to the engine thread — after months of
        // observations this is real work and has no place on a button's onClick.
        val obs = collector?.observations
        scope.launch(engineDispatcher) {
            // A corner in progress when the drive ends still counts as a pass.
            radiusAuditor?.closePass(); radiusAuditor = null
            val bbEnd = blackBox
            bbEnd?.log("drive_end", mapOf(
                "distanceM" to distanceM,
                "observations" to (obs?.size ?: 0),
                "usable" to (obs?.let { com.rallycopilot.core.profile.Learning.usable(it).size } ?: 0),
            ))
            // Every corner the collector closed, with the exact reason it was or was
            // not allowed to teach the profile. This is the record that answers
            // "why did a drive with ten corners learn one?".
            obs?.forEach { o ->
                bbEnd?.log("obs", mapOf(
                    "corner" to o.cornerId, "band" to o.band.name,
                    "rM" to o.minRadiusM,
                    "vEntry" to o.vEntryMps, "vMin" to o.vMinMps, "vExit" to o.vExitMps,
                    "aLat" to o.aLatObserved, "g" to o.aLatObserved / 9.81,
                    "mapConf" to o.mapConfidence, "pathConf" to o.pathConfidence,
                    "constrained" to o.wasConstrained, "spirited" to o.spirited,
                    "confirmed" to o.confirmed, "slid" to o.slid,
                    "throttle" to o.throttleMean,
                    "rejectedBecause" to when {
                        o.wasConstrained -> "constrained"
                        !o.spirited -> "not spirited"
                        o.mapConfidence < 0.6 -> "map confidence %.2f < 0.60".format(o.mapConfidence)
                        o.slid -> "the car was sliding — not evidence of grip"
                        !o.confirmed -> "never matched onto the corner's own edge"
                        o.aLatObserved <= 0.5 -> "lateral g too low"
                        else -> null
                    },
                ))
            }
            if (rid >= 0 && distanceM < MIN_SAVED_DRIVE_M && obs.isNullOrEmpty()) {
                // Went nowhere: a mis-tap, or the app opened to check a setting.
                // Not a drive, so do not keep one.
                db.deleteRun(rid)
            } else if (rid >= 0) {
                db.endRun(rid, distanceM)
                if (obs != null) {
                    // Calibration runs cut everything before the detected onset — the
                    // warm-up corners never enter the learning history at all.
                    val useObs = if (isCalibration) obs.drop(Learning.onsetIndex(obs)) else obs
                    db.saveObservations(useObs)
                    if (useObs.isNotEmpty()) {
                        // Learn only from history matching this run's conditions.
                        val history = db.observationsForLearning(conditions)
                        val updated = Learning.applySession(db.loadProfile(conditions), history)
                        db.saveProfile(updated, conditions)
                    }
                }
            }
            blackBox?.close(); blackBox = null
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

        /** Under this distance with nothing learned, a run is discarded rather than
         *  saved — see the note on AppDb.deleteRun. */
        const val MIN_SAVED_DRIVE_M = 100.0

        const val CHANNEL = "drive"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.rallycopilot.STOP"
        const val EXTRA_WET = "wet"
        const val EXTRA_COND_AUTO = "cond_auto"
        const val EXTRA_CALIBRATION = "calibration"
        const val EXTRA_DEMO = "demo"

        /** The live service instance, for the activity to rebind after recreation. */
        @Volatile var instance: DriveService? = null
            private set
    }
}
