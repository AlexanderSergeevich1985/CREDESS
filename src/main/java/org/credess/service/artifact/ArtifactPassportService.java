package org.credess.service.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.credess.model.artifact.ArtifactPassport;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for managing Artifact Passports — the localized engineering
 * context track of the hybrid semi-persistent memory framework (Section 4.2).
 *
 * Passports are stored in Redis Hashes keyed by taskId, ensuring that when a
 * replacement agent assumes the contract, it extracts the immediate tactical
 * status of the artifact directly from the task tracker.
 */
@Service
public class ArtifactPassportService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PASSPORT_PREFIX = "credess:passport:";
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    public ArtifactPassportService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        // Register JavaTimeModule for Instant serialization
        this.objectMapper.findAndRegisterModules();
    }

    /**
     * Creates a new Artifact Passport for a task when an agent first locks it via CAS.
     * Initializes the metadata ledger with the agent's identity and iteration bounds.
     *
     * @param taskId The unique identifier of the task.
     * @param agentId The unique identifier of the locking agent.
     * @param maxIterations The maximum allowed self-correction iterations (MaxIt).
     * @return The newly created ArtifactPassport.
     */
    public ArtifactPassport createPassport(String taskId, String agentId, int maxIterations) {
        String passportId = UUID.randomUUID().toString();
        ArtifactPassport passport = new ArtifactPassport(passportId, taskId, agentId, maxIterations);
        passport.setStatus(ArtifactPassport.PassportStatus.IN_PROGRESS);
        savePassport(passport);
        return passport;
    }

    /**
     * Creates a passport with default iteration limit.
     */
    public ArtifactPassport createPassport(String taskId, String agentId) {
        return createPassport(taskId, agentId, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Retrieves an existing Artifact Passport for a task.
     * Used by replacement agents to extract immediate tactical status.
     *
     * @param taskId The unique identifier of the task.
     * @return The ArtifactPassport, or null if no passport exists.
     */
    public ArtifactPassport getPassport(String taskId) {
        String passportKey = PASSPORT_PREFIX + taskId;
        String serialized = (String) redisTemplate.opsForHash().get(passportKey, "passport");

        if (serialized == null) {
            return null;
        }

        try {
            return objectMapper.readValue(serialized, ArtifactPassport.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize ArtifactPassport for task: " + taskId, e);
        }
    }

    /**
     * Saves or updates an Artifact Passport in Redis.
     */
    public void savePassport(ArtifactPassport passport) {
        String passportKey = PASSPORT_PREFIX + passport.getTaskId();
        try {
            String serialized = objectMapper.writeValueAsString(passport);
            redisTemplate.opsForHash().put(passportKey, "passport", serialized);
            redisTemplate.opsForHash().put(passportKey, "updatedAt", Instant.now().toString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ArtifactPassport", e);
        }
    }

    /**
     * Appends a Docker sandbox log entry to the passport (Barrier 1 failure).
     */
    public void appendDockerLog(String taskId, String logEntry) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.appendDockerLog(logEntry);
            savePassport(passport);
        }
    }

    /**
     * Appends a functional simulation error to the passport (Barrier 2 failure).
     */
    public void appendFunctionalError(String taskId, String error) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.appendFunctionalError(error);
            savePassport(passport);
        }
    }

    /**
     * Appends semantic feedback from LLM judges to the passport (Barrier 3 failure).
     */
    public void appendSemanticFeedback(String taskId, String feedback, double qualityScore) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.appendSemanticFeedback(feedback, qualityScore);
            savePassport(passport);
        }
    }

    /**
     * Increments the iteration counter for a task's passport.
     * Called at the start of each self-correction loop cycle.
     */
    public void incrementIteration(String taskId) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.incrementIteration();
            savePassport(passport);
        }
    }

    /**
     * Adds consumed tokens to the passport's total.
     */
    public void addTokensConsumed(String taskId, double tokens) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.addTokensConsumed(tokens);
            savePassport(passport);
        }
    }

    /**
     * Transfers the passport to a new agent (replacement agent assumes the contract).
     * Preserves all historical logs while updating the current agent identity.
     */
    public void transferToAgent(String taskId, String newAgentId) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.setCurrentAgentId(newAgentId);
            savePassport(passport);
        }
    }

    /**
     * Finalizes the passport upon task completion or hard failure.
     * Sets the terminal status based on the outcome.
     */
    public void finalizePassport(String taskId, ArtifactPassport.PassportStatus finalStatus) {
        ArtifactPassport passport = getPassport(taskId);
        if (passport != null) {
            passport.setStatus(finalStatus);
            savePassport(passport);
        }
    }

    /**
     * Checks if a passport exists for the given task.
     */
    public boolean hasPassport(String taskId) {
        String passportKey = PASSPORT_PREFIX + taskId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(passportKey));
    }

    /**
     * Retrieves all passport task IDs for monitoring purposes.
     */
    public List<String> getAllPassportTaskIds() {
        Set<String> keys = redisTemplate.keys(PASSPORT_PREFIX + "*");
        return keys != null ?
                keys.stream()
                        .map(k -> k.replace(PASSPORT_PREFIX, ""))
                        .toList() :
                List.of();
    }
}