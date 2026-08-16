# T-705. Build context is the repo root (docker-compose.yml sets it), not this module — the
# reactor build needs every module's pom.xml and source, not just tributary-api's own.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency resolution first, source second: on a rebuild with unchanged poms, Docker's layer
# cache skips straight to compilation. Doesn't help the very first, no-cache build (T-705's own
# verification target) but costs nothing and helps every build after it.
COPY pom.xml .
COPY tributary-domain/pom.xml tributary-domain/pom.xml
COPY tributary-application/pom.xml tributary-application/pom.xml
COPY tributary-adapter-co-factus/pom.xml tributary-adapter-co-factus/pom.xml
COPY tributary-adapter-es-verifactu/pom.xml tributary-adapter-es-verifactu/pom.xml
COPY tributary-adapter-de-en16931/pom.xml tributary-adapter-de-en16931/pom.xml
COPY tributary-persistence/pom.xml tributary-persistence/pom.xml
COPY tributary-api/pom.xml tributary-api/pom.xml
RUN mvn -q -pl tributary-api -am dependency:go-offline

COPY tributary-domain tributary-domain
COPY tributary-application tributary-application
COPY tributary-adapter-co-factus tributary-adapter-co-factus
COPY tributary-adapter-es-verifactu tributary-adapter-es-verifactu
COPY tributary-adapter-de-en16931 tributary-adapter-de-en16931
COPY tributary-persistence tributary-persistence
COPY tributary-api tributary-api
RUN mvn -q -pl tributary-api -am package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Runs as a non-root user — a compromised app process gets no write access outside /app.
RUN useradd --system --create-home --uid 10001 tributary
COPY --from=build /workspace/tributary-api/target/tributary-api-*.jar app.jar
USER tributary
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
