package org.credess.model.validator;

/**
 * Data Transfer Object representing the outcome of a validation barrier.
 */
public class ValidationResult {
    private final boolean passed;
    private final String errorMessage;
    private final double qualityScore; // Represents elements of Qj* vector (Eq. 28)
    private final long executionTimeMs; // TTFT or execution latency

    public ValidationResult(boolean passed, String errorMessage, double qualityScore, long executionTimeMs) {
        this.passed = passed;
        this.errorMessage = errorMessage;
        this.qualityScore = qualityScore;
        this.executionTimeMs = executionTimeMs;
    }

    // Getters
    public boolean isPassed() { return passed; }
    public String getErrorMessage() { return errorMessage; }
    public double getQualityScore() { return qualityScore; }
    public long getExecutionTimeMs() { return executionTimeMs; }

    /**
     * Factory method for a successful validation.
     */
    public static ValidationResult success(double qualityScore, long executionTimeMs) {
        return new ValidationResult(true, null, qualityScore, executionTimeMs);
    }

    /**
     * Factory method for a failed validation.
     */
    public static ValidationResult failure(String errorMessage) {
        return new ValidationResult(false, errorMessage, 0.0, 0);
    }
}