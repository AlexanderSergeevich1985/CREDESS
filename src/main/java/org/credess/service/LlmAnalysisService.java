package org.credess.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LangChain4j service for LLM-based analysis of simulation results.
 */
@Service
public class LlmAnalysisService {

    private final ChatLanguageModel chatModel;

    public LlmAnalysisService(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName) {
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .build();
    }

    /**
     * Generates a natural language summary of simulation results.
     */
    public String analyzeSimulation(String simulationData) {
        String prompt = buildAnalysisPrompt(simulationData);
        return chatModel.generate(prompt);
    }

    /**
     * Suggests optimal parameters based on previous results.
     */
    public String suggestParameters(String previousResults) {
        String prompt = """
            Based on the following CREDESS simulation results, 
            suggest optimal parameters (num_agents, num_tasks, steps) 
            for maximizing agent utilization and minimizing collisions.
            
            Results: %s
            
            Provide your suggestion in JSON format.
            """.formatted(previousResults);
        return chatModel.generate(prompt);
    }

    private String buildAnalysisPrompt(String data) {
        return """
            You are an expert in multi-agent systems and swarm intelligence.
            Analyze the following CREDESS simulation results and provide:
            1. Key findings about agent behavior
            2. Token economy efficiency
            3. Any bottlenecks or issues
            4. Recommendations for improvement
            
            Simulation data: %s
            """.formatted(data);
    }
}