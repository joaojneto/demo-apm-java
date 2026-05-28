# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

COPY --from=builder /build/target/apm-example2-product-service-1.0.0.jar app.jar

# Baixa o agente APM
ARG APM_AGENT_VERSION=1.51.0
ADD https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/${APM_AGENT_VERSION}/elastic-apm-agent-${APM_AGENT_VERSION}.jar /app/elastic-apm-agent.jar

RUN mkdir -p /app/logs

EXPOSE 8082

# O example2 usa -javaagent: todas as configs vêm como -D (system properties)
# passadas via JAVA_TOOL_OPTIONS — lidas automaticamente pela JVM.
ENTRYPOINT ["java", \
  "-javaagent:/app/elastic-apm-agent.jar", \
  "-jar", "app.jar"]
