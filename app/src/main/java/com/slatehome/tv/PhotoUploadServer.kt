package com.slatehome.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny LAN web server so a phone can send photos to the TV — there's no touchscreen or file
 * browser worth using on a TV. It only exists while the "Add from Phone" sheet is open, listens
 * only on the TV's home-network address, and every URL carries a random code made fresh each
 * time the sheet opens, so other devices on the network can't push images to it.
 *
 * The phone's browser shrinks each photo first and POSTs the raw JPEG bytes, which keeps the
 * server to "read Content-Length bytes" instead of parsing multipart forms.
 *
 * It is deliberately stingy — two connections at a time, a hard deadline per connection, small
 * headers — so a misbehaving client can't tie up the TV's threads or memory.
 */
class PhotoUploadServer(private val onPhoto: suspend (ByteArray) -> Boolean) {

    private val code: String = SecureRandom().let { rnd ->
        (1..6).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
    }

    /** What the phone should open, once the server is listening. */
    var url by mutableStateOf<String?>(null)
        private set

    /** True when the TV has no home-network address to listen on. */
    var offline by mutableStateOf(false)
        private set

    var received by mutableIntStateOf(0)
        private set

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var stopped = false
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val slots = Semaphore(MAX_CONNECTIONS)

    /** Serves until [stop] is called. */
    suspend fun run() = withContext(Dispatchers.IO) {
        val host = runCatching { lanAddress() }.getOrNull()
        if (host == null) {
            withContext(Dispatchers.Main) { offline = true }
            return@withContext
        }
        val bindTo = InetAddress.getByName(host)
        val server = PORTS.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket(port, BACKLOG, bindTo) }.getOrNull()
        } ?: return@withContext
        socket = server
        try {
            if (stopped) return@withContext // the sheet closed while we were binding
            withContext(Dispatchers.Main) { url = "http://$host:${server.localPort}/$code" }
            coroutineScope {
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (!slots.tryAcquire()) {
                        runCatching { client.close() }
                        continue
                    }
                    clients += client
                    launch {
                        // Blocking reads ignore coroutine cancellation, so a watchdog closes the
                        // socket at the deadline — that's what unblocks a stalled read.
                        val watchdog = launch {
                            delay(CONNECTION_DEADLINE_MS)
                            runCatching { client.close() }
                        }
                        try {
                            runCatching { handle(client) }
                        } finally {
                            watchdog.cancel()
                            clients -= client
                            slots.release()
                        }
                    }
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private suspend fun handle(client: Socket) = client.use {
        it.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(it.getInputStream())
        val output = it.getOutputStream()
        val requestLine = readLine(input) ?: return@use
        val (method, path) = requestLine.split(" ").takeIf { parts -> parts.size >= 2 } ?: return@use
        val headers = HashMap<String, String>()
        var lines = 0
        while (true) {
            val line = readLine(input) ?: return@use
            if (line.isEmpty()) break // end of headers
            if (++lines > MAX_HEADER_LINES) return@use // more than any browser sends
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        when {
            method == "GET" && path == "/$code" ->
                respond(output, 200, "text/html; charset=utf-8", UPLOAD_PAGE.toByteArray())

            method == "POST" && path == "/$code/upload" -> {
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length <= 0 || length > MAX_UPLOAD_BYTES) {
                    respond(output, 413, "text/plain", "Photo is too large".toByteArray())
                } else {
                    val body = readExactly(input, length)
                    val accepted = body != null && withContext(Dispatchers.Main) { onPhoto(body) }
                    if (accepted) withContext(Dispatchers.Main) { received++ }
                    respond(
                        output,
                        if (accepted) 200 else 415,
                        "text/plain",
                        (if (accepted) "ok" else "Not a photo").toByteArray(),
                    )
                }
            }

            else -> respond(output, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_LINE) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) return null
            read += n
        }
        return body
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Unsupported Media Type"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet), e.g. 192.168.1.42. */
    private fun lanAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    private companion object {
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no 0/o or 1/l/i to misread
        val PORTS = 8765..8775
        const val BACKLOG = 8
        const val MAX_CONNECTIONS = 2
        const val CONNECTION_DEADLINE_MS = 60_000L
        const val READ_TIMEOUT_MS = 15_000
        // Phones shrink photos to well under 1 MB; this only matters for raw uploads.
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
        const val MAX_HEADER_LINE = 4 * 1024
        const val MAX_HEADER_LINES = 50

        // No JS template literals: Kotlin would try to interpolate their dollar signs.
        val UPLOAD_PAGE = """
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send to TV</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
         font: 17px -apple-system, BlinkMacSystemFont, system-ui, sans-serif; color: #fff;
         background: linear-gradient(160deg, #1b2a6b, #5b2a86 55%, #c2577a); }
  .card { width: min(90vw, 420px); padding: 30px 24px; border-radius: 30px; text-align: center;
          background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.3);
          -webkit-backdrop-filter: blur(30px); backdrop-filter: blur(30px); }
  h1 { font-size: 27px; margin: 0 0 6px; }
  p { margin: 0 0 24px; opacity: .75; }
  label { display: block; padding: 16px; border-radius: 16px; background: #fff; color: #111;
          font-weight: 600; cursor: pointer; }
  input { display: none; }
  ul { list-style: none; padding: 0; margin: 18px 0 0; text-align: left; }
  li { padding: 9px 0; border-top: 1px solid rgba(255,255,255,.15); font-size: 15px; }
</style>
</head><body>
<div class="card">
  <h1>Send to TV</h1>
  <p>Pick photos to use as your TV wallpaper.</p>
  <label>Choose Photos<input id="pick" type="file" accept="image/*" multiple></label>
  <ul id="log"></ul>
</div>
<script>
  var pick = document.getElementById('pick'), log = document.getElementById('log');
  // Shrink on the phone: a 12 MP photo becomes ~400 KB, and HEIC becomes JPEG.
  function shrink(file) {
    return createImageBitmap(file).then(function (img) {
      var s = Math.min(1, 2560 / Math.max(img.width, img.height));
      var c = document.createElement('canvas');
      c.width = Math.round(img.width * s); c.height = Math.round(img.height * s);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      return new Promise(function (done) { c.toBlob(function (b) { done(b || file); }, 'image/jpeg', 0.9); });
    }).catch(function () { return file; });
  }
  pick.onchange = async function () {
    for (const file of pick.files) {
      var row = document.createElement('li');
      row.textContent = 'Sending ' + file.name + '…';
      log.prepend(row);
      try {
        var res = await fetch(location.pathname + '/upload', { method: 'POST', body: await shrink(file) });
        row.textContent = (res.ok ? '✓ ' : '✕ ') + file.name + (res.ok ? ' — on your TV' : ' — not a photo');
      } catch (e) {
        row.textContent = '✕ ' + file.name + ' — lost connection to the TV';
      }
    }
    pick.value = '';
  };
</script>
</body></html>
""".trimIndent()
    }
}
