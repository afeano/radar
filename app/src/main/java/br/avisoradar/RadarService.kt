package br.avisoradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class RadarService : Service(), LocationListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "br.avisoradar.STOP"
        const val CHANNEL = "aviso_radar"
        const val NOTIF_ID = 1
        const val ARQUIVO = "radares.csv"

        @Volatile var running = false
        @Volatile var status = "Parado"
        @Volatile var kmh = 0
        @Volatile var distRadar = -1      // metros até o próximo radar à frente (-1 = nenhum)
        @Volatile var limiteRadar = 0
        @Volatile var alertando = false
    }

    private var lm: LocationManager? = null
    private var evaluator: Evaluator? = null
    private var radares: List<Radar> = emptyList()
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    private var voz = true
    private var soAcima = false
    private var beepAtivo = false
    private var beepDist = 0.0
    private var beepVel = 0.0
    private var beepFix = 0L
    private var ultimoBip = 0L
    private var avisos = 0

    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var prevT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            parar()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        status = "Carregando radares…"
        criarCanal()
        val notif = montarNotificacao("Carregando radares…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        voz = prefs.getBoolean("voz", true)
        soAcima = prefs.getBoolean("soAcima", false)
        val tipos = prefs.getStringSet("tipos", setOf("fixo", "semaforo-radar", "movel")) ?: emptySet()

        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }
        tts = TextToSpeech(this, this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avisoradar:gps").apply { acquire() }

        Thread {
            val arq = File(filesDir, ARQUIVO)
            val lista = try {
                if (arq.exists()) CsvLoader.carregar(arq, tipos).radares else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                if (!running) return@post
                radares = lista
                if (lista.isEmpty()) {
                    parar("Nenhum radar carregado. Escolha o arquivo CSV e os tipos de radar.")
                } else {
                    evaluator = Evaluator(lista)
                    iniciarGps()
                }
            }
        }.start()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun iniciarGps() {
        val l = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = l
        try {
            l.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            main.post(beepLoop)
            status = "${radares.size} radares. Aguardando sinal de GPS…"
            atualizarNotificacao("Aguardando GPS…")
        } catch (e: SecurityException) {
            parar("Sem permissão de localização.")
        } catch (e: Exception) {
            parar("GPS indisponível: ${e.message}")
        }
    }

    override fun onLocationChanged(loc: Location) {
        val ev = evaluator ?: return
        val agora = SystemClock.elapsedRealtime()
        var vel = if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN
        var rumo: Double? = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) loc.bearing.toDouble() else null

        if (!prevLat.isNaN()) {
            val d = Core.distM(prevLat, prevLon, loc.latitude, loc.longitude)
            val dt = (agora - prevT) / 1000.0
            if (vel.isNaN() && dt > 0) vel = d / dt
            if (rumo == null && d > 8) rumo = Core.bearing(prevLat, prevLon, loc.latitude, loc.longitude)
            if (d > 8) { prevLat = loc.latitude; prevLon = loc.longitude; prevT = agora }
        } else {
            prevLat = loc.latitude; prevLon = loc.longitude; prevT = agora
        }
        if (vel.isNaN()) vel = 0.0

        val r = ev.evaluate(loc.latitude, loc.longitude, vel, rumo, soAcima)
        val velKmh = (vel * 3.6).roundToInt()

        if (r.trigger >= 0) avisar(radares[r.trigger], r.triggerDist)

        // Bipes: enquanto houver radar à frente dentro do alcance, cada vez mais rápidos (ver beepLoop).
        val limite = if (r.nearest >= 0) radares[r.nearest].maxSpeed else 0
        val ignorar = soAcima && limite > 0 && velKmh <= limite
        beepAtivo = r.nearest >= 0 && r.nearestDist <= Core.alertRadius(vel * 3.6) && !ignorar
        beepDist = r.nearestDist
        beepVel = vel
        beepFix = agora

        kmh = velKmh
        distRadar = if (r.nearest >= 0) r.nearestDist.roundToInt() else -1
        limiteRadar = limite
        alertando = beepAtivo && r.nearestDist > 15

        status = if (r.nearest >= 0) {
            val c = radares[r.nearest]
            "$velKmh km/h · radar à frente a ${r.nearestDist.roundToInt()} m" +
                (if (c.maxSpeed > 0) " (limite ${c.maxSpeed})" else "")
        } else {
            "$velKmh km/h · nenhum radar à frente"
        }
        atualizarNotificacao(status)
    }

    private fun avisar(c: Radar, dist: Double) {
        avisos++
        bipar(ToneGenerator.TONE_PROP_BEEP2, 250)
        ultimoBip = SystemClock.elapsedRealtime() + 1500
        if (voz && ttsOk) {
            val d = max(50, (dist / 50.0).roundToInt() * 50)
            val lim = if (c.maxSpeed > 0) ", limite ${c.maxSpeed}" else ""
            tts?.speak("Radar à frente, $d metros$lim", TextToSpeech.QUEUE_FLUSH, null, "radar")
        }
    }

    /** Roda a cada 80 ms: o ritmo dos bipes acompanha a distância estimada, não só os fixes de 1 s do GPS. */
    private val beepLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val agora = SystemClock.elapsedRealtime()
            if (beepAtivo && agora - beepFix < 3000) {
                val d = max(0.0, beepDist - beepVel * (agora - beepFix) / 1000.0)
                if (d > 15 && agora - ultimoBip >= Core.beepIntervalMs(d)) {
                    bipar(ToneGenerator.TONE_PROP_BEEP, 120)
                    ultimoBip = agora
                }
            }
            main.postDelayed(this, 80)
        }
    }

    private fun bipar(tipo: Int, ms: Int) {
        try { tone?.startTone(tipo, ms) } catch (e: Exception) { }
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("pt", "BR"))
            ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    // Em Android antigos (8 a 10) estes três métodos são obrigatórios.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        RadarService.status = "GPS desligado. Ative a localização do celular."
    }

    private fun parar(mensagem: String? = null) {
        running = false
        status = mensagem ?: "Parado"
        kmh = 0; distRadar = -1; limiteRadar = 0; alertando = false; beepAtivo = false
        main.removeCallbacks(beepLoop)
        try { lm?.removeUpdates(this) } catch (e: Exception) { }
        lm = null
        tts?.shutdown(); tts = null
        tone?.release(); tone = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) parar()
        super.onDestroy()
    }

    private fun criarCanal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aviso de radar", NotificationManager.IMPORTANCE_LOW))
    }

    private fun montarNotificacao(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, RadarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Aviso de Radar ligado")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", parar)
            .build()
    }

    private var ultimaNotif = 0L
    private fun atualizarNotificacao(texto: String) {
        val t = SystemClock.elapsedRealtime()
        if (t - ultimaNotif < 3000) return
        ultimaNotif = t
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, montarNotificacao(texto))
    }
}
