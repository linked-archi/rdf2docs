package archi.linked.rdf2docs

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Files

@Command(
    name = "generate",
    description = ["Generate AI context document and HTML pages from RDF"]
)
class GenerateCommand : Runnable {

    private val log = LoggerFactory.getLogger(javaClass)

    @Option(names = ["-i", "--input"], description = ["Input RDF file (Turtle/TriG)"], required = true)
    lateinit var input: File

    @Option(names = ["-o", "--output-dir"], description = ["Output directory"], required = true)
    lateinit var outputDir: File

    @Option(names = ["--base"], description = ["Base IRI used to mint IRIs (must match --base used in the converter)."], required = true)
    lateinit var base: String

    @Option(names = ["--model-id"], description = ["Model identifier segment (must match --model-id used in the converter)."], required = false)
    var modelId: String? = null

    @Option(names = ["--ai-context"], description = ["Generate AI context document"], defaultValue = "true")
    var generateAiContext: Boolean = true

    @Option(names = ["--html-pages"], description = ["Generate HTML pages"], defaultValue = "true")
    var generateHtmlPages: Boolean = true

    @Option(names = ["--html-base-url"], description = ["Base URL where HTML pages are served (used to generate data model links in AI context)"], required = false)
    var htmlBaseUrl: String? = null

    @Option(names = ["--graph-config"], description = ["YAML config file for the graph page: excludedNodeTypes (hidden at first) and filterGroups (the type filter's groups)."], required = false)
    var graphConfigFile: File? = null

    @Option(
        names = ["--presentation-context"],
        description = ["The presentation context the diagrams are drawn for when a page opens: an IRI, a local name or a name " +
            "of one of the model's languages' contexts (e.g. ArchitectContext). Default: each language's own notation. " +
            "Every view page offers the other contexts too."],
        required = false,
    )
    var presentationContext: String? = null

    @Option(names = ["--ns-core"], description = ["Linked.Archi core namespace. Default: https://meta.linked.archi/core#"], required = false)
    var nsCore: String = "https://meta.linked.archi/core#"

    @Option(names = ["--ns-core-vis"], description = ["Linked.Archi core-vis namespace. Default: https://meta.linked.archi/core-vis#"], required = false)
    var nsCoreVis: String = "https://meta.linked.archi/core-vis#"

    @Option(names = ["--ns-archimate"], description = ["Linked.Archi ArchiMate namespace. Default: https://meta.linked.archi/archimate#"], required = false)
    var nsArchimate: String = "https://meta.linked.archi/archimate#"

    @Option(names = ["--ns-vocab"], description = ["Vocabulary namespace for archiUrl and custom predicates."], required = false)
    var nsVocab: String? = null

