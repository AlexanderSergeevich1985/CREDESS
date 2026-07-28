package org.credess.service.demotion;

import org.credess.model.demotion.DemotionStage;
import org.credess.service.tasks.RedisTaskQueueService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service responsible for executing the Progressive Demotion Cascade (Section 4.2, Eq. 15).
 * Protects the network from resource depletion by forcing failing agents
 * into lower-overhead roles and layers.
 */
@Service
public class ProgressiveDemotionService {

    private final RedisTaskQueueService redisQueueService;

    // Penalties for liquidity slashing (Eq. 15)
    private static final double PENALTY_FAIL = 20.0;
    private static final double PENALTY_DEMOTION = 50.0;

    public ProgressiveDemotionService(RedisTaskQueueService redisQueueService) {
        this.redisQueueService = redisQueueService;
    }

    /**
     * Executes the demotion cascade based on the agent's consecutive failure count.
     *
     * @param agentId The unique identifier of the failing agent.
     * @param failureCount The number of consecutive task failures or timeout events.
     * @return A string describing the demotion action taken.
     */
    public String executeDemotionCascade(String agentId, int failureCount) {
        // Retrieve current agent profile from Redis
        Map<String, String> profile = redisQueueService.getAgentProfile(agentId);
        String currentRole = profile.getOrDefault("role", "Dev");
        String currentLayer = profile.getOrDefault("layer", "execution");
        DemotionStage currentStage = DemotionStage.valueOf(
                profile.getOrDefault("demotionStage", DemotionStage.TASK_DOWNGRADE.name())
        );

        if (failureCount == 1) {
            // Stage 1: Task-scale downgrade
            currentStage = DemotionStage.TASK_DOWNGRADE;
            redisQueueService.updateAgentProfile(agentId, Map.of("demotionStage", currentStage.name()));

            // Apply minor penalty for failure (Eq. 15)
            redisQueueService.burnTransactionFee(agentId, PENALTY_FAIL);

            return String.format("STAGE 1 [Task Downgrade]: Agent %s restricted to low-budget tasks in layer %s. Penalty: %.2f",
                    agentId, currentLayer, PENALTY_FAIL);

        } else if (failureCount == 2) {
            // Stage 2: Intra-layer pivoting
            currentStage = DemotionStage.INTRA_LAYER_PIVOT;
            String newRole = pivotRoleIntraLayer(currentRole);

            redisQueueService.updateAgentProfile(agentId, Map.of(
                    "demotionStage", currentStage.name(),
                    "role", newRole
            ));

            // Apply transition inertia tax τi,t and failure penalty (Eq. 15)
            double inertiaTax = 10.0;
            redisQueueService.burnTransactionFee(agentId, PENALTY_FAIL + inertiaTax);

            return String.format("STAGE 2 [Intra-layer Pivot]: Agent %s pivoted from %s to %s in layer %s. Total penalty: %.2f",
                    agentId, currentRole, newRole, currentLayer, PENALTY_FAIL + inertiaTax);

        } else {
            // Stage 3: Cross-layer demotion (failureCount >= 3)
            currentStage = DemotionStage.CROSS_LAYER_DEMOTION;
            String newLayer = demoteLayer(currentLayer);
            String newRole = getDefaultRoleForLayer(newLayer);

            redisQueueService.updateAgentProfile(agentId, Map.of(
                    "demotionStage", currentStage.name(),
                    "layer", newLayer,
                    "role", newRole
            ));

            // Severe liquidity slashing (Eq. 15)
            redisQueueService.burnTransactionFee(agentId, PENALTY_DEMOTION);

            return String.format("STAGE 3 [Cross-layer Demotion]: Agent %s demoted from %s/%s to %s/%s. Liquidity slashed by %.2f",
                    agentId, currentRole, currentLayer, newRole, newLayer, PENALTY_DEMOTION);
        }
    }

    /**
     * Pivots the agent to a lower-overhead role within the same layer.
     */
    private String pivotRoleIntraLayer(String currentRole) {
        return switch (currentRole) {
            case "Dev" -> "Sub Critic";
            case "Architect" -> "Task Decomposer";
            case "QA Automation" -> "Security Auditor";
            default -> "Observer";
        };
    }

    /**
     * Demotes the agent to a lower pipeline layer.
     */
    private String demoteLayer(String currentLayer) {
        return switch (currentLayer) {
            case "execution" -> "verification";
            case "verification" -> "inference";
            case "inference" -> "planning";
            default -> "planning"; // Bottom layer
        };
    }

    /**
     * Returns the default role for a given pipeline layer.
     */
    private String getDefaultRoleForLayer(String layer) {
        return switch (layer) {
            case "planning" -> "Architect";
            case "inference" -> "Data Analyst";
            case "execution" -> "Dev";
            case "verification" -> "QA Automation";
            default -> "Idle";
        };
    }
}