package org.credess.service.validator;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.credess.model.validator.ValidationBarrier;
import org.credess.model.validator.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Barrier 3: High-level semantic audit driven by consensus-based vote among
 * independent LLM judges (Section 4.2).
 */
@Component
public class LLMConsensusBarrier implements ValidationBarrier {

    private final ChatLanguageModel judgeModel;
    private static final int NUM_JUDGES = 3; // Number of independent LLM judges

    public LLMConsensusBarrier(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName) {

        // Initialize LangChain4j model for the judges
        this.judgeModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2) // Low temperature for deterministic judging
                .build();
    }

    @Override
    public ValidationResult validate(String artifact) {
        long startTime = System.currentTimeMillis();
        List<Boolean> votes = new ArrayList<>();

        String promptTemplate = """
            You are an expert code reviewer and semantic auditor. 
            Evaluate the following artifact for semantic correctness, adherence to requirements, and overall quality.
            
            Artifact:
            %s
            
            Respond ONLY with "PASS" if it meets high standards, or "FAIL" followed by a brief reason.
            """;

        for (int i = 0; i < NUM_JUDGES; i++) {
            String response = judgeModel.generate(String.format(promptTemplate, artifact));
            votes.add(response.trim().startsWith("PASS"));
        }

        // Consensus mechanism: Majority vote (at least 2 out of 3 judges must pass)
        long passCount = votes.stream().filter(v -> v).count();
        boolean consensusPassed = passCount >= (NUM_JUDGES / 2 + 1);

        long executionTime = System.currentTimeMillis() - startTime;

        if (consensusPassed) {
            // Quality score based on judge agreement (Eq. 28)
            double qualityScore = (double) passCount / NUM_JUDGES;
            return ValidationResult.success(qualityScore, executionTime);
        } else {
            return ValidationResult.failure("LLM Consensus failed. Only " + passCount + "/" + NUM_JUDGES + " judges approved.");
        }
    }
}