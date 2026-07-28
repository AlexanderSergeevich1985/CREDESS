package org.credess.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for simulation.
 */
@Data
public class SimulationRequest {
    private int numAgents = 5;
    private int numTasks = 10;
    private int seed = 42;
    private int steps = 3;
    private List<Map<String, Object>> agents;
    private List<Map<String, Object>> tasks;
}