package io.github.patschgoo.castapp.mediaservers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections

/** A local HTTP server for the tests: records every request and answers with what [handler] says. */
class TestServer(private val handler: (Req) -> Resp?) : AutoCloseable {
    class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String) {
        /** The value of a query parameter (percent-decoded), or null. */
        fun param(name: String): String? = query.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == name }?.second?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Resp(val code: Int = 200, val body: String = "", val contentType: String = "application/json")

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests: MutableList<Req> = Collections.synchronizedList(ArrayList())
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.firstOrNull().orEmpty() }
        val req = Req(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(), headers, body)
        requests += req
        val resp = handler(req) ?: Resp(404, """{"error":"not found"}""")
        val bytes = resp.body.toByteArray()
        ex.responseHeaders.add("Content-Type", resp.contentType)
        ex.sendResponseHeaders(resp.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    fun requestsTo(path: String): List<Req> = synchronized(requests) { requests.filter { it.path == path } }

    override fun close() = server.stop(0)
}
