package dev.bountysystem.configuration;

import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.logging.Logger;

/**
 * Typed, immutable configuration. Invalid settings fail fast with a clear
 * message — nothing is silently defaulted except documented optional fields.
 * {@code /bounty reload} builds a fresh instance and swaps it in only after
 * validation succeeded.
 */
public final class BountyConfig {

    public enum EconomyMode { VAULT, ITEMS }

    // ------------------------------------------------------------------ messages

    /** All player-facing message templates; %placeholder% pairs are replaced via {@link #apply}. */
    public static final class Messages {
        public final String prefix;
        public final String noPermission;
        public final String playersOnly;
        public final String playerNotOnline;
        public final String placed;
        public final String cancelled;
        public final String bountyNotFound;
        public final String ambiguousId;
        public final String reloadSuccess;
        public final String reloadFailed;
        public final String reloadEconomyRestart;
        public final String listHeader;
        public final String listEmpty;
        public final String listEntry;
        public final String adminLedgerEmpty;
        public final String adminResolved;

        private Messages(String prefix, String noPermission, String playersOnly, String playerNotOnline,
                         String placed, String cancelled, String bountyNotFound, String ambiguousId,
                         String reloadSuccess, String reloadFailed, String reloadEconomyRestart,
                         String listHeader, String listEmpty, String listEntry,
                         String adminLedgerEmpty, String adminResolved) {
            this.prefix = prefix;
            this.noPermission = noPermission;
            this.playersOnly = playersOnly;
            this.playerNotOnline = playerNotOnline;
            this.placed = placed;
            this.cancelled = cancelled;
            this.bountyNotFound = bountyNotFound;
            this.ambiguousId = ambiguousId;
            this.reloadSuccess = reloadSuccess;
            this.reloadFailed = reloadFailed;
            this.reloadEconomyRestart = reloadEconomyRestart;
            this.listHeader = listHeader;
            this.listEmpty = listEmpty;
            this.listEntry = listEntry;
            this.adminLedgerEmpty = adminLedgerEmpty;
            this.adminResolved = adminResolved;
        }

        static Messages load(FileConfiguration yaml) {
            return new Messages(
                    yaml.getString("messages.prefix", "&8[&6Bounty&8] &7"),
                    yaml.getString("messages.no-permission", "&cYou do not have permission to do that."),
                    yaml.getString("messages.players-only", "&cOnly players can use this command."),
                    yaml.getString("messages.player-not-online", "&cThat player is not online right now."),
                    yaml.getString("messages.placed", "&aPlaced a &6%amount% &abounty on &e%target%&a."),
                    yaml.getString("messages.cancelled", "&aCancelled the bounty — &6%amount% &areturned to you."),
                    yaml.getString("messages.bounty-not-found", "&cNone of your active bounties matches &e%id%&c."),
                    yaml.getString("messages.ambiguous-id", "&cThe id &e%id% &cmatches several of your bounties — use the full id from your bounty list."),
                    yaml.getString("messages.reload-success", "&aConfiguration reloaded — limits, cooldown, messages and GUI text are live."),
                    yaml.getString("messages.reload-failed", "&cReload refused (invalid configuration): &f%reason%&c. The old settings stay active."),
                    yaml.getString("messages.reload-economy-restart", "&eEconomy settings changed — they take effect after a server restart."),
                    yaml.getString("messages.list-header", "&8&m――――――――&6 Active bounties &8&m――――――――"),
                    yaml.getString("messages.list-empty", "&7There are no active bounties right now."),
                    yaml.getString("messages.list-entry", "&7ID: &e%id% &8| &7Target: &e%target% &7- &6%amount% %currency% &7(by &e%placer%&7)"),
                    yaml.getString("messages.admin-ledger-empty", "&aNo open ledger entries — nothing needs manual review."),
                    yaml.getString("messages.admin-resolved", "&aLedger entry &e%id% &aresolved: &f%note%"));
        }

