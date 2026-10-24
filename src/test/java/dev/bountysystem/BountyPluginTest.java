package dev.bountysystem;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Build-time metadata tests. They run without a server: constructing a
 * {@code JavaPlugin} requires Paper's runtime bootstrap, so a server-side
 * smoke test happens during manual install checks instead.
 */
class BountyPluginTest {

    @Test
    void pluginYamlDeclaresMatchingMetadata() throws IOException {
        String yaml = resourceText("/plugin.yml");
        assertTrue(yaml.contains("main: dev.bountysystem.BountyPlugin"));
        assertTrue(yaml.contains("api-version: '26.3'"));
        // version is expanded from the Gradle version during processResources
        assertTrue(yaml.contains("version: '0.1.0'"));
    }

    @Test
    void pluginYamlDeclaresCommandAndPermissions() throws IOException {
        String yaml = resourceText("/plugin.yml");
        assertTrue(yaml.contains("bounty:"), "command must be registered under commands:");
        assertTrue(yaml.contains("permission: bounty.use"), "command entry point must be gated");
        assertTrue(yaml.contains("bounty.use:"));   // declared + defaulted to true
        assertTrue(yaml.contains("default: true"));
        assertTrue(yaml.contains("bounty.admin:")); // admin tooling behind its own permission
        assertTrue(yaml.contains("default: op"));
    }

    @Test
    void defaultConfigDocumentsAllSections() throws IOException {
        String yaml = resourceText("/config.yml");
        // every section a server owner can tune must exist in the shipped default
        assertTrue(yaml.contains("economy:"));
        assertTrue(yaml.contains("limits:"));
        assertTrue(yaml.contains("bounties:"));
        assertTrue(yaml.contains("cancellation:"));
        assertTrue(yaml.contains("refund-ratio:"));
        assertTrue(yaml.contains("messages:"));
        assertTrue(yaml.contains("claim-broadcast:"));
        assertTrue(yaml.contains("gui:"));
        assertTrue(yaml.contains("titles:"));
        assertTrue(yaml.contains("presets:"));
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = BountyPluginTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "resource missing: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
