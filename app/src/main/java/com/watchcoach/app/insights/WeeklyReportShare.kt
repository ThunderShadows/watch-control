package com.watchcoach.app.insights

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object WeeklyReportShare {
    fun writeText(context: Context, report: WeeklyReport): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "watch-coach-week-${report.weekStart}.txt")
        file.writeText(report.asText() + "\n")
        return file
    }

    fun writePng(context: Context, report: WeeklyReport): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "watch-coach-week-${report.weekStart}.png")
        val w = 1080
        val h = 1480
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#111318"))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6E6E6")
            textSize = 36f
        }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A0A4AE")
            textSize = 28f
        }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7DCEA0")
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        var y = 90f
        c.drawText("Watch Coach", 64f, y, title); y += 70f
        c.drawText("Weekly report  ${report.weekStart} – ${report.weekEnd}", 64f, y, body); y += 70f
        c.drawText("Steps  ${report.totalSteps}", 64f, y, accent); y += 50f
        c.drawText("${report.avgSteps} / day   ·   goal ${report.goal}   ·   met ${report.daysGoalMet}/7", 64f, y, body)
        y += 70f
        report.daily.forEach { d ->
            val barMax = report.daily.maxOf { it.steps }.coerceAtLeast(1)
            val barW = (d.steps.toFloat() / barMax) * 700f
            val barPaint = Paint().apply { color = Color.parseColor("#5DADE2") }
            c.drawText(d.date.dayOfWeek.name.take(3), 64f, y, muted)
            c.drawRect(180f, y - 28f, 180f + barW, y + 8f, barPaint)
            c.drawText("${d.steps}", 900f, y, body)
            y += 48f
        }
        y += 24f
        c.drawText(
            "Night RHR  " + (report.weekRhr?.let { "$it bpm" } ?: "—") +
                (report.lastNightRhr?.let { "   last night $it" } ?: ""),
            64f, y, body,
        )
        y += 56f
        c.drawText("Most active  ${report.heatmap.mostActiveLabel}    Least  ${report.heatmap.leastActiveLabel}", 64f, y, body)
        y += 56f
        c.drawText("HR zones (220−${report.zones.age})", 64f, y, body)
        y += 48f
        report.zones.labels.forEach { (name, _) ->
            c.drawText("$name   ${report.zones.counts[name] ?: 0} samples", 64f, y, muted)
            y += 40f
        }
        y += 24f
        c.drawText("Distance and calories are phone estimates, not watch fields.", 64f, y, muted)
        FileOutputStream(file).use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out) }
        bmp.recycle()
        return file
    }

    fun writePdf(context: Context, report: WeeklyReport): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "watch-coach-week-${report.weekStart}.pdf")
        val png = writePng(context, report)
        val bmp = android.graphics.BitmapFactory.decodeFile(png.absolutePath)
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
        val page = doc.startPage(pageInfo)
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        bmp.recycle()
        return file
    }

    fun share(context: Context, file: File, mime: String, subject: String = "Watch Coach weekly report") {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (mime == "text/plain") putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share weekly report"))
    }
}
