# syntax=docker/dockerfile:1.7

# Stage 1: build
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy gradle wrapper + build descriptors first to maximise layer caching.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY server/build.gradle.kts ./server/

# Pre-fetch dependencies (cached unless build files change).
RUN ./gradlew --no-daemon :server:dependencies > /dev/null 2>&1 || true

# Copy sources and build the install distribution.
COPY server ./server
RUN ./gradlew --no-daemon :server:installDist

# Stage 2: runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S krail && adduser -S krail -G krail

COPY --from=builder --chown=krail:krail /app/server/build/install/server /app/

USER krail
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["/app/bin/server"]
