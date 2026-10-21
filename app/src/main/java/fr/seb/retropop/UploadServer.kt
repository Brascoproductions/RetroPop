package fr.seb.retropop

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Petit serveur web local : on ouvre http://<ip-de-la-box>:8080 depuis un
 * téléphone ou un PC du même réseau pour envoyer ou supprimer des jeux.
 */
class UploadServer(
    private val context: Context,
    private val library: GameLibrary,
    port: Int,
    private val onLibraryChanged: () -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/" -> page()
            session.method == Method.GET && session.uri == "/games" -> json(gamesJson())
            session.method == Method.POST && session.uri == "/upload" -> upload(session)
            session.method == Method.POST && session.uri == "/delete" -> delete(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Introuvable")
        }
    } catch (e: Exception) {
        json(JSONObject().put("ok", false).put("error", e.message ?: "Erreur"), Response.Status.INTERNAL_ERROR)
    }

    private fun upload(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return json(JSONObject().put("ok", false).put("error", "Nom manquant"), Response.Status.BAD_REQUEST)
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return json(JSONObject().put("ok", false).put("error", "Taille inconnue"), Response.Status.BAD_REQUEST)
        if (length > MAX_UPLOAD_BYTES) {
            BoundedInputStream(session.inputStream, length).use { it.skip(length) }
            return json(JSONObject().put("ok", false).put("error", "Fichier trop gros"), Response.Status.BAD_REQUEST)
        }

        val saved = BoundedInputStream(session.inputStream, length).use { body ->
            val result = library.import(name, body)
            body.skip(Long.MAX_VALUE) // vide le reste de la requête si besoin
            result
        }
        return if (saved != null) {
            onLibraryChanged()
            json(JSONObject().put("ok", true).put("file", saved))
        } else {
            json(
                JSONObject().put("ok", false)
                    .put("error", "Format non reconnu (attendu : .nes .sfc .smc .zip)"),
                Response.Status.BAD_REQUEST,
            )
        }
    }

    private fun delete(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull().orEmpty()
        val ok = library.delete(name)
        if (ok) onLibraryChanged()
        return json(JSONObject().put("ok", ok))
    }

    private fun gamesJson(): JSONArray {
        val arr = JSONArray()
        library.scan().forEach { g ->
            arr.put(
                JSONObject()
                    .put("file", g.file.name)
                    .put("title", g.title)
                    .put("system", g.system.shortLabel)
                    .put("size", g.file.length())
            )
        }
        return arr
    }

    private fun page(): Response {
        val html = context.assets.open("upload.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun json(body: Any, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    /** Limite la lecture au corps de la requête (sinon NanoHTTPD bloque sur la socket). */
    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun skip(n: Long): Long {
            var skipped = 0L
            val buf = ByteArray(8192)
            while (skipped < n && remaining > 0) {
                val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
                if (r <= 0) break
                skipped += r
            }
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
        override fun close() { /* la socket appartient à NanoHTTPD */ }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024

        /** Démarre sur 8080, ou le premier port libre jusqu'à 8090. */
        fun startOnFreePort(
            context: Context,
            library: GameLibrary,
            onLibraryChanged: () -> Unit,
        ): UploadServer? {
            for (port in DEFAULT_PORT..DEFAULT_PORT + 10) {
                val server = UploadServer(context, library, port, onLibraryChanged)
                try {
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                    server.stop()
                }
            }
            return null
        }

        /** Adresse IPv4 locale de la box (Ethernet ou Wi-Fi). */
        fun localIpAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .sortedByDescending { it.isSiteLocalAddress }
                .firstOrNull()
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
