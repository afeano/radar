package br.avisoradar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
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

    private val COR_FUNDO = 0xFFF2F2EE.toInt()
    private val COR_CARTAO = 0xFFFFFFFF.toInt()
    private val COR_TEXTO = 0xFF17191C.toInt()
    private val COR_SUAVE = 0xFF5B6168.toInt()
    private val COR_ALERTA = 0xFFC2370C.toInt()
    private val COR_OK = 0xFF1B7A45.toInt()

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
    private lateinit var txtGps: TextView
    private lateinit var cartaoStatus: LinearLayout
    private lateinit var txtRotulo: TextView
    private lateinit var txtGrande: TextView
    private lateinit var txtDetalhe: TextView
    private lateinit var txtVel: TextView
    private lateinit var btnLigar: Button
    private val handler = Handler(Looper.getMainLooper())

    private val atualiza = object : Runnable {
        override fun run() {
            desenhaStatus()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fundo(cor: Int, raio: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(cor)
        cornerRadius = dp(raio).toFloat()
    }

    private fun gpsAtivo(): Boolean = try {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) { false }

    /** O arquivo de radares já vem dentro do app (pasta assets); copia na primeira abertura. */
    private fun prepararArquivoEmbutido() {
        val destino = File(filesDir, RadarService.ARQUIVO)
        if (destino.exists()) return
        for (nome in listOf("radares.csv", "maparadar.csv")) {
            try {
                assets.open(nome).use { entrada ->
                    destino.outputStream().use { saida -> entrada.copyTo(saida) }
                }
                return
            } catch (e: Exception) { /* tenta o próximo nome */ }
        }
    }

    // ---------- blocos de tela ----------
    private fun texto(t: String, tam: Float = 15f, negrito: Boolean = false, cor: Int = COR_TEXTO) = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(cor)
        if (negrito) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cartao(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = fundo(COR_CARTAO)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun titulo(t: String) = texto(t.uppercase(), 12f, true, COR_SUAVE).apply {
        setPadding(0, 0, 0, dp(6))
        letterSpacing = 0.08f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        prepararArquivoEmbutido()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
            setBackgroundColor(COR_FUNDO)
        }

        raiz.addView(texto("Aviso de Radar", 26f, true))
        raiz.addView(texto("Apita e fala quando há radar à frente, mesmo com a tela apagada.", 14f, false, COR_SUAVE).apply {
            setPadding(0, dp(2), 0, 0)
        })

        // Aviso de GPS desligado
        txtGps = texto("A localização (GPS) está desligada. Toque aqui para ligar.", 15f, true, Color.WHITE).apply {
            background = fundo(COR_ALERTA)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        raiz.addView(txtGps)

        // Cartão grande de estado
        cartaoStatus = cartao().apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        txtRotulo = texto("", 12f, true, COR_SUAVE).apply { letterSpacing = 0.08f }
        txtGrande = texto("—", 52f, true).apply { gravity = android.view.Gravity.CENTER }
        txtDetalhe = texto("", 15f, false, COR_SUAVE).apply { gravity = android.view.Gravity.CENTER }
        txtVel = texto("0 km/h", 22f, true).apply { setPadding(0, dp(8), 0, 0) }
        cartaoStatus.addView(txtRotulo)
        cartaoStatus.addView(txtGrande)
        cartaoStatus.addView(txtDetalhe)
        cartaoStatus.addView(txtVel)
        raiz.addView(cartaoStatus)

        // Botão principal
        btnLigar = Button(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener {
                if (RadarService.running) {
                    startService(Intent(this@MainActivity, RadarService::class.java).setAction(RadarService.ACTION_STOP))
                } else {
                    iniciar()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { topMargin = dp(12) }
        }
        raiz.addView(btnLigar)

        // Tipos de radar
        val marcados = prefs.getStringSet("tipos", padrao) ?: padrao
        val cTipos = cartao()
        cTipos.addView(titulo("Tipos de radar"))
        for ((chave, nome) in tipos) {
            val c = CheckBox(this).apply {
                text = nome
                setTextColor(COR_TEXTO)
                isChecked = chave in marcados
                setOnCheckedChangeListener { _, _ -> salvar() }
            }
            caixas[chave] = c
            cTipos.addView(c)
        }
        cTipos.addView(texto("Vale na próxima vez que você tocar em Iniciar.", 12f, false, COR_SUAVE))
        raiz.addView(cTipos)

        // Opções
        val cOpc = cartao()
        cOpc.addView(titulo("Opções"))
        chkVoz = CheckBox(this).apply {
            text = "Avisar por voz"
            setTextColor(COR_TEXTO)
            isChecked = prefs.getBoolean("voz", true)
            setOnCheckedChangeListener { _, _ -> salvar() }
        }
        chkAcima = CheckBox(this).apply {
            text = "Só avisar se eu estiver acima do limite do radar"
            setTextColor(COR_TEXTO)
            isChecked = prefs.getBoolean("soAcima", false)
            setOnCheckedChangeListener { _, _ -> salvar() }
        }
        cOpc.addView(chkVoz)
        cOpc.addView(chkAcima)
        raiz.addView(cOpc)

        // Arquivo
        val cArq = cartao()
        cArq.addView(titulo("Arquivo de radares"))
        txtArquivo = texto("", 14f)
        cArq.addView(txtArquivo)
        cArq.addView(Button(this).apply {
            text = "Trocar o arquivo (opcional)"
            isAllCaps = false
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, REQ_ARQUIVO)
            }
        })
        raiz.addView(cArq)

        // Dicas
        val cDicas = cartao()
        cDicas.addView(titulo("Para não ser pausado"))
        cDicas.addView(texto(
            "Deixe a localização permitida e a bateria deste app como \"Sem restrições\". " +
                "O GPS gasta bateria: use com o celular carregando e o volume de mídia alto.", 14f))
        cDicas.addView(Button(this).apply {
            text = "Abrir configurações de bateria"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
            }
        })
        raiz.addView(cDicas)

        raiz.addView(texto(
            "Os dados do MapaRadar são de uso pessoal. Este app não substitui a sinalização da via nem a atenção ao dirigir.",
            12f, false, COR_SUAVE
        ).apply { setPadding(0, dp(16), 0, 0) })

        val rolagem = ScrollView(this)
        rolagem.setBackgroundColor(COR_FUNDO)
        rolagem.addView(raiz, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(rolagem)
        atualizaArquivo()
        desenhaStatus()
    }

    private fun desenhaStatus() {
        txtGps.visibility = if (gpsAtivo()) View.GONE else View.VISIBLE
        val ligado = RadarService.running
        val alerta = ligado && RadarService.alertando

        btnLigar.text = if (ligado) "Parar aviso" else "Iniciar aviso"
        btnLigar.background = fundo(if (ligado) COR_ALERTA else COR_OK, 14)

        val corFundo = if (alerta) COR_ALERTA else COR_CARTAO
        val corTexto = if (alerta) Color.WHITE else COR_TEXTO
        val corSuave = if (alerta) Color.WHITE else COR_SUAVE
        cartaoStatus.background = fundo(corFundo)
        txtRotulo.setTextColor(corSuave)
        txtGrande.setTextColor(corTexto)
        txtDetalhe.setTextColor(corSuave)
        txtVel.setTextColor(corTexto)

        if (!ligado) {
            txtRotulo.text = "DESLIGADO"
            txtGrande.text = "—"
            txtGrande.textSize = 52f
            txtDetalhe.text = RadarService.status
            txtVel.text = ""
        } else if (RadarService.distRadar >= 0) {
            txtRotulo.text = if (alerta) "RADAR À FRENTE" else "PRÓXIMO RADAR À FRENTE"
            txtGrande.text = "${RadarService.distRadar} m"
            txtGrande.textSize = 52f
            txtDetalhe.text = if (RadarService.limiteRadar > 0) "Limite ${RadarService.limiteRadar} km/h" else "Limite não informado"
            txtVel.text = "${RadarService.kmh} km/h"
        } else {
            txtRotulo.text = "LIGADO"
            txtGrande.text = "Sem radar à frente"
            txtGrande.textSize = 24f
            txtDetalhe.text = RadarService.status
            txtVel.text = "${RadarService.kmh} km/h"
        }
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
            "Arquivo de radares pronto (${f.length() / 1024} KB)."
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
                val r = CsvLoader.carregar(File(filesDir, RadarService.ARQUIVO), tipos.keys)
                atualizaArquivo()
                txtArquivo.text = txtArquivo.text.toString() + "\nRadares reconhecidos: ${r.radares.size}"
            } catch (e: Exception) {
                txtArquivo.text = "Não consegui ler o arquivo: ${e.message}"
            }
        }
    }

    private fun iniciar() {
        if (!File(filesDir, RadarService.ARQUIVO).exists()) {
            txtDetalhe.text = "Escolha o arquivo CSV primeiro."
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
            txtDetalhe.text = "Sem permissão de localização. O app precisa dela para saber onde você está."
        }
    }

    private fun ligar() {
        val i = Intent(this, RadarService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}
