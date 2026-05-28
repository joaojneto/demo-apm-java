package com.example.apm1;

import co.elastic.apm.attach.ElasticApmAttacher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        /*
         * OPÇÃO A: Attach programático (sem -javaagent na JVM)
         * Útil para testes rápidos sem alterar scripts de startup.
         * Em produção, prefira -javaagent (Opção B no README).
         */
        // Lê variáveis de ambiente injetadas pelo Docker Compose (ou sistema)
        // Fallback para valores locais caso rode fora do Docker
        Map<String, String> apmConfig = new HashMap<>();
        apmConfig.put("service_name", "order-service");
        apmConfig.put("service_version", "1.0.0");
        apmConfig.put("environment",    getEnv("APM_ENVIRONMENT", "development"));
        apmConfig.put("server_url",     getEnv("ELASTIC_APM_SERVER_URL", "http://localhost:8200"));
        apmConfig.put("api_key",        getEnv("ELASTIC_APM_API_KEY", ""));
        apmConfig.put("application_packages", "com.example.apm1");
        apmConfig.put("enable_log_correlation", "true");
        apmConfig.put("capture_body", "all");
        ElasticApmAttacher.attach(apmConfig);

        SpringApplication.run(OrderServiceApplication.class, args);
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
