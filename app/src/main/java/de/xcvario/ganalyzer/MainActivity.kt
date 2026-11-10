package de.xcvario.ganalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Sample(val index: Int, val x: Double, val y: Double, val z: Double) {
    val nz: Double get() = -z
}
data class Exceedance(val start: Int, val end: Int, val peak: Double, val positive: Boolean)

class MainActivity : Activity() {
    private val samples = mutableListOf<Sample>()
    private var positiveLimit = 5.3
    private var negativeLimit = -3.0
    private var minDuration = 0.4
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var chart: GChart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        val title = TextView(this).apply {
            text = "XCVario G-Analyzer"
            textSize = 25f
            setTextColor(Color.rgb(16, 42, 67))
        }
        status = TextView(this).apply { text = "NMEA-Datei auswählen"; textSize = 15f; setPadding(0, 12, 0, 12) }
        summary = TextView(this).apply { textSize = 16f; setTextColor(Color.DKGRAY); setPadding(0, 12, 0, 12) }
        chart = GChart(this)
        val import = Button(this).apply { text = "NMEA-Datei öffnen"; setOnClickListener { openFile() } }
        val limits = Button(this).apply { text = "Grenzwerte einstellen"; setOnClickListener { editLimits() } }
        val export = Button(this).apply { text = "Ereignisse als CSV teilen"; setOnClickListener { exportEvents() } }
        root.addView(title)
        root.addView(status)
        root.addView(import)
        root.addView(limits)
        root.addView(summary)
        root.addView(chart, LinearLayout.LayoutParams(-1, 260))
        root.addView(export)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, 41)
    }

    @Deprecated("Legacy result callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); readNmea(uri) }
        }
    }

    private fun readNmea(uri: Uri) {
        samples.clear()
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("$" + "PXCV")) {
                            val body = line.substringBefore("*")
                            val fields = body.split(',')
                            if (fields.size >= 4) {
                                val nums = fields.takeLast(3).map { it.trim().toDoubleOrNull() }
                                if (nums.all { it != null }) {
                                    val v = nums.map { it!! }
                                    samples.add(Sample(samples.size, v[0], v[1], v[2]))
                                }
                            }
                        }
                    }
                }
            }
            if (samples.isEmpty()) {
                status.text = "Keine gültigen PXCV-Datensätze gefunden. Erwartet: letzte drei Felder numerisch."
                summary.text = ""
                chart.setData(emptyList(), positiveLimit, negativeLimit)
            } else {
                val maxNz = samples.maxOf { it.nz }
                val minNz = samples.minOf { it.nz }
                val events = findEvents()
                status.text = "Import: ${samples.size} PXCV-Messpunkte"
                summary.text = String.format(Locale.GERMANY,
                    "Nz = −Z   |   Maximum: %+.2f G   |   Minimum: %+.2f G\nX/Y/Z sind die letzten drei PXCV-Felder.\nExceedances: %d (Schwellen %+.1f / %+.1f G; Mindestdauer %.1f s)",
                    maxNz, minNz, events.size, positiveLimit, negativeLimit, minDuration)
                chart.setData(samples, positiveLimit, negativeLimit)
            }
        } catch (e: Exception) {
            status.text = "Fehler beim Lesen: ${e.message}"
        }
    }

    private fun findEvents(): List<Exceedance> {
        if (samples.isEmpty()) return emptyList()
        val hz = 5.0 // PXCV nominal sample rate; editable interval is planned for a later version
        val required = max(1, kotlin.math.ceil(minDuration * hz).toInt())
        val out = mutableListOf<Exceedance>()
        var start = -1
        var positive = true
        var peak = 0.0
        for (i in samples.indices) {
            val n = samples[i].nz
            val over = when {
                n > positiveLimit -> true
                n < negativeLimit -> false
                else -> null
            }
            if (over != null) {
                if (start < 0 || over != positive) {
                    if (start >= 0 && i - start >= required) out.add(Exceedance(start, i - 1, peak, positive))
                    start = i; positive = over; peak = n
                } else {
                    peak = if (positive) max(peak, n) else min(peak, n)
                }
            } else if (start >= 0) {
                if (i - start >= required) out.add(Exceedance(start, i - 1, peak, positive))
                start = -1
            }
        }
        if (start >= 0 && samples.size - start >= required) out.add(Exceedance(start, samples.lastIndex, peak, positive))
        return out
    }

    private fun editLimits() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val pos = android.widget.EditText(this).apply { hint = "Positive Grenze (z.B. 5.3)"; setText(positiveLimit.toString()); inputType = 8194 }
        val neg = android.widget.EditText(this).apply { hint = "Negative Grenze (z.B. -3.0)"; setText(negativeLimit.toString()); inputType = 8194 }
        val dur = android.widget.EditText(this).apply { hint = "Mindestdauer in Sekunden"; setText(minDuration.toString()); inputType = 8194 }
        fields.addView(pos); fields.addView(neg); fields.addView(dur)
        AlertDialog.Builder(this).setTitle("Grenzwerte – ASK 21 Startprofil")
            .setMessage("Bitte Werte mit dem gültigen Flughandbuch der konkreten ASK-21-Variante abgleichen. Keine geschwindigkeitsabhängige Prüfung.")
            .setView(fields)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Übernehmen") { _, _ ->
                positiveLimit = pos.text.toString().replace(',', '.').toDoubleOrNull() ?: positiveLimit
                negativeLimit = neg.text.toString().replace(',', '.').toDoubleOrNull() ?: negativeLimit
                minDuration = (dur.text.toString().replace(',', '.').toDoubleOrNull() ?: minDuration).coerceAtLeast(0.0)
                if (samples.isNotEmpty()) {
                    val events = findEvents()
                    summary.text = String.format(Locale.GERMANY,
                        "Nz = −Z   |   Maximum: %+.2f G   |   Minimum: %+.2f G\nExceedances: %d (Schwellen %+.1f / %+.1f G; Mindestdauer %.1f s)",
                        samples.maxOf { it.nz }, samples.minOf { it.nz }, events.size, positiveLimit, negativeLimit, minDuration)
                    chart.setData(samples, positiveLimit, negativeLimit)
                }
            }.show()
    }

    private fun exportEvents() {
        val events = findEvents()
        val csv = buildString {
            append("start_sample,end_sample,duration_s,peak_g,direction\n")
            events.forEach { append("${it.start},${it.end},${String.format(Locale.US, "%.2f", (it.end-it.start+1)/5.0)},${String.format(Locale.US, "%.3f", it.peak)},${if (it.positive) "positive" else "negative"}\n") }
        }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv); putExtra(Intent.EXTRA_SUBJECT, "XCVario G-Analyzer Exceedances") }
        startActivity(Intent.createChooser(send, "Ereignis-CSV teilen"))
    }
}

