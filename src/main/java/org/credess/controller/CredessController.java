package org.credess.controller;

import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import org.credess.service.CredessOrchestrationService;
import org.credess.service.tasks.RedisTaskQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API controller for the CREDESS decentralized multi-agent platform.
 * Exposes endpoints for simulation, CAS task allocation, and system monitoring.
 */
@RestController
@RequestMapping("/api/credess")
public class CredessController {

    private final CredessOrchestrationService orchestrationService;
    private final RedisTaskQueueService redisQueueService;

    public CredessController(CredessOrchestrationService orchestrationService,
                             RedisTaskQueueService redisQueueService) {
        this.orchestrationService = orchestrationService;
        this.redisQueueService = redisQueueService;
    }

    /**
     * Runs a full CREDESS simulation cycle and enriches results with LLM analysis.
     *
     * @param request The simulation parameters (numAgents, numTasks, steps, etc.).
     * @return The simulation report containing execution metrics and LLM summary.
     */
    @PostMapping("/simulate")
    public ResponseEntity<SimulationReport> simulate(
            @RequestBody SimulationRequest request) {
        SimulationReport report = orchestrationService.runAndAnalyze(request);
        return ResponseEntity.ok(report);
    }

    /**
     * Health check endpoint for the entire CREDESS microservices architecture.
     *
     * @return System health status and component availability.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean redisHealthy = true; // Add actual Redis ping check if needed
        boolean pythonServiceHealthy = orchestrationService.isSystemHealthy();

        Map<String, Object> response = new HashMap<>();
        response.put("status", (redisHealthy && pythonServiceHealthy) ? "ok" : "degraded");
        response.put("java_service", "up");
        response.put("python_service", pythonServiceHealthy ? "up" : "down");
        response.put("redis", redisHealthy ? "up" : "down");

        return ResponseEntity.ok(response);
    }

    /**
     * Returns statistics about the sharded Redis ZSET queues.
     * Useful for monitoring task distribution across roles and layers.
     *
     * @return A map of queue keys to their current task counts.
     */
    @GetMapping("/queue-stats")
    public ResponseEntity<Map<String, Object>> getQueueStats() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("queues", redisQueueService.getQueueStats());
        return ResponseEntity.ok(response);
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

    /**
     * Retrieves the current token balance of a specific agent.
     *
     * @param agentId The unique identifier of the agent.
     * @return The agent's current liquidity balance.
     */
    @GetMapping("/agent/{agentId}/balance")
    public ResponseEntity<Map<String, Object>> getAgentBalance(
            @PathVariable String agentId) {

        double balance = redisQueueService.getAgentBalance(agentId);

        Map<String, Object> response = new HashMap<>();
        response.put("agent_id", agentId);
        response.put("balance", balance);

        return ResponseEntity.ok(response);
    }

    /**
     * Tests the atomic CAS (Compare-And-Swap) task allocation mechanism (Eq. 20).
     * Simulates an agent trying to lock a task from a sharded Redis ZSET queue.
     *
     * @param agentId The unique identifier of the agent.
     * @param role The agent's functional role (e.g., "Dev", "QA").
     * @param layer The pipeline layer (e.g., "execution", "verification").
     * @return A string describing the outcome (Success, Race Condition, or Empty Queue).
     */
    @PostMapping("/allocate")
    public ResponseEntity<Map<String, Object>> testCasAllocation(
            @RequestParam String agentId,
            @RequestParam String role,
            @RequestParam String layer) {

        // Create dummy tasks for the CAS test
        var tasks = java.util.List.of(
                new CredessOrchestrationService.TaskDTO("T1", 100.0, true),
                new CredessOrchestrationService.TaskDTO("T2", 150.0, true)
        );

        String result = orchestrationService.executeAllocationCycle(agentId, role, layer, tasks);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("allocation_result", result);
        response.put("current_balance", redisQueueService.getAgentBalance(agentId));

        return ResponseEntity.ok(response);
    }

    /**
     * Calculates and returns the dynamic refill rate for a specific agent.
     */
    @GetMapping("/agent/{agentId}/refill-rate")
    public ResponseEntity<Map<String, Object>> getAgentRefillRate(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "7.0") double paramCountBillions) {

        double refillRate = redisQueueService.calculateDynamicRefillRate(
                agentId, paramCountBillions, true
        );

        Map<String, Object> response = new HashMap<>();
        response.put("agent_id", agentId);
        response.put("parameter_count_b", paramCountBillions);
        response.put("current_balance", redisQueueService.getAgentBalance(agentId));
        response.put("refill_rate", refillRate);
        response.put("formula", "ρi,t = ρbase · exp(−αgov · Liquidity · (Ni + 1))");

        return ResponseEntity.ok(response);
    }

    /**
     * Manually triggers a bucket refill for testing purposes.
     */
    @PostMapping("/agent/{agentId}/refill")
    public ResponseEntity<Map<String, Object>> triggerRefill(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "7.0") double paramCountBillions) {

        double tokensAdded = redisQueueService.refillAgentBucket(
                agentId, paramCountBillions, true, 1.0
        );

        Map<String, Object> response = new HashMap<>();
        response.put("agent_id", agentId);
        response.put("tokens_added", tokensAdded);
        response.put("new_balance", redisQueueService.getAgentBalance(agentId));

        return ResponseEntity.ok(response);
    }

    /**
     * Advances the simulation by one epoch (Δt).
     * Triggers the macroeconomic refill cycle and returns the updated system state.
     * Corresponds to the discrete time-step execution in Section 4.7.
     */
    @PostMapping("/epoch/advance")
    public ResponseEntity<Map<String, Object>> advanceEpoch() {
        // 1. Trigger the refill cycle for all registered agents
        orchestrationService.refillAllActiveAgents();

        // 2. Gather current state for the response
        Map<String, Object> epochState = new HashMap<>();
        epochState.put("timestamp", System.currentTimeMillis());
        epochState.put("message", "Epoch advanced. Token buckets refilled based on Eq. 33.");

        // Add balances of all known agents
        Map<String, Double> balances = new HashMap<>();
        for (String agentId : orchestrationService.getRegisteredAgentIds()) {
            balances.put(agentId, redisQueueService.getAgentBalance(agentId));
        }
        epochState.put("agent_balances", balances);

        return ResponseEntity.ok(epochState);
    }
}