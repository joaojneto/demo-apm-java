package com.example.apm2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EXEMPLO 2 — Usando -javaagent (abordagem recomendada para produção)
 *
 * Esta aplicação NÃO usa attach programático.
 * O agente é passado como argumento da JVM:
 *
 *   java -javaagent:elastic-apm-agent-1.51.0.jar \
 *        -Delastic.apm.service_name=product-service \
 *        -Delastic.apm.service_version=1.0.0 \
 *        -Delastic.apm.environment=development \
 *        -Delastic.apm.server_url=http://localhost:8200 \
 *        -Delastic.apm.application_packages=com.example.apm2 \
 *        -Delastic.apm.enable_log_correlation=true \
 *        -Delastic.apm.capture_body=all \
 *        -jar apm-example2-product-service-1.0.0.jar
 *
 * Baixe o agente em:
 *   https://search.maven.org/artifact/co.elastic.apm/elastic-apm-agent
 */
@SpringBootApplication
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
