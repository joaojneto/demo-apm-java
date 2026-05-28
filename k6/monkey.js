import http from "k6/http";
import { sleep, check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ─── Métricas customizadas do k6 ─────────────────────────────────────────────
const orderSuccessCount  = new Counter("monkey_orders_success");
const orderErrorCount    = new Counter("monkey_orders_error");
const productHitCount    = new Counter("monkey_product_found");
const productMissCount   = new Counter("monkey_product_notfound");
const errorRate          = new Rate("monkey_error_rate");
const orderLatency       = new Trend("monkey_order_latency_ms", true);
const productLatency     = new Trend("monkey_product_latency_ms", true);

// ─── Perfil de carga ──────────────────────────────────────────────────────────
// Simula um dia de trabalho comprimido em ciclos contínuos:
//   ramp-up → pico → queda → pico de erros → idle
export const options = {
  scenarios: {
    // Tráfego normal constante
    steady_traffic: {
      executor: "constant-arrival-rate",
      rate: 5,              // 5 iterações/s
      timeUnit: "1s",
      duration: "99999h",   // roda indefinidamente (até docker stop)
      preAllocatedVUs: 10,
      maxVUs: 30,
      exec: "normalTraffic",
    },
    // Spike periódico a cada 2 min
    spike: {
      executor: "ramping-arrival-rate",
      startRate: 1,
      timeUnit: "1s",
      stages: [
        { target: 30, duration: "30s" },  // sobe rápido
        { target: 30, duration: "1m" },   // sustenta pico
        { target: 1,  duration: "30s" },  // desce
        { target: 1,  duration: "1m" },   // idle antes do próximo spike
      ],
      preAllocatedVUs: 30,
      maxVUs: 60,
      exec: "normalTraffic",
    },
    // Chaos: erros intencionais periódicos
    chaos: {
      executor: "constant-arrival-rate",
      rate: 1,
      timeUnit: "2s",
      duration: "99999h",
      preAllocatedVUs: 3,
      maxVUs: 5,
      exec: "chaosTraffic",
    },
  },
  // Thresholds — falha o teste se ultrapassar (visível nos logs do k6)
  thresholds: {
    monkey_error_rate:        ["rate < 0.30"],  // aceita até 30% de erro (chaos intencional)
    monkey_order_latency_ms:  ["p(95) < 2000"],
    monkey_product_latency_ms:["p(95) < 1000"],
  },
};

// ─── Dados de teste ───────────────────────────────────────────────────────────
const ORDER_SERVICE   = __ENV.ORDER_SERVICE_URL   || "http://order-service:8081";
const PRODUCT_SERVICE = __ENV.PRODUCT_SERVICE_URL || "http://product-service:8082";

const PRODUCTS = [
  "NOTEBOOK-DELL", "MOUSE-MX", "TECLADO-MECH",
  "MONITOR-4K", "HEADSET-PRO", "WEBCAM-HD",
  "SSD-1TB", "RAM-16GB", "HUB-USB",
];

const VALID_PRODUCT_IDS = ["PROD-001", "PROD-002", "PROD-003"];

const INVALID_PRODUCT_IDS = [
  "PROD-999", "PROD-000", "NAO-EXISTE",
  "PRODUTO-FANTASMA", "", "null", "../../etc/passwd",
];

const INVALID_ORDER_BODIES = [
  '{}',                                          // sem campos
  '{"product": "", "quantity": 0}',              // campos vazios/zerados
  '{"product": "X", "quantity": -5}',            // quantity negativo
  '{"product": null, "quantity": 1}',            // null
  'nao-e-json',                                  // body inválido
  '{"product": "NOTEBOOK", "quantity": 99999}',  // quantidade absurda
];

// ─── Cenário 1: tráfego normal ────────────────────────────────────────────────
export function normalTraffic() {
  const action = weightedRandom([
    { fn: createValidOrder,    weight: 40 },
    { fn: getExistingProduct,  weight: 30 },
    { fn: updateProductPrice,  weight: 15 },
    { fn: getProductNotFound,  weight: 10 },
    { fn: triggerAPMError,     weight:  5 },
  ]);

  action();
  sleep(randomBetween(0.1, 0.8));
}

// ─── Cenário 2: chaos monkey ──────────────────────────────────────────────────
export function chaosTraffic() {
  const action = weightedRandom([
    { fn: sendMalformedOrder,    weight: 35 },
    { fn: hitInvalidProductId,   weight: 35 },
    { fn: updateNegativePrice,   weight: 20 },
    { fn: triggerAPMError,       weight: 10 },
  ]);

  action();
  sleep(randomBetween(0.5, 2.0));
}

// ─── Ações individuais ────────────────────────────────────────────────────────

function createValidOrder() {
  const product  = randomItem(PRODUCTS);
  const quantity = Math.floor(randomBetween(1, 20));
  const payload  = JSON.stringify({ product, quantity });

  const start = Date.now();
  const res = http.post(`${ORDER_SERVICE}/orders`, payload, {
    headers: { "Content-Type": "application/json" },
    tags:    { action: "create_order", type: "normal" },
  });
  orderLatency.add(Date.now() - start);

  const ok = check(res, {
    "order created 200": (r) => r.status === 200,
    "has orderId":       (r) => r.json("orderId") !== undefined,
    "status CONFIRMED":  (r) => r.json("status") === "CONFIRMED",
  });

  ok ? orderSuccessCount.add(1) : orderErrorCount.add(1);
  errorRate.add(!ok);
}

function getExistingProduct() {
  const id = randomItem(VALID_PRODUCT_IDS);

  const start = Date.now();
  const res = http.get(`${PRODUCT_SERVICE}/products/${id}`, {
    tags: { action: "get_product", type: "normal" },
  });
  productLatency.add(Date.now() - start);

  const ok = check(res, {
    "product found 200": (r) => r.status === 200,
    "has name":          (r) => r.json("name") !== undefined,
  });

  ok ? productHitCount.add(1) : productMissCount.add(1);
  errorRate.add(!ok);
}

function updateProductPrice() {
  const id       = randomItem(VALID_PRODUCT_IDS);
  const newPrice = parseFloat(randomBetween(99, 9999).toFixed(2));
  const payload  = JSON.stringify({ price: newPrice });

  const res = http.patch(`${PRODUCT_SERVICE}/products/${id}/price`, payload, {
    headers: { "Content-Type": "application/json" },
    tags:    { action: "update_price", type: "normal" },
  });

  const ok = check(res, {
    "price updated 200 or 404": (r) => r.status === 200 || r.status === 404,
  });
  errorRate.add(!ok);
}

function getProductNotFound() {
  const id = randomItem(INVALID_PRODUCT_IDS);

  const res = http.get(`${PRODUCT_SERVICE}/products/${encodeURIComponent(id)}`, {
    tags: { action: "get_product_miss", type: "expected_error" },
  });

  // 404 é o resultado esperado — não conta como erro de negócio
  const ok = check(res, {
    "404 as expected": (r) => r.status === 404 || r.status === 400,
  });

  productMissCount.add(1);
  errorRate.add(!ok);
}

function triggerAPMError() {
  const res = http.get(`${ORDER_SERVICE}/orders/error`, {
    tags: { action: "force_error", type: "chaos" },
  });

  // Esperamos 500 aqui — é o ponto de teste do APM Errors
  check(res, { "500 error triggered": (r) => r.status === 500 });
  errorRate.add(false); // não conta como falha do teste
}

// ─── Chaos monkey ─────────────────────────────────────────────────────────────

function sendMalformedOrder() {
  const body = randomItem(INVALID_ORDER_BODIES);

  const res = http.post(`${ORDER_SERVICE}/orders`, body, {
    headers: { "Content-Type": "application/json" },
    tags:    { action: "malformed_order", type: "chaos" },
  });

  orderErrorCount.add(1);
  // Qualquer resposta 4xx/5xx é esperada
  check(res, { "rejected malformed": (r) => r.status >= 400 });
  errorRate.add(false);
}

function hitInvalidProductId() {
  const id = randomItem([
    ...INVALID_PRODUCT_IDS,
    "A".repeat(500),          // ID gigante
    "' OR 1=1 --",            // SQL injection attempt
    "<script>alert(1)</script>",
  ]);

  const res = http.get(`${PRODUCT_SERVICE}/products/${encodeURIComponent(id)}`, {
    tags: { action: "invalid_product_id", type: "chaos" },
  });

  productMissCount.add(1);
  check(res, { "handled invalid id": (r) => r.status < 500 });
  errorRate.add(false);
}

function updateNegativePrice() {
  const id      = randomItem(VALID_PRODUCT_IDS);
  const payload = JSON.stringify({ price: -randomBetween(1, 999) });

  const res = http.patch(`${PRODUCT_SERVICE}/products/${id}/price`, payload, {
    headers: { "Content-Type": "application/json" },
    tags:    { action: "negative_price", type: "chaos" },
  });

  // Esperamos 400/500 — dispara o span de auditoria com captureException
  check(res, { "rejected negative price": (r) => r.status >= 400 });
  errorRate.add(false);
}

// ─── Utilitários ──────────────────────────────────────────────────────────────

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randomBetween(min, max) {
  return Math.random() * (max - min) + min;
}

function weightedRandom(items) {
  const total  = items.reduce((sum, i) => sum + i.weight, 0);
  let roll     = Math.random() * total;
  for (const item of items) {
    roll -= item.weight;
    if (roll <= 0) return item.fn;
  }
  return items[items.length - 1].fn;
}
