# syntax=docker/dockerfile:1.7

# Stage 1: build
FROM eclipse-temurin:17-jdk-alpine@sha256:638937c54b6d63f0973a20501973e7c433a36b1f22262bd2b25afa7be5ff8c4a AS builder
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
FROM eclipse-temurin:17-jre-alpine@sha256:02320dd4ce20e243dfb915c686089cf9315c763084fafbb12d5c9993aee18b57
WORKDIR /app

# Build SHA for /health `version`. CI passes --build-arg GIT_SHA=$GITHUB_SHA;
# DO's builder can't (no .git in context) and reports "dev".
ARG GIT_SHA=dev
ENV GIT_SHA=$GIT_SHA

RUN addgroup -S krail && adduser -S krail -G krail

COPY --from=builder --chown=krail:krail /app/server/build/install/server /app/

USER krail
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["/app/bin/server"]
