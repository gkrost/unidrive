# UniDrive Docker Test Container
# Provides hermetic test environment with localfs, MinIO (S3), and SFTP
#
# Build: docker build -t unidrive-test -f docker/Dockerfile .
# Run:   docker run --rm unidrive-test

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build unidrive fat JAR
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy Gradle build files
COPY gradlew ./
COPY settings.gradle.kts ./
COPY build.gradle.kts ./
COPY gradle/ ./gradle/

# Copy all modules
COPY core/ ./core/
COPY sync/ ./sync/
COPY cli/ ./cli/
COPY xtra/ ./xtra/
COPY provider-localfs/ ./provider-localfs/
COPY provider-s3/ ./provider-s3/
COPY provider-sftp/ ./provider-sftp/
COPY provider-webdav/ ./provider-webdav/
COPY provider-rclone/ ./provider-rclone/
COPY provider-onedrive/ ./provider-onedrive/
COPY provider-hidrive/ ./provider-hidrive/
COPY provider-internxt/ ./provider-internxt/

# Build fat JAR
RUN ./gradlew :cli:shadowJar -q --no-daemon

# Output JAR location for next stage
RUN ls -la cli/build/libs/unidrive-*.jar

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime with test infrastructure
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    openssh-client \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install MinIO client (mc) separately
RUN curl -sSL https://dl.min.io/client/mc/release/linux-amd64/mc -o /usr/local/bin/mc && chmod +x /usr/local/bin/mc

# Copy built JAR
COPY --from=builder /build/cli/build/libs/unidrive-*.jar /opt/unidrive/unidrive.jar

# Copy test scripts
COPY docker/test-matrix.sh /opt/unidrive/test-matrix.sh
RUN chmod +x /opt/unidrive/test-matrix.sh

WORKDIR /opt/unidrive

ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED"
ENV JAR_PATH="/opt/unidrive/unidrive.jar"

ENTRYPOINT ["/opt/unidrive/test-matrix.sh"]