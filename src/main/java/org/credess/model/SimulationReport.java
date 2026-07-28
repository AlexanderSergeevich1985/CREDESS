package org.credess.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Response DTO from Python service.
 */
@Data
public class SimulationReport {
    private SimulationRequest config;
    private List<Map<String, Object>> results;
    private String llmSummary;
}