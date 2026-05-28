# Elastic APM — Java 11 + Spring Web MVC

Dois microsserviços de exemplo para testar o **Elastic APM** com Java 11 e Spring Web MVC, incluindo correlação de logs, métricas customizadas e um chaos monkey com k6 para gerar tráfego automático.

---

## Visão geral

```
┌─────────────────────────────────────────────────────────┐
│                     docker compose                       │
│                                                         │
│  ┌──────────────────┐     ┌──────────────────────────┐  │
│  │  order-service   │     │    product-service        │  │
│  │  :8081           │     │    :8082                  │  │
│  │                  │     │                           │  │
│  │  attach          │     │  -javaagent               │  │
│  │  programático    │     │  (JAVA_TOOL_OPTIONS)      │  │
│  └──────────────────┘     └──────────────────────────┘  │
│           ▲                          ▲                   │
│           └──────────┬───────────────┘                   │
│                      │                                   │
│             ┌────────────────┐                           │
│             │   k6 monkey    │  tráfego contínuo +       │
│             │   (chaos)      │  spikes + erros           │
│             └────────────────┘                           │
└─────────────────────────────────────────────────────────┘
          │ traces + logs + métricas
          ▼
   Elastic APM / Elasticsearch
```

### O que cada serviço demonstra

| | order-service | product-service |
|---|---|---|
| **Porta** | 8081 | 8082 |
| **Attach do agente** | Programático (`ElasticApmAttacher`) | `-javaagent` via `JAVA_TOOL_OPTIONS` |
| **Instrumentação** | `@CaptureTransaction`, `@CaptureSpan` | API fluente manual (`startSpan(...)`) |
| **Métricas** | `Counter` (pedidos criados/falhos), `Timer` (latência) | `Counter` (cache hits/misses), `Gauge` (tamanho do cache) |
| **Correlação de logs** | ECS encoder no Logback | ECS encoder no Logback |
| **Erros no APM** | `captureException()` + endpoint `/orders/error` | `captureException()` em preço negativo |

---

## Pré-requisitos

- Docker e Docker Compose v2+
- Uma instância do **Elastic APM Server** acessível (Elastic Cloud, self-managed ou local)
- A **API Key** do seu APM Server

---

## Início rápido

**1. Clone o repositório**

```bash
git clone https://github.com/seu-usuario/elastic-apm-java-examples.git
cd elastic-apm-java-examples
```

**2. Configure o `.env`**

```bash
# Edite as duas variáveis obrigatórias
APM_SERVER_URL=https://SEU-APM-SERVER.apm.us-east-1.aws.elastic.cloud:443
APM_API_KEY=SUA_API_KEY_AQUI
```

**3. Suba tudo**

```bash
docker compose up --build
```

O k6 aguarda os healthchecks das aplicações antes de iniciar. Em ~45s você já verá tráfego chegando no APM UI.

**Subir sem o chaos monkey:**

```bash
docker compose up --build order-service product-service
```

---

## Endpoints disponíveis

### order-service — `http://localhost:8081`

```bash
# Criar pedido (gera transação + spans + logs correlacionados)
curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '{"product": "NOTEBOOK-X1", "quantity": 2}'

# Consultar pedido
curl http://localhost:8081/orders/ABC123

# Forçar erro 500 (aparece na aba Errors do APM)
curl http://localhost:8081/orders/error

# Health
curl http://localhost:8081/actuator/health
```

### product-service — `http://localhost:8082`

```bash
# Buscar produto (span cache → span DB)
curl http://localhost:8082/products/PROD-001
curl http://localhost:8082/products/PROD-002
curl http://localhost:8082/products/PROD-003

# Produto inexistente (404 + métrica notfound)
curl http://localhost:8082/products/PROD-999

# Atualizar preço (span de auditoria)
curl -X PATCH http://localhost:8082/products/PROD-001/price \
  -H "Content-Type: application/json" \
  -d '{"price": 4999.99}'

# Forçar erro de validação (captureException no span)
curl -X PATCH http://localhost:8082/products/PROD-001/price \
  -H "Content-Type: application/json" \
  -d '{"price": -10}'
```

