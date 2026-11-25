# rdf2docs' container image: Java 25 and the jar, run as `rdf2docs`.
#
# Built by the release workflow (.github/workflows/release.yml) from the jar it builds and attaches to the
# release, so the image runs exactly that jar. By hand:
#
#   ./gradlew clean shadowJar
#   docker build --tag rdf2docs .
#   docker run --rm -v "$PWD:/work" -w /work rdf2docs rdf2docs generate -i model.ttl -o site --base https://example.org/
#
# No entrypoint of its own: a CI job runs its script in the image's shell, which an entrypoint would replace. Run
# the tool by name. (The Java image's own entrypoint only installs extra CA certificates, then runs the command.)
ARG JAVA_IMAGE=eclipse-temurin:25-jre
FROM ${JAVA_IMAGE}

ARG VERSION=0.0.0
LABEL org.opencontainers.image.title="rdf2docs" \
      org.opencontainers.image.description="Documentation sites and AI context from Linked.Archi architecture models in RDF" \
      org.opencontainers.image.source="https://github.com/linked-archi/rdf2docs" \
      org.opencontainers.image.documentation="https://github.com/linked-archi/rdf2docs#readme" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

COPY build/libs/rdf2docs-*-all.jar /opt/rdf2docs/rdf2docs.jar
COPY LICENSE NOTICE /opt/rdf2docs/
COPY --chmod=755 docker/rdf2docs /usr/local/bin/rdf2docs

WORKDIR /work
CMD ["rdf2docs", "--help"]
