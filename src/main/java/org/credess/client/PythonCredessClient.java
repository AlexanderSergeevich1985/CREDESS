package org.credess.client;

import org.credess.model.SimulationRequest;
import org.credess.model.SimulationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP client for calling the Python CREDESS FastAPI service.
 */
@Service
public class PythonCredessClient {

    private final WebClient webClient;

    public PythonCredessClient(
            @Value("${credess.python.service-url}") String serviceUrl,
            WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(serviceUrl)
                .build();
    }

    /**
     * Runs simulation on Python service.
     */
    public SimulationReport runSimulation(SimulationRequest request) {
        return webClient.post()
                .uri("/simulate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SimulationReport.class)
                .block();  // In production, use reactive flow
    }

    /**
     * Health check for Python service.
     */
    public boolean isPythonServiceHealthy() {
        try {
            String response = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return response != null && response.contains("ok");
        } catch (Exception e) {
            return false;
        }
    }
}