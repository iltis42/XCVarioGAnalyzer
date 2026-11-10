package de.xcvario.ganalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class Sample(val index: Int, val x: Double, val y: Double, val z: Double, val utcMs: Long?) {
    val nz: Double get() = -z
}
data class Exceedance(val start: Int, val end: Int, val peak: Double, val positive: Boolean)

class MainActivity : Activity() {
    companion object {
        private const val KEY_POSITIVE_LIMIT = "positive_limit"
        private const val KEY_NEGATIVE_LIMIT = "negative_limit"
        private const val KEY_MIN_DURATION = "minimum_duration"
        private const val KEY_SAMPLE_INDEX = "sample_index"
        private const val KEY_SAMPLE_X = "sample_x"
        private const val KEY_SAMPLE_Y = "sample_y"
        private const val KEY_SAMPLE_Z = "sample_z"
        private const val KEY_SAMPLE_UTC = "sample_utc"
    }

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
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        val title = TextView(this).apply {
            text = "XCVario G-Analyzer"
            textSize = 24f
            setTextColor(Color.rgb(16, 42, 67))
        }
        status = TextView(this).apply { text = "NMEA-Datei auswählen"; textSize = 14f; setPadding(0, 8, 0, 8) }
        summary = TextView(this).apply { textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 8) }
        chart = GChart(this)
        val import = Button(this).apply { text = "NMEA-Datei öffnen"; setOnClickListener { openFile() } }
        val limits = Button(this).apply { text = "G-Limits einstellen"; setOnClickListener { editLimits() } }
        val export = Button(this).apply { text = "Ereignisse als CSV teilen"; setOnClickListener { exportEvents() } }
        val zoomXIn = Button(this).apply { text = "X +"; setOnClickListener { chart.zoomX(1.5) } }
        val zoomXOut = Button(this).apply { text = "X −"; setOnClickListener { chart.zoomX(1.0 / 1.5) } }
        val zoomYIn = Button(this).apply { text = "Y +"; setOnClickListener { chart.zoomY(1.5) } }
        val zoomYOut = Button(this).apply { text = "Y −"; setOnClickListener { chart.zoomY(1.0 / 1.5) } }
        val reset = Button(this).apply { text = "Zoom zurücksetzen"; setOnClickListener { chart.resetZoom() } }
        root.addView(title)
        root.addView(status)
        root.addView(import)
        root.addView(limits)
        root.addView(summary)
        val chartHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density / 2f).toInt().coerceAtLeast(320)
        root.addView(chart, LinearLayout.LayoutParams(-1, chartHeight))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(zoomXIn, zoomXOut, zoomYIn, zoomYOut).forEach { zoomRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(zoomRow)
        root.addView(reset)
        root.addView(export)
        root.addView(TextView(this).apply {
            text = "Diagramm: mit einem Finger verschieben, mit zwei Fingern zoomen. X/Y-Tasten zoomen die jeweilige Achse separat; „Zoom zurücksetzen“ stellt die Gesamtansicht wieder her."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 8)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        // Restore the analysis after Android recreates the Activity on screen rotation.
        if (savedInstanceState != null) {
            positiveLimit = savedInstanceState.getDouble(KEY_POSITIVE_LIMIT, 5.3)
            negativeLimit = savedInstanceState.getDouble(KEY_NEGATIVE_LIMIT, -3.0)
            minDuration = savedInstanceState.getDouble(KEY_MIN_DURATION, 0.4)
            val indices = savedInstanceState.getIntArray(KEY_SAMPLE_INDEX)
            val xs = savedInstanceState.getDoubleArray(KEY_SAMPLE_X)
            val ys = savedInstanceState.getDoubleArray(KEY_SAMPLE_Y)
            val zs = savedInstanceState.getDoubleArray(KEY_SAMPLE_Z)
            val times = savedInstanceState.getLongArray(KEY_SAMPLE_UTC)
            if (indices != null && xs != null && ys != null && zs != null &&
                times != null && indices.size == xs.size && xs.size == ys.size &&
                ys.size == zs.size && zs.size == times.size) {
                samples.clear()
                for (i in indices.indices) {
                    val utc = times[i].takeIf { it != Long.MIN_VALUE }
                    samples.add(Sample(indices[i], xs[i], ys[i], zs[i], utc))
                }
                if (samples.isNotEmpty()) {
                    status.text = "Analyse nach Bildschirmdrehung wiederhergestellt: ${samples.size} PXCV-Messpunkte"
                    refreshAnalysis()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble(KEY_POSITIVE_LIMIT, positiveLimit)
        outState.putDouble(KEY_NEGATIVE_LIMIT, negativeLimit)
        outState.putDouble(KEY_MIN_DURATION, minDuration)
        outState.putIntArray(KEY_SAMPLE_INDEX, samples.map { it.index }.toIntArray())
        outState.putDoubleArray(KEY_SAMPLE_X, samples.map { it.x }.toDoubleArray())
        outState.putDoubleArray(KEY_SAMPLE_Y, samples.map { it.y }.toDoubleArray())
        outState.putDoubleArray(KEY_SAMPLE_Z, samples.map { it.z }.toDoubleArray())
        outState.putLongArray(KEY_SAMPLE_UTC, samples.map { it.utcMs ?: Long.MIN_VALUE }.toLongArray())
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
            data?.data?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                readNmea(uri)
            }
        }
    }

    private fun parseUtcMillis(field: String): Long? {
        val raw = field.trim()
        if (raw.length < 6) return null
        return try {
            val hh = raw.substring(0, 2).toInt()
            val mm = raw.substring(2, 4).toInt()
            val ss = raw.substring(4, 6).toInt()
            val fraction = if (raw.length > 7 && raw[6] == '.') raw.substring(7).take(3).padEnd(3, '0').toInt() else 0
            if (hh !in 0..23 || mm !in 0..59 || ss !in 0..60) null
            else ((hh * 3600L + mm * 60L + ss) * 1000L + fraction)
        } catch (_: Exception) { null }
    }

    private fun readNmea(uri: Uri) {
        samples.clear()
        var latestTod: Long? = null
        var dayOffset = 0L
        var previousTod: Long? = null
        var utcCount = 0
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { line ->
                        val body = line.substringBefore("*")
                        val fields = body.split(',')
                        val sentence = fields.firstOrNull()?.substringAfterLast('$') ?: ""
                        // RMC and GGA both carry UTC time in field 1.
                        if ((sentence.endsWith("RMC") || sentence.endsWith("GGA")) && fields.size > 1) {
                            val tod = parseUtcMillis(fields[1])
                            if (tod != null) {
                                if (previousTod != null && previousTod!! - tod > 12L * 60L * 60L * 1000L) dayOffset += 24L * 60L * 60L * 1000L
                                previousTod = tod
                                latestTod = dayOffset + tod
                            }
                        }
                        if (line.startsWith("$" + "PXCV")) {
                            if (fields.size >= 4) {
                                val nums = fields.takeLast(3).map { it.trim().toDoubleOrNull() }
                                if (nums.all { it != null }) {
                                    val v = nums.map { it!! }
                                    val stamp = latestTod
                                    if (stamp != null) utcCount++
                                    samples.add(Sample(samples.size, v[0], v[1], v[2], stamp))
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
                val events = findEvents()
                val hasUtc = utcCount > 0
                status.text = "Import: ${samples.size} PXCV-Messpunkte"
                summary.text = String.format(Locale.GERMANY,
                    "Nz = −Z | Maximum: %+.2f G | Minimum: %+.2f G\nExceedances: %d | Limits: %+.1f / %+.1f G | Mindestdauer %.1f s",
                    samples.maxOf { it.nz }, samples.minOf { it.nz }, events.size, positiveLimit, negativeLimit, minDuration) +
                    if (hasUtc) "\nUTC-Zeit aus NMEA übernommen." else "\nKeine UTC-Zeit gefunden – X-Achse zeigt Messpunktnummern."
                chart.setData(samples, positiveLimit, negativeLimit)
            }
        } catch (e: Exception) {
            status.text = "Fehler beim Lesen: ${e.message}"
        }
    }

    private fun findEvents(): List<Exceedance> {
        if (samples.isEmpty()) return emptyList()
        val required = max(1, ceil(minDuration * 5.0).toInt())
        val out = mutableListOf<Exceedance>()
        var start = -1
        var positive = true
        var peak = 0.0
        for (i in samples.indices) {
            val n = samples[i].nz
            val over = when { n > positiveLimit -> true; n < negativeLimit -> false; else -> null }
            if (over != null) {
                if (start < 0 || over != positive) {
                    if (start >= 0 && i - start >= required) out.add(Exceedance(start, i - 1, peak, positive))
                    start = i; positive = over; peak = n
                } else peak = if (positive) max(peak, n) else min(peak, n)
            } else if (start >= 0) {
                if (i - start >= required) out.add(Exceedance(start, i - 1, peak, positive))
                start = -1
            }
        }
        if (start >= 0 && samples.size - start >= required) out.add(Exceedance(start, samples.lastIndex, peak, positive))
        return out
    }

    private fun editLimits() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 4) }
        val pos = EditText(this).apply { hint = "Positive Grenze in G"; setText(positiveLimit.toString()); inputType = 8194 }
        val neg = EditText(this).apply { hint = "Negative Grenze in G"; setText(negativeLimit.toString()); inputType = 8194 }
        val dur = EditText(this).apply { hint = "Mindestdauer in Sekunden"; setText(minDuration.toString()); inputType = 8194 }
        fields.addView(pos); fields.addView(neg); fields.addView(dur)
        AlertDialog.Builder(this).setTitle("G-Limits einstellen")
            .setMessage("Startprofil ASK 21. Werte unbedingt mit dem Flughandbuch der konkreten Variante und dem jeweiligen Geschwindigkeitsbereich abgleichen. Keine zertifizierte Strukturüberwachung.")
            .setView(fields).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Übernehmen") { _, _ ->
                val p = pos.text.toString().replace(',', '.').toDoubleOrNull()
                val n = neg.text.toString().replace(',', '.').toDoubleOrNull()
                val d = dur.text.toString().replace(',', '.').toDoubleOrNull()
                if (p != null && n != null && d != null && p > n && d >= 0.0) {
                    positiveLimit = p; negativeLimit = n; minDuration = d
                    refreshAnalysis()
                } else {
                    AlertDialog.Builder(this).setMessage("Bitte gültige Zahlen eingeben. Positive Grenze muss größer als negative Grenze sein.").setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun refreshAnalysis() {
        if (samples.isEmpty()) return
        val events = findEvents()
        summary.text = String.format(Locale.GERMANY,
            "Nz = −Z | Maximum: %+.2f G | Minimum: %+.2f G\nExceedances: %d | Limits: %+.1f / %+.1f G | Mindestdauer %.1f s",
            samples.maxOf { it.nz }, samples.minOf { it.nz }, events.size, positiveLimit, negativeLimit, minDuration)
        chart.setData(samples, positiveLimit, negativeLimit)
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
    private var xZoom = 1.0
    private var xCenter = 0.5
    private var yZoom = 1.0
    private var yCenter = 0.0
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.toDouble().coerceIn(0.5, 2.0)
            xZoom = (xZoom * factor).coerceIn(1.0, 100000.0)
            yZoom = (yZoom * factor).coerceIn(1.0, 10000.0)
            invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { scaling = false }
    })

    fun setData(d: List<Sample>, p: Double, n: Double) {
        data = d; pos = p; neg = n
        resetZoom()
    }
    fun zoomX(factor: Double) { xZoom = (xZoom * factor).coerceIn(1.0, 100000.0); invalidate() }
    fun zoomY(factor: Double) { yZoom = (yZoom * factor).coerceIn(1.0, 10000.0); invalidate() }
    fun resetZoom() { xZoom = 1.0; xCenter = 0.5; yZoom = 1.0; yCenter = 0.0; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; dragging = true }
            MotionEvent.ACTION_MOVE -> if (!scaling && dragging && data.size > 1) {
                val plotW = max(1f, width - 76f)
                val plotH = max(1f, height - 68f)
                xCenter = (xCenter - (event.x - lastX) / plotW / xZoom).coerceIn(0.0, 1.0)
                val range = baseYRange()
                yCenter += (event.y - lastY) / plotH * (range.second - range.first) / yZoom
                lastX = event.x; lastY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun baseYRange(): Pair<Double, Double> {
        val lo = min(-1.5, min(neg - 0.7, data.minOfOrNull { it.nz } ?: -1.5))
        val hi = max(2.0, max(pos + 0.7, data.maxOfOrNull { it.nz } ?: 2.0))
        return Pair(lo, hi)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = 76f; val r = width - 16f; val t = 24f; val b = height - 62f
        paint.color = Color.GRAY; paint.strokeWidth = 1f; paint.pathEffect = null
        c.drawLine(l, t, l, b, paint); c.drawLine(l, b, r, b, paint)
        if (data.isEmpty()) {
            paint.textSize = 14f; c.drawText("Nach dem Import erscheint hier Nz", l, height / 2f, paint); return
        }
        val (baseLow, baseHigh) = baseYRange()
        val baseSpan = baseHigh - baseLow
        val ySpan = baseSpan / yZoom
        val low = yCenter - ySpan / 2.0
        val high = yCenter + ySpan / 2.0
        val hasUtc = data.any { it.utcMs != null }
        val firstUtc = data.firstNotNullOfOrNull { it.utcMs } ?: 0L
        val lastUtc = data.lastOrNull { it.utcMs != null }?.utcMs ?: (firstUtc + data.size * 200L)
        val x0 = if (hasUtc) firstUtc.toDouble() else 0.0
        val x1 = if (hasUtc) max(x0 + 1.0, lastUtc.toDouble()) else max(1.0, data.lastIndex.toDouble())
        val span = (x1 - x0) / xZoom
        val viewStart = (x0 + xCenter * (x1 - x0) - span / 2.0).coerceIn(x0, max(x0, x1 - span))
        val viewEnd = min(x1, viewStart + span)
        fun xFor(s: Sample): Float {
            val xv = (s.utcMs?.toDouble() ?: if (hasUtc) x0 + s.index * 200.0 else s.index.toDouble())
            return (l + ((xv - viewStart) / max(1.0, viewEnd - viewStart) * (r-l))).toFloat()
        }
        fun yFor(v: Double) = (b - (v - low) / (high - low) * (b-t)).toFloat()
        paint.textSize = 15f; paint.color = Color.DKGRAY
        for (i in 0..4) {
            val value = low + (high-low) * i / 4.0
            val yy = yFor(value)
            paint.color = Color.LTGRAY; c.drawLine(l, yy, r, yy, paint)
            paint.color = Color.DKGRAY; c.drawText(String.format(Locale.US, "%.1f", value), 3f, yy+4, paint)
        }
        paint.color = Color.RED; paint.strokeWidth = 1.6f; paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
        if (pos in low..high) c.drawLine(l, yFor(pos), r, yFor(pos), paint)
        if (neg in low..high) c.drawLine(l, yFor(neg), r, yFor(neg), paint)
        paint.pathEffect = null
        paint.color = Color.rgb(21, 101, 192); paint.strokeWidth = 2f
        val path = Path(); var started = false
        data.forEach { s ->
            val xx = xFor(s)
            if (xx in l..r) {
                if (!started) { path.moveTo(xx, yFor(s.nz)); started = true } else path.lineTo(xx, yFor(s.nz))
            }
        }
        c.save(); c.clipRect(l, t, r, b); c.drawPath(path, paint); c.restore()
        paint.color = Color.DKGRAY; paint.textSize = 14f
        val mid = (viewStart + viewEnd) / 2.0
        if (hasUtc) {
            fun timeLabel(v: Double): String {
                val ms = v.toLong() % 86400000L
                val h = ms / 3600000; val m = (ms / 60000) % 60; val s = (ms / 1000) % 60
                return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
            }
            c.drawText(timeLabel(viewStart), l, height-28f, paint)
            val label = timeLabel(mid); c.drawText(label, (l+r)/2f-34f, height-28f, paint)
            val endLabel = timeLabel(viewEnd); c.drawText(endLabel, r-70f, height-28f, paint)
            paint.textSize = 13f; c.drawText("UTC", l, height-8f, paint)
        } else {
            c.drawText("Messpunkt ${viewStart.toInt()}", l, height-22f, paint)
            c.drawText("Messpunkt ${viewEnd.toInt()}", r-100f, height-22f, paint)
        }
        c.drawText("Nz = −Z  |  X: ${String.format(Locale.US, "%.1f", xZoom)}×  Y: ${String.format(Locale.US, "%.1f", yZoom)}×", l, t-4f, paint)
    }
}
