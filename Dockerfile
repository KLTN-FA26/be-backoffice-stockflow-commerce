# =============================================================================
# StockFlowCommerce - production image.
#
# Two stages: build with the JDK, run on the JRE. The runtime image never contains Maven, the
# source, or the build cache - which is both smaller and one less thing to keep patched.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies are copied and resolved BEFORE the source. Docker caches each layer, so an ordinary
# code change reuses the dependency layer and the build takes seconds instead of re-downloading
# every jar. Copying everything at once - the obvious thing to write - throws that cache away on
# every commit.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in CI against real containers, not here: the build image has no Docker socket, so
# Testcontainers cannot start Postgres and every integration test would fail.
RUN mvn -B clean package -DskipTests

# Normalise the name FIRST. The extracted application layer keeps whatever the jar was called, so
# extracting target/*.jar directly leaves stockflow-monolith-1.0.0-SNAPSHOT.jar - a name the entry
# point would have to hard-code and that changes on every version bump.
RUN cp target/*.jar application.jar

# Split into Spring Boot's layers. Dependencies change rarely and application classes change on
# every commit, so a redeploy ships a few hundred KB rather than the whole 60 MB jar.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Never root. A container escape from an application running as root is a container escape as root
# on the host; as an unprivileged user it is far less useful to an attacker.
RUN addgroup -S stockflow && adduser -S stockflow -G stockflow

WORKDIR /app

# Layer order matters: least-likely to change first, so the cached layers stay valid longest.
COPY --from=build --chown=stockflow:stockflow /build/extracted/dependencies/ ./
COPY --from=build --chown=stockflow:stockflow /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=stockflow:stockflow /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=stockflow:stockflow /build/extracted/application/ ./

USER stockflow

EXPOSE 8080

# Container-aware defaults.
#
#   MaxRAMPercentage - the JVM's default heap is 1/4 of the container limit, which wastes most of
#   the memory you paid for. 75% leaves room for metaspace, thread stacks and native buffers.
#
#   ExitOnOutOfMemoryError - a JVM that has run out of heap does not recover; it thrashes GC while
#   the health check still passes, so the orchestrator never replaces it. Exiting turns a silent
#   degradation into a restart.
#
#   UseContainerSupport is on by default in 21 and stated for the next reader.
#
#   /dev/urandom - SecureRandom blocks on /dev/random in some kernels, and a container with little
#   entropy can hang for minutes at startup. Identifiers.newId() calls SecureRandom on every id.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseContainerSupport \
    -Djava.security.egd=file:/dev/./urandom \
    -Duser.timezone=UTC"

# `-jar application.jar`, NOT JarLauncher.
#
# The two jarmodes produce different layouts and the entry points are not interchangeable. The old
# `layertools` mode exploded the jar into BOOT-INF/ and needed
# org.springframework.boot.loader.launch.JarLauncher to assemble a classpath from it. The `tools`
# mode used here puts a PLAIN jar in the application layer, with a Class-Path manifest pointing at
# the extracted libraries - so the ordinary `-jar` launcher is correct, and JarLauncher would find
# no BOOT-INF to work with and die at container start. Never at build time, so CI stays green.
#
# `exec` matters as much: it replaces the shell, so the JVM becomes PID 1 and receives SIGTERM,
# which Spring Boot turns into a graceful shutdown - in-flight orders finish. Without exec the shell
# holds PID 1, swallows the signal, and the container is SIGKILLed after the grace period with
# requests still running.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]

# Kubernetes uses its own probes and ignores this; it is here for docker compose and for anyone
# running the image by hand.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
