# syntax=docker/dockerfile:1

# ---------- 1) build: compila e empacota (testes rodam no CI, não no build da imagem) ----------
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
# dependências em camada própria: só são baixadas de novo quando o pom.xml muda
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/activation-service.jar extract --layers --launcher --destination target/extracted

# ---------- 2) runtime: JRE enxuta, camadas do Spring Boot, usuário sem privilégios ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
# camadas da menos para a mais volátil: rebuild só troca a camada "application"
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER app

# Memória da JVM proporcional ao limite do contêiner; morre rápido em OOM (o orquestrador reinicia).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health/readiness >/dev/null || exit 1

# forma "exec": a JVM é o PID 1 e recebe o SIGTERM → desligamento gracioso
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
