package archi.linked.rdf2docs

import org.yaml.snakeyaml.Yaml
import java.io.File

data class FilterGroup(val label: String, val iris: List<String>)

data class GraphConfig(
    /**
     * IRIs of rdf:type values whose checkbox starts UNCHECKED (hidden) by default.
     * All other types present in the data start checked (visible).
     */
    val excludedNodeTypes: List<String> = emptyList(),
    /**
     * Named groups of type IRIs rendered as sections in the filter dropdown.
     * Types not assigned to any group are collected into an "Other" group automatically.
     * If empty, all types are placed in a single ungrouped list.
     */
    val filterGroups: List<FilterGroup> = emptyList()
) {
    companion object {
        fun load(file: File): GraphConfig {
            val raw = Yaml().load<Map<String, Any>>(file.readText()) ?: return GraphConfig()
            @Suppress("UNCHECKED_CAST")
            val excluded = (raw["excludedNodeTypes"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val groups = (raw["filterGroups"] as? List<Map<String, Any>>)?.map { g ->
                FilterGroup(
                    label = g["label"] as? String ?: "",
                    iris = (g["iris"] as? List<String>) ?: emptyList()
                )
            } ?: emptyList()
            return GraphConfig(excludedNodeTypes = excluded, filterGroups = groups)
        }
    }
}
