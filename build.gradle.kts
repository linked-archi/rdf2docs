plugins {
    kotlin("jvm") version "2.3.0"
    application
    id("com.gradleup.shadow") version "9.3.1"
}

group = "archi.linked"
// The version is in gradle.properties; a release sets it from its tag: ./gradlew -Pversion=0.2.0 shadowJar

repositories {
    mavenCentral()
}

dependencies {
    // RDF4J via the BOM, at the version the converters publish with.
    //
    // The generator reads what the converters write, so the two have to agree on what a graph is.
    // While this was pinned to 5.1.0 and the converters were on 6.0.0, RDF 1.2 — triple terms and
    // base-direction literals, added in 6.0.0 — was a graph a converter could legitimately emit and
    // this tool could not parse. Nothing emits RDF 1.2 yet, which is the only reason the split had
    // not been noticed, and the reason to close it now rather than after something does.
    //
    // Version lives in gradle.properties so there is one place to change it, as in the converters.
    implementation(platform("org.eclipse.rdf4j:rdf4j-bom:${property("rdf4jVersion")}"))
    implementation("org.eclipse.rdf4j:rdf4j-rio-turtle")
    implementation("org.eclipse.rdf4j:rdf4j-rio-trig")
    implementation("org.eclipse.rdf4j:rdf4j-model")
    implementation("org.eclipse.rdf4j:rdf4j-query")
    implementation("org.eclipse.rdf4j:rdf4j-repository-sail")
    implementation("org.eclipse.rdf4j:rdf4j-sail-memory")
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.yaml:snakeyaml:2.5")
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    // RDF4J 6.x is compiled for Java 25, so this is set by the dependency rather than by choice.
    // Gradle resolves the toolchain from the JDKs installed on the machine; see gradle.properties
    // if it cannot find one you know is there.
    jvmToolchain(25)
}

application {
    mainClass.set("archi.linked.rdf2docs.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("rdf2docs")
    archiveClassifier.set("all")
    mergeServiceFiles()
    // What `rdf2docs --version` prints.
    manifest {
        attributes("Implementation-Title" to "rdf2docs", "Implementation-Version" to project.version.toString())
    }
}
