# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# Apollo Billing — multi-stage image
#
#   Stage 1 (build)   compile the fat JAR with Gradle, cached across builds.
#   Stage 2 (jlink)   assemble a minimal custom JRE with only the modules used.
#   Stage 3 (runtime) bare Alpine + the custom JRE + the JAR. No JDK, no Gradle.
#
# Build:   docker compose build billing      (recommended)
#          docker build -t apollo-billing .
# Requires BuildKit (default on Docker 24+) for the --mount=type=cache layers.
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

# Set GRADLE_VERBOSE=1 to show full Gradle output during the build (default: quiet).
ARG GRADLE_VERBOSE=0

WORKDIR /build

# git is required by build-info tasks (rev-parse --short HEAD).
RUN apk add --no-cache git

# Resolve dependencies in a cacheable layer (reruns only when Gradle files change)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# Strip the local macOS JDK path so Gradle uses the container's JDK, and disable
# the configuration cache (never reusable across Docker builds — only adds
# "Calculating task graph…" overhead).
RUN sed -i \
      -e '/^org\.gradle\.java\.home=/d' \
      -e '/^org\.gradle\.configuration-cache=/d' \
      -e '/^org\.gradle\.jvmargs=/d' \
      gradle.properties \
 && echo 'org.gradle.configuration-cache=off' >> gradle.properties \
 && echo 'org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError' >> gradle.properties

# Cache mounts persist the Gradle home + build cache across builds.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    chmod +x gradlew && \
    if [ "${GRADLE_VERBOSE}" = "1" ]; then \
        ./gradlew dependencies --configuration runtimeClasspath --no-daemon; \
    else \
        ./gradlew dependencies --configuration runtimeClasspath --no-daemon -q 2>&1 | tail -3 || true; \
    fi

# Build the fat JAR (tests run separately via CI / make test)
COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.gradle/build-cache \
    if [ "${GRADLE_VERBOSE}" = "1" ]; then \
        ./gradlew shadowJar --no-daemon --build-cache -x test; \
    else \
        ./gradlew shadowJar --no-daemon --build-cache -x test -q; \
    fi && \
    mv build/libs/apollo-billing-*.jar build/libs/app.jar

# ── Stage 2: custom JRE via jlink ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS jlink

# Modules required by Ktor/Netty + PostgreSQL + Micrometer.
# jdk.unsupported is mandatory for Netty's Unsafe usage.
RUN jlink \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --strip-debug \
    --add-modules java.base,java.logging,java.naming,java.net.http,java.security.jgss,java.sql,java.instrument,java.management,jdk.management,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.unsupported \
    --output /jre-minimal

# ── Stage 3: runtime (bare Alpine + custom JRE) ──────────────────────────────
FROM alpine:3.21

# Build metadata — passed via --build-arg and surfaced as OCI labels.
ARG BUILD_VERSION=1.0.0
ARG BUILD_REVISION=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="Apollo Billing" \
      org.opencontainers.image.description="Apollo Billing — subscription & entitlement service (Kotlin/Ktor)" \
      org.opencontainers.image.vendor="Apollo Deploy" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/Apollo-Deploy/apollo-billing-api"

# libstdc++ is required for JVM crypto modules (jdk.crypto.ec / jdk.crypto.cryptoki)
# BusyBox wget (already on Alpine) handles the HEALTHCHECK
RUN apk add --no-cache libstdc++ \
 && addgroup -S apollo \
 && adduser  -S apollo -G apollo

ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"

COPY --from=jlink /jre-minimal $JAVA_HOME

WORKDIR /app

COPY --from=build /build/build/libs/app.jar app.jar

ENV MAIN_CLASS=com.apollodeploy.billing.BillingApplicationKt

USER apollo

EXPOSE 3040

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
    CMD wget -qO /dev/null http://localhost:3040/health || exit 1

ENTRYPOINT ["sh", "-c", \
    "exec java \
    -XX:+UseZGC \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -cp app.jar \
    $MAIN_CLASS"]
