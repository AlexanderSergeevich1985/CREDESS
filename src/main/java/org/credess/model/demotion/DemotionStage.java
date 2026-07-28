package org.credess.model.demotion;
/**
 * Enum representing the three stages of the Progressive Demotion Cascade
 * as described in Section 4.2 and 5.2.4 of the CREDESS paper.
 */
public enum DemotionStage {
    /**
     * Stage 1: Task-scale downgrade.
     * Agent is restricted to low-overhead, low-budget tasks within its current layer.
     */
    TASK_DOWNGRADE,

    /**
     * Stage 2: Intra-layer pivoting.
     * Agent changes its specific role within the same pipeline layer
     * (e.g., from "Code Generator" to "Sub Critic").
     */
    INTRA_LAYER_PIVOT,

    /**
     * Stage 3: Cross-layer demotion.
     * Agent is pushed down to a lower pipeline layer (e.g., from L_exec to L_ver),
     * accompanied by severe liquidity slashing (Eq. 15).
     */
    CROSS_LAYER_DEMOTION
}