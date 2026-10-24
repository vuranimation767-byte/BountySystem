package dev.bountysystem.gui;

import dev.bountysystem.bounties.Bounty;
import dev.bountysystem.bounties.BountyManager;
import dev.bountysystem.configuration.BountyConfig;
import dev.bountysystem.utilities.Formats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * Builds and routes all bounty menus. The GUI never touches currency: every
 * money decision goes through {@link BountyManager}. Click routing uses
 * server-side slot positions stored at render time — item names, lore and
 * titles are pure decoration and never proof of an action.
 */
public final class GuiService {

    private static final int[] CONTENT_SLOTS = contentSlots();
    private static final int PAGE_SIZE = CONTENT_SLOTS.length; // 28

    private static final int MENU_SIZE_54 = 54;
    private static final int MENU_SIZE_27 = 27;

    private static int[] contentSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots.add(row * 9 + column);
            }
        }
        int[] array = new int[slots.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = slots.get(i);
        }
        return array;
    }

    // 54-slot menu bar
    private static final int SLOT_PAGE_INFO = 4;
    private static final int SLOT_BACK_54 = 45;
    private static final int SLOT_PREV_54 = 47;
    private static final int SLOT_NEXT_54 = 51;
    private static final int SLOT_CLOSE_54 = 53;

    // shallow 27-slot menus: back top-left, close bottom-right, cancel/confirm centered
    private static final int SLOT_BACK_27 = 0;
    private static final int SLOT_CLOSE_27 = 26;
    private static final int SLOT_CANCEL_27 = 11;
    private static final int SLOT_HEAD_27 = 13;
    private static final int SLOT_CONFIRM_27 = 15;

    // MAIN
    private static final int SLOT_BROWSE = 11;
    private static final int SLOT_PLACE = 13;
    private static final int SLOT_MINE = 15;

    // REWARD
    private static final int[] SLOT_PRESETS = {10, 11, 12, 13, 14};
    private static final int SLOT_CUSTOM = 16;

    private static final long CONFIRM_DEBOUNCE_MILLIS = 250;

    /** Aggregated view of one target's active bounties (for Browse). */
    private record TargetSummary(UUID target, String name, double total, int count) {
    }

    /** One player's open GUI state; exists exactly while a bounty menu is open. */
    private static final class Session {
        final UUID playerId;
        MenuKey menu;
        int page = 1;
        UUID browsedTarget;
        volatile boolean awaitingChatAmount;
        final PlaceFlow flow;
        final Map<Integer, UUID> slotTargets = new HashMap<>();
        final Map<Integer, Bounty> slotBounties = new HashMap<>();
        final Map<Integer, Double> slotAmounts = new HashMap<>();

        Session(UUID playerId, GuiService service) {
            this.playerId = playerId;
            // re-resolves the target's display name at confirm time; failure feedback comes back through rearm()
            this.flow = new PlaceFlow((placer, target, amount) ->
                            service.onPlacementAttempted(placer, target, amount, service.targetName(target)),
                    System::currentTimeMillis, CONFIRM_DEBOUNCE_MILLIS);
        }
    }

    private final Plugin plugin;
    private final Supplier<BountyConfig> configs;
    private final BountyManager manager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Players whose old menu is closing because we are opening the next one (not a real close). */
    private final Set<UUID> switchingMenus = ConcurrentHashMap.newKeySet();

    public GuiService(Plugin plugin, Supplier<BountyConfig> configs, BountyManager manager) {
        this.plugin = plugin;
        this.configs = configs;
        this.manager = manager;
    }

    private BountyConfig config() {
        return configs.get();
    }

    private BountyConfig.Messages messages() {
        return config().messages();
    }

    // ------------------------------------------------------------------- menu opening

    public void openMain(Player player) {
        Session session = session(player);
        session.menu = MenuKey.MAIN;
        session.awaitingChatAmount = false;
        session.flow.reset();

        BountyConfig.Gui gui = config().gui();
        MenuHolder holder = new MenuHolder(MenuKey.MAIN, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_27, colored(gui.titleMain));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_27);
        inventory.setItem(SLOT_BROWSE, item(gui.iconBrowse, gui.nameBrowse));
        inventory.setItem(SLOT_PLACE, item(gui.iconPlace, gui.namePlace));
        inventory.setItem(SLOT_MINE, item(gui.iconMine, gui.nameMine));
        inventory.setItem(SLOT_CLOSE_27, item(gui.iconClose, gui.nameClose));
        show(player, inventory);
    }

    public void openBrowse(Player player, int page) {
        Session session = session(player);
        session.menu = MenuKey.BROWSE;
        session.awaitingChatAmount = false;

        BountyConfig.Gui gui = config().gui();
        List<TargetSummary> summaries = targetSummaries();
        session.page = Pagination.clampPage(page, summaries.size(), PAGE_SIZE);

        MenuHolder holder = new MenuHolder(MenuKey.BROWSE, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_54, colored(gui.titleBrowse));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_54);

        session.slotTargets.clear();
        List<TargetSummary> slice = Pagination.page(summaries, session.page, PAGE_SIZE);
        for (int i = 0; i < slice.size(); i++) {
            TargetSummary summary = slice.get(i);
            int slot = CONTENT_SLOTS[i];
            session.slotTargets.put(slot, summary.target());
            inventory.setItem(slot, head("&e" + summary.name(), List.of(colored(messages().apply(
                    gui.loreTarget, "count", Integer.toString(summary.count()), "total", Formats.amount(summary.total())))),
                    summary.target()));
        }
        renderBottomBar(inventory, session, summaries.size(), gui);
        show(player, inventory);
    }

    public void openDetail(Player player, int page) {
        Session session = session(player);
        UUID target = session.browsedTarget;
        if (target == null) { // stale state — restart at the browse list
            openBrowse(player, 1);
            return;
        }
        session.menu = MenuKey.DETAIL;
        session.awaitingChatAmount = false;

        BountyConfig.Gui gui = config().gui();
        List<Bounty> entries = manager.activeBountiesOn(target);
        double total = 0.0;
        for (Bounty entry : entries) {
            total += entry.amount();
        }
        session.page = Pagination.clampPage(page, entries.size(), PAGE_SIZE);

        MenuHolder holder = new MenuHolder(MenuKey.DETAIL, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_54,
                colored(gui.titleDetail.replace("%target%", targetName(target))));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_54);

        inventory.setItem(SLOT_PAGE_INFO, head("&e" + targetName(target), List.of(colored(messages().apply(
                gui.loreTarget, "count", Integer.toString(entries.size()), "total", Formats.amount(total)))), target));

        session.slotBounties.clear();
        List<Bounty> slice = Pagination.page(entries, session.page, PAGE_SIZE);
        for (int i = 0; i < slice.size(); i++) {
            Bounty entry = slice.get(i);
            inventory.setItem(CONTENT_SLOTS[i], item(gui.iconPreset, "&6" + Formats.amount(entry.amount()), List.of(
                    colored(messages().apply(gui.loreEntry, "placer", targetName(entry.placer()),
                            "amount", Formats.amount(entry.amount()))))));
        }
        renderBottomBar(inventory, session, entries.size(), gui);
        show(player, inventory);
    }

    public void openPick(Player player, int page) {
        openPick(player, page, true);
    }

    /** @param resetFlow false when returning from the reward menu: the chosen target must survive */
    public void openPick(Player player, int page, boolean resetFlow) {
        Session session = session(player);
        if (resetFlow) {
            session.flow.begin(); // CHOOSE_TARGET — entering the placement flow fresh
        }
        session.menu = MenuKey.PICK;
        session.awaitingChatAmount = false;

        BountyConfig.Gui gui = config().gui();
        List<Player> targets = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!candidate.getUniqueId().equals(player.getUniqueId())) {
                targets.add(candidate);
            }
        }
        targets.sort(Comparator.comparing(Player::getName));
        session.page = Pagination.clampPage(page, targets.size(), PAGE_SIZE);

        MenuHolder holder = new MenuHolder(MenuKey.PICK, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_54, colored(gui.titlePick));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_54);

        session.slotTargets.clear();
        List<Player> slice = Pagination.page(targets, session.page, PAGE_SIZE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            session.slotTargets.put(CONTENT_SLOTS[i], target.getUniqueId());
            inventory.setItem(CONTENT_SLOTS[i], head("&e" + target.getName()));
        }
        renderBottomBar(inventory, session, targets.size(), gui);
        show(player, inventory);
    }

    public void openReward(Player player) {
        Session session = session(player);
        session.menu = MenuKey.REWARD;
        session.awaitingChatAmount = false; // no stray chat capture from a previous prompt

        BountyConfig.Gui gui = config().gui();
        MenuHolder holder = new MenuHolder(MenuKey.REWARD, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_27, colored(gui.titleReward));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_27);

        session.slotAmounts.clear();
        List<Double> presets = gui.presets.size() > SLOT_PRESETS.length
                ? gui.presets.subList(0, SLOT_PRESETS.length)
                : gui.presets;
        for (int i = 0; i < presets.size(); i++) {
            int slot = SLOT_PRESETS[i];
            double preset = presets.get(i);
            session.slotAmounts.put(slot, preset);
            inventory.setItem(slot, item(gui.iconPreset, "&6" + Formats.amount(preset),
                    List.of(colored(messages().apply(gui.lorePreset, "amount", Formats.amount(preset))))));
        }
        inventory.setItem(SLOT_CUSTOM, item(gui.iconCustom, gui.nameCustom,
                List.of(colored(gui.loreCustom))));
        inventory.setItem(SLOT_BACK_27, item(gui.iconBack, gui.nameBack));
        inventory.setItem(SLOT_CLOSE_27, item(gui.iconClose, gui.nameClose));
        show(player, inventory);
    }

    public void openConfirm(Player player) {
        Session session = session(player);
        session.menu = MenuKey.CONFIRM;
        session.awaitingChatAmount = false;
        UUID target = session.flow.targetId();
        if (target == null || session.flow.stage() != PlaceFlow.Stage.CONFIRM) {
            openReward(player); // corrupted flow — restart the step gracefully
            return;
        }

        BountyConfig.Gui gui = config().gui();
        double amount = session.flow.amount();
        MenuHolder holder = new MenuHolder(MenuKey.CONFIRM, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_27, colored(gui.titleConfirm));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_27);

        inventory.setItem(SLOT_CANCEL_27, item(gui.iconCancel, gui.nameCancel));
        inventory.setItem(SLOT_HEAD_27, head("&e" + targetName(target), List.of(colored(messages().apply(
                gui.loreInfo, "target", targetName(target), "amount", Formats.amount(amount)))), target));
        inventory.setItem(SLOT_CONFIRM_27, item(gui.iconConfirm, gui.nameConfirm + " &7(" + Formats.amount(amount) + ")"));
        inventory.setItem(SLOT_CLOSE_27, item(gui.iconClose, gui.nameClose));
        show(player, inventory);
    }

    public void openMine(Player player, int page) {
        Session session = session(player);
        session.menu = MenuKey.MINE;
        session.awaitingChatAmount = false;

        BountyConfig.Gui gui = config().gui();
        List<Bounty> mine = manager.activeBountiesBy(player.getUniqueId());
        if (mine.isEmpty()) {
            send(player.getUniqueId(), messages().prefix + messages().listEmpty);
            openMain(player);
            return;
        }
        session.page = Pagination.clampPage(page, mine.size(), PAGE_SIZE);

        MenuHolder holder = new MenuHolder(MenuKey.MINE, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE_54, colored(gui.titleMine));
        holder.attach(inventory);
        fillBackground(inventory, MENU_SIZE_54);

        session.slotBounties.clear();
        List<Bounty> slice = Pagination.page(mine, session.page, PAGE_SIZE);
        for (int i = 0; i < slice.size(); i++) {
            Bounty entry = slice.get(i);
            int slot = CONTENT_SLOTS[i];
            session.slotBounties.put(slot, entry);
            inventory.setItem(slot, head("&e" + targetName(entry.target()), List.of(
                    colored(messages().apply(gui.loreMyBounty, "target", targetName(entry.target()),
                            "amount", Formats.amount(entry.amount()))),
                    colored(gui.loreCancelHint)), entry.target()));
        }
        renderBottomBar(inventory, session, mine.size(), gui);
        show(player, inventory);
    }

    private void renderBottomBar(Inventory inventory, Session session, int totalItems, BountyConfig.Gui gui) {
        int pages = Pagination.pageCount(totalItems, PAGE_SIZE);
        inventory.setItem(SLOT_BACK_54, item(gui.iconBack, gui.nameBack));
        if (session.page > 1) {
            inventory.setItem(SLOT_PREV_54, item(gui.iconPrev, gui.namePrev));
        }
        if (session.page < pages) {
            inventory.setItem(SLOT_NEXT_54, item(gui.iconNext, gui.nameNext));
        }
        inventory.setItem(SLOT_PAGE_INFO, item(Material.PAPER, "&7Page " + session.page + "/" + pages));
        inventory.setItem(SLOT_CLOSE_54, item(gui.iconClose, gui.nameClose));
    }

    // ------------------------------------------------------------------- click routing

    public void click(Player player, MenuHolder holder, int slot) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.menu != holder.key() || !holder.playerId().equals(player.getUniqueId())) {
            return; // a stale holder from a closed view — never act on it
        }
        switch (session.menu) {
            case MAIN -> {
                if (slot == SLOT_BROWSE) {
                    openBrowse(player, 1);
                } else if (slot == SLOT_PLACE) {
                    openPick(player, 1);
                } else if (slot == SLOT_MINE) {
                    openMine(player, 1);
                } else if (slot == SLOT_CLOSE_27) {
                    close(player);
                }
            }
            case BROWSE -> {
                if (slot == SLOT_BACK_54) {
                    openMain(player);
                } else if (slot == SLOT_PREV_54) {
                    openBrowse(player, session.page - 1);
                } else if (slot == SLOT_NEXT_54) {
                    openBrowse(player, session.page + 1);
                } else if (slot == SLOT_CLOSE_54) {
                    close(player);
                } else {
                    UUID target = session.slotTargets.get(slot);
                    if (target != null) {
                        session.browsedTarget = target;
                        openDetail(player, 1);
                    }
                }
            }
            case DETAIL -> {
                if (slot == SLOT_BACK_54) {
                    openBrowse(player, session.page);
                } else if (slot == SLOT_PREV_54) {
                    openDetail(player, session.page - 1);
                } else if (slot == SLOT_NEXT_54) {
                    openDetail(player, session.page + 1);
                } else if (slot == SLOT_CLOSE_54) {
                    close(player);
                } // entry clicks intentionally do nothing — Detail is view-only
            }
            case PICK -> {
                if (slot == SLOT_BACK_54) {
                    openMain(player); // back resets the flow (fresh entry next time)
                } else if (slot == SLOT_PREV_54) {
                    openPick(player, session.page - 1);
                } else if (slot == SLOT_NEXT_54) {
                    openPick(player, session.page + 1);
                } else if (slot == SLOT_CLOSE_54) {
                    close(player);
                } else {
                    UUID target = session.slotTargets.get(slot);
                    if (target != null) {
                        session.flow.chooseTarget(target, targetName(target));
                        openReward(player);
                    }
                }
            }
            case REWARD -> {
                if (slot == SLOT_BACK_27) {
                    openPick(player, 1, false); // target kept — only the stage steps back
                } else if (slot == SLOT_CUSTOM) {
                    session.flow.requestCustomInput();
                    session.awaitingChatAmount = true;
                    send(player.getUniqueId(), messages().prefix + config().gui().promptCustomAmount);
                } else if (slot == SLOT_CLOSE_27) {
                    close(player);
                } else {
                    Double preset = session.slotAmounts.get(slot);
                    if (preset != null) {
                        session.flow.chooseAmount(preset);
                        openConfirm(player);
                    }
                }
            }
            case CONFIRM -> {
                if (slot == SLOT_CANCEL_27) {
                    session.flow.back();
                    openReward(player);
                } else if (slot == SLOT_CONFIRM_27) {
                    session.flow.confirm(player.getUniqueId()); // debounced + one-shot inside the flow
                } else if (slot == SLOT_CLOSE_27) {
                    close(player);
                }
            }
            case MINE -> {
                if (slot == SLOT_BACK_54) {
                    openMain(player);
                } else if (slot == SLOT_PREV_54) {
                    openMine(player, session.page - 1);
                } else if (slot == SLOT_NEXT_54) {
                    openMine(player, session.page + 1);
                } else if (slot == SLOT_CLOSE_54) {
                    close(player);
                } else {
                    Bounty bounty = session.slotBounties.get(slot);
                    if (bounty != null) {
                        cancelBounty(player, session, bounty);
                    }
                }
            }
        }
    }

    private void cancelBounty(Player player, Session session, Bounty bounty) {
        try {
            manager.cancelById(player.getUniqueId(), bounty.id().toString());
            send(player.getUniqueId(), messages().apply(messages().prefix + messages().cancelled,
                    "amount", Formats.amount(bounty.amount())));
        } catch (RuntimeException error) {
            send(player.getUniqueId(), messages().prefix + error.getMessage());
        }
        openMine(player, session.page); // refresh in place so the list stays live
    }

    /** Confirmed placement: runs on the main thread after the debounced confirm click. */
    private void onPlacementAttempted(UUID placerId, UUID targetId, double amount, String targetName) {
        Session session = sessions.get(placerId);
        if (session == null) {
            return; // session vanished mid-click — never place for a closed session
        }
        if (Bukkit.getPlayer(targetId) == null) {
            // the pick menu only shows online players, but they can leave while the flow is open
            send(placerId, messages().prefix + messages().playerNotOnline);
            session.flow.rearm();
            return;
        }
        try {
            manager.create(placerId, targetId, amount);
        } catch (RuntimeException error) {
            send(placerId, messages().prefix + error.getMessage());
            session.flow.rearm(); // allow a retry (e.g. insufficient funds) without reopening the menu
            plugin.getLogger().log(Level.FINE, "Placement declined in GUI: " + error.getMessage());
            return;
        }
        send(placerId, messages().apply(messages().prefix + messages().placed,
                "target", targetName == null ? "unknown" : targetName, "amount", Formats.amount(amount)));
        session.flow.reset();
        Bukkit.getScheduler().runTask(plugin, () -> close(placerId)); // close next tick: a click event is still in flight
    }

    // ------------------------------------------------------------------- chat amount capture

    /** Whether the session currently waits for a chat-typed reward amount (read async from chat). */
    public boolean awaitingChatAmount(UUID playerId) {
        Session session = sessions.get(playerId);
        return session != null && session.awaitingChatAmount;
    }

    /** Chat text handling — always runs on the main thread via {@code dev.bountysystem.gui.MenuListener}. */
    public void onChatText(UUID playerId, String text) {
        Session session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || !session.awaitingChatAmount
                || session.menu != MenuKey.REWARD) {
            return; // session closed while typing
        }
        session.awaitingChatAmount = false;
        BountyConfig config = config();
        BountyConfig.Gui gui = config.gui();
        if (text.trim().equalsIgnoreCase(gui.promptCancelWord)) {
            session.flow.cancelInput();
            send(playerId, messages().prefix + gui.promptAmountCancelled);
            openMain(player); // full flow reset
            return;
        }
        double amount = parseAmount(text);
        if (Double.isNaN(amount) || amount < config.minAmount() || amount > config.maxAmount()) {
            session.awaitingChatAmount = true; // keep waiting for a valid amount
            send(playerId, messages().prefix + gui.promptAmountInvalid);
            return;
        }
        session.flow.chooseAmount(amount);
        openConfirm(player);
    }

    /** Lenient parse: surrounding whitespace and thousands commas removed, must be a finite number. */
    private double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(",", ""));
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------- lifecycle

    /**
     * Opens a bounty menu. The flag tells the close listener that the event raised for
     * the *previous* menu (Bukkit closes it synchronously when another is opened) is a
     * menu switch — not a player closing the GUI — so the session must survive it.
     */
    private void show(Player player, Inventory inventory) {
        switchingMenus.add(player.getUniqueId());
        try {
            player.openInventory(inventory);
        } finally {
            switchingMenus.remove(player.getUniqueId());
        }
    }

    /** Cleans the session when a bounty menu closes. Ignored during a menu switch. */
    public void closed(UUID playerId) {
        if (switchingMenus.contains(playerId)) {
            return; // this close belongs to the previous menu we just replaced
        }
        sessions.remove(playerId);
    }

    /** Hard cleanup on disconnect — always drops the session, including a pending chat prompt. */
    public void quit(UUID playerId) {
        switchingMenus.remove(playerId);
        sessions.remove(playerId);
    }

    private Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new Session(id, this));
    }

    private void close(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.closeInventory();
        }
    }

    private void close(Player player) {
        player.closeInventory();
    }

    // ------------------------------------------------------------------- helpers

    private List<TargetSummary> targetSummaries() {
        Map<UUID, TargetSummary> byTarget = new LinkedHashMap<>();
        for (Bounty bounty : manager.activeBounties()) {
            TargetSummary current = byTarget.get(bounty.target());
            if (current == null) {
                byTarget.put(bounty.target(), new TargetSummary(bounty.target(), targetName(bounty.target()),
                        bounty.amount(), 1));
            } else {
                byTarget.put(bounty.target(), new TargetSummary(current.target(), current.name(),
                        current.total() + bounty.amount(), current.count() + 1));
            }
        }
        List<TargetSummary> summaries = new ArrayList<>(byTarget.values());
        summaries.sort(Comparator.comparing(TargetSummary::name));
        return summaries;
    }

    private String targetName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String cached = Bukkit.getOfflinePlayer(playerId).getName();
        return cached == null ? "unknown" : cached;
    }

    private void fillBackground(Inventory inventory, int size) {
        ItemStack filler = new ItemStack(config().gui().background);
        for (int slot = 0; slot < size; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private Component colored(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private ItemStack item(Material icon, String legacyName) {
        return item(icon, legacyName, List.of());
    }

    private ItemStack item(Material icon, String legacyName, List<Component> lore) {
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(colored(legacyName));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack head(String legacyName) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(colored(legacyName));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack head(String legacyName, List<Component> lore, UUID ownerId) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (ownerId != null) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(owner);
            }
        }
        meta.displayName(colored(legacyName));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void send(UUID playerId, String legacyColored) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(colored(legacyColored));
        }
    }
}
