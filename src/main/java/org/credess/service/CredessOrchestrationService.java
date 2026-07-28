package org.credess.service;

import org.credess.client.PythonCredessClient;
import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Main orchestration service combining Python simulation + LLM analysis.
 */
@Service
public class CredessOrchestrationService {

    private final PythonCredessClient pythonClient;
    private final LlmAnalysisService llmService;
    private final ObjectMapper objectMapper;

    public CredessOrchestrationService(
            PythonCredessClient pythonClient,
            LlmAnalysisService llmService,
            ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs simulation and enriches results with LLM analysis.
     */
    public SimulationReport runAndAnalyze(SimulationRequest request) {
        // Step 1: Run Python simulation
        SimulationReport report = pythonClient.runSimulation(request);

        // Step 2: Analyze with LLM
        try {
            String jsonData = objectMapper.writeValueAsString(report.getResults());
            String summary = llmService.analyzeSimulation(jsonData);
            report.setLlmSummary(summary);
        } catch (Exception e) {
            report.setLlmSummary("LLM analysis failed: " + e.getMessage());
        }

        return report;
    }

    /**
     * Checks if Python service is available.
     */
    public boolean isSystemHealthy() {
        return pythonClient.isPythonServiceHealthy();
    }
}