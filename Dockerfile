# Built natively on both Apple Silicon and Oracle's Ampere A1 — both are arm64, so there is
# no cross-compilation step and no --platform flag to remember.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so source edits do not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S thavalon && adduser -S thavalon -G thavalon

WORKDIR /app
COPY --from=build /build/target/thavalon.jar app.jar
RUN mkdir -p /data && chown -R thavalon:thavalon /data /app

USER thavalon

ENV THAVALON_DATA_DIR=/data
# A 1 GB Ampere shape runs this comfortably; SerialGC keeps the footprint down at this size.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
