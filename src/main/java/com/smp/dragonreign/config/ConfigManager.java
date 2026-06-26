package com.smp.dragonreign.config;

import com.smp.dragonreign.DragonReign;
import com.smp.dragonreign.announce.SoundMode;
import org.bukkit.configuration.file.FileConfiguration;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Typed view over config.yml. Reads come from the in-memory FileConfiguration so
 * they never touch disk; the GUI mutators write through and persist immediately.
 */
public final class ConfigManager {

    private final DragonReign plugin;

    public ConfigManager(DragonReign plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        ensureSalt();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        ensureSalt();
        // Touch the values once so any obviously-bad numbers get clamped/warned now
        // rather than at first use.
        if (getInactivityDays() < 1) {
            plugin.getLogger().warning("inactivity-days was < 1; treating as 1.");
        }
    }

    /**
     * On first run the IP-hash salt is blank; generate a strong random one and write it
     * back exactly once. This makes stored hashes non-reversible and non-comparable
     * across servers. Idempotent — once a salt exists we never touch it (changing it
     * would silently invalidate every hash already on record).
     */
    private void ensureSalt() {
        String salt = cfg().getString("strict-ownership.ip-hash-salt", "");
        if (salt == null || salt.isEmpty()) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String generated = Base64.getEncoder().encodeToString(raw);
            cfg().set("strict-ownership.ip-hash-salt", generated);
            save();
            plugin.getLogger().info("Generated a fresh IP-hash salt (stored in config.yml; never share it).");
        }
    }

    private void save() {
        plugin.saveConfig();
    }

    // ── Protection toggles ──────────────────────────────────────────────────

    public boolean isNoContainers() {
        return cfg().getBoolean("no-containers", true);
    }

    public void setNoContainers(boolean value) {
        cfg().set("no-containers", value);
        save();
    }

    public boolean isNoDrop() {
        return cfg().getBoolean("no-drop", true);
    }

    public void setNoDrop(boolean value) {
        cfg().set("no-drop", value);
        save();
    }

    public boolean isEnderSweepEnabled() {
        return cfg().getBoolean("enderchest-sweep.enabled", true);
    }

    public void setEnderSweepEnabled(boolean value) {
        cfg().set("enderchest-sweep.enabled", value);
        save();
    }

    public int getEnderSweepIntervalMinutes() {
        return Math.max(1, cfg().getInt("enderchest-sweep.interval-minutes", 5));
    }

    public boolean isRespawnEnabled() {
        return cfg().getBoolean("respawn-on-inactivity.enabled", true);
    }

    public void setRespawnEnabled(boolean value) {
        cfg().set("respawn-on-inactivity.enabled", value);
        save();
    }

    public int getInactivityDays() {
        return Math.max(1, cfg().getInt("respawn-on-inactivity.inactivity-days", 14));
    }

    public void setInactivityDays(int days) {
        cfg().set("respawn-on-inactivity.inactivity-days", Math.max(1, days));
        save();
    }

    public int getCheckIntervalMinutes() {
        return Math.max(1, cfg().getInt("respawn-on-inactivity.check-interval-minutes", 5));
    }

    // ── Announcement ────────────────────────────────────────────────────────

    public boolean isAnnounceEnabled() {
        return cfg().getBoolean("announce.enabled", true);
    }

    public void setAnnounceEnabled(boolean value) {
        cfg().set("announce.enabled", value);
        save();
    }

    public List<String> getAnnounceChat() {
        List<String> lines = cfg().getStringList("announce.chat");
        if (lines.isEmpty()) {
            return Arrays.asList("<dark_purple>The Dragon Egg has returned to the End!</dark_purple>");
        }
        return lines;
    }

    public String getTitleText() {
        return cfg().getString("announce.title.title", "<light_purple>The Egg Reigns Again</light_purple>");
    }

    public String getSubtitleText() {
        return cfg().getString("announce.title.subtitle", "<gray>A new keeper is needed</gray>");
    }

    public int getTitleFadeIn() {
        return Math.max(0, cfg().getInt("announce.title.fade-in-ticks", 10));
    }

    public int getTitleStay() {
        return Math.max(0, cfg().getInt("announce.title.stay-ticks", 70));
    }

    public int getTitleFadeOut() {
        return Math.max(0, cfg().getInt("announce.title.fade-out-ticks", 20));
    }

    public SoundMode getSoundMode() {
        return SoundMode.parse(cfg().getString("announce.sound.mode", "BOTH"));
    }

    public void setSoundMode(SoundMode mode) {
        cfg().set("announce.sound.mode", mode.name());
        save();
    }

    public String getCustomSoundKey() {
        return cfg().getString("announce.sound.custom-key", "minecraft:entity.ender_dragon.growl");
    }

    // ── Bookkeeping ─────────────────────────────────────────────────────────

    public int getMaxHistory() {
        return Math.max(1, cfg().getInt("history.max-entries", 1000));
    }

    public boolean isLogBlocksToHistory() {
        return cfg().getBoolean("log-blocks-to-history", true);
    }

    // ── Messages ────────────────────────────────────────────────────────────

    public String getPrefix() {
        return cfg().getString("messages.prefix", "<dark_purple>[DragonReign]</dark_purple> ");
    }

    public String getBlockedContainerMessage() {
        return cfg().getString("messages.blocked-container", "<red>The Dragon Egg can't go in there.</red>");
    }

    public String getBlockedBundleMessage() {
        return cfg().getString("messages.blocked-bundle", "<red>The Dragon Egg can't go in a bundle.</red>");
    }

    public String getBlockedDropMessage() {
        return cfg().getString("messages.blocked-drop", "<red>You can't drop the Dragon Egg.</red>");
    }

    // ── Respawn countdown (v2) ────────────────────────────────────────────────

    public boolean isCountdownEnabled() {
        return cfg().getBoolean("respawn-countdown.enabled", true);
    }

    public void setCountdownEnabled(boolean value) {
        cfg().set("respawn-countdown.enabled", value);
        save();
    }

    public int getCountdownDurationSeconds() {
        return Math.max(1, cfg().getInt("respawn-countdown.duration-seconds", 300));
    }

    /** Seconds-remaining marks at which to broadcast a warning. Sane default if unset. */
    public List<Integer> getCountdownWarnSeconds() {
        List<Integer> marks = cfg().getIntegerList("respawn-countdown.warn-at-seconds");
        if (marks.isEmpty()) {
            return new ArrayList<>(Arrays.asList(300, 60, 30, 10, 5, 4, 3, 2, 1));
        }
        return marks;
    }

    public boolean isAbortOnOwnerReturn() {
        return cfg().getBoolean("respawn-countdown.abort-on-owner-return", true);
    }

    public String getCountdownTickSound() {
        return cfg().getString("respawn-countdown.tick-sound", "minecraft:block.note_block.pling");
    }

    // ── Strict ownership + IP/alt detection (v2) ──────────────────────────────

    public boolean isStrictOwnershipEnabled() {
        return cfg().getBoolean("strict-ownership.enabled", false);
    }

    public void setStrictOwnershipEnabled(boolean value) {
        cfg().set("strict-ownership.enabled", value);
        save();
    }

    public int getMinReceiverActiveDays() {
        return Math.max(1, cfg().getInt("strict-ownership.min-receiver-active-days", 7));
    }

    public boolean isCheckIpAlts() {
        return cfg().getBoolean("strict-ownership.check-ip-alts", true);
    }

    public boolean isAutoEnforceIpLinks() {
        return cfg().getBoolean("strict-ownership.auto-enforce-ip-links", false);
    }

    /**
     * Max distinct accounts allowed to share one IP hash before that hash is treated as a
     * CGNAT/VPN/shared endpoint and ignored for alt-grouping. 0 = no cap. Default 8.
     */
    public int getMaxIpGroup() {
        return Math.max(0, cfg().getInt("strict-ownership.max-ip-group", 8));
    }

    public String getIpHashSalt() {
        return cfg().getString("strict-ownership.ip-hash-salt", "");
    }

    // ── Staff inbox (v2) ──────────────────────────────────────────────────────

    public boolean isInboxEnabled() {
        return cfg().getBoolean("inbox.enabled", true);
    }

    public boolean isInboxNotifyOnJoin() {
        return cfg().getBoolean("inbox.notify-on-join", true);
    }

    public int getInboxMaxEntries() {
        return Math.max(1, cfg().getInt("inbox.max-entries", 200));
    }

    // ── v2 messages ───────────────────────────────────────────────────────────

    public String getCountdownStartedMessage() {
        return cfg().getString("messages.countdown-started",
                "<gold>The egg's keeper has vanished — it returns to the End in <seconds>s unless they return!</gold>");
    }

    public String getCountdownWarnMessage() {
        return cfg().getString("messages.countdown-warn",
                "<yellow>The Dragon Egg respawns in <white><seconds>s</white> — get to the End!</yellow>");
    }

    public String getKeeperReturnedMessage() {
        return cfg().getString("messages.keeper-returned",
                "<green><player> returned in time — the Dragon Egg stays where it is.</green>");
    }

    /** On-screen title shown at each countdown warn mark. {@code <seconds>} is substituted. */
    public String getCountdownTitle() {
        return cfg().getString("messages.countdown-title",
                "<gradient:#b388ff:#5e2b97>Egg Respawn</gradient>");
    }

    public String getCountdownSubtitle() {
        return cfg().getString("messages.countdown-subtitle",
                "<yellow><seconds>s</yellow> <gray>until it returns to the End</gray>");
    }

    // ── Advanced ────────────────────────────────────────────────────────────

    public String getEndWorldName() {
        return cfg().getString("end-world-name", "");
    }
}
