package ph.ojt.attendance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.location.Location
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Rec(
    val id: Long, val day: String, val type: String, val epochMs: Long,
    val lat: Double, val lng: Double, val acc: Float,
    val photo: String, val photoHash: String, val prevHash: String, val hash: String
)

object PH {
    val zone: ZoneId = ZoneId.of("Asia/Manila")
    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    fun full(ms: Long): String =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy hh:mm:ss a", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(ms).atZone(zone)) + " PHT"
    fun time(ms: Long): String =
        DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))
    fun dayLabel(day: String): String =
        DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(LocalDate.parse(day))
}

fun fmt(d: Double): String = String.format(Locale.US, "%.6f", d)

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun sha256File(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i ->
        val buf = ByteArray(8192)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun loadScaled(f: File, w: Int): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    val s = maxOf(1, o.outWidth / (w * 2))
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        ?: return Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
    return Bitmap.createScaledBitmap(b, w, (w.toFloat() * b.height / b.width).toInt(), true)
}

class Store(private val ctx: Context) : SQLiteOpenHelper(ctx, "attendance.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL, type TEXT NOT NULL, epoch_ms INTEGER NOT NULL,
                lat REAL, lng REAL, acc REAL,
                photo TEXT, photo_hash TEXT, prev_hash TEXT, hash TEXT,
                UNIQUE(day, type))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    private val photoDir get() = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun photoFile(name: String) = File(photoDir, name)

    fun all(): List<Rec> {
        val out = mutableListOf<Rec>()
        readableDatabase.rawQuery(
            "SELECT id,day,type,epoch_ms,lat,lng,acc,photo,photo_hash,prev_hash,hash FROM records ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Rec(
                    c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                    c.getDouble(4), c.getDouble(5), c.getFloat(6),
                    c.getString(7), c.getString(8), c.getString(9), c.getString(10)
                )
            }
        }
        return out
    }

    private fun hashOf(prev: String, day: String, type: String, ms: Long, lat: Double, lng: Double, photoHash: String) =
        sha256("$prev|$day|$type|$ms|${fmt(lat)}|${fmt(lng)}|$photoHash")

    /** Throws IllegalStateException with a user-friendly message on rule violations. */
    fun save(type: String, photo: Bitmap, nowMs: Long, loc: Location, name: String) {
        val day = PH.day(nowMs)
        val today = all().filter { it.day == day }
        if (today.any { it.type == type }) throw IllegalStateException("Already recorded today.")
        if (type == "OUT" && today.none { it.type == "IN" }) throw IllegalStateException("Time In first.")

        val lines = listOf(
            "TIME $type - $name",
            PH.full(nowMs),
            "Lat ${fmt(loc.latitude)}  Lng ${fmt(loc.longitude)}  (+/-${loc.accuracy.toInt()} m)"
        )
        val stamped = stamp(photo, lines)
        val fileName = "${day}_$type.jpg"
        val f = photoFile(fileName)
        FileOutputStream(f).use { stamped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val ph = sha256File(f)
        val prev = all().lastOrNull()?.hash ?: "GENESIS"
        val h = hashOf(prev, day, type, nowMs, loc.latitude, loc.longitude, ph)
        val cv = ContentValues().apply {
            put("day", day); put("type", type); put("epoch_ms", nowMs)
            put("lat", loc.latitude); put("lng", loc.longitude); put("acc", loc.accuracy)
            put("photo", fileName); put("photo_hash", ph); put("prev_hash", prev); put("hash", h)
        }
        if (writableDatabase.insert("records", null, cv) < 0) {
            f.delete()
            throw IllegalStateException("Could not save record.")
        }
    }

    fun verify(): List<String> {
        val problems = mutableListOf<String>()
        var prev = "GENESIS"
        for (r in all()) {
            val label = "${r.day} ${r.type}"
            if (r.prevHash != prev) problems += "Chain broken at $label"
            if (hashOf(prev, r.day, r.type, r.epochMs, r.lat, r.lng, r.photoHash) != r.hash)
                problems += "Record altered: $label"
            val f = photoFile(r.photo)
            if (!f.exists() || sha256File(f) != r.photoHash) problems += "Photo missing or altered: $label"
            prev = r.hash
        }
        return problems
    }

    private fun stamp(src: Bitmap, lines: List<String>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val size = out.width / 30f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = size; typeface = Typeface.DEFAULT_BOLD
        }
        val pad = size * 0.6f
        val lh = size * 1.25f
        val h = pad * 2 + lh * lines.size
        val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        c.drawRect(0f, out.height - h, out.width.toFloat(), out.height.toFloat(), bg)
        lines.forEachIndexed { i, s -> c.drawText(s, pad, out.height - h + pad + lh * (i + 0.85f), p) }
        return out
    }
}

object PdfExport {
    fun create(ctx: Context, recs: List<Rec>, name: String): File {
        if (recs.isEmpty()) throw IllegalStateException("No records yet.")
        val store = Store(ctx)
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bold = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val txt = Paint().apply { textSize = 10f }
        var n = 1
        for ((day, list) in recs.groupBy { it.day }.toSortedMap()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n++).create())
            val c = page.canvas
            c.drawText("OJT Attendance Record", 40f, 50f, title)
            c.drawText("Name: $name", 40f, 75f, txt)
            c.drawText(PH.dayLabel(day), 40f, 92f, txt)
            var x = 40f
            for (type in listOf("IN", "OUT")) {
                val r = list.find { it.type == type }
                c.drawText(if (type == "IN") "TIME IN" else "TIME OUT", x, 125f, bold)
                if (r == null) {
                    c.drawText("- no record -", x, 145f, txt)
                } else {
                    val bmp = loadScaled(store.photoFile(r.photo), 240)
                    c.drawBitmap(bmp, x, 135f, null)
                    var y = 135f + bmp.height + 16f
                    c.drawText(PH.full(r.epochMs), x, y, txt); y += 14f
                    c.drawText("Lat ${fmt(r.lat)}, Lng ${fmt(r.lng)} (+/-${r.acc.toInt()} m)", x, y, txt); y += 14f
                    c.drawText("Record ID: ${r.hash.take(16)}", x, y, txt)
                }
                x += 270f
            }
            doc.finishPage(page)
        }
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "attendance.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
