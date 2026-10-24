package dev.bountysystem.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Configuration validation: defaults load sensibly, overrides work, and every
 * invalid value is rejected with a diagnosable message before it can go live.
 */
class BountyConfigTest {

    private static final Logger LOGGER = Logger.getLogger("config-test");

    @Test
    void defaultsAreCompleteAndSensible() {
        BountyConfig config = BountyConfig.defaults();
        assertEquals(BountyConfig.EconomyMode.ITEMS, config.economyMode());
        assertEquals(Material.DIAMOND, config.itemCurrency());
        assertEquals(1.0, config.itemValue());
        assertFalse(config.selfClaimAllowed());
        assertEquals(1.0, config.minAmount());
        assertEquals(100_000_000.0, config.maxAmount());
        assertEquals(5, config.placementCooldownSeconds());
        assertEquals(1.0, config.refundRatio());
        assertEquals(5, config.gui().presets.size());
        assertTrue(config.gui().presets.stream().allMatch(preset -> preset >= config.minAmount() && preset <= config.maxAmount()));
        assertFalse(config.messages().prefix.isBlank());
    }

    @Test
    void valuesOverrideViaYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("economy.mode", "vault");
        yaml.set("economy.items.currency", "EMERALD");
        yaml.set("economy.items.item-value", 2.0);
        yaml.set("bounties.self-claim", true);
        yaml.set("bounties.cancellation.refund-ratio", 0.75);
        yaml.set("limits.min-amount", 50.0);
        yaml.set("limits.max-amount", 5000.0);
        yaml.set("limits.placement-cooldown-seconds", 10);        yaml.set("gui.titles.main", "&1Custom");
        yaml.set("gui.presets", List.of(50.0, 500.0));
        BountyConfig config = BountyConfig.load(yaml, LOGGER);

        assertEquals(BountyConfig.EconomyMode.VAULT, config.economyMode());
        assertEquals(Material.EMERALD, config.itemCurrency());
        assertEquals(2.0, config.itemValue());
        assertTrue(config.selfClaimAllowed());
        assertEquals(0.75, config.refundRatio());
        assertEquals(50.0, config.minAmount());
        assertEquals(5000.0, config.maxAmount());
        assertEquals(10, config.placementCooldownSeconds());
        assertEquals("&1Custom", config.gui().titleMain);
        assertEquals(List.of(50.0, 500.0), config.gui().presets);
    }

    @Test
    void claimBroadcastFormatsPlaceholdersAndColors() {
        String broadcast = BountyConfig.defaults().claimBroadcastFor("Block", "Victim", 250.5);
        assertTrue(broadcast.contains("Block"));
        assertTrue(broadcast.contains("Victim"));
        assertTrue(broadcast.contains("§6250.50")); // &-codes translated and amount at 2dp
    }

    @Test
    void messageTemplatesReplacePairs() {
        BountyConfig.Messages messages = BountyConfig.defaults().messages();
        String applied = messages.apply("&e%target% &6%amount% — %target%", "target", "T", "amount", "5");
        assertEquals("&eT &65 — T", applied);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "gold", "VAULTS", "ItemsMode"})
    void rejectsUnknownEconomyModes(String mode) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("economy.mode", mode);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BountyConfig.load(yaml, LOGGER));
        assertTrue(error.getMessage().contains("economy.mode"));
    }

    @Test
    void rejectsUnknownCurrenciesAndGuiMaterials() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("economy.items.currency", "MOONSHIP");
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(yaml, LOGGER));

        YamlConfiguration gui = new YamlConfiguration();
        gui.set("gui.icons.close", "SPARKLY_BUTTON");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BountyConfig.load(gui, LOGGER));
        assertTrue(error.getMessage().contains("gui.icons.close"));
    }

    @Test
    void rejectsBrokenLimitsCooldownAndRatio() {
        YamlConfiguration crossed = new YamlConfiguration();
        crossed.set("limits.min-amount", 100.0);
        crossed.set("limits.max-amount", 50.0);
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(crossed, LOGGER));

        YamlConfiguration zeroMax = new YamlConfiguration();
        zeroMax.set("limits.max-amount", 0.0);
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(zeroMax, LOGGER));

        YamlConfiguration negativeCooldown = new YamlConfiguration();
        negativeCooldown.set("limits.placement-cooldown-seconds", -3);
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(negativeCooldown, LOGGER));

        YamlConfiguration brokenRatio = new YamlConfiguration();
        brokenRatio.set("bounties.cancellation.refund-ratio", 1.5);
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(brokenRatio, LOGGER));

        YamlConfiguration badItemValue = new YamlConfiguration();
        badItemValue.set("economy.items.item-value", 0.0);
        assertThrows(IllegalArgumentException.class, () -> BountyConfig.load(badItemValue, LOGGER));
    }

    @Test
    void rejectsPresetsOutsideLimits() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("limits.max-amount", 100.0);
        // default presets (100…2500) now exceed max-amount → refused instead of silently broken GUI
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BountyConfig.load(yaml, LOGGER));
        assertTrue(error.getMessage().contains("gui.presets"));
    }

    @Test
    void reloadCaseModeTextIsForgiving() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("economy.mode", " items ");
        assertEquals(BountyConfig.EconomyMode.ITEMS, BountyConfig.load(yaml, LOGGER).economyMode());
    }
}
