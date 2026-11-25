package archi.linked.rdf2docs

/**
 * What every page shares: the header — the site's name, its four ways in (Index, Graph, SPARQL, RDF) and the menus
 * `tree.js` fills from the model tree's data (the models, the folders) — and, on the index and the resources'
 * pages, the model tree on the left, beside the page.
 */
internal object PageLayout {

    /** The header. [tree] adds the button that shows or hides the model tree; [extra] goes at its right. */
    fun header(back: String, current: String, tree: Boolean = false, extra: String = ""): String = buildString {
        append("<header class=\"site-header\">")
        if (tree) append("<button type=\"button\" id=\"tree-toggle\" class=\"tree-toggle\" title=\"Show or hide the model tree\">☰</button>")
        append("<a class=\"brand\" href=\"${back}index.html\">Architecture</a><nav>")
        for ((key, label) in listOf("index" to "Index", "graph" to "Graph", "sparql" to "SPARQL", "rdf" to "RDF")) {
            append("<a href=\"$back$key.html\"${if (key == current) " class=\"current\"" else ""}>$label</a>")
        }
        append("</nav><div id=\"site-menus\"></div><span class=\"spacer\"></span>").append(extra).append("</header>")
    }

    /** The `<body>` tag: where the site's root is from the page, and what the page is about, for the tree. */
    fun body(back: String, iri: String? = null, cls: String? = null): String =
        "<body data-root=\"${esc(back)}\"" + (iri?.let { " data-iri=\"${esc(it)}\"" } ?: "") + (cls?.let { " class=\"$it\"" } ?: "") + ">"

    /** The model tree's panel, which `tree.js` fills. */
    const val TREE_PANEL = "<aside class=\"tree-panel\" id=\"model-tree\" aria-label=\"Model tree\"></aside>"

    /** The scripts every page with the header loads: the tree's data, the shared behaviour, the tree and the menus. */
    fun scripts(back: String, vararg more: String): String =
        (listOf("${SiteAssets.DIR}/data/tree.js", "${SiteAssets.DIR}/js/site.js", "${SiteAssets.DIR}/js/tree.js") + more)
            .joinToString("\n") { "<script src=\"$back$it\"></script>" }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
