package com.smp.dragonreign.listener;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EventType;
import com.smp.dragonreign.util.Egg;
import com.smp.dragonreign.util.Msg;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * Feature 1 — the egg may only live in a player's own inventory or as a placed
 * block. This catches every way it could slip into a container, the ender chest,
 * or (the nasty one) a bundle inside the player's own inventory.
 */
public final class ContainerProtectionListener implements Listener {

    private final DragonReign plugin;

    public ContainerProtectionListener(DragonReign plugin) {
        this.plugin = plugin;
    }

    /** A container the egg must never enter: anything that isn't the player's own grid. */
    private boolean isBlocked(Inventory inv) {
        if (inv == null) {
            return false;
        }
        InventoryType type = inv.getType();
        return type != InventoryType.PLAYER && type != InventoryType.CRAFTING;
    }

    private String describe(Inventory inv) {
        if (inv == null) {
            return "container";
        }
        return inv.getType() == InventoryType.ENDER_CHEST ? "ender chest"
                : inv.getType().name().toLowerCase().replace('_', ' ');
    }

    // ── Clicks ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ConfigManager config = plugin.config();
        boolean noContainers = config.isNoContainers();
        boolean noDrop = config.isNoDrop();
        if (!noContainers && !noDrop) {
            return;
        }
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player) || player.hasPermission("dragonreign.bypass")) {
            return;
        }

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        InventoryAction action = event.getAction();
        InventoryView view = event.getView();
        Inventory clicked = event.getClickedInventory();
        Inventory top = view.getTopInventory();

        // 0) Dropping the egg from INSIDE an open inventory (Q over a slot, or clicking
        //    outside the window with it on the cursor). These never fire PlayerDropItemEvent
        //    — they arrive ONLY as a DROP_* InventoryClickEvent (and a cursor-outside drop
        //    has a null clickedInventory, so the container guards below miss it). Block when
        //    EITHER no-drop or no-containers is on, so the egg can't be voided over the void.
        if (noDrop || noContainers) {
            switch (action) {
                case DROP_ONE_SLOT, DROP_ALL_SLOT -> {
                    if (carriesEgg(current)) {
                        cancelDrop(event, player, config);
                        return;
                    }
                }
                case DROP_ONE_CURSOR, DROP_ALL_CURSOR -> {
                    if (carriesEgg(cursor)) {
                        cancelDrop(event, player, config);
                        return;
                    }
                }
                default -> {
                    // fall through to the container checks
                }
            }
        }

        // Everything past here only matters for the container rule.
        if (!noContainers) {
            return;
        }

        // 1) Egg <-> bundle inside the player's own inventory.
        if (involvesEggAndBundle(action, cursor, current)) {
            cancelBundle(event, player, config);
            return;
        }

        // 2a) Shift-click moving an egg (or a bundle already hiding one) to the other inventory.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && carriesEgg(current)) {
            Inventory dest = (clicked != null && clicked.equals(top)) ? view.getBottomInventory() : top;
            if (isBlocked(dest)) {
                cancelContainer(event, player, config, describe(dest));
                return;
            }
        }

        // 2b) Number-key / hotbar / offhand swap putting an egg into a blocked slot.
        if (action == InventoryAction.HOTBAR_SWAP && isBlocked(clicked)) {
            int button = event.getHotbarButton();
            ItemStack incoming = (button >= 0)
                    ? player.getInventory().getItem(button)
                    : player.getInventory().getItemInOffHand();
            if (carriesEgg(incoming)) {
                cancelContainer(event, player, config, describe(clicked));
                return;
            }
        }

        // 2c) Placing the egg (or a tainted bundle) from the cursor straight into a blocked slot.
        if (isBlocked(clicked) && carriesEgg(cursor)
                && (action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR)) {
            cancelContainer(event, player, config, describe(clicked));
        }
    }

    /** The egg itself, or a bundle that already has one nested inside it. */
    private boolean carriesEgg(ItemStack item) {
        return Egg.isDragonEgg(item) || Egg.bundleContainsDragonEgg(item);
    }

    /**
     * True if this click would bring a dragon egg and a bundle into contact. We
     * deliberately over-block: a false positive is a minor annoyance, a false
     * negative is a permanent dupe/hide exploit.
     */
    private boolean involvesEggAndBundle(InventoryAction action, ItemStack cursor, ItemStack current) {
        boolean cursorEgg = Egg.isDragonEgg(cursor);
        boolean cursorBundle = Egg.isBundle(cursor);
        boolean slotEgg = Egg.isDragonEgg(current);
        boolean slotBundle = Egg.isBundle(current);

        if ((cursorEgg && slotBundle) || (cursorBundle && slotEgg)) {
            return true;
        }
        String name = action.name();
        if ((name.endsWith("_INTO_BUNDLE") || name.endsWith("_FROM_BUNDLE")) && (cursorEgg || slotEgg)) {
            return true;
        }
        // Double-clicking to vacuum items onto a bundle cursor could pull eggs in.
        return action == InventoryAction.COLLECT_TO_CURSOR && cursorBundle;
    }

    // ── Drag ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        ConfigManager config = plugin.config();
        if (!config.isNoContainers()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || player.hasPermission("dragonreign.bypass")) {
            return;
        }

        ItemStack dragged = event.getOldCursor();
        boolean draggingEgg = Egg.isDragonEgg(dragged);
        boolean draggingBundle = Egg.isBundle(dragged);
        boolean draggingTaintedBundle = Egg.bundleContainsDragonEgg(dragged);
        if (!draggingEgg && !draggingBundle) {
            return;
        }

        InventoryView view = event.getView();
        for (int raw : event.getRawSlots()) {
            Inventory invAt = view.getInventory(raw);
            ItemStack atSlot = view.getItem(raw);

            if ((draggingEgg || draggingTaintedBundle) && isBlocked(invAt)) {
                cancelContainer(event, player, config, describe(invAt));
                return;
            }
            if ((draggingEgg && Egg.isBundle(atSlot)) || (draggingBundle && Egg.isDragonEgg(atSlot))) {
                cancelBundle(event, player, config);
                return;
            }
        }
    }

    // ── Hopper / dropper transport ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        ConfigManager config = plugin.config();
        if (!config.isNoContainers()) {
            return;
        }
        ItemStack item = event.getItem();
        if (!Egg.isDragonEgg(item) && !Egg.bundleContainsDragonEgg(item)) {
            return;
        }
        event.setCancelled(true);
        if (config.isLogBlocksToHistory() && !Msg.throttled("move-transport", 2000L)) {
            plugin.history().appendSystem(EventType.BLOCKED_CONTAINER, null, "blocked egg transport between containers");
        }
    }

    // ── Hopper / hopper-minecart sucking up a ground item ──────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        ConfigManager config = plugin.config();
        if (!config.isNoContainers()) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        if (!Egg.isDragonEgg(item) && !Egg.bundleContainsDragonEgg(item)) {
            return;
        }
        // Leave the item on the ground so a player can still pick it up normally
        // (EntityPickupItemEvent reassigns ownership); just deny the container.
        event.setCancelled(true);
        if (config.isLogBlocksToHistory() && !Msg.throttled("hopper-pickup", 2000L)) {
            plugin.history().appendSystem(EventType.BLOCKED_CONTAINER, null, "blocked hopper pickup of the egg");
        }
    }

    // ── Item frames (egg must never leave the inventory/block model) ────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ConfigManager config = plugin.config();
        if (!config.isNoContainers()) {
            return;
        }
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("dragonreign.bypass")) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        ItemStack inHand = (hand == EquipmentSlot.OFF_HAND)
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!carriesEgg(inHand)) {
            return;
        }
        cancelContainer(event, player, config, "item frame");
    }

    // ── Shared cancel helpers ──────────────────────────────────────────────────

    private void cancelContainer(org.bukkit.event.Cancellable event, Player player, ConfigManager config, String detail) {
        event.setCancelled(true);
        Msg.nudge(player, Msg.prefixed(config.getPrefix(), config.getBlockedContainerMessage()));
        if (config.isLogBlocksToHistory() && !Msg.throttled("container:" + player.getUniqueId(), 2000L)) {
            plugin.history().append(EventType.BLOCKED_CONTAINER, player, null, "into " + detail);
        }
    }

    private void cancelDrop(org.bukkit.event.Cancellable event, Player player, ConfigManager config) {
        event.setCancelled(true);
        Msg.nudge(player, Msg.prefixed(config.getPrefix(), config.getBlockedDropMessage()));
        if (config.isLogBlocksToHistory() && !Msg.throttled("drop:" + player.getUniqueId(), 2000L)) {
            plugin.history().append(EventType.BLOCKED_DROP, player, null, "tried to drop the egg from a menu");
        }
    }

    private void cancelBundle(org.bukkit.event.Cancellable event, Player player, ConfigManager config) {
        event.setCancelled(true);
        Msg.nudge(player, Msg.prefixed(config.getPrefix(), config.getBlockedBundleMessage()));
        if (config.isLogBlocksToHistory() && !Msg.throttled("bundle:" + player.getUniqueId(), 2000L)) {
            plugin.history().append(EventType.BLOCKED_BUNDLE, player, null, "tried to stash the egg in a bundle");
        }
    }
}
