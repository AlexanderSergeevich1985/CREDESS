package org.credess.model.artifact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Artifact Passport (Artifact Metadata) — a semi-persistent memory structure
 * attached directly to the task infrastructure rather than the agent state.
 *
 * Implements the hybrid memory framework described in Section 4.2 of the CREDESS paper.
 * Decouples structural workspace into localized engineering context (this passport)
 * and broad technological context (Vectorial RAG Buffer).
 *
 * When an agent within the execution layer (Lexec) generates a faulty artifact,
 * the respective compiler exceptions and Docker runtime logs are serialized
 * into the metadata ledger of ticket Tj.
 */
public class ArtifactPassport {

    private String passportId;
    private String taskId;
    private String currentAgentId;
    private Instant createdAt;
    private Instant updatedAt;

    // Iteration tracking (bounded iterative execution, Section 4.6)
    private int iterationCount;
    private int maxIterations;

    // Token consumption tracking
    private double totalTokensConsumed;

    // Quality vector Qj* = [q1, q2, ..., qM]^T (Eq. 28)
    private List<Double> qualityVector;

    // Barrier 1: Docker sandbox syntactic control logs
    private List<String> dockerLogs;

    // Barrier 2: Digital twin functional simulation errors
    private List<String> functionalErrors;

    // Barrier 3: LLM consensus semantic audit feedback
    private List<String> semanticFeedback;

    // Overall status
    private PassportStatus status;

    /**
     * Enum representing the lifecycle states of an artifact passport.
     */
    public enum PassportStatus {
        INITIALIZED,
        IN_PROGRESS,
        PASSED,
        FAILED_ITERATION_LIMIT,
        FAILED_TTL_EXPIRED,
        FAILED_TOKEN_DEPLETED
    }

    public ArtifactPassport() {
        this.dockerLogs = new ArrayList<>();
        this.functionalErrors = new ArrayList<>();
        this.semanticFeedback = new ArrayList<>();
        this.qualityVector = new ArrayList<>();
        this.iterationCount = 0;
        this.totalTokensConsumed = 0.0;
        this.status = PassportStatus.INITIALIZED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ArtifactPassport(String passportId, String taskId, String agentId, int maxIterations) {
        this();
        this.passportId = passportId;
        this.taskId = taskId;
        this.currentAgentId = agentId;
        this.maxIterations = maxIterations;
    }

    // === Getters and Setters ===

    public String getPassportId() { return passportId; }
    public void setPassportId(String passportId) { this.passportId = passportId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getCurrentAgentId() { return currentAgentId; }
    public void setCurrentAgentId(String currentAgentId) {
        this.currentAgentId = currentAgentId;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public int getIterationCount() { return iterationCount; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public double getTotalTokensConsumed() { return totalTokensConsumed; }
    public void setTotalTokensConsumed(double totalTokensConsumed) { this.totalTokensConsumed = totalTokensConsumed; }

    public List<Double> getQualityVector() { return qualityVector; }
    public void setQualityVector(List<Double> qualityVector) { this.qualityVector = qualityVector; }

    public List<String> getDockerLogs() { return dockerLogs; }
    public void setDockerLogs(List<String> dockerLogs) { this.dockerLogs = dockerLogs; }

    public List<String> getFunctionalErrors() { return functionalErrors; }
    public void setFunctionalErrors(List<String> functionalErrors) { this.functionalErrors = functionalErrors; }

    public List<String> getSemanticFeedback() { return semanticFeedback; }
    public void setSemanticFeedback(List<String> semanticFeedback) { this.semanticFeedback = semanticFeedback; }

    public PassportStatus getStatus() { return status; }
    public void setStatus(PassportStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    /**
     * Increments the iteration counter and updates the timestamp.
     * Called at the start of each self-correction loop cycle.
     */
    public void incrementIteration() {
        this.iterationCount++;
        this.updatedAt = Instant.now();
    }

    /**
     * Adds tokens consumed during the current iteration.
     */
    public void addTokensConsumed(double tokens) {
        this.totalTokensConsumed += tokens;
    }

    /**
     * Appends a Docker sandbox log entry (Barrier 1 failure).
     */
    public void appendDockerLog(String logEntry) {
        this.dockerLogs.add("[" + Instant.now() + "] " + logEntry);
        this.updatedAt = Instant.now();
    }

    /**
     * Appends a functional simulation error (Barrier 2 failure).
     */
    public void appendFunctionalError(String error) {
        this.functionalErrors.add("[" + Instant.now() + "] " + error);
        this.updatedAt = Instant.now();
    }

    /**
     * Appends semantic feedback from LLM judges (Barrier 3 failure).
     * Also updates the quality vector Qj*.
     */
    public void appendSemanticFeedback(String feedback, double qualityScore) {
        this.semanticFeedback.add("[" + Instant.now() + "] Q=" + qualityScore + " | " + feedback);
        this.qualityVector.add(qualityScore);
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if the passport has exceeded the maximum iteration limit (MaxIt).
     * Corresponds to the hard ticket failure condition in Section 4.6.
     */
    public boolean isIterationLimitExceeded() {
        return this.iterationCount >= this.maxIterations;
    }

    /**
     * Calculates the average quality score across all iterations.
     * Used for liquidity update calculations (Eq. 29, 30).
     */
    public double getAverageQualityScore() {
        if (qualityVector.isEmpty()) return 0.0;
        return qualityVector.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
}