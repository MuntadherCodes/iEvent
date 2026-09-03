# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# warm the dependency cache; tolerate partial failure (full resolve happens in package)
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN useradd --system --home /app ievent
WORKDIR /app
RUN mkdir -p /app/data/uploads && chown -R ievent /app/data
COPY --from=build /workspace/target/ievent.jar app.jar
USER ievent
EXPOSE 8080
# 60% heap leaves room for metaspace, code cache, thread stacks and PDF/QR buffers inside the compose memory limit
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
