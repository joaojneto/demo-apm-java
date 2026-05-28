package com.example.apm2.service;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Scope;
import co.elastic.apm.api.Span;
import co.elastic.apm.api.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final Map<String, Map<String, Object>> productDatabase = new ConcurrentHashMap<>();
    private final AtomicInteger activeCacheSize = new AtomicInteger(0);

    private final Counter productSearchCounter;
    private final Counter productNotFoundCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public ProductService(MeterRegistry meterRegistry) {
        this.productSearchCounter = Counter.builder("products.search.total")
                .description("Total de buscas de produto")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.productNotFoundCounter = Counter.builder("products.notfound.total")
                .description("Produtos não encontrados")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.cacheHitCounter = Counter.builder("products.cache.hits")
                .description("Cache hits de produto")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("products.cache.misses")
                .description("Cache misses de produto")
                .tag("service", "product-service")
                .register(meterRegistry);

        Gauge.builder("products.cache.size", activeCacheSize, AtomicInteger::get)
                .description("Número de produtos no cache")
                .tag("service", "product-service")
                .register(meterRegistry);

        seedDatabase();
    }

    public Map<String, Object> searchProduct(String productId) {
        Transaction transaction = ElasticApm.currentTransaction();
        transaction.addLabel("product_id", productId);
        transaction.setName("GET /products/" + productId);

        log.info("Buscando produto productId={}", productId);
        productSearchCounter.increment();

        // Span 1: verificar cache
        // Scope.close() faz o deactivate — compatível com todas as versões do agente
        Span cacheSpan = ElasticApm.currentSpan()
                .startSpan("app", "cache", "redis-local")
                .setName("cache.get product:" + productId);
        try (Scope cacheScope = cacheSpan.activate()) {
            return checkCache(productId, transaction);
        } finally {
            cacheSpan.end();
        }
    }

    private Map<String, Object> checkCache(String productId, Transaction transaction) {
        sleep(2, 8);

        boolean cacheHit = productDatabase.containsKey(productId) &&
                ThreadLocalRandom.current().nextBoolean();

        if (cacheHit) {
            cacheHitCounter.increment();
            log.debug("Cache HIT para productId={}", productId);
            transaction.addLabel("cache_result", "hit");
            return productDatabase.get(productId);
        }

        cacheMissCounter.increment();
        log.debug("Cache MISS para productId={} — indo ao banco", productId);
        transaction.addLabel("cache_result", "miss");

        // Span 2: busca no banco
        Span dbSpan = ElasticApm.currentSpan()
                .startSpan("db", "postgresql", "query")
                .setName("SELECT products WHERE id=" + productId);
        try (Scope dbScope = dbSpan.activate()) {
            return fetchFromDatabase(productId, transaction);
        } finally {
            dbSpan.end();
        }
    }

    private Map<String, Object> fetchFromDatabase(String productId, Transaction transaction) {
        sleep(20, 100);

        Map<String, Object> product = productDatabase.get(productId);

        if (product == null) {
            productNotFoundCounter.increment();
            transaction.addLabel("found", false);
            log.warn("Produto não encontrado productId={}", productId);
            return null;
        }

        transaction.addLabel("found", true);
        transaction.addLabel("product_name", product.get("name").toString());
        activeCacheSize.incrementAndGet();

        log.info("Produto encontrado productId={} name={} price={}",
                productId, product.get("name"), product.get("price"));

        return product;
    }

    public Map<String, Object> updatePrice(String productId, double newPrice) {
        Transaction transaction = ElasticApm.currentTransaction();
        transaction.addLabel("product_id", productId);
        transaction.addLabel("new_price", newPrice);

        log.info("Atualizando preço productId={} newPrice={}", productId, newPrice);

        Span auditSpan = ElasticApm.currentSpan()
                .startSpan("app", "audit", "price-audit")
                .setName("audit.priceChange");
        try (Scope auditScope = auditSpan.activate()) {
            sleep(5, 15);

            if (newPrice < 0) {
                RuntimeException ex = new IllegalArgumentException("Preço não pode ser negativo: " + newPrice);
                auditSpan.captureException(ex);
                log.error("Preço inválido productId={} newPrice={}", productId, newPrice);
                throw ex;
            }

            log.info("Auditoria aprovada productId={} newPrice={}", productId, newPrice);
        } finally {
            auditSpan.end();
        }

        Map<String, Object> product = productDatabase.computeIfPresent(productId, (k, v) -> {
            v.put("price", newPrice);
            v.put("updatedAt", System.currentTimeMillis());
            return v;
        });

        if (product == null) {
            log.warn("Produto não encontrado para atualização productId={}", productId);
        } else {
            log.info("Preço atualizado com sucesso productId={} newPrice={}", productId, newPrice);
        }

        return product;
    }

    private void seedDatabase() {
        List.of(
                Map.of("id", "PROD-001", "name", "Notebook Dell XPS", "price", 5999.99, "stock", 10),
                Map.of("id", "PROD-002", "name", "Mouse Logitech MX", "price", 349.90, "stock", 50),
                Map.of("id", "PROD-003", "name", "Teclado Mecânico", "price", 599.00, "stock", 25)
        ).forEach(p -> productDatabase.put(p.get("id").toString(), new HashMap<>(p)));
    }

    private void sleep(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}