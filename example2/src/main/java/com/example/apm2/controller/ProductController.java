package com.example.apm2.controller;

import com.example.apm2.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * GET /products/{id}
     * O agente APM cria automaticamente a transação HTTP.
     * trace.id é injetado no MDC → logs correlacionados.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getProduct(@PathVariable String id) {
        log.info("GET /products/{} recebido", id);

        Map<String, Object> product = productService.searchProduct(id);

        if (product == null) {
            log.warn("Produto não encontrado na resposta productId={}", id);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(product);
    }

    /**
     * PATCH /products/{id}/price
     */
    @PatchMapping("/{id}/price")
    public ResponseEntity<?> updatePrice(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        double newPrice = ((Number) body.get("price")).doubleValue();
        log.info("PATCH /products/{}/price newPrice={}", id, newPrice);

        Map<String, Object> updated = productService.updatePrice(id, newPrice);

        if (updated == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(updated);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "product-service"));
    }
}
