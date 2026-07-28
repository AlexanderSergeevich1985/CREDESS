package org.credess.service.rag;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vectorial RAG Buffer Service — the long-term technological context track
 * of the hybrid semi-persistent memory framework (Section 4.2, 4.9).
 *
 * Encapsulates long-term tool expertise and operational insights.
 * Before an ephemeral role prompt is wiped during a pivot, critical insights
 * are atomized and indexed via deterministic technology sub-specialization tags (Kj).
 * Upon role onboarding, the incoming agent executes a rapid, sub-linear RAG query
 * conditioned on the targeted ticket tags, retrieving historical "professional intuition"
 * without inflating the primary context window.
 */
@Service
public class VectorialRagBufferService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RAG_TAG_PREFIX = "credess:rag:tag:";
    private static final String RAG_INSIGHT_PREFIX = "credess:rag:insight:";
    private static final int MAX_CONTEXT_CHUNKS = 3; // Prevents context window inflation

    public VectorialRagBufferService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Pushes a critical operational insight into the external vector store (simulated via Redis).
     * Indexes the insight by its sub-specialization tags (Kj).
     *
     * @param tags List of deterministic technology sub-specialization tags (e.g., ["#fastapi", "#sql"]).
     * @param insightText The atomized text embedding/insight (e.g., "Use asyncpg for DB connections").
     * @param sourceAgentId The ID of the agent that generated this insight.
     * @return The unique ID of the stored insight.
     */
    public String storeInsight(List<String> tags, String insightText, String sourceAgentId) {
        String insightId = UUID.randomUUID().toString();
        String insightKey = RAG_INSIGHT_PREFIX + insightId;

        // Store the insight metadata and content
        Map<String, Object> insightData = new HashMap<>();
        insightData.put("id", insightId);
        insightData.put("text", insightText);
        insightData.put("sourceAgent", sourceAgentId);
        insightData.put("timestamp", Instant.now().toString());
        redisTemplate.opsForHash().putAll(insightKey, insightData);

        // Index the insight by each tag (Kj)
        for (String tag : tags) {
            String tagKey = RAG_TAG_PREFIX + tag;
            redisTemplate.opsForSet().add(tagKey, insightId);
        }

        return insightId;
    }

    /**
     * Executes a rapid, sub-linear RAG query conditioned on the targeted ticket tags (Kj).
     * Retrieves historical "professional intuition" relevant to the current task.
     *
     * @param targetTags The sub-specialization tags of the target ticket/role.
     * @return A list of relevant insight texts, bounded to prevent context bloat.
     */
    public List<String> retrieveInsights(List<String> targetTags) {
        Set<String> relevantInsightIds = new HashSet<>();

        // Fetch all insight IDs associated with the target tags
        for (String tag : targetTags) {
            String tagKey = RAG_TAG_PREFIX + tag;
            Set<Object> ids = redisTemplate.opsForSet().members(tagKey);
            if (ids != null) {
                relevantInsightIds.addAll(ids.stream().map(Object::toString).collect(Collectors.toSet()));
            }
        }

        // Fetch the actual insight texts, bounded by MAX_CONTEXT_CHUNKS to protect the token bucket
        List<String> insights = new ArrayList<>();
        int count = 0;
        for (String id : relevantInsightIds) {
            if (count >= MAX_CONTEXT_CHUNKS) break;

            String insightKey = RAG_INSIGHT_PREFIX + id;
            Object text = redisTemplate.opsForHash().get(insightKey, "text");
            if (text != null) {
                insights.add(text.toString());
                count++;
            }
        }

        return insights;
    }

    /**
     * Clears insights for a specific tag (useful for cache invalidation or epoch resets).
     */
    public void clearTagIndex(String tag) {
        String tagKey = RAG_TAG_PREFIX + tag;
        redisTemplate.delete(tagKey);
    }
}