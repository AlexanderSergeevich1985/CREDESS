package org.credess.controller;

import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import org.credess.service.CredessOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API controller for CREDESS platform.
 */
@RestController
@RequestMapping("/api/credess")
public class CredessController {

    private final CredessOrchestrationService orchestrationService;

    public CredessController(CredessOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * Runs simulation with LLM analysis.
     */
    @PostMapping("/simulate")
    public ResponseEntity<SimulationReport> simulate(
            @RequestBody SimulationRequest request) {
        SimulationReport report = orchestrationService.runAndAnalyze(request);
        return ResponseEntity.ok(report);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean pythonHealthy = orchestrationService.isSystemHealthy();
        return ResponseEntity.ok(Map.of(
                "status", pythonHealthy ? "ok" : "degraded",
                "pythonService", pythonHealthy ? "up" : "down",
                "javaService", "up"
        ));
    }

    /**
     * Get LLM suggestions for parameters.
     */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, String>> suggest(
            @RequestBody String previousResults) {
        // Implementation would call llmService.suggestParameters()
        return ResponseEntity.ok(Map.of("suggestion", "Coming soon"));
    }
}