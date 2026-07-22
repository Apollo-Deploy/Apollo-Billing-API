# syntax=docker/dockerfile:1.7

# ─────────────────────────────────────────────────────────────────────────────
# Apollo Billing — multi-stage image
#
# Targets:
#   dev         Local development with Compose Watch
#   production  Minimal runtime image with a custom Java runtime
#
# Build:
#   docker compose build billing
#
#   docker build \
#     --target production \
#     --build-arg BUILD_VERSION=1.0.0 \
#     --build-arg BUILD_REVISION="$(git rev-parse HEAD)" \
#     --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
#     -t apollo-billing .
#
# Requires BuildKit for cache mounts and COPY --chmod/--chown.
# ─────────────────────────────────────────────────────────────────────────────

ARG JAVA_VERSION=21
ARG ALPINE_VERSION=3.21


# ── Shared Gradle base ───────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS gradle-base

ENV GRADLE_USER_HOME=/root/.gradle

WORKDIR /build

RUN apk add --no-cache git

# Copy build metadata before application source so dependency resolution remains
# cached until Gradle configuration changes.
COPY --chmod=755 gradlew ./
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Keep container-specific Gradle settings outside the repository files.
#
# Repository gradle.properties may contain a machine-local org.gradle.java.home,
# but the higher-priority GRADLE_USER_HOME configuration below ensures the
# container JDK and container-specific memory limits are used.
RUN mkdir -p "${GRADLE_USER_HOME}" && \
    cat > "${GRADLE_USER_HOME}/gradle.properties" <<'EOF'
org.gradle.configuration-cache=false
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx1g
EOF


# ── Resolve dependencies ─────────────────────────────────────────────────────
FROM gradle-base AS dependencies

ARG GRADLE_VERBOSE=0

RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    set -eu; \
    if [ "${GRADLE_VERBOSE}" = "1" ]; then \
        ./gradlew dependencies \
            --configuration runtimeClasspath \
            --no-daemon \
            --stacktrace \
            --console=plain; \
    else \
        ./gradlew dependencies \
            --configuration runtimeClasspath \
            --no-daemon \
            --console=plain \
            --quiet; \
    fi


# ── Build application ────────────────────────────────────────────────────────
FROM dependencies AS build

ARG GRADLE_VERBOSE=0
ARG BUILD_VERSION=0.0.0
ARG BUILD_REVISION=unknown
ARG BUILD_DATE=unknown

# Available to build tasks through System.getenv().
ENV BUILD_VERSION=${BUILD_VERSION} \
    BUILD_REVISION=${BUILD_REVISION} \
    BUILD_DATE=${BUILD_DATE}

COPY src ./src

RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    set -eu; \
    if [ "${GRADLE_VERBOSE}" = "1" ]; then \
        ./gradlew shadowJar \
            --no-daemon \
            --build-cache \
            --stacktrace \
            --console=plain \
            -x test; \
    else \
        ./gradlew shadowJar \
            --no-daemon \
            --build-cache \
            --console=plain \
            --quiet \
            -x test; \
    fi; \
    test -s build/libs/app.jar


# ── Development image ────────────────────────────────────────────────────────
FROM gradle-base AS dev

ENV APP_ENV=development \
    PORT=3040

# wget is already provided by BusyBox in Alpine. No extra package is needed
# solely for the health check.
COPY src ./src

EXPOSE 3040

HEALTHCHECK \
    --interval=15s \
    --timeout=5s \
    --start-period=60s \
    --retries=5 \
    CMD wget -q --spider "http://127.0.0.1:3040/health" || exit 1

STOPSIGNAL SIGTERM

CMD [
    "./gradlew",
    "run",
    "--no-daemon",
    "--console=plain",
    "-Pio.ktor.development=true"
]


# ── Detect required Java modules ─────────────────────────────────────────────
FROM build AS module-analysis

RUN set -eu; \
    detected_modules="$( \
        jdeps \
            --ignore-missing-deps \
            --multi-release 21 \
            --print-module-deps \
            /build/build/libs/app.jar \
    )"; \
    service_modules="\
java.instrument,\
java.management,\
jdk.management,\
jdk.crypto.ec,\
jdk.crypto.cryptoki,\
jdk.naming.dns,\
jdk.unsupported"; \
    printf '%s,%s\n' "${detected_modules}" "${service_modules}" \
        | tr ',' '\n' \
        | sed '/^$/d' \
        | sort -u \
        | paste -sd, - \
        > /tmp/java-modules; \
    test -s /tmp/java-modules; \
    echo "Java modules:"; \
    cat /tmp/java-modules


# ── Build custom Java runtime ────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS jlink

COPY --from=module-analysis /tmp/java-modules /tmp/java-modules

RUN jlink \
        --module-path "${JAVA_HOME}/jmods" \
        --add-modules "$(cat /tmp/java-modules)" \
        --bind-services \
        --strip-debug \
        --no-header-files \
        --no-man-pages \
        --compress=2 \
        --output /opt/jre && \
    /opt/jre/bin/java -version


# ── Production runtime ───────────────────────────────────────────────────────
FROM alpine:${ALPINE_VERSION} AS production

ARG BUILD_VERSION=0.0.0
ARG BUILD_REVISION=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="Apollo Billing" \
      org.opencontainers.image.description="Apollo Billing subscription and entitlement service" \
      org.opencontainers.image.vendor="Apollo Deploy" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/Apollo-Deploy/apollo-billing-api"

# ca-certificates is required for outbound TLS connections.
# libstdc++ supports native JVM components and crypto providers.
# tzdata supports named time zones used by billing periods and schedules.
RUN apk add --no-cache \
        ca-certificates \
        libstdc++ \
        tzdata && \
    addgroup \
        -S \
        -g 10001 \
        apollo && \
    adduser \
        -S \
        -D \
        -H \
        -u 10001 \
        -G apollo \
        apollo

ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:${PATH} \
    PORT=3040 \
    JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

COPY --from=jlink \
    --chown=root:root \
    /opt/jre \
    /opt/jre

WORKDIR /app

COPY --from=build --chown=apollo:apollo /build/build/libs/app.jar /app/app.jar

USER apollo

EXPOSE 3040

HEALTHCHECK \
    --interval=15s \
    --timeout=5s \
    --start-period=30s \
    --retries=3 \
    CMD wget -q --spider "http://127.0.0.1:3040/health" || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-cp", "/app/app.jar"]
CMD ["com.apollodeploy.billing.BillingApplicationKt"]
