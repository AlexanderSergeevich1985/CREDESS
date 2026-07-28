package org.credess.service.validator;

import org.credess.model.validator.ValidationResult;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Triple Green Light validation cascade (Section 4.2, 4.6).
 * Executes barriers sequentially. If any barrier fails, the cascade aborts
 * and triggers the self-correction loop.
 */
@Service
public class TripleGreenLightValidator {

    private final DockerSandboxBarrier dockerBarrier;
    private final DigitalTwinBarrier digitalTwinBarrier;
    private final LLMConsensusBarrier llmBarrier;

    public TripleGreenLightValidator(
            DockerSandboxBarrier dockerBarrier,
            DigitalTwinBarrier digitalTwinBarrier,
            LLMConsensusBarrier llmBarrier) {
        this.dockerBarrier = dockerBarrier;
        this.digitalTwinBarrier = digitalTwinBarrier;
        this.llmBarrier = llmBarrier;
    }

    /**
     * Executes the full validation cascade.
     *
     * @param artifact The generated code/text to validate.
     * @return Final aggregated ValidationResult.
     */
    public ValidationResult executeCascade(String artifact) {
        // Barrier 1: Syntactic Control
        ValidationResult dockerResult = dockerBarrier.validate(artifact);
        if (!dockerResult.isPassed()) {
            return dockerResult; // Abort cascade
        }

        // Barrier 2: Functional Simulation
        ValidationResult twinResult = digitalTwinBarrier.validate(artifact);
        if (!twinResult.isPassed()) {
            return twinResult; // Abort cascade
        }

        // Barrier 3: Semantic Audit
        ValidationResult llmResult = llmBarrier.validate(artifact);
        if (!llmResult.isPassed()) {
            return llmResult; // Abort cascade
        }

        // All barriers passed (Triple Green Light achieved)
        // Aggregate quality score (Eq. 28, 29)
        double aggregatedQuality = (dockerResult.getQualityScore() +
                twinResult.getQualityScore() +
                llmResult.getQualityScore()) / 3.0;

        long totalLatency = dockerResult.getExecutionTimeMs() +
                twinResult.getExecutionTimeMs() +
                llmResult.getExecutionTimeMs();

        return ValidationResult.success(aggregatedQuality, totalLatency);
    }
}