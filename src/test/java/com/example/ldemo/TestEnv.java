package com.example.ldemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Loads LD_SDK_KEY from the environment or a local .env (same idea as run.sh). */
final class TestEnv {
    private TestEnv() { }

    static Optional<String> sdkKey() {
        String fromEnv = System.getenv("LD_SDK_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.trim());
        }
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.startsWith("LD_SDK_KEY=")) {
                    continue;
                }
                String value = trimmed.substring("LD_SDK_KEY=".length()).trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
