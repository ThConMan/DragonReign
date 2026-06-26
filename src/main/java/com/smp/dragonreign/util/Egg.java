package com.smp.dragonreign.util;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
