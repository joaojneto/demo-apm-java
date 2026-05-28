package com.example.apm1.service;

import co.elastic.apm.api.CaptureSpan;
import co.elastic.apm.api.CaptureTransaction;
import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // Métricas customizadas via Micrometer
    private final Counter ordersCreatedCounter;
    private final Counter ordersFailedCounter;
    private final Timer orderProcessingTimer;

    public OrderService(MeterRegistry meterRegistry) {
        this.ordersCreatedCounter = Counter.builder("orders.created")
                .description("Total de pedidos criados com sucesso")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.ordersFailedCounter = Counter.builder("orders.failed")
                .description("Total de pedidos com falha")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.orderProcessingTimer = Timer.builder("orders.processing.time")
                .description("Tempo de processamento de pedidos")
                .tag("service", "order-service")
                .register(meterRegistry);
    }

    /**
     * @CaptureTransaction cria uma nova transação APM manualmente.
     * Útil para operações que não são HTTP (ex: batch, consumers).
     * Para endpoints HTTP o agente já cria automaticamente.
     */
    @CaptureTransaction(value = "processOrder", type = "business")
    public Map<String, Object> processOrder(String orderId, String product, int quantity) {

        // O trace.id já está no MDC graças ao enable_log_correlation=true
        // Os logs abaixo aparecem CORRELACIONADOS na aba Logs do APM
        log.info("Iniciando processamento do pedido orderId={} product={} quantity={}",
                orderId, product, quantity);

        return orderProcessingTimer.record(() -> {
            try {
                // Label customizado aparece nos detalhes da transação no APM UI
                Transaction currentTransaction = ElasticApm.currentTransaction();
                currentTransaction.addLabel("order_id", orderId);
                currentTransaction.addLabel("product", product);
                currentTransaction.addLabel("quantity", quantity);

                // Simula sub-operações como spans separados
                validateOrder(orderId, product, quantity);
                Map<String, Object> inventory = checkInventory(product, quantity);
                String paymentId = processPayment(orderId, quantity);

                ordersCreatedCounter.increment();

                Map<String, Object> result = new HashMap<>();
                result.put("orderId", orderId);
                result.put("status", "CONFIRMED");
                result.put("paymentId", paymentId);
                result.put("inventory", inventory);

                log.info("Pedido processado com sucesso orderId={} paymentId={}", orderId, paymentId);
                return result;

            } catch (Exception e) {
                ordersFailedCounter.increment();
                // Captura a exceção na transação APM
                ElasticApm.currentTransaction().captureException(e);
                log.error("Falha ao processar pedido orderId={} error={}", orderId, e.getMessage(), e);
                throw e;
            }
        });
    }

    @CaptureSpan(value = "validateOrder", type = "app", subtype = "validation")
    public void validateOrder(String orderId, String product, int quantity) {
        log.debug("Validando pedido orderId={}", orderId);

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId não pode ser vazio");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity deve ser maior que zero");
        }

        // Simula latência de validação
        sleep(10, 30);
        log.debug("Pedido válido orderId={}", orderId);
    }

    @CaptureSpan(value = "checkInventory", type = "app", subtype = "inventory")
    public Map<String, Object> checkInventory(String product, int quantity) {
        log.debug("Verificando estoque product={} quantity={}", product, quantity);

        sleep(20, 80);

        int available = ThreadLocalRandom.current().nextInt(quantity, quantity + 100);

        // Label no span atual
        ElasticApm.currentSpan().addLabel("available_stock", available);

        Map<String, Object> inventory = new HashMap<>();
        inventory.put("product", product);
        inventory.put("requested", quantity);
        inventory.put("available", available);
        inventory.put("reserved", quantity);

        log.info("Estoque reservado product={} requested={} available={}", product, quantity, available);
        return inventory;
    }

    @CaptureSpan(value = "processPayment", type = "ext", subtype = "payment-gateway")
    public String processPayment(String orderId, int quantity) {
        log.debug("Processando pagamento orderId={}", orderId);

        sleep(50, 150);

        // Simula falha ocasional de pagamento (10% das vezes)
        if (ThreadLocalRandom.current().nextInt(10) == 0) {
            throw new RuntimeException("Gateway de pagamento indisponível");
        }

        String paymentId = "PAY-" + orderId.toUpperCase() + "-" + System.currentTimeMillis();
        log.info("Pagamento aprovado orderId={} paymentId={}", orderId, paymentId);
        return paymentId;
    }

    private void sleep(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