class GChart(context: android.content.Context) : View(context) {
    private val paint = Paint(3)
    private var data: List<Sample> = emptyList()
    private var pos = 5.3
    private var neg = -3.0
    fun setData(d: List<Sample>, p: Double, n: Double) { data = d; pos = p; neg = n; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = 52f; val r = width - 16f; val t = 16f; val b = height - 24f
        paint.color = Color.GRAY; paint.strokeWidth = 1f
        c.drawLine(l, t, l, b, paint); c.drawLine(l, b, r, b, paint)
        if (data.isEmpty()) {
            paint.textSize = 15f; c.drawText("Nach dem Import erscheint hier Nz", l, height / 2f, paint); return
        }
        val vals = data.map { it.nz }
        val low = min(-1.5, min(neg - 0.7, vals.minOrNull() ?: -1.5))
        val high = max(2.0, max(pos + 0.7, vals.maxOrNull() ?: 2.0))
        fun y(v: Double) = (b - (v - low) / (high - low) * (b - t)).toFloat()
        paint.color = Color.RED; paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
        c.drawLine(l, y(pos), r, y(pos), paint); c.drawLine(l, y(neg), r, y(neg), paint)
        paint.pathEffect = null
        paint.color = Color.rgb(21, 101, 192); paint.strokeWidth = 2f
        val path = android.graphics.Path()
        val stride = max(1, data.size / max(1, (r-l).toInt()))
        var first = true
        var i = 0
        while (i < data.size) {
            val x = l + (i.toFloat() / max(1, data.lastIndex)) * (r-l)
            if (first) { path.moveTo(x, y(data[i].nz)); first = false } else path.lineTo(x, y(data[i].nz))
            i += stride
        }
        if (data.isNotEmpty()) path.lineTo(r, y(data.last().nz))
        c.drawPath(path, paint)
        paint.color = Color.DKGRAY; paint.textSize = 12f
        c.drawText(String.format(Locale.US, "%.1f G", high), 2f, t+5, paint)
        c.drawText(String.format(Locale.US, "%.1f G", low), 2f, b, paint)
        c.drawText("Nz = −Z", l, height-4f, paint)
    }
}
