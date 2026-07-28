package org.credess.service.validator;

import org.credess.model.validator.ValidationResult;
import org.credess.service.artifact.ArtifactPassportService;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Triple Green Light validation cascade (Section 4.2, 4.6).
 * Automatically logs all barrier failures into the Artifact Passport,
 * ensuring replacement agents have full tactical context.
 */
@Service
public class TripleGreenLightValidator {

    private final DockerSandboxBarrier dockerBarrier;
    private final DigitalTwinBarrier digitalTwinBarrier;
    private final LLMConsensusBarrier llmBarrier;
    private final ArtifactPassportService passportService;

    public TripleGreenLightValidator(
            DockerSandboxBarrier dockerBarrier,
            DigitalTwinBarrier digitalTwinBarrier,
            LLMConsensusBarrier llmBarrier,
            ArtifactPassportService passportService) {
        this.dockerBarrier = dockerBarrier;
        this.digitalTwinBarrier = digitalTwinBarrier;
        this.llmBarrier = llmBarrier;
        this.passportService = passportService;
    }

    /**
     * Executes the full validation cascade with automatic passport logging.
     *
     * @param taskId The task identifier (used to locate the passport).
     * @param artifact The generated code/text to validate.
     * @return Final aggregated ValidationResult.
     */
    public ValidationResult executeCascade(String taskId, String artifact) {
        // Barrier 1: Syntactic Control (Docker Sandbox)
        ValidationResult dockerResult = dockerBarrier.validate(artifact);
        if (!dockerResult.isPassed()) {
            passportService.appendDockerLog(taskId,
                    "BARRIER_1_FAIL: " + dockerResult.getErrorMessage());
            return dockerResult;
        }

        // Barrier 2: Functional Simulation (Digital Twin)
        ValidationResult twinResult = digitalTwinBarrier.validate(artifact);
        if (!twinResult.isPassed()) {
            passportService.appendFunctionalError(taskId,
                    "BARRIER_2_FAIL: " + twinResult.getErrorMessage());
            return twinResult;
        }

        // Barrier 3: Semantic Audit (LLM Consensus)
        ValidationResult llmResult = llmBarrier.validate(artifact);
        if (!llmResult.isPassed()) {
            passportService.appendSemanticFeedback(taskId,
                    "BARRIER_3_FAIL: " + llmResult.getErrorMessage(),
                    llmResult.getQualityScore());
            return llmResult;
        }

        // All barriers passed (Triple Green Light achieved)
        passportService.appendSemanticFeedback(taskId,
                "TRIPLE_GREEN_LIGHT_PASSED", llmResult.getQualityScore());

        double aggregatedQuality = (dockerResult.getQualityScore() +
                twinResult.getQualityScore() +
                llmResult.getQualityScore()) / 3.0;

        long totalLatency = dockerResult.getExecutionTimeMs() +
                twinResult.getExecutionTimeMs() +
                llmResult.getExecutionTimeMs();

        return ValidationResult.success(aggregatedQuality, totalLatency);
    }
}