    override fun run() {
        val startTime = System.currentTimeMillis()
        require(input.exists() && input.isFile) { "Input file not found: ${input.absolutePath}" }
        outputDir.mkdirs()

        val baseIri = listOfNotNull(base.trimEnd('/'), modelId?.takeIf { it.isNotEmpty() }).joinToString("/")

        val ns = Namespaces(
            core = nsCore.ensureHashOrSlash(),
            coreVis = nsCoreVis.ensureHashOrSlash(),
            archimate = nsArchimate.ensureHashOrSlash(),
            vocab = nsVocab?.ensureHashOrSlash() ?: nsCore.ensureHashOrSlash()
        )

        val graphConfig = graphConfigFile?.let { GraphConfig.load(it) } ?: GraphConfig()

        log.info("Loading RDF from: {}", input.absolutePath)
        val loadStartTime = System.currentTimeMillis()
        val repository = loadRdf(input, baseIri)
        val loadTimeMs = System.currentTimeMillis() - loadStartTime

        var elementsExtracted = 0
        var viewsExtracted = 0
        var relationshipsExtracted = 0
        var aiContextPartitions = emptyList<AiContextGenerator.Partition>()
        var htmlPagesGenerated = 0
        var htmlPagesByKind = emptyMap<HtmlPageGenerator.ResourceKind, Int>()
        var skippedResources = emptyList<HtmlPageGenerator.SkippedResource>()
        var aiContextTimeMs = 0L
        var htmlGenerationTimeMs = 0L

        try {
            if (generateAiContext) {
                log.info("Generating AI context document...")
                val aiContextStartTime = System.currentTimeMillis()
                val aiContextFile = File(outputDir, "ai-context.md")
                val aiContext = AiContextGenerator.generate(repository, baseIri, aiContextFile, htmlBaseUrl, ns)
                elementsExtracted = aiContext.elements
                viewsExtracted = aiContext.views
                relationshipsExtracted = aiContext.relationships
                aiContextPartitions = aiContext.partitions
                aiContextTimeMs = System.currentTimeMillis() - aiContextStartTime
                log.info("AI context written to: {}", aiContextFile.absolutePath)
            }

            if (generateHtmlPages) {
                log.info("Generating HTML pages...")
                val htmlStartTime = System.currentTimeMillis()
                val htmlDir = File(outputDir, "html")
                htmlDir.mkdirs()
                val html = HtmlPageGenerator.generate(repository, baseIri, htmlDir, ns, graphConfig, presentationContext)
                htmlPagesGenerated = html.pagesGenerated
                htmlPagesByKind = html.pagesByKind
                skippedResources = html.skipped
                htmlGenerationTimeMs = System.currentTimeMillis() - htmlStartTime
                log.info("HTML pages written to: {}", htmlDir.absolutePath)
            }

            val totalTimeMs = System.currentTimeMillis() - startTime
            val stats = GenerationStats(
                elementsExtracted = elementsExtracted,
                viewsExtracted = viewsExtracted,
                relationshipsExtracted = relationshipsExtracted,
                htmlPagesGenerated = htmlPagesGenerated,
                htmlPagesByKind = htmlPagesByKind,
                skippedResources = skippedResources,
                aiContextGenerated = generateAiContext,
                aiContextPartitions = aiContextPartitions,
                loadTimeMs = loadTimeMs,
                aiContextTimeMs = aiContextTimeMs,
                htmlGenerationTimeMs = htmlGenerationTimeMs,
                totalTimeMs = totalTimeMs
            )

            printStats(stats)
            writeStatsReport(stats)
            log.info("Done.")
        } finally {
            repository.shutDown()
        }
    }

    private fun loadRdf(file: File, baseIri: String): Repository {
        val format = Rio.getParserFormatForFileName(file.name).orElse(RDFFormat.TURTLE)
        val repository = SailRepository(MemoryStore())
        repository.init()

        repository.connection.use { conn ->
            Files.newInputStream(file.toPath()).use { ins ->
                conn.add(ins, baseIri, format)
            }
        }

        return repository
    }

    private fun printStats(stats: GenerationStats) {
        log.info("Elements: {}, Views: {}, Relationships: {}, HTML pages: {}",
            stats.elementsExtracted, stats.viewsExtracted, stats.relationshipsExtracted, stats.htmlPagesGenerated)
        if (stats.skippedResources.isNotEmpty()) {
            log.warn("Skipped: {} resource(s) that could not be written — listed in generation-stats.md",
                stats.skippedResources.size)
        }
        if (stats.aiContextPartitions.size > 1) {
            log.info("AI context: {} partitions, indexed from ai-context.md", stats.aiContextPartitions.size)
        }
        log.info("Performance: Load={}ms, AI={}ms, HTML={}ms, Total={}ms",
            stats.loadTimeMs, stats.aiContextTimeMs, stats.htmlGenerationTimeMs, stats.totalTimeMs)
    }

