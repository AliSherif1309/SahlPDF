package com.sahl.pdf

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Lightweight local HTTP server for "Send from phone" over Wi-Fi with a 6-digit code.
 * Zero external dependencies; uses standard Java sockets.
 */
class WifiTransfer(
    private val context: Context,
    private val onReceived: (File, String) -> Unit,
) {
    val code: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    val url: String
        get() {
            val port = server?.localPort ?: 8090
            return "http://${localIp()}:$port"
        }

    fun start(): Boolean {
        return try {
            val ss = try { ServerSocket(8090) } catch (_: Exception) { ServerSocket(0) }
            server = ss
            running = true
            Thread({
                while (running) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(s) }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }, "sahl-wifi-transfer").start()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val inp = BufferedInputStream(s.getInputStream(), 16384)
            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            if (method == "GET" && (path == "/" || path == "/index.html")) {
                respond(s, "200 OK", "text/html; charset=utf-8", htmlPage().toByteArray(Charsets.UTF_8))
                return
            }

            if (method == "POST" && path == "/upload") {
                val ct = headers["content-type"].orEmpty()
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                val boundary = ct.substringAfter("boundary=", "").trim().trim('"')
                if (boundary.isEmpty() || len <= 0 || len > 256L * 1024 * 1024) {
                    respond(s, "400 Bad Request", "text/plain; charset=utf-8", "Bad request".toByteArray())
                    return
                }
                val body = readExact(inp, len.toInt())
                val result = processMultipart(body, "--$boundary".toByteArray(Charsets.ISO_8859_1))
                when (result) {
                    -1 -> respond(s, "403 Forbidden", "text/plain; charset=utf-8", "Wrong code / الرمز غير صحيح".toByteArray())
                    0 -> respond(s, "400 Bad Request", "text/plain; charset=utf-8", "No PDF selected / لم يتم اختيار ملف PDF".toByteArray())
                    else -> respond(s, "200 OK", "text/plain; charset=utf-8", "Sent $result PDF(s) / تم إرسال $result ملف".toByteArray())
                }
                return
            }

            respond(s, "404 Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
        }
    }

    private fun processMultipart(body: ByteArray, boundary: ByteArray): Int {
        var givenCode = ""
        val files = ArrayList<Pair<String, ByteArray>>()
        var pos = indexOf(body, boundary, 0)
        while (pos >= 0) {
            val partStart = pos + boundary.size
            if (partStart + 2 <= body.size && body[partStart] == '-'.code.toByte() && body[partStart + 1] == '-'.code.toByte()) break
            val headerStart = if (partStart + 2 <= body.size && body[partStart] == '\r'.code.toByte() && body[partStart + 1] == '\n'.code.toByte()) partStart + 2 else partStart
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1), headerStart)
            if (headerEnd < 0) break
            val headerText = String(body, headerStart, headerEnd - headerStart, Charsets.UTF_8)
            val dataStart = headerEnd + 4
            val nextBoundary = indexOf(body, boundary, dataStart)
            if (nextBoundary < 0) break
            var dataEnd = nextBoundary
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r'.code.toByte() && body[dataEnd - 1] == '\n'.code.toByte()) dataEnd -= 2

            val name = Regex("""name="([^"]+)"""").find(headerText)?.groupValues?.get(1) ?: ""
            val filename = Regex("""filename="([^"]*)"""").find(headerText)?.groupValues?.get(1)
            if (name == "code") {
                givenCode = String(body, dataStart, dataEnd - dataStart, Charsets.UTF_8).trim()
            } else if (filename != null && filename.isNotBlank() && dataEnd > dataStart) {
                val slice = body.copyOfRange(dataStart, dataEnd)
                files += filename to slice
            }
            pos = nextBoundary
        }

        if (givenCode != code) return -1
        if (files.isEmpty()) return 0

        val dir = targetDir()
        var saved = 0
        for ((rawName, bytes) in files) {
            val clean = rawName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("""[^\w.\-\u0600-\u06FF ]+"""), "_").trim().ifBlank { "book.pdf" }
            val finalName = if (clean.lowercase().endsWith(".pdf")) clean else "$clean.pdf"
            val out = uniqueFile(dir, finalName)
            FileOutputStream(out).use { it.write(bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf("application/pdf"), null)
            val uri = Uri.fromFile(out)
            val key = Library.keyFor(uri, out.name, out.length())
            Library.upsert(context, Book(key, uri.toString(), out.name, out.length(), 0, 0, System.currentTimeMillis()))
            onReceived(out, out.name)
            saved++
        }
        return saved
    }

    private fun targetDir(): File {
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sahlDir = File(pub, "Sahl PDF")
        if (sahlDir.mkdirs() || (sahlDir.isDirectory && sahlDir.canWrite())) return sahlDir
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Sahl PDF").apply { mkdirs() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
        var idx = 2
        while (f.exists()) {
            f = File(dir, "$base ($idx).pdf")
            idx++
        }
        return f
    }

    private fun respond(s: Socket, status: String, contentType: String, body: ByteArray) {
        val hdr = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val os = s.getOutputStream()
        os.write(hdr.toByteArray(Charsets.ISO_8859_1))
        os.write(body)
        os.flush()
    }

    private fun readLine(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun readExact(inp: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = inp.read(out, off, len - off)
            if (r <= 0) break
            off += r
        }
        return if (off == len) out else out.copyOf(off)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val limit = haystack.size - needle.size
        var i = from
        while (i <= limit) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        return a.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) { }
        return "127.0.0.1"
    }

    private fun htmlPage() = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sahl PDF · سهل PDF</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0b110f;color:#fff;margin:0;padding:24px;display:flex;justify-content:center}
.card{max-width:420px;width:100%;background:#131c19;border-radius:20px;padding:24px;box-sizing:border-box}
h1{margin:0 0 6px;font-size:22px;color:#f5c84c}
p{color:#9aa3ab;font-size:14px;margin:0 0 18px}
label{display:block;font-size:14px;margin:14px 0 6px}
input[type=text],input[type=file]{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid #2b3035;background:#0b110f;color:#fff;font-size:16px}
button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:14px;background:#0e6b4e;color:#fff;font-size:16px;font-weight:700;cursor:pointer}
#st{margin-top:14px;font-size:14px;color:#f5c84c;min-height:20px}
</style>
</head>
<body>
<div class="card">
<h1>Sahl PDF · سهل PDF</h1>
<p>Send PDFs to your TV / أرسل ملفات PDF إلى التلفاز</p>
<form id="f">
<label>6-digit code / الرمز المكون من 6 أرقام</label>
<input type="text" name="code" inputmode="numeric" maxlength="6" required placeholder="000000">
<label>PDF files / ملفات PDF</label>
<input type="file" name="pdf" accept="application/pdf,.pdf" multiple required>
<button type="submit">Send / إرسال</button>
</form>
<div id="st"></div>
</div>
<script>
document.getElementById('f').onsubmit = async (e) => {
  e.preventDefault();
  const st = document.getElementById('st');
  st.textContent = 'Sending… / جارٍ الإرسال…';
  try {
    const res = await fetch('/upload', { method: 'POST', body: new FormData(e.target) });
    st.textContent = await res.text();
    if (res.ok) e.target.querySelector('input[type=file]').value = '';
  } catch (err) { st.textContent = 'Error / خطأ: ' + err; }
};
</script>
</body>
</html>"""
}
