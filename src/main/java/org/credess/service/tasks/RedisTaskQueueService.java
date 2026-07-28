package org.credess.service.tasks;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for managing sharded Redis ZSET queues
 * and atomic CAS (Compare-And-Swap) task locking.
 * Implements the decentralized routing logic described in Section 4.4 of the CREDESS paper.
 */
@Service
public class RedisTaskQueueService {
    // Governance regularization hyperparameter (αgov)
    private static final double ALPHA_GOV = 0.01;
    // Base refill rate
    private static final double RHO_BASE = 10.0;
    /**
     * Invariant transaction fee δt (Eq. 20, 34).
     * Permanently burned from the agent's liquid balance upon successful CAS lock.
     * Acts as an anti-spam cost regulator and deflationary stabilizing shock.
     */
    private static final double DELTA_T = 2.0;

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis key prefixes for namespacing
    private static final String QUEUE_PREFIX = "credess:queue:";
    private static final String LOCK_PREFIX = "credess:lock:";
    private static final String BALANCE_PREFIX = "credess:balance:";
    private static final String AGENT_PREFIX = "credess:agent:";
    private static final String METRICS_PREFIX = "credess:metrics:";

    /**
     * Constructor injection for RedisTemplate.
     */
    public RedisTaskQueueService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Adds a task to the sharded ZSET queue for a specific role and pipeline layer.
     * The score represents the task priority (higher score = higher priority).
     *
     * @param role The functional role of the agent (e.g., "Dev", "QA").
     * @param layer The pipeline layer (e.g., "execution", "verification").
     * @param taskId The unique identifier of the task.
     * @param priorityScore The calculated priority score for the ZSET.
     */
    public void addTaskToQueue(String role, String layer, String taskId, double priorityScore) {
        String queueKey = QUEUE_PREFIX + role + ":" + layer;
        redisTemplate.opsForZSet().add(queueKey, taskId, priorityScore);
    }

    /**
     * Retrieves the top-K tasks from the queue in descending order of priority.
     * Equivalent to the Redis ZREVRANGE command.
     *
     * @param role The functional role.
     * @param layer The pipeline layer.
     * @param count The maximum number of tasks to retrieve.
     * @return A set of typed tuples containing task IDs and their scores.
     */
    public Set<ZSetOperations.TypedTuple<Object>> getTopTasks(String role, String layer, int count) {
        String queueKey = QUEUE_PREFIX + role + ":" + layer;
        // Fetch from index 0 to count-1
        return redisTemplate.opsForZSet().reverseRangeWithScores(queueKey, 0, count - 1);
    }

    /**
     * Attempts an atomic CAS (Compare-And-Swap) lock on a task.
     * Uses Redis SET NX (Set if Not eXists) with a Time-To-Live (TTL) to prevent deadlocks.
     * If the task is already locked by another agent, this method returns false.
     *
     * @param taskId The unique identifier of the task to lock.
     * @param agentId The unique identifier of the agent attempting the lock.
     * @param lockTimeoutSeconds The TTL for the lock in seconds.
     * @return true if the lock was successfully acquired, false otherwise (race condition).
     */
    public boolean attemptCasLock(String taskId, String agentId, long lockTimeoutSeconds) {
        String lockKey = LOCK_PREFIX + taskId;
        String lockValue = agentId + ":" + System.currentTimeMillis();

        // setIfAbsent executes the atomic SET NX command
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                lockTimeoutSeconds,
                TimeUnit.SECONDS
        );

