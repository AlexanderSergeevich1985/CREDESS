package org.credess.service.tools;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for managing the dynamic tool procurement economy (Section 4.3).
 * Agents do not have open-ended access to external tools; they must lease them
 * programmatically from this infrastructure registry.
 */
@Service
public class ToolRegistryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String TOOL_PREFIX = "credess:tool:";
    private static final String AGENT_TOOLS_PREFIX = "credess:agent_tools:";

    public ToolRegistryService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        initializeDefaultTools();
    }

    /**
     * Populates the registry with default infrastructure tools.
     * In a production environment, this would be loaded from a database or config file.
     */
    private void initializeDefaultTools() {
        registerTool("docker-sandbox", 15.0, "Isolated Docker execution environment");
        registerTool("sql-compiler", 10.0, "SQL syntax validation and execution");
        registerTool("json-parser", 5.0, "Strict JSON/YAML structural parsing");
        registerTool("git-plugin", 20.0, "Version control operations");
    }

    /**
     * Registers a new tool in the infrastructure registry.
     *
     * @param toolId Unique identifier for the tool.
     * @param cost The procurement fee C_tool(Rk) in liquidity units.
     * @param description Brief description of the tool's capability.
     */
    public void registerTool(String toolId, double cost, String description) {
        String toolKey = TOOL_PREFIX + toolId;
        Map<String, Object> toolData = new HashMap<>();
        toolData.put("id", toolId);
        toolData.put("cost", cost);
        toolData.put("description", description);
        redisTemplate.opsForHash().putAll(toolKey, toolData);
    }

    /**
     * Retrieves the procurement fee for a specific tool.
     *
     * @param toolId The unique identifier of the tool.
     * @return The cost C_tool(Rk), or 0.0 if the tool is not found.
     */
    public double getToolCost(String toolId) {
        String toolKey = TOOL_PREFIX + toolId;
        Object cost = redisTemplate.opsForHash().get(toolKey, "cost");
        return cost != null ? (double) cost : 0.0;
    }

    /**
     * Leases a tool to an agent. Deducts the procurement fee from the agent's balance.
     * Implements the economic friction mechanism described in Section 4.3.
     *
     * @param agentId The unique identifier of the agent.
     * @param toolId The unique identifier of the tool to lease.
     * @param currentBalance The agent's current liquidity balance.
     * @return true if the tool was successfully leased, false if balance is insufficient.
     */
    public boolean leaseTool(String agentId, String toolId, double currentBalance) {
        double toolCost = getToolCost(toolId);

        if (currentBalance < toolCost) {
            return false; // Insufficient liquidity
        }

        // Add tool to the agent's leased set
        String agentToolsKey = AGENT_TOOLS_PREFIX + agentId;
        redisTemplate.opsForSet().add(agentToolsKey, toolId);

        return true;
    }

    /**
     * Checks if an agent has leased a specific tool.
     * Implements the indicator function I(Tool Used == 1) from Eq. 16.
     */
    public boolean isToolLeased(String agentId, String toolId) {
        String agentToolsKey = AGENT_TOOLS_PREFIX + agentId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(agentToolsKey, toolId));
    }
}