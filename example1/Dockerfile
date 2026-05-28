# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /build
COPY pom.xml .
# Baixa dependências em camada separada (cache eficiente)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

# Copia o JAR da aplicação
COPY --from=builder /build/target/apm-example1-order-service-1.0.0.jar app.jar

# Baixa o agente APM no build (não precisa de volume externo)
ARG APM_AGENT_VERSION=1.51.0
ADD https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/${APM_AGENT_VERSION}/elastic-apm-agent-${APM_AGENT_VERSION}.jar /app/elastic-apm-agent.jar

# Diretório de logs (montado como volume no compose)
RUN mkdir -p /app/logs

EXPOSE 8081

# O example1 usa attach programático — agente configurado via código Java.
# As variáveis de ambiente são lidas em OrderServiceApplication.java via
# System.getenv(), sobrescrevendo os valores hardcoded.
ENTRYPOINT ["java", "-jar", "app.jar"]
