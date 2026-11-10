package com.nuvio.tv.livetv.extras

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** What the phone sent: an Xtream server, username and password (nothing else). */
data class PhoneXtreamLogin(val server: String, val username: String, val password: String)

/**
 * "Use your phone" on the Xtream login: a one-page form on the home network (the TV's own
 * address, a random port, a random one-time code in the address) with only the Xtream server,
 * username and password. Anything without the code gets "Not found". Once a login is sent the
 * server stops; the screen stops it too when it closes.
 */
class PhoneLoginServer private constructor(
    private val socket: ServerSocket,
    private val token: String,
    val url: String,
    private val onLogin: (PhoneXtreamLogin) -> Unit
) {
    private val used = AtomicBoolean(false)

    val isRunning: Boolean get() = !socket.isClosed

    fun stop() { runCatching { socket.close() } }

    private fun loop() {
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                Thread { serve(client) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "phone-login"; start() }
    }

    private fun serve(client: Socket) {
        runCatching {
            client.use { c ->
                c.soTimeout = 8_000
                val input = c.getInputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.firstOrNull().orEmpty().split(' ')
                val method = parts.getOrNull(0).orEmpty().uppercase()
                val target = parts.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                val out = c.getOutputStream()
                val route = route(path, token)
                if (route == null || used.get()) {
                    respond(out, 404, "text/plain; charset=utf-8", "Not found")
                    return
                }
                when {
                    route == Route.PAGE && method == "GET" -> respond(out, 200, HTML, page(null, null))
                    route == Route.LOGIN && method == "POST" -> {
                        val length = lines.firstNotNullOfOrNull { l ->
                            if (l.startsWith("content-length:", ignoreCase = true)) l.substringAfter(':').trim().toIntOrNull() else null
                        } ?: 0
                        if (length !in 1..MAX_BODY) { respond(out, 400, HTML, page("Something went wrong. Try again.", null)); return }
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                        val result = validate(form["server"], form["username"], form["password"])
                        val login = result.getOrNull()
                        if (login == null) {
                            respond(out, 200, HTML, page(result.exceptionOrNull()?.message ?: "Check the details.", form))
                        } else if (used.compareAndSet(false, true)) {
                            respond(out, 200, HTML, done())
                            stop()
                            onLogin(login)
                        } else {
                            respond(out, 404, "text/plain; charset=utf-8", "Not found")
                        }
                    }
                    else -> respond(out, 405, "text/plain; charset=utf-8", "Not allowed")
                }
            }
        }
    }

    private fun readHead(input: java.io.InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) return buf.substring(0, buf.length - 4)
        }
        return null
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 405 -> "Method Not Allowed"; else -> "Not Found" }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun page(error: String?, form: Map<String, String>?): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>TVXO · Xtream login</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#0d0b10;color:#eee;margin:0;padding:20px}
h1{font-size:22px;margin:0 0 6px}
p{color:#aaa;font-size:15px;margin:4px 0 16px}
label{display:block;font-size:14px;color:#ccc;margin:12px 0 6px}
input{width:100%;box-sizing:border-box;font-size:17px;padding:14px;border-radius:10px;border:1px solid #333;background:#1b1820;color:#eee}
button{margin-top:18px;font-size:18px;padding:14px 22px;border:0;border-radius:10px;background:#ff2d87;color:#fff;font-weight:600;width:100%}
.err{color:#ff7a9e;font-size:15px;margin-top:10px}
</style></head><body>
<h1>Xtream login</h1>
<p>Fill these in and send them to your TV.</p>
<form method="post" action="/$token/login" autocomplete="off">
<label>Server URL</label>
<input name="server" value="${esc(form?.get("server").orEmpty())}" placeholder="http://example.com:8080" autocapitalize="off" autocorrect="off" inputmode="url" required>
<label>Username</label>
<input name="username" value="${esc(form?.get("username").orEmpty())}" autocapitalize="off" autocorrect="off" required>
<label>Password</label>
<input name="password" type="password" autocapitalize="off" autocorrect="off" required>
<button type="submit">Log in on TV</button>
</form>
${if (error != null) "<div class=\"err\">${esc(error)}</div>" else ""}
</body></html>"""

    private fun done(): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>TVXO</title>
<style>body{font-family:-apple-system,Roboto,sans-serif;background:#0d0b10;color:#eee;margin:0;padding:20px}h1{font-size:22px}p{color:#aaa}</style>
</head><body><h1>Sent</h1><p>Look at your TV. You can close this page.</p></body></html>"""

    enum class Route { PAGE, LOGIN }

    companion object {
        private const val HTML = "text/html; charset=utf-8"
        private const val MAX_HEAD = 16 * 1024
        internal const val MAX_BODY = 8 * 1024
        internal const val TOKEN_LENGTH = 16
        private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"

        /** Null when there's no home network. [onLogin] is called once, on a background thread. */
        fun start(onLogin: (PhoneXtreamLogin) -> Unit): PhoneLoginServer? {
            val ip = localAddress() ?: return null
            val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
            val token = randomToken()
            return PhoneLoginServer(socket, token, "http://$ip:${socket.localPort}/$token", onLogin).also { it.loop() }
        }

        internal fun randomToken(random: SecureRandom = SecureRandom()): String =
            (1..TOKEN_LENGTH).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")

        /** Constant-time compare, so the code can't be guessed a letter at a time. */
        internal fun tokenOk(given: String?, token: String): Boolean {
            if (given.isNullOrEmpty() || token.isEmpty()) return false
            return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
        }

        /** "/<token>" is the page and "/<token>/login" takes the form; anything else is null. */
        internal fun route(path: String, token: String): Route? {
            val segs = path.substringBefore('?').trim('/').split('/')
            if (!tokenOk(segs.getOrNull(0), token)) return null
            return when {
                segs.size == 1 -> Route.PAGE
                segs.size == 2 && segs[1] == "login" -> Route.LOGIN
                else -> null
            }
        }

        /** application/x-www-form-urlencoded -> map (the last value wins). */
        internal fun parseForm(body: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            body.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val k = decode(pair.substringBefore('='))
                val v = decode(pair.substringAfter('=', ""))
                if (k.isNotEmpty()) out[k] = v
            }
            return out
        }

        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** Checks what the phone sent; a bare host gets "http://". */
        internal fun validate(server: String?, username: String?, password: String?): Result<PhoneXtreamLogin> {
            val user = username?.trim().orEmpty()
            val pass = password.orEmpty()
            val url = normalizeServer(server) ?: return Result.failure(IllegalArgumentException("Enter the server URL (http:// or https://)."))
            if (user.isEmpty()) return Result.failure(IllegalArgumentException("Enter your username."))
            if (pass.isEmpty()) return Result.failure(IllegalArgumentException("Enter your password."))
            return Result.success(PhoneXtreamLogin(url, user, pass))
        }

        internal fun normalizeServer(raw: String?): String? {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            val scheme = s.substringBefore("://", "").lowercase()
            if (scheme.isEmpty()) s = "http://$s"
            else if (scheme != "http" && scheme != "https") return null
            val host = s.substringAfter("://").substringBefore('/').substringBefore(':')
            if (host.isEmpty() || !host.any { it.isLetterOrDigit() }) return null
            return s.trimEnd('/')
        }

        internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        private fun localAddress(): String? = runCatching {
            val found = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
            (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
        }.getOrNull()
    }
}