---

## Como a correlação de logs funciona

O agente APM injeta `trace.id` e `transaction.id` no MDC do Logback via `enable_log_correlation=true`. O `EcsEncoder` (configurado no `logback-spring.xml`) serializa esses campos em cada linha de log no formato JSON/ECS:

```json
{
  "@timestamp": "2024-01-15T10:30:00.000Z",
  "log.level": "INFO",
  "message": "Pedido processado orderId=X1B2 paymentId=PAY-X1B2-...",
  "service.name": "order-service",
  "trace.id": "abc123def456...",
  "transaction.id": "def456ghi789...",
  "span.id": "ghi789jkl012..."
}
```

Com o Filebeat apontando para o mesmo Elasticsearch, o APM UI cruza pelo `trace.id` e exibe os logs na aba **Logs** de cada transação.

> **Elastic Cloud / Fleet:** adicione `log_sending_enabled=true` no config do agente (>= 1.45) para enviar logs diretamente sem Filebeat.

---

## Métricas customizadas

As métricas são enviadas ao Elasticsearch via Micrometer (`micrometer-registry-elastic`) no índice configurado em `application.yml`.

| Métrica | Tipo | Serviço |
|---|---|---|
| `orders.created` | Counter | order-service |
| `orders.failed` | Counter | order-service |
| `orders.processing.time` | Timer | order-service |
| `products.search.total` | Counter | product-service |
| `products.cache.hits` | Counter | product-service |
| `products.cache.misses` | Counter | product-service |
| `products.cache.size` | Gauge | product-service |

---

## k6 Chaos Monkey

O serviço `k6-monkey` gera tráfego contínuo e automático com três cenários paralelos:

| Cenário | Taxa | Comportamento |
|---|---|---|
| `steady_traffic` | 5 req/s constante | Mix ponderado: pedidos válidos (40%), buscas (30%), atualizações de preço (15%), 404s esperados (10%), erros forçados (5%) |
| `spike` | Rampa 1→30 req/s a cada ~2 min | Simula pico de carga; visível no gráfico de throughput do APM |
| `chaos` | 1 req a cada 2s | Payloads malformados, IDs inválidos, preços negativos — popula a aba Errors do APM |

Acompanhe o output do k6 em tempo real:

```bash
docker compose logs -f k6-monkey
```

---

## Estrutura do projeto

```
.
├── .env                        # ← edite aqui: APM_SERVER_URL + APM_API_KEY
├── docker-compose.yml
├── k6/
│   └── monkey.js               # script de chaos/load testing
├── example1/                   # order-service
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/apm1/
│       │   ├── OrderServiceApplication.java   # attach programático
│       │   ├── controller/OrderController.java
│       │   └── service/OrderService.java      # @CaptureTransaction, @CaptureSpan, métricas
│       └── resources/
│           ├── application.yml
│           └── logback-spring.xml             # EcsEncoder
└── example2/                   # product-service
    ├── Dockerfile               # ENTRYPOINT com -javaagent
    ├── pom.xml
    └── src/main/
        ├── java/com/example/apm2/
        │   ├── ProductServiceApplication.java
        │   ├── controller/ProductController.java
        │   └── service/ProductService.java    # API fluente, spans manuais, Gauge
        └── resources/
            ├── application.yml
            └── logback-spring.xml
```

---

## Variáveis de ambiente

| Variável | Obrigatória | Padrão | Descrição |
|---|---|---|---|
| `APM_SERVER_URL` | ✅ | — | URL do APM Server |
| `APM_API_KEY` | ✅ | — | API Key do APM Server |
| `APM_ENVIRONMENT` | ❌ | `development` | Nome do ambiente no APM UI |
| `APM_AGENT_VERSION` | ❌ | `1.51.0` | Versão do agente a baixar no build |

---

## Stack

- **Java** 11
- **Spring Boot** 2.7.18 (Spring Web MVC)
- **Elastic APM Agent** 1.51.0
- **Elastic ECS Logging** 1.6.0 (Logback)
- **Micrometer** `micrometer-registry-elastic`
- **k6** latest (grafana/k6)
