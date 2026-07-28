package org.credess.service.policy;

import org.credess.service.tasks.RedisTaskQueueService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service responsible for Layer-Isolated Swarm Meta-Learning via Policy Transplantation (Section 4.8).
 *
 * To safeguard the decentralized environment against cascading systemic failures
 * under frozen network weights (Wfrozen = const), the architecture integrates a
 * layer-isolated evolutionary meta-learning protocol.
 *
 * Instead of enforcing unconstrained global policy replication, the validator court
 * restricts policy transplantation strictly within the boundaries of each independent
 * pipeline layer Lm.
 */
@Service
public class LayerIsolatedPolicyTransplantationService {

    private final RedisTaskQueueService redisQueueService;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String POLICY_PREFIX = "credess:policy:";
    private static final String LAYER_AGENTS_PREFIX = "credess:layer_agents:";

    // Penaltytrans: Minimal financial token fee subtracted from the beneficiary node's balance (Eq. 38)
    private static final double PENALTY_TRANS = 5.0;

    public LayerIsolatedPolicyTransplantationService(
            RedisTaskQueueService redisQueueService,
            RedisTemplate<String, Object> redisTemplate) {
        this.redisQueueService = redisQueueService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Registers an agent to a specific pipeline layer for meta-learning tracking.
     */
    public void registerAgentToLayer(String agentId, String layer) {
        String layerKey = LAYER_AGENTS_PREFIX + layer;
        redisTemplate.opsForSet().add(layerKey, agentId);
    }

    /**
     * Saves or updates the behavioral meta-policy matrix Pi_i,t for an agent (Eq. 35).
     * In a real system, this would be a complex matrix. Here we simulate it as a serialized prompt context.
     */
    public void saveAgentPolicy(String agentId, String policyContext) {
        String policyKey = POLICY_PREFIX + agentId;
        redisTemplate.opsForValue().set(policyKey, policyContext);
    }

    /**
     * Retrieves the behavioral meta-policy matrix for an agent.
     */
    public String getAgentPolicy(String agentId) {
        String policyKey = POLICY_PREFIX + agentId;
        return (String) redisTemplate.opsForValue().get(policyKey);
    }

    /**
     * Isolates the elite agent Abest within a specific layer (Eq. 36).
     * Selected by maximizing its computational token bucket refill rate rho_i,t
     * relative to its active financial token balance.
     *
     * Abest = argmax_{Ai in Lm} (rho_i,t * Liquidity_i,t)
     *
     * @param layer The pipeline layer to evaluate (e.g., "execution").
     * @return The ID of the elite agent, or null if no agents are found.
     */
    public String findEliteAgent(String layer) {
        String layerKey = LAYER_AGENTS_PREFIX + layer;
        Set<Object> agents = redisTemplate.opsForSet().members(layerKey);

        if (agents == null || agents.isEmpty()) {
            return null;
        }

        String eliteAgentId = null;
        double maxScore = Double.NEGATIVE_INFINITY;

        for (Object agentObj : agents) {
            String agentId = agentObj.toString();

            // Get refill rate and liquidity (simulated or fetched from RedisTaskQueueService)
            double refillRate = redisQueueService.getAgentRefillRate(agentId);
            double liquidity = redisQueueService.getAgentBalance(agentId);

            // Calculate the meta-learning score: rho_i,t * Liquidity_i,t
            double score = refillRate * liquidity;

            if (score > maxScore) {
                maxScore = score;
                eliteAgentId = agentId;
            }
        }

        return eliteAgentId;
    }

    /**
     * Executes the asynchronous extraction and hot-reloading of the elite candidate's
     * behavioral meta-policy matrix (Eq. 37 and 38).
     *
     * @param layer The pipeline layer where degradation was detected.
     * @param currentSuccessRate The current aggregate functional success rate of the layer.
     * @param criticalThreshold The critical threshold Lambda_Lm triggering the intervention.
     */
    public void evaluateAndTransplant(String layer, double currentSuccessRate, double criticalThreshold) {
        // Check if structural performance degradation occurred
        if (currentSuccessRate >= criticalThreshold) {
            System.out.println("Layer " + layer + " is healthy (" + currentSuccessRate + "). No transplantation needed.");
            return;
        }

        System.out.println("WARNING: Layer " + layer + " degraded (" + currentSuccessRate + " < " + criticalThreshold + "). Initiating Policy Transplantation...");

        // 1. Isolate the elite agent Abest (Eq. 36)
        String eliteAgentId = findEliteAgent(layer);
        if (eliteAgentId == null) {
            System.out.println("No agents found in layer " + layer + " to transplant from.");
            return;
        }

        // 2. Extract the elite candidate's behavioral meta-policy matrix Pi_best,t
        String elitePolicy = getAgentPolicy(eliteAgentId);
        if (elitePolicy == null) {
            System.out.println("Elite agent " + eliteAgentId + " has no saved policy.");
            return;
        }

        // 3. Hot-reload the optimized context structure into target execution windows of peer nodes (Eq. 37)
        String layerKey = LAYER_AGENTS_PREFIX + layer;
        Set<Object> peerAgents = redisTemplate.opsForSet().members(layerKey);

        int transplantedCount = 0;
        if (peerAgents != null) {
            for (Object peerObj : peerAgents) {
                String peerId = peerObj.toString();

                // Do not overwrite the elite agent itself
                if (!peerId.equals(eliteAgentId)) {
                    // Eq. 37: Pi_k,t+1 <- Pi_best,t
                    saveAgentPolicy(peerId, elitePolicy);

                    // Eq. 38: Liquidity_k,t+1 <- Liquidity_k,t - Penalty_trans
                    redisQueueService.deductInferenceCost(peerId, PENALTY_TRANS);

                    transplantedCount++;
                }
            }
        }

        System.out.println("SUCCESS: Transplanted elite policy from " + eliteAgentId + " to " + transplantedCount + " peers in layer " + layer + ".");
    }
}