package com.smp.dragonreign.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.List;

/** Small predicates about dragon eggs and bundles. Stateless on purpose. */
public final class Egg {

    private Egg() {
    }

    /**
     * The single, shared "hand a player some eggs" path used by /giveegg, the
     * death-respawn return, the ender-chest sweep, and the respawn flush. Adds each egg
     * to the player's inventory, and only if that overflows drops the remainder at their
     * feet. Centralising this means egg <em>creation</em> happens in exactly one place.
     *
     * <p>Any egg that does have to hit the ground is marked unlimited-lifetime so the
     * server's single unique egg can never silently despawn on the 5-minute item timer
     * while it waits to be picked back up.
     */
    public static void giveOrDrop(Player player, int count) {
        if (player == null || count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            ItemStack egg = new ItemStack(Material.DRAGON_EGG, 1);
            var overflow = player.getInventory().addItem(egg);
            for (ItemStack left : overflow.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), left);
                dropped.setUnlimitedLifetime(true); // the unique egg must never despawn on the ground
            }
        }
    }

    /**
     * Is the egg anywhere on this player's person right now: inventory (including armor
     * slots and offhand), the item held on their mouse cursor mid-click, their own 2x2
     * crafting grid, or nested inside a bundle they hold. Menu shuffling must never make
     * the egg look "not carried" for a tick — that's how phantom-loss bugs start.
     */
    public static boolean isCarrying(Player player) {
        if (player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isDragonEgg(stack) || (isBundle(stack) && bundleContainsDragonEgg(stack))) {
                return true;
            }
        }
        if (isDragonEgg(player.getItemOnCursor())) {
            return true;
        }
        // The 2x2 crafting grid is part of the player's own screen (type CRAFTING), not a
        // container, so an egg parked there is still on their person.
        InventoryView view = player.getOpenInventory();
        if (view.getType() == InventoryType.CRAFTING || view.getType() == InventoryType.CREATIVE) {
            for (ItemStack stack : view.getTopInventory().getContents()) {
                if (isDragonEgg(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isDragonEgg(Material material) {
        return material == Material.DRAGON_EGG;
    }

    public static boolean isDragonEgg(ItemStack item) {
        return item != null && item.getType() == Material.DRAGON_EGG;
    }

    /**
     * Any bundle, dyed or not. We match on the name suffix so we don't have to
     * hardcode the full color list (and we pick up new variants for free).
     */
    public static boolean isBundle(Material material) {
        return material != null && material.name().endsWith("BUNDLE");
    }

    public static boolean isBundle(ItemStack item) {
        return item != null && isBundle(item.getType());
    }

    /**
     * Does this bundle (or a bundle nested inside it) hold a dragon egg? Used by
     * the sweep/erase paths to clean up an already-tainted bundle.
     */
    public static boolean bundleContainsDragonEgg(ItemStack item) {
        if (!isBundle(item) || !(item.getItemMeta() instanceof BundleMeta meta) || !meta.hasItems()) {
            return false;
        }
        for (ItemStack content : meta.getItems()) {
            if (isDragonEgg(content)) {
                return true;
            }
            if (isBundle(content) && bundleContainsDragonEgg(content)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strip every dragon egg from an inventory, including eggs hidden inside any
     * bundles it holds. Returns how many egg items were removed.
     */
    public static int purgeFrom(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null) {
                continue;
            }
            if (isDragonEgg(stack)) {
                removed += stack.getAmount();
                inventory.setItem(slot, null);
            } else if (isBundle(stack) && bundleContainsDragonEgg(stack)) {
                removed += cleanBundle(stack);
                inventory.setItem(slot, stack);
            }
        }
        return removed;
    }

    /**
     * Every dragon egg currently loose in the world — dropped items and falling blocks —
     * across all loaded worlds.
     *
     * <p>"Loose" is a legitimate place for the egg to be: it is what the ground between
     * two players looks like. Several systems decide whether the egg still exists, and any
     * of them that forgets this form will either conjure a replacement for an egg that is
     * lying right there, or spawn a fresh one on the fountain while the old one waits in the
     * grass. Both end in two eggs, so this lives in one place and gets reused.
     *
     * <p>Asks the server directly rather than reading a cached handle, so it still works
     * when the loose-egg tracker never saw the spawn, was switched off by config, or lost
     * its reference across a restart.
     */
    public static List<Entity> collectLooseEggs() {
        List<Entity> found = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isDragonEgg(item.getItemStack())) {
                    found.add(item);
                }
            }
            for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
                if (fb.getBlockData().getMaterial() == Material.DRAGON_EGG) {
                    found.add(fb);
                }
            }
        }
        return found;
    }

    /** Is any egg loose in the world right now? */
    public static boolean anyLooseEgg() {
        return !collectLooseEggs().isEmpty();
    }

    /** How many egg items a loose entity represents (a dropped stack can hold more than one). */
    public static int looseCount(Entity entity) {
        if (entity instanceof Item item) {
            return item.getItemStack().getAmount();
        }
        return 1;
    }

    /**
     * Count every dragon egg in an inventory without touching it — the read-only twin of
     * {@link #purgeFrom(Inventory)}, and it has to stay in step with it. An audit that
     * searches fewer places than the purge reaches would report "all clear" about eggs the
     * plugin can plainly see, which is worse than not auditing at all.
     */
    public static int countIn(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int found = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null) {
                continue;
            }
            if (isDragonEgg(stack)) {
                found += stack.getAmount();
            } else if (isBundle(stack)) {
                found += countInBundle(stack);
            }
        }
        return found;
    }

    /** Count dragon eggs nested in a bundle (recursively), without modifying it. */
    public static int countInBundle(ItemStack bundle) {
        if (!(bundle.getItemMeta() instanceof BundleMeta meta) || !meta.hasItems()) {
            return 0;
        }
        int found = 0;
        for (ItemStack content : meta.getItems()) {
            if (content == null) {
                continue;
            }
            if (isDragonEgg(content)) {
                found += content.getAmount();
            } else if (isBundle(content)) {
                found += countInBundle(content);
            }
        }
        return found;
    }

    /** Remove dragon eggs nested in a bundle (recursively). Returns the count removed. */
    public static int cleanBundle(ItemStack bundle) {
        if (!(bundle.getItemMeta() instanceof BundleMeta meta) || !meta.hasItems()) {
            return 0;
        }
        int removed = 0;
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack content : meta.getItems()) {
            if (isDragonEgg(content)) {
                removed += content.getAmount();
            } else if (isBundle(content) && bundleContainsDragonEgg(content)) {
                removed += cleanBundle(content);
                kept.add(content);
            } else {
                kept.add(content);
            }
        }
        meta.setItems(kept);
        bundle.setItemMeta(meta);
        return removed;
    }
}
