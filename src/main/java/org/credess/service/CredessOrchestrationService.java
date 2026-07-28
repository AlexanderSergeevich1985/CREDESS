package org.credess.service;

import org.credess.client.PythonCredessClient;
import org.credess.model.SimulationReport;
import org.credess.model.SimulationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.credess.model.validator.ValidationResult;
import org.credess.service.demotion.ProgressiveDemotionService;
import org.credess.service.tasks.RedisTaskQueueService;
import org.credess.service.validator.TripleGreenLightValidator;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final RedisTaskQueueService redisQueueService;
    private final PythonCredessClient pythonClient;
    private final LlmAnalysisService llmService;
    private final TripleGreenLightValidator validator;
    private final ProgressiveDemotionService demotionService;
    private int failureCount = 0;
    private final ObjectMapper objectMapper;

    public CredessOrchestrationService(
            PythonCredessClient pythonClient,
            LlmAnalysisService llmService,
            RedisTaskQueueService redisQueueService,
            TripleGreenLightValidator validator,
            ProgressiveDemotionService demotionService,
            ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.llmService = llmService;
        this.redisQueueService = redisQueueService;
        this.validator = validator;
        this.demotionService = demotionService;
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
            // Burn invariant transaction fee δt from agent's liquid balance (Anti-spam cost regulator)
            double newBalance = redisQueueService.burnTransactionFee(agentId, TRANSACTION_FEE);

            // Simulate task execution and release lock afterwards
            String simulatedArtifact = "print('Hello from agent " + agentId + "')";
            ValidationResult validationResult = executeTaskWithValidation(targetTaskId, simulatedArtifact, agentId);

            boolean executionSuccess = validationResult.isPassed();
            redisQueueService.releaseLock(targetTaskId, agentId);

            if (executionSuccess) {
                return String.format("SUCCESS: Agent %s locked task %s. Fee δt=%.2f burned. New balance: %.2f. Quality: %.2f",
                        agentId, targetTaskId, TRANSACTION_FEE, newBalance, validationResult.getQualityScore());
            } else {
                // Task execution failed validation. Trigger Progressive Demotion Cascade (Eq. 15).
                failureCount++;
                String demotionResult = demotionService.executeDemotionCascade(agentId, failureCount);

                return String.format("EXECUTION FAILED: Agent %s locked task %s but failed validation. %s",
                        agentId, targetTaskId, demotionResult);
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
     * Executes the Triple Green Light validation cascade (Section 4.6).
     * Calculates the final local loss minimization objective Lagent (Eq. 29)
     * and updates liquid capital balance (Eq. 30).
     */
    public ValidationResult executeTaskWithValidation(String taskId, String artifact, String agentId) {
        // 1. Run Triple Green Light Cascade
        ValidationResult result = validator.executeCascade(artifact);

        // 2. Apply Resource Clearing and Liquidity Updates (Eq. 29, 30)
        if (result.isPassed()) {
            // Success: ξj* == 1
            // Liquidity update: Liquidity + β * Bj * (1 - η * IterUsed/MaxIt) - τi,t
            // (Simplified for this step)
            double reward = 50.0; // Example base reward Bj
            redisQueueService.burnTransactionFee(agentId, -reward); // Negative fee = reward

            System.out.println("Task " + taskId + " PASSED Triple Green Light. Quality: " + result.getQualityScore());
        } else {
            // Failure: ξj* == 0
            // Triggers closed self-correction loop and progressive demotion
            // Liquidity update: - τi,t - Penaltyfail
            double penalty = 20.0;
            redisQueueService.burnTransactionFee(agentId, penalty);

            System.out.println("Task " + taskId + " FAILED at Barrier. Error: " + result.getErrorMessage());
        }

        return result;
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