package org.credess.controller;

import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import org.credess.model.artifact.ArtifactPassport;
import org.credess.service.CredessOrchestrationService;
import org.credess.service.artifact.ArtifactPassportService;
import org.credess.service.tasks.RedisTaskQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
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
    private final ArtifactPassportService passportService;

    public CredessController(CredessOrchestrationService orchestrationService,
                             RedisTaskQueueService redisQueueService,
                             ArtifactPassportService passportService) {
        this.orchestrationService = orchestrationService;
        this.redisQueueService = redisQueueService;
        this.passportService = passportService;
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

    /**
     * Returns macroeconomic metrics regarding the deflationary stabilizing shock.
     * Tracks the total amount of invariant transaction fees (δt) burned system-wide.
     * Corresponds to the anti-spam and sybil-protection metrics in Section 5.2.3.
     *
     * @return A map containing the total burned fees and the current invariant fee value.
     */
    @GetMapping("/metrics/deflation")
    public ResponseEntity<Map<String, Object>> getDeflationMetrics() {
        Map<String, Object> response = new HashMap<>();
        response.put("invariant_fee_delta_t", 2.0);
        response.put("total_burned_fees", redisQueueService.getTotalBurnedFees());
        response.put("mechanism", "Deflationary stabilizing shock (Eq. 34)");

        return ResponseEntity.ok(response);
    }

    /**
     * Allows an agent to lease a tool from the infrastructure registry.
     * Triggers the economic friction mechanism and deducts the cost (Eq. 16).
     */
    @PostMapping("/agent/{agentId}/lease-tool")
    public ResponseEntity<Map<String, Object>> leaseTool(
            @PathVariable String agentId,
            @RequestParam String toolId) {

        String result = orchestrationService.procureTool(agentId, toolId);

        Map<String, Object> response = new HashMap<>();
        response.put("status", result.startsWith("SUCCESS") ? "success" : "failed");
        response.put("message", result);
        response.put("current_balance", redisQueueService.getAgentBalance(agentId));

        return ResponseEntity.ok(response);
    }

    /**
     * Lists all available tools in the infrastructure registry and their procurement fees.
     */
    @GetMapping("/tools/registry")
    public ResponseEntity<Map<String, Object>> getToolRegistry() {
        // For simplicity, returning a static map. In production, fetch from Redis Hash.
        Map<String, Double> tools = new HashMap<>();
        tools.put("docker-sandbox", 15.0);
        tools.put("sql-compiler", 10.0);
        tools.put("json-parser", 5.0);
        tools.put("git-plugin", 20.0);

        Map<String, Object> response = new HashMap<>();
        response.put("available_tools", tools);
        response.put("mechanism", "Dynamic Tool Procurement Economy (Eq. 16)");

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the Artifact Passport for a specific task.
     * Provides full tactical context for replacement agents or monitoring dashboards.
     */
    @GetMapping("/task/{taskId}/passport")
    public ResponseEntity<Map<String, Object>> getTaskPassport(@PathVariable String taskId) {
        ArtifactPassport passport = orchestrationService.getTaskPassport(taskId);

        if (passport == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("passport_id", passport.getPassportId());
        response.put("task_id", passport.getTaskId());
        response.put("current_agent_id", passport.getCurrentAgentId());
        response.put("status", passport.getStatus().name());
        response.put("iteration_count", passport.getIterationCount());
        response.put("max_iterations", passport.getMaxIterations());
        response.put("total_tokens_consumed", passport.getTotalTokensConsumed());
        response.put("average_quality_score", passport.getAverageQualityScore());
        response.put("docker_logs", passport.getDockerLogs());
        response.put("functional_errors", passport.getFunctionalErrors());
        response.put("semantic_feedback", passport.getSemanticFeedback());
        response.put("quality_vector", passport.getQualityVector());
        response.put("created_at", passport.getCreatedAt());
        response.put("updated_at", passport.getUpdatedAt());

        return ResponseEntity.ok(response);
    }

    /**
     * Lists all tasks that have active Artifact Passports.
     */
    @GetMapping("/passports/active")
    public ResponseEntity<Map<String, Object>> getActivePassports() {
        List<String> taskIds = passportService.getAllPassportTaskIds();

        Map<String, Object> response = new HashMap<>();
        response.put("active_passports_count", taskIds.size());
        response.put("task_ids", taskIds);

        return ResponseEntity.ok(response);
    }
}