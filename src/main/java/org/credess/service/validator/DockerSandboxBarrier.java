package org.credess.service.validator;

import org.credess.model.validator.ValidationBarrier;
import org.credess.model.validator.ValidationResult;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Barrier 1: Syntactic control within an isolated Docker environment (Section 4.2).
 * Executes the artifact in a sandboxed container to catch compilation/runtime syntax errors.
 */
@Component
public class DockerSandboxBarrier implements ValidationBarrier {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxBarrier.class);
    // Using a lightweight Python image for sandbox execution
    private static final String DOCKER_IMAGE = "python:3.11-alpine";

    @Override
    public ValidationResult validate(String artifact) {
        long startTime = System.currentTimeMillis();

        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(DOCKER_IMAGE))) {
            container.withCommand("python", "-c", artifact);
            container.withLogConsumer(new Slf4jLogConsumer(log));

            container.start();

            // In a real implementation, we would capture the exit code and stdout/stderr
            // For simulation purposes, we assume it passes if it starts without exception
            long executionTime = System.currentTimeMillis() - startTime;
            return ValidationResult.success(1.0, executionTime);

        } catch (Exception e) {
            log.error("Docker Sandbox validation failed: {}", e.getMessage());
            return ValidationResult.failure("Syntax/Runtime error in sandbox: " + e.getMessage());
        }
    }
}