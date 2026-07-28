package org.credess.service.prompt;

import org.credess.model.artifact.ArtifactPassport;
import org.credess.service.artifact.ArtifactPassportService;
import org.credess.service.rag.VectorialRagBufferService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Prompt Compiler Agent (Section 4.9).
 * Acts as a non-stochastic, deterministic compiler that synthesizes the final prompt payload.
 * Restricts input context length by filtering redundant data, parsing only:
 * 1. Relevant tactical failure logs from the Artifact Passport.
 * 2. Targeted tool-invocation traces from the Vectorial RAG Buffer.
 */
@Service
public class PromptCompilerService {

    private final ArtifactPassportService passportService;
    private final VectorialRagBufferService ragBufferService;

    public PromptCompilerService(
            ArtifactPassportService passportService,
            VectorialRagBufferService ragBufferService) {
        this.passportService = passportService;
        this.ragBufferService = ragBufferService;
    }

    /**
     * Synthesizes the final prompt payload for an agent assuming a task.
     * Combines the baseline role prompt, tactical logs, and RAG insights.
     *
     * @param taskId The target ticket ID.
     * @param rolePrompt The baseline system prompt for the target role.
     * @param targetTags The sub-specialization tags (Kj) of the ticket.
     * @return The compiled, context-bounded prompt string.
     */
    public String compilePrompt(String taskId, String rolePrompt, List<String> targetTags) {
        StringBuilder compiledPrompt = new StringBuilder();

        // 1. Base Role Prompt
        compiledPrompt.append("### SYSTEM ROLE ###\n").append(rolePrompt).append("\n\n");

        // 2. Tactical Context (from Artifact Passport)
        ArtifactPassport passport = passportService.getPassport(taskId);
        if (passport != null && !passport.getDockerLogs().isEmpty()) {
            compiledPrompt.append("### TACTICAL FAILURE LOGS (From Passport) ###\n");
            // Take only the last 2 logs to prevent context bloat
            List<String> recentLogs = passport.getDockerLogs().subList(
                    Math.max(0, passport.getDockerLogs().size() - 2),
                    passport.getDockerLogs().size()
            );
            compiledPrompt.append(String.join("\n", recentLogs)).append("\n\n");
        }

        // 3. Strategic Context (from Vectorial RAG Buffer)
        List<String> ragInsights = ragBufferService.retrieveInsights(targetTags);
        if (!ragInsights.isEmpty()) {
            compiledPrompt.append("### PROFESSIONAL INTUITION (From RAG Buffer) ###\n");
            compiledPrompt.append(String.join("\n", ragInsights)).append("\n\n");
        }

        // 4. Task Instruction
        compiledPrompt.append("### TASK INSTRUCTION ###\n");
        compiledPrompt.append("Proceed with the task, avoiding previous errors and applying best practices.\n");

        return compiledPrompt.toString();
    }
}