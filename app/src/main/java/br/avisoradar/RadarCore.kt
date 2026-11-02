package br.avisoradar

import java.io.File
import java.text.Normalizer
import kotlin.math.*

class Radar(val lat: Double, val lon: Double, val maxSpeed: Int, val dir: Int, val tipo: String)

/** Mesma lógica testada na página web e no aparelho. */
object Core {
    const val MIN_SPEED_KMH = 10.0
    private const val EARTH = 6371000.0

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH * asin(sqrt(h))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    fun alertRadius(speedKmh: Double): Double = max(300.0, speedKmh / 3.6 * 15.0)

    fun beepIntervalMs(distM: Double): Long = min(1600.0, max(350.0, distM * 3.0)).toLong()
}

object CsvLoader {
    private val TIPOS = mapOf(
        "radar fixo" to "fixo",
        "semaforo com radar" to "semaforo-radar",
        "radar movel" to "movel",
        "semaforo com camera" to "semaforo-camera",
        "policia rodoviaria" to "policia"
    )

    private fun normaliza(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    /** Quantidade de linhas por tipo (para mostrar na tela). */
    class Resultado(val radares: List<Radar>, val porTipo: Map<String, Int>)

    /**
     * Aceita dois formatos, linha a linha:
     *  MapaRadar: longitude,latitude,Tipo - 60 kmh@60
     *  Aparelho:  lat,lon,maxspeed,direction
     */
    fun carregar(arquivo: File, tiposOk: Set<String>): Resultado {
        val lista = ArrayList<Radar>(20000)
        val contagem = HashMap<String, Int>()
        arquivo.bufferedReader(Charsets.UTF_8).useLines { linhas ->
            for (bruta in linhas) {
                val linha = bruta.trim()
                if (linha.isEmpty()) continue
                val p = linha.split(',')
                if (p.size >= 3 && p[2].contains('@')) {
                    val lon = p[0].trim().toDoubleOrNull() ?: continue
                    val lat = p[1].trim().toDoubleOrNull() ?: continue
                    val desc = p.drop(2).joinToString(",")
                    val nomeBruto = desc.substringBefore('@').replace(Regex("\\s*-\\s*\\d+\\s*kmh.*$", RegexOption.IGNORE_CASE), "")
                    val nome = normaliza(nomeBruto)
                    contagem[nome] = (contagem[nome] ?: 0) + 1
                    val tipo = TIPOS[nome] ?: continue
                    if (tipo !in tiposOk) continue
                    var ms = desc.substringAfter('@').trim().toIntOrNull() ?: 0
                    if (ms < 5 || ms > 200) ms = 0
                    lista.add(Radar(lat, lon, ms, -1, tipo))
                } else if (p.size >= 2) {
                    val lat = p[0].trim().toDoubleOrNull() ?: continue   // cabeçalho
                    val lon = p[1].trim().toDoubleOrNull() ?: continue
                    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue
                    var ms = if (p.size > 2) p[2].trim().toIntOrNull() ?: 0 else 0
                    if (ms < 5 || ms > 200) ms = 0
                    var dr = if (p.size > 3) p[3].trim().toIntOrNull() ?: -1 else -1
                    if (dr < 0 || dr >= 360) dr = -1
                    contagem["aparelho"] = (contagem["aparelho"] ?: 0) + 1
                    lista.add(Radar(lat, lon, ms, dr, "aparelho"))
                }
            }
        }
        return Resultado(lista, contagem)
    }
}

class Evaluator(private val radares: List<Radar>) {
    private val armed = BooleanArray(radares.size)

    class Result(val trigger: Int, val triggerDist: Double, val nearest: Int, val nearestDist: Double)

    fun reset() = armed.fill(false)

    fun evaluate(lat: Double, lon: Double, speedMps: Double, heading: Double?, onlyAbove: Boolean): Result {
        val speedKmh = speedMps * 3.6
        val radius = Core.alertRadius(speedKmh)
        val far = radius * 1.5 + 500.0
        val dLatLim = far / 111000.0
        val moving = speedKmh >= Core.MIN_SPEED_KMH && heading != null
        val h = heading ?: 0.0
        var trig = -1
        var trigD = 0.0
        var near = -1
        var nearD = 0.0
        for (i in radares.indices) {
            val c = radares[i]
            if (abs(c.lat - lat) > dLatLim) { armed[i] = false; continue }
            val d = Core.distM(lat, lon, c.lat, c.lon)
            if (d > far) { armed[i] = false; continue }
            if (!moving) continue
            if (Core.angDiff(h, Core.bearing(lat, lon, c.lat, c.lon)) > 50.0) continue   // não está à frente
            if (c.dir >= 0 && Core.angDiff(h, c.dir.toDouble()) > 70.0) continue          // outro sentido
            if (near < 0 || d < nearD) { near = i; nearD = d }
            if (d <= radius && !armed[i]) {
                if (onlyAbove && c.maxSpeed > 0 && speedKmh <= c.maxSpeed) continue
                if (trig < 0 || d < trigD) { trig = i; trigD = d }
            }
        }
        if (trig >= 0) {   // radares colados (a menos de 40 m) contam como um só
            val t = radares[trig]
            for (i in radares.indices) {
                val c = radares[i]
                if (abs(c.lat - t.lat) < 0.0006 && Core.distM(t.lat, t.lon, c.lat, c.lon) < 40.0) armed[i] = true
            }
        }
        return Result(trig, trigD, near, nearD)
    }
}
