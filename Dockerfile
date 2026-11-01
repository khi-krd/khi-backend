# Multi-stage build — compiles the jar inside Docker so deployments
# never depend on a jar or Dockerfile existing on the host.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so source changes reuse the cached layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
# ffmpeg/ffprobe: faststart remux of uploaded MP4s + automatic file metadata.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /build/target/khi_backend-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60
EXPOSE 8080
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
