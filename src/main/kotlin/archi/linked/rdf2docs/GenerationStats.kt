package archi.linked.rdf2docs

/**
 * Statistics collected during RDF to documentation generation.
 */
data class GenerationStats(
    /** Number of elements extracted from RDF */
    val elementsExtracted: Int = 0,
    /** Number of views extracted from RDF */
    val viewsExtracted: Int = 0,
    /** Number of relationships extracted from RDF */
    val relationshipsExtracted: Int = 0,
    /** Number of HTML pages generated */
    val htmlPagesGenerated: Int = 0,
    /** The resource pages written, by kind; the index page is not one of them. */
    val htmlPagesByKind: Map<HtmlPageGenerator.ResourceKind, Int> = emptyMap(),
    /** Resources that could not be written, and why. Empty on a clean run. */
    val skippedResources: List<HtmlPageGenerator.SkippedResource> = emptyList(),
    /** Whether AI context was generated */
    val aiContextGenerated: Boolean = false,
    /** The AI context documents written — one per model, or a single document for one model. */
    val aiContextPartitions: List<AiContextGenerator.Partition> = emptyList(),
    // Performance metrics in milliseconds
    /** Time spent loading RDF data */
    val loadTimeMs: Long = 0,
    /** Time spent generating AI context */
    val aiContextTimeMs: Long = 0,
    /** Time spent generating HTML pages */
    val htmlGenerationTimeMs: Long = 0,
    /** Total execution time */
    val totalTimeMs: Long = 0
)