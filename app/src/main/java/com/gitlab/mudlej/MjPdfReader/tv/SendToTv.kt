// Sahl PDF (TV edition of MJ PDF). License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.tv

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.BufferedInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/** True on Android TV / Google TV (no touch screen, a remote instead). */
fun Context.isTv() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** Where PDFs sent from the phone are kept: Download/Sahl PDF (falls back to the app's own folder). */
fun Context.tvLibraryDir(): File {
    val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sahl PDF")
    if ((pub.exists() || pub.mkdirs()) && pub.canWrite()) return pub
    return File(getExternalFilesDir(null) ?: filesDir, "Sahl PDF").apply { mkdirs() }
}

/**
 * Tiny web page for sending PDFs from the phone to the TV over home Wi-Fi.
 * Runs only while the "Send from phone" window is open and accepts uploads only with the 6-digit code shown on the TV.
 * Files are saved once into the TV library folder and stay there.
 */
class UploadServer(
    private val dir: File,
    private val onProgress: (String, Long, Long) -> Unit,
    private val onFile: (File?, String) -> Unit,
) {
    companion object {
        const val PORT = 8767
        fun ip(): String = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "?"
        } catch (_: Exception) { "?" }
    }

    val code: String = String.format("%06d", SecureRandom().nextInt(1_000_000))
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wrong = 0

    fun start() {
        Thread {
            try {
                val s = ServerSocket(PORT); server = s
                while (!s.isClosed) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (_: Exception) {}
        }.start()
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    private fun send(c: Socket, status: String, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush()
        }
    }

    /** A safe file name: no folders, ends in .pdf, never overwrites another book. */
    private fun target(raw: String): File {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().ifEmpty { "Book.pdf" }
        if (!name.lowercase().endsWith(".pdf")) name += ".pdf"
        var f = File(dir, name); var i = 2
        while (f.exists()) { f = File(dir, name.dropLast(4) + " ($i).pdf"); i++ }
        return f
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedInputStream(c.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val ch = inp.read(); if (ch < 0) return; head.append(ch.toChar()); if (head.length > 16_000) return }
            val lines = head.lines()
            val (method, path) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val headers = lines.drop(1).filter { it.contains(":") }.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            if (method == "GET") { send(c, "200 OK", "text/html", PAGE); return }
            if (method == "POST" && path.startsWith("/upload")) {
                if (headers["x-code"] != code) {
                    if (++wrong >= 10) stop()                 // too many wrong codes: close until the window is opened again
                    Thread.sleep(800); send(c, "403 Forbidden", "text/plain", "wrong code"); return
                }
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                if (len <= 0 || len > 1024L * 1024 * 1024) { send(c, "400 Bad Request", "text/plain", "size"); return }
                val name = try { URLDecoder.decode(headers["x-name"] ?: "Book.pdf", "UTF-8") } catch (_: Exception) { "Book.pdf" }
                val out = target(name)
                val tmp = File(dir, ".${out.name}.part")
                var first4 = ""
                tmp.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024); var done = 0L; var last = 0L
                    while (done < len) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), len - done).toInt()); if (n < 0) break
                        if (done == 0L) first4 = String(buf, 0, minOf(4, n), Charsets.ISO_8859_1)
                        o.write(buf, 0, n); done += n
                        if (done - last > 1_000_000 || done == len) { last = done; val d = done; main.post { onProgress(out.name, d, len) } }
                    }
                    if (done != len) { tmp.delete(); send(c, "400 Bad Request", "text/plain", "incomplete"); return }
                }
                if (first4 != "%PDF") { tmp.delete(); send(c, "415 Unsupported", "text/plain", "not a PDF"); main.post { onFile(null, name) }; return }
                tmp.renameTo(out)
                send(c, "200 OK", "text/plain", "ok")
                main.post { onFile(out, out.name) }
                return
            }
            send(c, "404 Not Found", "text/plain", "")
        } catch (_: Exception) {
        } finally { try { c.close() } catch (_: Exception) {} }
    }

    private val PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
