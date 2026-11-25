package archi.linked.rdf2docs

import picocli.CommandLine
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "rdf2docs",
    description = ["Generate AI context and HTML pages from RDF graphs"],
    mixinStandardHelpOptions = true,
    versionProvider = ManifestVersion::class,
    subcommands = [GenerateCommand::class]
)
class MainCommand

/**
 * The version the build wrote into the jar's manifest (`Implementation-Version`): the project's version, which a
 * release sets from its tag. Run from classes rather than a jar, there is none.
 */
class ManifestVersion : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> =
        arrayOf("rdf2docs " + (MainCommand::class.java.`package`?.implementationVersion ?: "(development build)"))
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(MainCommand()).execute(*args))
}
