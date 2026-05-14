# UniDrive 360° E2E Test Container
# Build:  docker build -t unidrive-360 -f core/app/e2e-360/Dockerfile .
# Run:    docker run --rm -v ~/.config/unidrive:/creds:ro unidrive-360 \
#           groundtruth -p internxt-test --sync-root /sync --profile dev

FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY .git/ .git/
COPY core/ core/

WORKDIR /build/core
RUN ./gradlew :app:cli-full:shadowJar :app:e2e-360:installDist -q --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# UnidriveRunner.findJar() looks for the fat JAR under
# core/app/cli-full/build/libs relative to CWD.
COPY --from=builder /build/core/app/cli-full/build/libs/unidrive-full-*.jar \
     /opt/e2e-360/core/app/cli-full/build/libs/
COPY --from=builder /build/core/app/e2e-360/build/install/e2e-360/ /opt/e2e-360/
COPY core/app/e2e-360/config/360-config.toml /opt/e2e-360/config/360-config.toml
COPY core/app/e2e-360/docker-entrypoint.sh /opt/e2e-360/docker-entrypoint.sh

WORKDIR /opt/e2e-360

ENV HOME=/tmp
VOLUME /creds
VOLUME /sync

ENTRYPOINT ["/opt/e2e-360/docker-entrypoint.sh"]
