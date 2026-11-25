package archi.linked.rdf2docs.notation

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Files the notation publishes at meta.linked.archi, read when a drawing needs their content rather than a link to
 * them: a decoration's symbol, drawn inside a line's marker in the line's colour. Each is read once a run; when
 * meta.linked.archi cannot be reached, none is read again, and the drawing uses its built-in geometry, which draws
 * the same decorations.
 */
object Published {
    private val log = LoggerFactory.getLogger(Published::class.java)
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private val texts = ConcurrentHashMap<String, Optional>()
    @Volatile private var unreachable = false

    private class Optional(val text: String?)

    private val served = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether meta.linked.archi serves [iri]: false when it answers that it has no such file, so that a page does not
     * show a broken picture — as la-gram takes away a badge whose picture does not load — and true when it serves it,
     * when it is no IRI of meta.linked.archi's, or when meta.linked.archi cannot be asked.
     */
    fun serves(iri: String): Boolean {
        if (!iri.startsWith(NS.META) || unreachable) return true
        return served.computeIfAbsent(iri) {
            try {
                val request = HttpRequest.newBuilder(URI(iri)).timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status == 404 || status == 410) log.info("meta.linked.archi has no {}: not shown", iri)
                status != 404 && status != 410
            } catch (e: Exception) {
                unreachable = true
                log.info("meta.linked.archi unreachable ({}): its pictures are linked unchecked", e.javaClass.simpleName)
                true
            }
        }
    }

    /** The text of a published file, or null when it is not one, cannot be read, or meta.linked.archi is unreachable. */
    fun text(iri: String): String? {
        if (!iri.startsWith(NS.META) || unreachable) return null
        return texts.computeIfAbsent(iri) {
            val text = try {
                val response = client.send(HttpRequest.newBuilder(URI(iri)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) response.body() else null
            } catch (e: Exception) {
                unreachable = true
                log.info("meta.linked.archi unreachable ({}): drawing decorations with built-in geometry", e.javaClass.simpleName)
                null
            }
            Optional(text)
        }.text
    }
}
