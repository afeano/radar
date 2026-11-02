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
    private var ativo = -1
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
        val kmh = (vel * 3.6).roundToInt()

        if (r.trigger >= 0) {
            ativo = r.trigger
            avisar(radares[r.trigger], r.triggerDist)
        }
        if (ativo >= 0) {
            if (r.nearest == ativo && r.nearestDist > 25) {
                if (agora - ultimoBip > Core.beepIntervalMs(r.nearestDist)) {
                    bipar(ToneGenerator.TONE_PROP_BEEP, 160)
                    ultimoBip = agora
                }
            } else {
                ativo = -1
            }
        }

        status = if (r.nearest >= 0) {
            val c = radares[r.nearest]
            "$kmh km/h · radar à frente a ${r.nearestDist.roundToInt()} m" +
                (if (c.maxSpeed > 0) " (limite ${c.maxSpeed})" else "")
        } else {
            "$kmh km/h · nenhum radar à frente"
        }
        atualizarNotificacao(status)
    }

    private fun avisar(c: Radar, dist: Double) {
        avisos++
        bipar(ToneGenerator.TONE_PROP_BEEP2, 250)
        ultimoBip = SystemClock.elapsedRealtime() + 900
        if (voz && ttsOk) {
            val d = max(50, (dist / 50.0).roundToInt() * 50)
            val lim = if (c.maxSpeed > 0) ", limite ${c.maxSpeed}" else ""
            tts?.speak("Radar à frente, $d metros$lim", TextToSpeech.QUEUE_FLUSH, null, "radar")
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