        /** Replaces %key% placeholders: apply(template, "target", "Block", "amount", "250"). */
        public String apply(String template, String... replacements) {
            String result = template;
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                result = result.replace("%" + replacements[i] + "%", replacements[i + 1]);
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ gui

    /** GUI titles, icons, button names, lore templates, presets and chat-prompt strings. */
    public static final class Gui {
        public final String titleMain;
        public final String titleBrowse;
        public final String titleDetail;
        public final String titlePick;
        public final String titleReward;
        public final String titleConfirm;
        public final String titleMine;

        public final Material background;
        public final Material iconBrowse;
        public final Material iconPlace;
        public final Material iconMine;
        public final Material iconClose;
        public final Material iconBack;
        public final Material iconPrev;
        public final Material iconNext;
        public final Material iconConfirm;
        public final Material iconCancel;
        public final Material iconPreset;
        public final Material iconCustom;

        public final String nameBrowse;
        public final String namePlace;
        public final String nameMine;
        public final String nameClose;
        public final String nameBack;
        public final String namePrev;
        public final String nameNext;
        public final String nameConfirm;
        public final String nameCancel;
        public final String nameCustom;

        public final String loreTarget;
        public final String loreEntry;
        public final String loreMyBounty;
        public final String loreCancelHint;
        public final String lorePreset;
        public final String loreCustom;
        public final String loreInfo;

        public final List<Double> presets;

        public final String promptCustomAmount;
        public final String promptCancelWord;
        public final String promptAmountInvalid;
        public final String promptAmountCancelled;

        private Gui(String titleMain, String titleBrowse, String titleDetail, String titlePick,
                    String titleReward, String titleConfirm, String titleMine,
                    Material background, Material iconBrowse, Material iconPlace, Material iconMine,
                    Material iconClose, Material iconBack, Material iconPrev, Material iconNext,
                    Material iconConfirm, Material iconCancel, Material iconPreset, Material iconCustom,
                    String nameBrowse, String namePlace, String nameMine, String nameClose, String nameBack,
                    String namePrev, String nameNext, String nameConfirm, String nameCancel, String nameCustom,
                    String loreTarget, String loreEntry, String loreMyBounty, String loreCancelHint,
                    String lorePreset, String loreCustom, String loreInfo,
                    List<Double> presets, String promptCustomAmount, String promptCancelWord,
                    String promptAmountInvalid, String promptAmountCancelled) {
            this.titleMain = titleMain;
            this.titleBrowse = titleBrowse;
            this.titleDetail = titleDetail;
            this.titlePick = titlePick;
            this.titleReward = titleReward;
            this.titleConfirm = titleConfirm;
            this.titleMine = titleMine;
            this.background = background;
            this.iconBrowse = iconBrowse;
            this.iconPlace = iconPlace;
            this.iconMine = iconMine;
            this.iconClose = iconClose;
            this.iconBack = iconBack;
            this.iconPrev = iconPrev;
            this.iconNext = iconNext;
            this.iconConfirm = iconConfirm;
            this.iconCancel = iconCancel;
            this.iconPreset = iconPreset;
            this.iconCustom = iconCustom;
            this.nameBrowse = nameBrowse;
            this.namePlace = namePlace;
            this.nameMine = nameMine;
            this.nameClose = nameClose;
            this.nameBack = nameBack;
            this.namePrev = namePrev;
            this.nameNext = nameNext;
            this.nameConfirm = nameConfirm;
            this.nameCancel = nameCancel;
            this.nameCustom = nameCustom;
            this.loreTarget = loreTarget;
            this.loreEntry = loreEntry;
            this.loreMyBounty = loreMyBounty;
            this.loreCancelHint = loreCancelHint;
            this.lorePreset = lorePreset;
            this.loreCustom = loreCustom;
            this.loreInfo = loreInfo;
            this.presets = List.copyOf(presets);
            this.promptCustomAmount = promptCustomAmount;
            this.promptCancelWord = promptCancelWord;
            this.promptAmountInvalid = promptAmountInvalid;
            this.promptAmountCancelled = promptAmountCancelled;
        }

        static Gui load(FileConfiguration yaml, Logger logger) {
            Material background = material(yaml, "gui.icons.background", Material.GRAY_STAINED_GLASS_PANE);
            List<Double> presets = yaml.isList("gui.presets")
                    ? yaml.getDoubleList("gui.presets")
                    : List.of(100.0, 250.0, 500.0, 1000.0, 2500.0);
            for (double preset : presets) {
                if (preset <= 0 || !Double.isFinite(preset)) {
                    throw new IllegalArgumentException("gui.presets entries must be positive numbers");
                }
            }
            return new Gui(
                    yaml.getString("gui.titles.main", "&8Bounty Board"),
                    yaml.getString("gui.titles.browse", "&8Active Bounties"),
                    yaml.getString("gui.titles.detail", "&8Bounties on %target%"),
                    yaml.getString("gui.titles.pick-target", "&8Choose a Target"),
                    yaml.getString("gui.titles.reward", "&8Set the Reward"),
                    yaml.getString("gui.titles.confirm", "&8Confirm Placement"),
                    yaml.getString("gui.titles.mine", "&8My Bounties"),
                    background,
                    material(yaml, "gui.icons.browse", Material.WRITABLE_BOOK),
                    material(yaml, "gui.icons.place", Material.PLAYER_HEAD),
                    material(yaml, "gui.icons.mine", Material.CHEST),
                    material(yaml, "gui.icons.close", Material.BARRIER),
                    material(yaml, "gui.icons.back", Material.ARROW),
                    material(yaml, "gui.icons.prev", Material.ARROW),
                    material(yaml, "gui.icons.next", Material.ARROW),
                    material(yaml, "gui.icons.confirm", Material.LIME_DYE),
                    material(yaml, "gui.icons.cancel", Material.RED_DYE),
                    material(yaml, "gui.icons.preset", Material.EMERALD),
                    material(yaml, "gui.icons.custom", Material.WRITABLE_BOOK),
                    yaml.getString("gui.names.browse", "&6Active Bounties"),
                    yaml.getString("gui.names.place", "&aPlace a Bounty"),
                    yaml.getString("gui.names.mine", "&eMy Bounties"),
                    yaml.getString("gui.names.close", "&cClose"),
                    yaml.getString("gui.names.back", "&eBack"),
                    yaml.getString("gui.names.prev", "&ePrevious page"),
                    yaml.getString("gui.names.next", "&eNext page"),
                    yaml.getString("gui.names.confirm", "&aConfirm"),
                    yaml.getString("gui.names.cancel", "&cBack to Amount"),
                    yaml.getString("gui.names.custom", "&eCustom Amount"),
                    yaml.getString("gui.lore.target", "&7%count% placer(s) · &6%total%&7 total"),
                    yaml.getString("gui.lore.entry", "&7by &e%placer% &8· &6%amount%"),
                    yaml.getString("gui.lore.my-bounty", "&7target &e%target% &8· &6%amount%"),
                    yaml.getString("gui.lore.cancel-hint", "&cClick to cancel this bounty"),
                    yaml.getString("gui.lore.preset", "&7Reward: &6%amount%"),
                    yaml.getString("gui.lore.custom", "&7Choose your own reward"),
                    yaml.getString("gui.lore.info", "&7Target: &e%target%&7 · Reward: &6%amount%"),
                    presets,
                    yaml.getString("gui.prompts.custom-amount",
                            "&eType the reward amount in chat (e.g. 250), or type 'cancel' to go back."),
                    yaml.getString("gui.prompts.cancel-word", "cancel"),
                    yaml.getString("gui.prompts.amount-invalid",
                            "&cThat is not a valid amount — try again or type 'cancel'."),
                    yaml.getString("gui.prompts.amount-cancelled",
                            "&cAmount entry cancelled - back to the bounty board."));
        }

        private static Material material(FileConfiguration yaml, String key, Material fallback) {
            String text = yaml.getString(key, fallback.name());
            Material material = Material.matchMaterial(text);
            if (material == null) {
                throw new IllegalArgumentException("invalid " + key + " '" + text + "' — unknown material");
            }
            return material;
        }
    }

    // ------------------------------------------------------------------ config fields

    private final EconomyMode economyMode;
    private final Material itemCurrency;
    private final double itemValue;
    private final boolean selfClaimAllowed;

    private final double minAmount;
    private final double maxAmount;
    private final int placementCooldownSeconds;
    private final double refundRatio;

    private final String claimBroadcast;
    private final Messages messages;
    private final Gui gui;

    private BountyConfig(EconomyMode economyMode, Material itemCurrency, double itemValue, boolean selfClaimAllowed,
                         double minAmount, double maxAmount, int placementCooldownSeconds, double refundRatio,
                         String claimBroadcast, Messages messages, Gui gui) {
        this.economyMode = economyMode;
        this.itemCurrency = itemCurrency;
        this.itemValue = itemValue;
        this.selfClaimAllowed = selfClaimAllowed;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.placementCooldownSeconds = placementCooldownSeconds;
        this.refundRatio = refundRatio;
        this.claimBroadcast = claimBroadcast;
        this.messages = messages;
        this.gui = gui;
    }

    /** Loads and validates the configuration; throws IllegalArgumentException on any invalid value. */
    public static BountyConfig load(FileConfiguration yaml, Logger logger) {
        String modeText = yaml.getString("economy.mode", "ITEMS");
        EconomyMode mode;
        try {
            mode = EconomyMode.valueOf(modeText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("invalid economy.mode '" + modeText + "' — use VAULT or ITEMS");
        }

        String currencyText = yaml.getString("economy.items.currency", "DIAMOND");
        Material currency = Material.matchMaterial(currencyText == null ? "" : currencyText);
        if (currency == null) {
            throw new IllegalArgumentException("invalid economy.items.currency '" + currencyText + "' — unknown material");
        }
        double itemValue = yaml.getDouble("economy.items.item-value", 1.0);
        if (!Double.isFinite(itemValue) || itemValue <= 0) {
            throw new IllegalArgumentException("economy.items.item-value must be a positive number");
        }

        boolean selfClaim = yaml.getBoolean("bounties.self-claim", false);

        double minAmount = yaml.getDouble("limits.min-amount", 1.0);
        double maxAmount = yaml.getDouble("limits.max-amount", 100_000_000.0);
        if (!Double.isFinite(minAmount) || minAmount <= 0) {
            throw new IllegalArgumentException("limits.min-amount must be a positive number");
        }
        if (!Double.isFinite(maxAmount) || maxAmount <= 0) {
            throw new IllegalArgumentException("limits.max-amount must be a positive number");
        }
        if (minAmount > maxAmount) {
            throw new IllegalArgumentException("limits.min-amount (" + minAmount + ") must not exceed limits.max-amount (" + maxAmount + ")");
        }

        int cooldown = (int) yaml.getInt("limits.placement-cooldown-seconds", 5);
        if (cooldown < 0) {
            throw new IllegalArgumentException("limits.placement-cooldown-seconds must be 0 (off) or positive");
        }

        double refundRatio = yaml.getDouble("bounties.cancellation.refund-ratio", 1.0);
        if (!Double.isFinite(refundRatio) || refundRatio < 0.0 || refundRatio > 1.0) {
            throw new IllegalArgumentException("bounties.cancellation.refund-ratio must be between 0.0 and 1.0");
        }

        String broadcast = yaml.getString("messages.claim-broadcast",
                "&e%killer% claimed the &6%amount% &7bounty on &c%victim%&7!");

        Gui gui = Gui.load(yaml, logger);
        double min = minAmount;
        double max = maxAmount;
        for (double preset : gui.presets) {
            if (preset < min || preset > max) {
                throw new IllegalArgumentException("gui.presets value " + preset + " is outside limits.min-amount…limits.max-amount (" + min + "…" + max + ")");
            }
        }
        return new BountyConfig(mode, currency, itemValue, selfClaim,
                minAmount, maxAmount, cooldown, refundRatio, broadcast, Messages.load(yaml), gui);
    }

    /** Fresh, fully-default configuration (tests + reload edge cases). */
    public static BountyConfig defaults() {
        return load(new YamlConfiguration(), Logger.getLogger("bounty-config-defaults"));
    }

    /** Neutral factory for unit tests. */
    public static BountyConfig forTesting(boolean selfClaimAllowed) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("bounties.self-claim", selfClaimAllowed);
        yaml.set("limits.placement-cooldown-seconds", 0);
        return load(yaml, Logger.getLogger("bounty-config-test"));
    }

    /** Test factory with a placement cooldown in seconds. */
    public static BountyConfig forTesting(boolean selfClaimAllowed, int placementCooldownSeconds) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("bounties.self-claim", selfClaimAllowed);
        yaml.set("limits.placement-cooldown-seconds", placementCooldownSeconds);
        return load(yaml, Logger.getLogger("bounty-config-test"));
    }

    public EconomyMode economyMode() {
        return economyMode;
    }

    public Material itemCurrency() {
        return itemCurrency;
    }

    public double itemValue() {
        return itemValue;
    }

    public boolean selfClaimAllowed() {
        return selfClaimAllowed;
    }

    public double minAmount() {
        return minAmount;
    }

    public double maxAmount() {
        return maxAmount;
    }

    public int placementCooldownSeconds() {
        return placementCooldownSeconds;
    }

    /** Fraction of the escrow returned when the placer cancels (0…1); the rest is forfeit. */
    public double refundRatio() {
        return refundRatio;
    }

    public Messages messages() {
        return messages;
    }

    public Gui gui() {
        return gui;
    }

    /** Colored claim broadcast; placeholders: %killer%, %victim%, %amount%. */
    public String claimBroadcastFor(String killerName, String victimName, double amount) {
        return ChatColor.translateAlternateColorCodes('&', claimBroadcast
                .replace("%killer%", killerName)
                .replace("%victim%", victimName)
                .replace("%amount%", String.format(Locale.ROOT, "%.2f", amount)));
    }
}
