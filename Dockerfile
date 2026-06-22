# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Resolve dependencies in a cacheable layer (reruns only when Gradle files change)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew \
 && ./gradlew dependencies --no-daemon -q 2>&1 | tail -3 || true

# Build the fat JAR (tests are run separately via CI / make test)
COPY src/ src/
RUN ./gradlew shadowJar --no-daemon -x test \
 && mv build/libs/apollo-billing-*.jar build/libs/app.jar

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

LABEL org.opencontainers.image.title="Apollo Billing"
LABEL org.opencontainers.image.description="Apollo Billing — subscription & entitlement service (Kotlin/Ktor)"
LABEL org.opencontainers.image.version="1.0.0"

# libstdc++ is required for JVM crypto modules (jdk.crypto.ec / jdk.crypto.cryptoki)
# BusyBox wget (already on Alpine) handles the HEALTHCHECK
RUN apk add --no-cache libstdc++

RUN addgroup -S apollo && adduser -S apollo -G apollo

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