<title>Sahl PDF</title><style>body{font-family:system-ui,sans-serif;background:#10161c;color:#eee;margin:0;padding:24px;text-align:center}
h1{color:#e0b85c;font-size:22px}input,button{font-size:20px;padding:12px;border-radius:12px;border:1px solid #e0b85c;margin:8px 0;width:100%;box-sizing:border-box;background:#18222b;color:#eee}
button{background:#e0b85c;color:#111;font-weight:bold}.bar{height:12px;background:#18222b;border-radius:6px;overflow:hidden;margin-top:6px}.fill{height:100%;width:0;background:#e0b85c}
p,li{color:#aab}ul{list-style:none;padding:0;text-align:start}</style></head><body><h1>Sahl PDF — Send to TV</h1>
<p>أرسل ملفات PDF إلى التلفاز · Send PDFs to the TV<br>They stay in the TV library.</p>
<input id="code" inputmode="numeric" maxlength="6" placeholder="الرمز الظاهر على التلفاز · code on the TV">
<input id="f" type="file" accept="application/pdf,.pdf" multiple>
<button onclick="go()">إرسال · Send</button><ul id="list"></ul>
<script>
function one(file,code,li){return new Promise(function(res){var x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('X-Code',code);
x.setRequestHeader('X-Name',encodeURIComponent(file.name));var fill=li.querySelector('.fill'),st=li.querySelector('.st');
x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(e.loaded*100/e.total)+'%'};
x.onload=function(){st.textContent=x.status==200?' ✓':(x.status==403?' — wrong code · الرمز غير صحيح':' — '+x.responseText);res(x.status)};
x.onerror=function(){st.textContent=' — connection failed';res(0)};x.send(file)})}
async function go(){var fs=document.getElementById('f').files,c=document.getElementById('code').value.trim(),L=document.getElementById('list');
if(!fs.length){alert('Choose PDF files · اختر الملفات');return}L.innerHTML='';
for(var i=0;i<fs.length;i++){var li=document.createElement('li');li.innerHTML='<span></span><span class="st"></span><div class="bar"><div class="fill"></div></div>';li.firstChild.textContent=fs[i].name;L.appendChild(li);
var s=await one(fs[i],c,li);if(s==403)break}}
</script></body></html>"""
}

/** The TV window that shows the address and code, and lists what arrived. */
object SendToTvDialog {
    fun show(a: Activity, onReceived: (File) -> Unit) {
        val ar = a.resources.configuration.locales[0].language == "ar"
        fun t(en: String, x: String) = if (ar) x else en
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val msg = TextView(a).apply { textSize = 18f; setPadding(pad, pad, pad, pad / 2) }
        val base = t("On your phone (same Wi-Fi), open:\n\n", "على هاتفك (نفس شبكة Wi-Fi) افتح:\n\n")
        val received = ArrayList<String>()
        lateinit var server: UploadServer
        fun refresh(extra: String = "") {
            msg.text = base + "http://${UploadServer.ip()}:${UploadServer.PORT}\n\n" + t("Code: ", "الرمز: ") + server.code + "\n\n" +
                t("Choose one or more PDFs and press Send. Each book is kept on the TV — send it once.",
                  "اختر ملفًا أو أكثر واضغط إرسال. يُحفظ كل كتاب على التلفاز — أرسله مرة واحدة.") +
                (if (extra.isNotEmpty()) "\n\n$extra" else "") +
                (if (received.isNotEmpty()) "\n\n" + t("Received:\n", "تم الاستلام:\n") + received.joinToString("\n") { "✓ $it" } else "")
        }
        server = UploadServer(a.tvLibraryDir(), { name, done, total ->
            refresh(t("Receiving $name … ${done * 100 / total}%", "جارٍ استلام $name … ${done * 100 / total}٪"))
        }) { file, name ->
            if (file == null) refresh(t("“$name” is not a PDF.", "«$name» ليس ملف PDF."))
            else { received.add(name); refresh(); onReceived(file) }
        }
        refresh()
        server.start()
        AlertDialog.Builder(a)
            .setTitle(t("Send PDFs from your phone", "أرسل ملفات PDF من الهاتف"))
            .setView(msg)
            .setPositiveButton(t("Done", "تم"), null)
            .setOnDismissListener { server.stop() }
            .show()
    }
}