        return Boolean.TRUE.equals(success);
    }

    /**
     * Releases the CAS lock for a task.
     * Verifies that the agent releasing the lock is the same one that acquired it.
     *
     * @param taskId The unique identifier of the task.
     * @param agentId The unique identifier of the agent releasing the lock.
     * @return true if the lock was successfully released, false otherwise.
     */
    public boolean releaseLock(String taskId, String agentId) {
        String lockKey = LOCK_PREFIX + taskId;
        Object currentValue = redisTemplate.opsForValue().get(lockKey);

        // Ensure only the locking agent can release the lock
        if (currentValue != null && currentValue.toString().startsWith(agentId + ":")) {
            return Boolean.TRUE.equals(redisTemplate.delete(lockKey));
        }
        return false;
    }

    /**
     * Burns the invariant transaction fee (delta_t) from the agent's balance.
     * Acts as an anti-spam mechanism for task acquisition.
     *
     * @param agentId The unique identifier of the agent.
     * @param fee The amount of tokens to burn.
     * @return The new balance of the agent after the fee is deducted.
     */
    public double burnTransactionFee(String agentId, double fee) {
        String balanceKey = BALANCE_PREFIX + agentId;

        // Initialize balance with a default value if it doesn't exist
        if (Boolean.FALSE.equals(redisTemplate.hasKey(balanceKey))) {
            redisTemplate.opsForValue().set(balanceKey, 100.0);
        }

        // Atomically decrement the balance
        Double newBalance = redisTemplate.opsForValue().increment(balanceKey, -fee);

        // Prevent balance from dropping below zero
        if (newBalance != null && newBalance < 0) {
            redisTemplate.opsForValue().set(balanceKey, 0.0);
            return 0.0;
        }

        return newBalance != null ? newBalance : 0.0;
    }

    /**
     * Retrieves the current token balance of an agent.
     *
     * @param agentId The unique identifier of the agent.
     * @return The current balance, or 100.0 if no balance record exists.
     */
    public double getAgentBalance(String agentId) {
        String balanceKey = BALANCE_PREFIX + agentId;
        Double balance = (Double) redisTemplate.opsForValue().get(balanceKey);
        return balance != null ? balance : 100.0;
    }

    /**
     * Retrieves statistics about all sharded Redis ZSET queues.
     * Useful for monitoring task distribution across roles and layers.
     *
     * @return A map where keys are queue identifiers (e.g., "queue:Dev:execution")
     *         and values are the number of tasks in each queue.
     */
    public Map<String, Long> getQueueStats() {
        Map<String, Long> stats = new HashMap<>();

        // Scan for all queue keys matching the pattern "credess:queue:*"
        Set<String> queueKeys = redisTemplate.keys(QUEUE_PREFIX + "*");

        if (queueKeys != null) {
            for (String queueKey : queueKeys) {
                // Get the cardinality (number of elements) of each ZSET
                Long size = redisTemplate.opsForZSet().zCard(queueKey);

                // Extract the readable queue name (remove prefix)
                String readableName = queueKey.replace(QUEUE_PREFIX, "");
                stats.put(readableName, size != null ? size : 0L);
            }
        }

        return stats;
    }

    // Добавьте этот префикс в начало класса, если его нет:
    // private static final String AGENT_PREFIX = "credess:agent:";

    /**
     * Saves or updates the agent's operational profile in Redis Hash.
     * Stores role, layer, and current demotion stage.
     *
     * @param agentId The unique identifier of the agent.
     * @param profileData A map of field-value pairs to update (e.g., "role", "Dev").
     */
    public void updateAgentProfile(String agentId, Map<String, String> profileData) {
        String agentKey = AGENT_PREFIX + agentId;
        redisTemplate.opsForHash().putAll(agentKey, profileData);
    }

    /**
     * Retrieves the agent's operational profile from Redis Hash.
     *
     * @param agentId The unique identifier of the agent.
     * @return A map containing the agent's role, layer, and demotion stage.
     */
    public Map<String, String> getAgentProfile(String agentId) {
        String agentKey = AGENT_PREFIX + agentId;
        // Fetch all fields from the hash
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(agentKey);

        Map<String, String> profile = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            profile.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return profile;
    }

    /**
     * Calculates the dynamic token bucket refill rate ρi,t with exponential penalty
     * as described in Equation 33 of the CREDESS paper (Section 4.7).
     *
     * This mechanism prevents token hoarding by agents that do not execute tasks,
     * and aggressively penalizes idle, high-overhead networks.
     *
     * Formula: ρi,t = ρbase · exp(−αgov · Liquidityi,t · (Ni + 1)) · I(Sandbox Status== Valid)
     *
     * @param agentId The unique identifier of the agent
     * @param parameterCountBillions The agent's model parameter count in billions (Ni)
     * @param sandboxStatusValid Whether the agent's sandbox status is valid
     * @return The calculated refill rate ρi,t (tokens per second)
     */
    public double calculateDynamicRefillRate(String agentId, double parameterCountBillions, boolean sandboxStatusValid) {
        // If sandbox status is invalid, refill rate is zero (Eq. 33 indicator function)
        if (!sandboxStatusValid) {
            return 0.0;
        }

        // Get current liquidity balance
        double currentLiquidity = getAgentBalance(agentId);

        // Calculate exponential penalty factor
        // exp(−αgov · Liquidityi,t · (Ni + 1))
        double penaltyExponent = -ALPHA_GOV * currentLiquidity * (parameterCountBillions + 1.0);
        double exponentialPenalty = Math.exp(penaltyExponent);

        // Final refill rate: ρbase · exp(...)
        double refillRate = RHO_BASE * exponentialPenalty;

        // Store the calculated refill rate in Redis for monitoring
        String refillRateKey = "credess:refill_rate:" + agentId;
        redisTemplate.opsForValue().set(refillRateKey, String.valueOf(refillRate));

        return refillRate;
    }

    /**
     * Refills the agent's token bucket based on the dynamic refill rate.
     * Should be called periodically (e.g., every second) for active agents.
     *
     * @param agentId The unique identifier of the agent
     * @param parameterCountBillions The agent's model parameter count in billions
     * @param sandboxStatusValid Whether the agent's sandbox status is valid
     * @param timeDelta The time elapsed since last refill (in seconds)
     * @return The amount of tokens added to the bucket
     */
    public double refillAgentBucket(String agentId, double parameterCountBillions, boolean sandboxStatusValid, double timeDelta) {
        double refillRate = calculateDynamicRefillRate(agentId, parameterCountBillions, sandboxStatusValid);
        double tokensToAdd = refillRate * timeDelta;

        if (tokensToAdd > 0) {
            String balanceKey = BALANCE_PREFIX + agentId;

            // Atomically increment the balance
            Double newBalance = redisTemplate.opsForValue().increment(balanceKey, tokensToAdd);

            // Ensure balance doesn't exceed max bucket capacity (if stored)
            String maxBucketKey = "credess:max_bucket:" + agentId;
            Double maxBucket = (Double) redisTemplate.opsForValue().get(maxBucketKey);

            if (maxBucket != null && newBalance != null && newBalance > maxBucket) {
                redisTemplate.opsForValue().set(balanceKey, String.valueOf(maxBucket));
                return maxBucket - (newBalance - tokensToAdd); // Return actual tokens added
            }

            return tokensToAdd;
        }

        return 0.0;
    }

    /**
     * Sets the maximum bucket capacity for an agent based on their credit score
     * as described in Equation 6 of the paper.
     *
     * Formula: Bucket_max = B_base · (1 + γ · CS / 100)
     *
     * @param agentId The unique identifier of the agent
     * @param creditScore The agent's current credit score
     * @param baseCapacity The base bucket capacity (B_base)
     * @param gamma The credit-scaling coefficient (γ)
     */
    public void updateMaxBucketCapacity(String agentId, double creditScore, double baseCapacity, double gamma) {
        double maxBucket = baseCapacity * (1.0 + gamma * creditScore / 100.0);

        String maxBucketKey = "credess:max_bucket:" + agentId;
        redisTemplate.opsForValue().set(maxBucketKey, String.valueOf(maxBucket));
    }

    /**
     * Retrieves the current refill rate for monitoring purposes.
     */
    public double getAgentRefillRate(String agentId) {
        String refillRateKey = "credess:refill_rate:" + agentId;
        String value = (String) redisTemplate.opsForValue().get(refillRateKey);
        return value != null ? Double.parseDouble(value) : 0.0;
    }

    /**
     * Burns the invariant transaction fee δt from the agent's balance.
     * This method is strictly called ONLY upon a successful CAS task lock (Eq. 20).
     * It also updates the global deflationary metric for system monitoring.
     *
     * @param agentId The unique identifier of the agent acquiring the task.
     * @return The new balance of the agent after the fee is burned.
     */
    public double burnInvariantTransactionFee(String agentId) {
        String balanceKey = BALANCE_PREFIX + agentId;
        String globalBurnKey = METRICS_PREFIX + "total_burned";

        // 1. Atomically deduct δt from the agent's liquid balance
        Double newBalance = redisTemplate.opsForValue().increment(balanceKey, -DELTA_T);

        // Prevent balance from dropping below zero (hard floor)
        if (newBalance != null && newBalance < 0) {
            redisTemplate.opsForValue().set(balanceKey, 0.0);
            newBalance = 0.0;
        }

        // 2. Update the global deflationary metric (Eq. 34 feedback loop)
        redisTemplate.opsForValue().increment(globalBurnKey, DELTA_T);

        return newBalance != null ? newBalance : 0.0;
    }

    /**
     * Retrieves the total amount of transaction fees (δt) burned across the entire system.
     * Useful for monitoring the deflationary stabilizing shock and anti-spam efficiency.
     *
     * @return The cumulative sum of all burned invariant fees.
     */
    public double getTotalBurnedFees() {
        String globalBurnKey = METRICS_PREFIX + "total_burned";
        Double totalBurned = (Double) redisTemplate.opsForValue().get(globalBurnKey);
        return totalBurned != null ? totalBurned : 0.0;
    }
}