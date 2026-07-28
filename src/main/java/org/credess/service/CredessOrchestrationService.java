package org.credess.service;

import org.credess.client.PythonCredessClient;
import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.credess.model.artifact.ArtifactPassport;
import org.credess.model.validator.ValidationResult;
import org.credess.service.artifact.ArtifactPassportService;
import org.credess.service.demotion.ProgressiveDemotionService;
import org.credess.service.tasks.RedisTaskQueueService;
import org.credess.service.tools.ToolRegistryService;
import org.credess.service.validator.TripleGreenLightValidator;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main orchestration service for the CREDESS framework.
 * Main orchestration service combining Python simulation + LLM analysis.
 * Implements the decentralized task allocation logic described in Section 4.4:
 * - Sharded Redis ZSET Queues
 * - Vectorized Load Balancing (Eq. 17)
 * - Atomic CAS Task Locking (Eq. 20)
 * - Transaction Fee Burning (Anti-spam mechanism)
 */
@Service
public class CredessOrchestrationService {
    // Hyperparameters from Eq. 17
    private static final double ALPHA = 1.0; // Base reward weight
    private static final double BETA = 0.5;  // Load-balancing penalty weight

    // Invariant transaction fee δt (Eq. 20)
    private static final double TRANSACTION_FEE = 5.0;

    // Local interaction radius rfield (Eq. 18)
    private static final double R_FIELD = 2.0;

    // Ni for each agent
    private final Map<String, Double> agentParameterCounts;

    private final RedisTaskQueueService redisQueueService;
    private final PythonCredessClient pythonClient;
    private final LlmAnalysisService llmService;
    private final TripleGreenLightValidator validator;
    private final ProgressiveDemotionService demotionService;
    private int failureCount = 0;
    private final ToolRegistryService toolRegistryService;
    private final ArtifactPassportService passportService;
    private final ObjectMapper objectMapper;

