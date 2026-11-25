package archi.linked.rdf2docs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class HtmlPageGeneratorTest {

    @Test
    fun `must have all required functionality`() {
        val code = File("src/main/kotlin/archi/linked/rdf2docs/HtmlPageGenerator.kt").readText()
        
        // 1-hop neighbor expansion
        assertFalse(code.contains("neighborIris"), "Found 1-hop expansion that causes duplicates")
        
        // facts.ttl generation
        assertTrue(code.contains("facts.ttl"), "Missing facts.ttl generation")
        assertTrue(code.contains("Rio.write"), "Missing RDF writing")
        
        // Complex relationship queries
        assertTrue(code.contains("buildViewsSection"), "Missing Views section")
        assertTrue(code.contains("buildRelationshipsSection"), "Missing Relationships section") 
        assertTrue(code.contains("buildEndpointsSection"), "Missing Endpoints section")
        assertTrue(code.contains("buildViewContentTabs"), "Missing View Content tabs")
        
        // Full row display (no .take() limits)
        assertFalse(code.contains("mainStmts.take("), "Found row limits on main statements")
        assertFalse(code.contains("props.take("), "Found row limits on properties")
        
        // ORDER BY in queries
        assertTrue(code.contains("ORDER BY ?subject"), "Missing ORDER BY in main query")
        assertTrue(code.contains("ORDER BY ?label"), "Missing ORDER BY in other queries")
        
        // Tab functionality
        assertTrue(code.contains("Views\" to viewsHtml"), "Missing Views tab")
        assertTrue(code.contains("Relationships\" to relsHtml"), "Missing Relationships tab")
        assertTrue(code.contains("Endpoints\" to endpointsHtml"), "Missing Endpoints tab")
        assertTrue(code.contains("Elements in view\" to elemsHtml"), "Missing Elements tab")
    }
}