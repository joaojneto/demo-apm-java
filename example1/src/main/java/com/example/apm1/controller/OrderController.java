package com.example.apm1.controller;

import com.example.apm1.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /orders
     * O Elastic APM Agent intercepta automaticamente esta requisição HTTP
     * e cria uma transação com:
     *   - nome: "POST /orders"
     *   - tipo: request
     *   - trace.id injetado no MDC → logs correlacionados
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> body) {
        String orderId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String product = (String) body.getOrDefault("product", "PRODUTO-A");
        int quantity = (int) body.getOrDefault("quantity", 1);

        log.info("Recebida requisição de criação de pedido product={} quantity={}", product, quantity);

        Map<String, Object> result = orderService.processOrder(orderId, product, quantity);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String id) {
        log.info("Consultando pedido orderId={}", id);
        return ResponseEntity.ok(Map.of(
                "orderId", id,
                "status", "CONFIRMED",
                "message", "Pedido encontrado"
        ));
    }

    /**
     * GET /orders/error  — força um erro para ver no APM
     */
    @GetMapping("/error")
    public ResponseEntity<Void> simulateError() {
        log.warn("Endpoint de erro chamado intencionalmente");
        throw new RuntimeException("Erro simulado para teste do APM");
    }
}
