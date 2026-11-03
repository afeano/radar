package br.avisoradar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.location.LocationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private val REQ_ARQUIVO = 10
    private val REQ_PERMISSOES = 11

    private val tipos = linkedMapOf(
        "fixo" to "Radar fixo",
        "semaforo-radar" to "Semáforo com radar",
        "movel" to "Radar móvel (pontos conhecidos)",
        "semaforo-camera" to "Semáforo com câmera",
        "policia" to "Polícia rodoviária"
    )
    private val padrao = setOf("fixo", "semaforo-radar", "movel")
    private val caixas = LinkedHashMap<String, CheckBox>()
    private lateinit var chkVoz: CheckBox
    private lateinit var chkAcima: CheckBox
    private lateinit var txtArquivo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtGps: TextView
    private lateinit var btnIniciar: Button
    private lateinit var btnParar: Button
    private val handler = Handler(Looper.getMainLooper())

    private val atualiza = object : Runnable {
        override fun run() {
            txtStatus.text = RadarService.status
            val gpsLigado = gpsAtivo()
            txtGps.visibility = if (gpsLigado) android.view.View.GONE else android.view.View.VISIBLE
            btnIniciar.isEnabled = !RadarService.running
            btnParar.isEnabled = RadarService.running
            handler.postDelayed(this, 1000)
        }
    }

    private fun gpsAtivo(): Boolean = try {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) { false }

    /** O arquivo de radares já vem dentro do app (pasta assets); copia na primeira abertura. */
    private fun prepararArquivoEmbutido() {
        val destino = File(filesDir, RadarService.ARQUIVO)
        if (destino.exists()) return
        try {
            assets.open(RadarService.ARQUIVO).use { entrada ->
                destino.outputStream().use { saida -> entrada.copyTo(saida) }
            }
        } catch (e: Exception) { /* sem arquivo embutido: o usuário escolhe um */ }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        prepararArquivoEmbutido()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun texto(t: String, tam: Float = 15f, negrito: Boolean = false) = TextView(this).apply {
            text = t
            textSize = tam
            if (negrito) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

        raiz.addView(texto("Aviso de Radar", 24f, true))
        raiz.addView(texto("Apita e fala quando há um radar à frente, mesmo com a tela apagada ou em outro app."))

        txtGps = texto("⚠ A localização (GPS) do celular está desligada. Ligue-a nas configurações ou na barra superior, senão o aviso não funciona.", 15f, true).apply {
            setTextColor(android.graphics.Color.rgb(190, 40, 20))
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        raiz.addView(txtGps)

        raiz.addView(texto("1. Arquivo de radares", 14f, true))
        val btnArq = Button(this).apply {
            text = "Trocar o arquivo de radares (opcional)"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, REQ_ARQUIVO)
            }
        }
        raiz.addView(btnArq)
        txtArquivo = texto("")
        raiz.addView(txtArquivo)

        val marcados = prefs.getStringSet("tipos", padrao) ?: padrao
        for ((chave, nome) in tipos) {
            val c = CheckBox(this).apply {
                text = nome
                isChecked = chave in marcados
                setOnCheckedChangeListener { _, _ -> salvar() }
            }
            caixas[chave] = c
            raiz.addView(c)
        }

        raiz.addView(texto("2. Opções", 14f, true))
        chkVoz = CheckBox(this).apply {
            text = "Avisar por voz"
            isChecked = prefs.getBoolean("voz", true)
            setOnCheckedChangeListener { _, _ -> salvar() }
        }
        chkAcima = CheckBox(this).apply {
            text = "Só avisar se eu estiver acima do limite do radar"
            isChecked = prefs.getBoolean("soAcima", false)
            setOnCheckedChangeListener { _, _ -> salvar() }
        }
        raiz.addView(chkVoz)
        raiz.addView(chkAcima)

        raiz.addView(texto("3. Ligar", 14f, true))
        btnIniciar = Button(this).apply {
            text = "Iniciar aviso"
            setOnClickListener { iniciar() }
        }
        btnParar = Button(this).apply {
            text = "Parar"
            setOnClickListener { startService(Intent(this@MainActivity, RadarService::class.java).setAction(RadarService.ACTION_STOP)) }
        }
        raiz.addView(btnIniciar)
        raiz.addView(btnParar)
        txtStatus = texto("", 16f, true)
        raiz.addView(txtStatus)

        val btnBateria = Button(this).apply {
            text = "Evitar que o Android pause o app (bateria)"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
            }
        }
        raiz.addView(btnBateria)
        raiz.addView(texto(
            "Para funcionar com a tela apagada, permita a localização \"o tempo todo\" ou \"durante o uso\" e " +
                "deixe a bateria como \"Sem restrições\" para este app. O GPS gasta bateria: use com o celular carregando.\n\n" +
                "Os dados do MapaRadar são de uso pessoal. Este app não substitui a sinalização da via nem a atenção ao dirigir.", 12f))

        val rolagem = ScrollView(this)
        rolagem.addView(raiz, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(rolagem)
        atualizaArquivo()
    }

    override fun onResume() {
        super.onResume()
        handler.post(atualiza)
    }

    override fun onPause() {
        handler.removeCallbacks(atualiza)
        super.onPause()
    }

    private fun salvar() {
        val sel = caixas.filter { it.value.isChecked }.keys
        getSharedPreferences("config", Context.MODE_PRIVATE).edit()
            .putStringSet("tipos", HashSet(sel))
            .putBoolean("voz", chkVoz.isChecked)
            .putBoolean("soAcima", chkAcima.isChecked)
            .apply()
    }

    private fun atualizaArquivo() {
        val f = File(filesDir, RadarService.ARQUIVO)
        txtArquivo.text = if (f.exists()) {
            "Arquivo de radares pronto (${f.length() / 1024} KB). Os tipos marcados abaixo valem na próxima vez que você iniciar."
        } else {
            "Este app veio sem arquivo de radares. Escolha o CSV exportado do MapaRadar."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ARQUIVO && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { entrada ->
                    File(filesDir, RadarService.ARQUIVO).outputStream().use { saida -> entrada.copyTo(saida) }
                }
                // Mostra quantos radares há por tipo, para conferir se o arquivo é o certo.
                val r = CsvLoader.carregar(File(filesDir, RadarService.ARQUIVO), tipos.keys)
                atualizaArquivo()
                txtArquivo.text = txtArquivo.text.toString() + "\nRadares nos tipos reconhecidos: ${r.radares.size}"
            } catch (e: Exception) {
                txtArquivo.text = "Não consegui ler o arquivo: ${e.message}"
            }
        }
    }

    private fun iniciar() {
        if (!File(filesDir, RadarService.ARQUIVO).exists()) {
            txtStatus.text = "Escolha o arquivo CSV primeiro."
            return
        }
        salvar()
        if (!gpsAtivo()) {
            AlertDialog.Builder(this)
                .setTitle("Ative a localização")
                .setMessage("O aviso usa o GPS do celular. Ative a localização (GPS) e volte para tocar em Iniciar aviso.")
                .setPositiveButton("Abrir configurações") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val faltam = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.ACCESS_FINE_LOCATION)
            faltam.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) faltam.add(Manifest.permission.POST_NOTIFICATIONS)
        if (faltam.isNotEmpty()) {
            requestPermissions(faltam.toTypedArray(), REQ_PERMISSOES)
        } else {
            ligar()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSOES) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ligar()
        } else {
            txtStatus.text = "Sem permissão de localização. O app precisa dela para saber onde você está."
        }
    }

    private fun ligar() {
        val i = Intent(this, RadarService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}