    private fun writeStatsReport(stats: GenerationStats) {
        val reportFile = File(outputDir, "generation-stats.md")
        val sb = StringBuilder()
        sb.appendLine("# RDF to Documentation Generation Statistics")
        sb.appendLine()
        sb.appendLine("## Settings")
        sb.appendLine()
        sb.appendLine("| Setting | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| input | `${input.absolutePath}` |")
        sb.appendLine("| output-dir | `${outputDir.absolutePath}` |")
        sb.appendLine("| base | `$base` |")
        sb.appendLine("| model-id | `${modelId ?: "none"}` |")
        sb.appendLine("| ai-context | `$generateAiContext` |")
        sb.appendLine("| html-pages | `$generateHtmlPages` |")
        sb.appendLine("| html-base-url | `${htmlBaseUrl ?: "none"}` |")
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()
        sb.appendLine("| Metric | Count |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Elements extracted | ${stats.elementsExtracted} |")
        sb.appendLine("| Views extracted | ${stats.viewsExtracted} |")
        sb.appendLine("| Relationships extracted | ${stats.relationshipsExtracted} |")
        sb.appendLine("| HTML pages generated | ${stats.htmlPagesGenerated} |")
        sb.appendLine("| Resources skipped | ${stats.skippedResources.size} |")
        sb.appendLine("| AI context generated | ${if (stats.aiContextGenerated) "Yes" else "No"} |")
        sb.appendLine()

        if (stats.htmlPagesByKind.isNotEmpty()) {
            sb.appendLine("## HTML pages by kind")
            sb.appendLine()
            sb.appendLine("The index page aside. A shape is a fragment of its view's page and a part is shown on its owner's,")
            sb.appendLine("so neither has a page of its own.")
            sb.appendLine()
            sb.appendLine("| Kind | Pages |")
            sb.appendLine("|---|---|")
            stats.htmlPagesByKind.forEach { (kind, count) ->
                sb.appendLine("| ${kind.name.lowercase().replace('_', ' ')} | $count |")
            }
            sb.appendLine()
        }

        if (stats.aiContextPartitions.size > 1) {
            sb.appendLine("## AI context partitions")
            sb.appendLine()
            sb.appendLine("| Model | Document | Resources |")
            sb.appendLine("|---|---|---|")
            stats.aiContextPartitions.forEach { p ->
                sb.appendLine("| `${p.key.ifEmpty { "(base)" }}` | `${p.file.name}` | ${p.resources} |")
            }
            sb.appendLine()
        }

        if (stats.skippedResources.isNotEmpty()) {
            sb.appendLine("## Skipped resources")
            sb.appendLine()
            sb.appendLine("These resources have no page. The run completed; everything else was written.")
            sb.appendLine()
            sb.appendLine("| IRI | Reason |")
            sb.appendLine("|---|---|")
            stats.skippedResources.forEach { sb.appendLine("| `${it.iri}` | ${it.reason} |") }
            sb.appendLine()
        }
        sb.appendLine("## Performance")
        sb.appendLine()
        sb.appendLine("| Phase | Time (ms) | Percentage |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| Loading RDF | ${stats.loadTimeMs} | ${String.format("%.1f", stats.loadTimeMs * 100.0 / stats.totalTimeMs)}% |")
        if (stats.aiContextGenerated) {
            sb.appendLine("| AI context generation | ${stats.aiContextTimeMs} | ${String.format("%.1f", stats.aiContextTimeMs * 100.0 / stats.totalTimeMs)}% |")
        }
        if (stats.htmlPagesGenerated > 0) {
            sb.appendLine("| HTML generation | ${stats.htmlGenerationTimeMs} | ${String.format("%.1f", stats.htmlGenerationTimeMs * 100.0 / stats.totalTimeMs)}% |")
        }
        sb.appendLine("| **Total** | **${stats.totalTimeMs}** | **100.0%** |")
        
        reportFile.writeText(sb.toString())
        log.info("Stats report written to: {}", reportFile.absolutePath)
    }
}

data class Namespaces(
    val core: String,
    val coreVis: String,
    val archimate: String,
    val vocab: String
)

private fun String.ensureHashOrSlash(): String =
    if (endsWith("#") || endsWith("/")) this else "$this#"
