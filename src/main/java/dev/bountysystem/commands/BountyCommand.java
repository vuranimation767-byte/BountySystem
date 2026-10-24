package dev.bountysystem.commands;

import dev.bountysystem.BountyPlugin;
import dev.bountysystem.bounties.Bounty;
import dev.bountysystem.bounties.BountyManager;
import dev.bountysystem.configuration.BountyConfig;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.gui.GuiService;
import dev.bountysystem.utilities.Formats;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /bounty} command tree: GUI, list, place, cancel, reload and the
 * admin-only ledger inspection/resolution tools.
 *
 * <p>Every argument is validated before any service call, permissions are
 * checked per subcommand ({@code bounty.admin} for all administrative paths)
 * and service-facing errors (insufficient funds, duplicates, cooldowns, refund
 * rejections) are surfaced as plain chat messages — no stack traces to players.</p>
 */
public final class BountyCommand implements CommandExecutor, TabCompleter {

    private final BountyPlugin plugin;
    private final BountyManager manager;
    private final GuiService gui;

    public BountyCommand(BountyPlugin plugin, BountyManager manager, GuiService gui) {
        this.plugin = plugin;
        this.manager = manager;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BountyConfig messages = manager.config();

        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!sender.hasPermission("bounty.use")) {
                    plain(sender, messages.messages().prefix + messages.messages().noPermission);
                    return true;
                }
                if (!manager.economyAvailable()) {
                    plain(sender, messages.messages().prefix + "Bounties are disabled — no economy provider is installed (see server log).");
                    return true;
                }
                gui.openMain(player);
            } else {
                usage(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "place" -> place(sender, args);
            case "cancel" -> cancel(sender, args);
            case "reload" -> reload(sender);
            case "admin" -> admin(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void list(CommandSender sender) {
        BountyConfig config = manager.config();
        if (!sender.hasPermission("bounty.use")) {
            plain(sender, config.messages().prefix + config.messages().noPermission);
            return;
        }
        List<Bounty> active = manager.activeBounties();
        if (active.isEmpty()) {
            plain(sender, config.messages().prefix + config.messages().listEmpty);
            return;
        }
        plain(sender, config.messages().listHeader);
        String currency = currencyLabel(config);
        for (Bounty bounty : active) {
            plain(sender, config.messages().apply(config.messages().prefix + config.messages().listEntry,
                    "id", bounty.id().toString().substring(0, 8),
                    "target", plugin.displayNameOf(bounty.target()),
                    "amount", Formats.amount(bounty.amount()),
                    "currency", currency,
                    "placer", plugin.displayNameOf(bounty.placer())));
        }
    }

    /** Human currency label for list lines: plural item name, or {@code $} for Vault. */
    private String currencyLabel(BountyConfig config) {
        if (config.economyMode() == BountyConfig.EconomyMode.ITEMS) {
            String name = config.itemCurrency().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return name.endsWith("s") ? name : name + "s";
        }
        return "$";
    }

    private void place(CommandSender sender, String[] args) {
        BountyConfig config = manager.config();
        if (!(sender instanceof Player player)) {
            plain(sender, config.messages().prefix + config.messages().playersOnly);
            return;
        }
        if (!sender.hasPermission("bounty.use")) {
            plain(sender, config.messages().prefix + config.messages().noPermission);
            return;
        }
        if (args.length < 3) {
            usage(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plain(sender, config.messages().prefix + config.messages().playerNotOnline);
            return;
        }
        double amount = parseAmount(args[2]);
        if (Double.isNaN(amount)) {
            plain(sender, config.messages().prefix + "That is not a valid amount — use a number like " + Formats.amount(config.minAmount()) + "…");
            return;
        }
        try {
            manager.create(player.getUniqueId(), target.getUniqueId(), amount);
        } catch (RuntimeException error) {
            // the manager's messages are player-friendly by design (reused, not duplicated)
            plain(sender, config.messages().prefix + error.getMessage());
            return;
        }
        plain(sender, config.messages().apply(config.messages().prefix + config.messages().placed,
                "target", target.getName(), "amount", Formats.amount(amount)));
    }

    private void cancel(CommandSender sender, String[] args) {
        BountyConfig config = manager.config();
        if (!(sender instanceof Player player)) {
            plain(sender, config.messages().prefix + config.messages().playersOnly);
            return;
        }
        if (!sender.hasPermission("bounty.use")) {
            plain(sender, config.messages().prefix + config.messages().noPermission);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        try {
            Bounty cancelled = manager.cancelById(player.getUniqueId(), args[1]);
            plain(sender, config.messages().apply(config.messages().prefix + config.messages().cancelled,
                    "amount", Formats.amount(cancelled.amount())));
        } catch (RuntimeException error) {
            plain(sender, config.messages().prefix + error.getMessage());
        }
    }

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        plugin.reloadBounties(sender);
    }

    /** Administrative ledger inspection and stuck-transaction resolution — never visible to ordinary players. */
    private void admin(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        BountyConfig config = manager.config();
        if (args.length < 2) {
            sender.sendMessage("§6/bounty admin ledger§7 — list open ledger entries");
            sender.sendMessage("§6/bounty admin bounties§7 — list all bounty records (any status)");
            sender.sendMessage("§6/bounty admin resolve <id> refund|reject§7 — resolve a stuck entry");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "ledger" -> {
                List<Transaction> open = manager.openTransactions();
                if (open.isEmpty()) {
                    plain(sender, config.messages().prefix + config.messages().adminLedgerEmpty);
                    return;
                }
                sender.sendMessage("§6Open ledger entries (" + open.size() + "):");
                for (Transaction tx : open) {
                    String statusColor = switch (tx.status()) {
                        case PENDING -> "§e";
                        case FAILED -> "§c";
                        default -> "§7";
                    };
                    sender.sendMessage(" " + statusColor + tx.type() + "/" + tx.status() + " §f" + tx.id()
                            + " §7" + tx.amount() + " → " + tx.playerId()
                            + (tx.note().isBlank() ? "" : " §8— " + tx.note()));
                }
            }
            case "bounties" -> {
                List<Bounty> all = manager.allBounties();
                if (all.isEmpty()) {
                    plain(sender, config.messages().prefix + "No bounty records exist yet.");
                    return;
                }
                sender.sendMessage("§6Bounty records (" + all.size() + "):");
                for (Bounty bounty : all) {
                    String statusColor = switch (bounty.status()) {
                        case ACTIVE -> "§a";
                        case COMPLETED -> "§e";
                        default -> "§7";
                    };
                    sender.sendMessage(" " + statusColor + bounty.status() + " §f" + bounty.id()
                            + " §7" + Formats.amount(bounty.amount())
                            + " §8" + bounty.placer() + " → " + bounty.target());
                }
            }
            case "resolve" -> {
                if (args.length < 4) {
                    sender.sendMessage("§6/bounty admin resolve <id> refund|reject");
                    return;
                }
                String action = args[3].toLowerCase(Locale.ROOT);
                if (!action.equals("refund") && !action.equals("reject")) {
                    sender.sendMessage("§cUnknown action '" + action + "' — use refund or reject.");
                    return;
                }
                UUID transactionId = null;
                try {
                    transactionId = UUID.fromString(args[2].trim());
                } catch (IllegalArgumentException notFullUuid) {
                    List<Transaction> matches = manager.openTransactions().stream()
                            .filter(tx -> tx.id().toString().startsWith(args[2].trim()))
                            .toList();
                    if (matches.isEmpty()) {
                        sender.sendMessage("§cNo open ledger entry matches that id.");
                        return;
                    }
                    if (matches.size() > 1) {
                        sender.sendMessage("§cThat id matches several open entries — use the full id.");
                        return;
                    }
                    transactionId = matches.getFirst().id();
                }
                switch (action) {
                    case "refund" -> {
                        boolean paid = manager.adminPayTransaction(transactionId, sender.getName());
                        sender.sendMessage(paid
                                ? "§aDue amount paid and the entry closed."
                                : "§cRefund failed — the entry stays open (no money moved).");
                    }
                    case "reject" -> sender.sendMessage(manager.adminRejectTransaction(transactionId, sender.getName())
                            ? "§aEntry permanently rejected (no money moved, never retried)."
                            : "§cUnknown or already-resolved transaction id.");
                    default -> {
                    }
                }
            }
            default -> sender.sendMessage("§cUnknown admin tool '" + args[1] + "' — use ledger, bounties or resolve.");
        }
    }

    // ------------------------------------------------------------------- helpers

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("bounty.admin")) {
            return true;
        }
        BountyConfig config = manager.config();
        plain(sender, config.messages().prefix + config.messages().noPermission);
        return false;
    }

    private double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(",", ""));
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void usage(CommandSender sender) {        boolean admin = sender.hasPermission("bounty.admin");
        sender.sendMessage("§6BountySystem commands:");
        sender.sendMessage("§e/bounty §7— open the bounty GUI");
        sender.sendMessage("§e/bounty list §7— show all active bounties");
        sender.sendMessage("§e/bounty place <player> <amount> §7— bounty an online player");
        sender.sendMessage("§e/bounty cancel <id> §7— cancel one of your bounties");
        if (admin) {
            sender.sendMessage("§e/bounty reload §7— apply configuration changes");
            sender.sendMessage("§e/bounty admin [ledger|bounties|resolve] §7— ledger tools");
        }
    }

    private void plain(CommandSender sender, String legacyText) {
        sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(legacyText));
    }

    // ------------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            addMatches(completions, args[0], List.of("list", "place", "cancel"));
            if (sender.hasPermission("bounty.admin")) {
                addMatches(completions, args[0], List.of("admin", "reload"));
            }
            return completions;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "place" -> {
                if (args.length == 2) {
                    List<String> names = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        names.add(online.getName());
                    }
                    addMatches(completions, args[1], names);
                }
                // amount: no completion (free input)
            }
            case "cancel" -> {
                if (args.length == 2 && sender instanceof Player player) {
                    List<String> mine = new ArrayList<>();
                    for (Bounty bounty : manager.activeBountiesBy(player.getUniqueId())) {
                        mine.add(bounty.id().toString().substring(0, 8)); // short id; unique-prefix resolution covers it
                    }
                    addMatches(completions, args[1], mine);
                }
            }
            case "admin" -> {
                if (!sender.hasPermission("bounty.admin")) {
                    return completions; // never suggest administrative tools
                }
                if (args.length == 2) {
                    addMatches(completions, args[1], List.of("ledger", "bounties", "resolve"));
                } else if (args.length == 3 && args[1].equalsIgnoreCase("resolve")) {
                    List<String> ids = new ArrayList<>();
                    for (Transaction tx : manager.openTransactions()) {
                        ids.add(tx.id().toString().substring(0, 8));
                        if (ids.size() >= 40) {
                            break; // keep tab-suggestions light on large ledgers
                        }
                    }
                    addMatches(completions, args[2], ids);
                } else if (args.length == 4 && args[1].equalsIgnoreCase("resolve")) {
                    addMatches(completions, args[3], List.of("refund", "reject"));
                }
            }
            default -> {
            }
        }
        return completions;
    }

    private void addMatches(List<String> target, String partial, List<String> options) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix) && !target.contains(option)) {
                target.add(option);
            }
        }
    }
}