    public CredessOrchestrationService(
            PythonCredessClient pythonClient,
            LlmAnalysisService llmService,
            RedisTaskQueueService redisQueueService,
            TripleGreenLightValidator validator,
            ProgressiveDemotionService demotionService,
            ToolRegistryService toolRegistryService,
            ArtifactPassportService passportService,
            ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.llmService = llmService;
        this.redisQueueService = redisQueueService;
        this.validator = validator;
        this.demotionService = demotionService;
        this.toolRegistryService = toolRegistryService;
        this.passportService = passportService;
        this.objectMapper = objectMapper;

        this.agentParameterCounts = new HashMap<>();

        // Initialize with example parameter counts (in billions)
        // In production, this would come from agent registration
        this.agentParameterCounts.put("Agent_A1", 7.0);
        this.agentParameterCounts.put("Agent_A2", 13.0);
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

    /**
     * Executes a single task allocation cycle for an agent.
     *
     * @param agentId The unique identifier of the agent.
     * @param role The agent's current functional role (e.g., "Dev").
     * @param layer The agent's current pipeline layer (e.g., "execution").
     * @param availableTasks List of tasks that passed the ABAC mask and DAG filter.
     * @return A string describing the outcome of the allocation cycle.
     */
    public String executeAllocationCycle(String agentId, String role, String layer, List<TaskDTO> availableTasks) {

        if (availableTasks == null || availableTasks.isEmpty()) {
            return String.format("Agent %s: No available tasks in queue for role %s in layer %s.", agentId, role, layer);
        }

        // 1. Populate Sharded Redis ZSET Queue (Eq. 17, 18)
        // In a real system, priority scores are calculated using vectorized GPU math (cublasGemmEx).
        // Here we simulate the priority score based on budget and a simplified load-balancing penalty.
        for (TaskDTO task : availableTasks) {
            double priorityScore = calculatePriorityScore(task, agentId);
            redisQueueService.addTaskToQueue(role, layer, task.getTaskId(), priorityScore);
        }

        // 2. Extract optimal task identifier j* via logarithmic retrieval O(log Blocal) (Eq. 19)
        Set<ZSetOperations.TypedTuple<Object>> topTasks = redisQueueService.getTopTasks(role, layer, 1);

        if (topTasks == null || topTasks.isEmpty()) {
            return String.format("Agent %s: ZSET queue is empty after filtering.", agentId);
        }

        String targetTaskId = topTasks.iterator().next().getValue().toString();

        // 3. Dispatch asynchronous lock request via atomic hardware CAS instruction (Eq. 20)
        // State_j* <- CAS(Available, Blocked, X_i)
        boolean casSuccess = redisQueueService.attemptCasLock(targetTaskId, agentId, 3600);

        if (casSuccess) {
            // 4. CAS Transaction Succeeded
            // Burn invariant transaction fee δt from agent's liquid balance (Eq. 20, 34).
            // This acts as an anti-spam cost regulator and deflationary stabilizing shock.
            double newBalance = redisQueueService.burnInvariantTransactionFee(agentId);

            // Simulate task execution and release lock afterward
            String simulatedArtifact = "print('Hello from agent " + agentId + "')";
            ValidationResult validationResult = executeTaskWithValidation(targetTaskId, simulatedArtifact, agentId);

            boolean executionSuccess = validationResult.isPassed();
            redisQueueService.releaseLock(targetTaskId, agentId);

            if (executionSuccess) {
                return String.format("SUCCESS: Agent %s locked task %s. Invariant fee δt=%.2f burned. New balance: %.2f. Quality: %.2f",
                        agentId, targetTaskId, 2.0, newBalance, validationResult.getQualityScore());
            } else {
                return String.format("EXECUTION FAILED: Agent %s locked task %s but failed validation. Fee δt already burned (Eq. 20). Error: %s. Balance: %.2f",
                        agentId, targetTaskId, validationResult.getErrorMessage(), newBalance);
            }
        } else {
            // 5. CAS Transaction Failed (Race Condition)
            // The transaction aborts WITHOUT penalty, and the agent targets the next fallback task.
            return String.format("RACE CONDITION: Agent %s failed to lock task %s. No penalty applied. Targeting fallback...",
                    agentId, targetTaskId);
        }
    }

    /**
     * Calculates the priority score for a task (Eq. 17).
     * Priority(j) = α * Bj - β * load_balancing_penalty
     *
     * Note: In the full architecture, the load-balancing penalty is computed
     * via fused element-wise Triton kernels in VRAM. Here we use a simplified Java approximation.
     */
    private double calculatePriorityScore(TaskDTO task, String agentId) {
        double baseReward = task.getBudget();

        // Simplified load-balancing penalty (simulating the exponential repulsion from Eq. 2 and 17)
        // In production, this would query the Redis ZSET to see how many agents are competing for this task.
        double competitionFactor = redisQueueService.getAgentBalance(agentId) / 100.0;
        double loadPenalty = BETA * Math.exp(-competitionFactor);

        return (ALPHA * baseReward) - loadPenalty;
    }

    /**
     * Executes the Triple Green Light validation cascade with Artifact Passport tracking.
     * Implements the bounded iterative execution loop (Section 4.6).
     *
     * If validation fails, the agent enters a closed self-correction loop,
     * with each iteration logged into the passport until MaxIt is reached.
     */
    public ValidationResult executeTaskWithValidation(String taskId, String artifact, String agentId) {
        // 1. Create or retrieve the Artifact Passport
        ArtifactPassport passport;
        if (!passportService.hasPassport(taskId)) {
            passport = passportService.createPassport(taskId, agentId);
        } else {
            passport = passportService.getPassport(taskId);
            passportService.transferToAgent(taskId, agentId);
        }

        // 2. Check iteration limit (MaxIt) — hard ticket failure condition
        if (passport.isIterationLimitExceeded()) {
            passportService.finalizePassport(taskId, ArtifactPassport.PassportStatus.FAILED_ITERATION_LIMIT);
            return ValidationResult.failure(
                    "MaxIt exceeded (" + passport.getMaxIterations() + "). " +
                            "Passport contains " + passport.getDockerLogs().size() + " Docker errors, " +
                            passport.getFunctionalErrors().size() + " functional errors, " +
                            passport.getSemanticFeedback().size() + " semantic feedbacks."
            );
        }

        // 3. Increment iteration counter for this self-correction cycle
        passportService.incrementIteration(taskId);

        // 4. Execute the Triple Green Light cascade (with automatic passport logging)
        ValidationResult result = validator.executeCascade(taskId, artifact);

        // 5. Update passport based on outcome
        if (result.isPassed()) {
            passportService.finalizePassport(taskId, ArtifactPassport.PassportStatus.PASSED);
        } else {
            // Log token consumption for this failed iteration
            passportService.addTokensConsumed(taskId, 10.0); // Example token cost per iteration
        }

        return result;
    }

    /**
     * Periodically refills all active agent buckets based on dynamic refill rates.
     * Runs every 1000 milliseconds (1 second) to simulate the passage of time (Δt).
     * Corresponds to the macroeconomic regulation cycle in Section 4.7 (Eq. 34).
     */
    @Scheduled(fixedRate = 1000) // Executes every 1 second
    public void refillAllActiveAgents() {
        for (Map.Entry<String, Double> entry : agentParameterCounts.entrySet()) {
            String agentId = entry.getKey();
            double paramCount = entry.getValue();

            // Assume sandbox is valid unless marked otherwise
            boolean sandboxValid = true;

            double tokensAdded = redisQueueService.refillAgentBucket(
                    agentId,
                    paramCount,
                    sandboxValid,
                    1.0 // timeDelta = 1 second
            );

            if (tokensAdded > 0) {
                System.out.printf("Agent %s: Refilled %.2f tokens (New balance: %.2f)%n",
                        agentId, tokensAdded, redisQueueService.getAgentBalance(agentId));
            }
        }
    }

    /**
     * Returns the list of currently registered agent IDs.
     */
    public Set<String> getRegisteredAgentIds() {
        return agentParameterCounts.keySet();
    }

    /**
     * Simulates an agent requesting and leasing an external tool.
     * Calculates the total cost using Eq. 16 and deducts it from the agent's balance.
     *
     * @param agentId The unique identifier of the agent.
     * @param toolId The identifier of the requested tool.
     * @return A string describing the outcome of the tool procurement.
     */
    public String procureTool(String agentId, String toolId) {
        double currentBalance = redisQueueService.getAgentBalance(agentId);

        // 1. Check if agent can afford the tool (Economic friction)
        if (!toolRegistryService.leaseTool(agentId, toolId, currentBalance)) {
            return String.format("PROCUREMENT FAILED: Agent %s has insufficient liquidity (%.2f) to lease tool '%s'.",
                    agentId, currentBalance, toolId);
        }

        // 2. Calculate total inference cost using Equation 16
        // Hyperparameters from the paper (Section 4.3)
        double Ni = 7.0; // Example: 7 Billion parameters
        double gamma = 2.0; // Fixed parameter tariff rate
        double lambda = 0.5; // Context multiplier
        double packetSize = 1024.0; // Size of Specialization Packet
        double contextLimit = 8192.0; // Context Limit
        double toolCost = toolRegistryService.getToolCost(toolId);
        boolean isToolUsed = true; // I(Tool Used == 1)

        double totalCost = redisQueueService.calculateTotalInferenceCost(
                Ni, gamma, lambda, packetSize, contextLimit, toolCost, isToolUsed
        );

        // 3. Deduct the cost from the agent's liquid balance
        double newBalance = redisQueueService.deductInferenceCost(agentId, totalCost);

        return String.format("SUCCESS: Agent %s leased tool '%s'. Total cost (Eq. 16): %.2f. New balance: %.2f",
                agentId, toolId, totalCost, newBalance);
    }

    /**
     * Retrieves the Artifact Passport for a task (for monitoring or replacement agent context).
     */
    public ArtifactPassport getTaskPassport(String taskId) {
        return passportService.getPassport(taskId);
    }

    /**
     * Simple Data Transfer Object for Tasks to avoid coupling with external models.
     */
    public static class TaskDTO {
        private final String taskId;
        private final double budget;
        private final boolean dagCleared;

        public TaskDTO(String taskId, double budget, boolean dagCleared) {
            this.taskId = taskId;
            this.budget = budget;
            this.dagCleared = dagCleared;
        }

        public String getTaskId() { return taskId; }
        public double getBudget() { return budget; }
        public boolean isDagCleared() { return dagCleared; }
    }
}