package com.smp.dragonreign.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Thin wrappers so the rest of the code isn't littered with scheduler boilerplate. */
public final class Scheduling {

    private Scheduling() {
    }

    public static BukkitTask timer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public static BukkitTask later(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static BukkitTask async(Plugin plugin, Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static long minutesToTicks(long minutes) {
        return Math.max(1L, minutes) * 60L * 20L;
    }
}
