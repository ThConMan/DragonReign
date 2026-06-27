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

    // ── Proximity compass (v1.2) ──────────────────────────────────────────────

    public boolean isCompassEnabled() {
        return cfg().getBoolean("compass.enabled", true);
    }

    public void setCompassEnabled(boolean value) {
        cfg().set("compass.enabled", value);
        save();
    }

    public int getCompassRadius() {
        // Clamped to a sane maximum: a very large radius would broadcast a directional
        // arrow toward the hidden egg to everyone in range, leaking a base from far away.
        return Math.min(64, Math.max(1, cfg().getInt("compass.radius", 16)));
    }

    public int getCompassUpdateTicks() {
        return Math.max(1, cfg().getInt("compass.update-ticks", 10));
    }

    public boolean isCompassShowToOwner() {
        return cfg().getBoolean("compass.show-to-owner", false);
    }

    // ── Egg-staleness respawn (v1.2) ──────────────────────────────────────────

    /**
     * Days the egg may sit untouched before it respawns even with the owner online.
     * 0 = off. Must be less than inactivity-days, or it would never get a chance to
     * fire ahead of the plain inactivity timer; if it's set too high we log once and
     * treat it as off.
     */
    public int getStalenessDays() {
        int days = cfg().getInt("respawn-on-inactivity.staleness-days", 10);
        if (days <= 0) {
            return 0;
        }
        if (days >= getInactivityDays()) {
            plugin.getLogger().warning("respawn-on-inactivity.staleness-days (" + days
                    + ") must be less than inactivity-days (" + getInactivityDays()
                    + "); treating staleness respawn as off.");
            return 0;
        }
        return days;
    }

    /** The stored staleness value as-is (no validation), for display and GUI adjustment. */
    public int getStalenessDaysRaw() {
        return Math.max(0, cfg().getInt("respawn-on-inactivity.staleness-days", 10));
    }

    public void setStalenessDays(int days) {
        cfg().set("respawn-on-inactivity.staleness-days", Math.max(0, days));
        save();
    }

    // ── Hold rewards (v1.2) ───────────────────────────────────────────────────

    public boolean isRewardsEnabled() {
        return cfg().getBoolean("rewards.enabled", true);
    }

    public void setRewardsEnabled(boolean value) {
        cfg().set("rewards.enabled", value);
        save();
    }

    public int getRewardIntervalMinutes() {
        return Math.max(1, cfg().getInt("rewards.interval-minutes", 60));
    }

    public boolean isRewardResetOnLoss() {
        return cfg().getBoolean("rewards.reset-on-loss", true);
    }

    /**
     * The reward ladder: each entry is a list of console commands to run for that tier,
     * with {@code %player%} and {@code %tier%} substituted. Returns an empty list if
     * nothing is configured. Defensive against malformed YAML (skips non-list rows).
     */
    public List<List<String>> getRewardTiers() {
        List<List<String>> out = new ArrayList<>();
        List<?> raw = cfg().getList("rewards.tiers");
        if (raw == null) {
            return out;
        }
        for (Object row : raw) {
            if (row instanceof List<?> cmds) {
                List<String> tier = new ArrayList<>();
                for (Object cmd : cmds) {
                    if (cmd != null) {
                        tier.add(cmd.toString());
                    }
                }
                if (!tier.isEmpty()) {
                    out.add(tier);
                }
            }
        }
        return out;
    }

    public String getRewardEarnedMessage() {
        return cfg().getString("messages.reward-earned",
                "<gold>You held the Dragon Egg long enough to earn a reward! (reward <tier>)</gold>");
    }

    // ── Anti-AFK (v1.2) ───────────────────────────────────────────────────────

    public boolean isAfkEnabled() {
        return cfg().getBoolean("afk.enabled", true);
    }

    public void setAfkEnabled(boolean value) {
        cfg().set("afk.enabled", value);
        save();
    }

    public int getAfkIdleSeconds() {
        return Math.max(1, cfg().getInt("afk.idle-seconds", 300));
    }

    // ── Void / loss safety (v1.2) ─────────────────────────────────────────────

    public boolean isVoidSafetyEnabled() {
        return cfg().getBoolean("void-safety.enabled", true);
    }

    public void setVoidSafetyEnabled(boolean value) {
        cfg().set("void-safety.enabled", value);
        save();
    }

    public int getVoidCheckTicks() {
        // Clamped to at most 1s: a loose egg falls at terminal velocity, so a wider check
        // window could let it cross the void-kill plane between checks and be lost for good.
        // Default is low so lava/water buoyancy stays smooth (it rides the same check).
        return Math.min(20, Math.max(1, cfg().getInt("void-safety.check-ticks", 4)));
    }

    public boolean isEggFireproof() {
        // Make a loose (dropped/falling) egg invulnerable so lava, fire, cactus and explosions
        // can't destroy the one unique egg. The placed block and inventory forms are unaffected.
        return cfg().getBoolean("void-safety.fireproof", true);
    }

    // ── Victor prestige cosmetics (v1.2) ──────────────────────────────────────

    public int getVictorThresholdHours() {
        return Math.max(1, cfg().getInt("victor.threshold-hours", 168));
    }

    public String getVictorTitle() {
        return cfg().getString("victor.title", "&6Dragonlord");
    }

    public boolean isChatEnabled() {
        return cfg().getBoolean("chat.enabled", false);
    }

    public String getChatFormat() {
        // &r&f&l before {name} stops the title's gradient from colouring the name — it
        // stays bold white. A colour code in the suffix resets the bold again afterwards.
        return cfg().getString("chat.format", "%luckperms_prefix%{title} &r&f&l{name}%luckperms_suffix%&8: &f");
    }

    public boolean isVictorTitleEnabled() {
        return cfg().getBoolean("victor.title-enabled", true);
    }

    public void setVictorTitleEnabled(boolean value) {
        cfg().set("victor.title-enabled", value);
        save();
    }

    public boolean isVictorParticleEnabled() {
        return cfg().getBoolean("victor.particle-enabled", true);
    }

    public void setVictorParticleEnabled(boolean value) {
        cfg().set("victor.particle-enabled", value);
        save();
    }

    public String getVictorParticle() {
        // DUST_COLOR_TRANSITION holds a TIGHT knot (the motes stay put) and shimmers colour —
        // so the spirits read as little balls. PORTAL flies outward and only ever makes a cloud,
        // so it's not the default. PORTAL/DUST are still selectable.
        return cfg().getString("victor.particle", "DUST_COLOR_TRANSITION");
    }

    public String getVictorAuraColor() {
        return cfg().getString("victor.aura-color", "#9D4EDD"); // ender purple (gradient start)
    }

    public String getVictorAuraColor2() {
        return cfg().getString("victor.aura-color-2", "#4ED9FF"); // spectral cyan (gradient end)
    }

    public double getVictorSpiritSize() {
        return Math.max(0.2, cfg().getDouble("victor.spirit-size", 0.45));
    }

    public double getVictorAuraSize() {
        return Math.max(0.2, cfg().getDouble("victor.aura-size", 0.6));
    }

    public String getVictorParticleMotion() {
        // How PORTAL (non-DUST) motes drift: still | inward | outward | up | swirl. Live-tunable.
        return cfg().getString("victor.particle-motion", "still");
    }

    public double getVictorParticleMotionSpeed() {
        return Math.max(0.0, cfg().getDouble("victor.particle-motion-speed", 0.5));
    }

    public double getVictorViewClearRadius() {
        // How wide a column around the player stays particle-free at face height. Bigger =
        // clearer first-person view but less aura hugging the player. Live-tunable.
        return Math.max(0.0, cfg().getDouble("victor.view-clear-radius", 2.4));
    }

    public int getVictorParticleDensity() {
        return Math.max(1, cfg().getInt("victor.particle-density", 5));
    }

    public boolean isVictorWingsEnabled() {
        return cfg().getBoolean("victor.wings-enabled", false);
    }

    public boolean isVictorVortexEnabled() {
        return cfg().getBoolean("victor.vortex-enabled", false);
    }

    public boolean isVictorGroundEnabled() {
        return cfg().getBoolean("victor.ground-enabled", false);
    }

    public boolean isVictorOrbitEnabled() {
        return cfg().getBoolean("victor.orbit-enabled", true);
    }

    public int getVictorOrbitCount() {
        // Number of wandering spirit-wisps; capped so a huge value can't lag the server.
        return Math.max(0, Math.min(24, cfg().getInt("victor.orbit-count", 7)));
    }

    public int getVictorParticleIntervalTicks() {
        // Low (every 2 ticks) so the spirits flow smoothly instead of jumping/pulsing. The
        // per-emission counts/density are scaled down to keep the on-screen thickness sane.
        return Math.max(1, cfg().getInt("victor.particle-interval-ticks", 2));
    }

    public boolean isVictorEmberEnabled() {
        return cfg().getBoolean("victor.ember-enabled", true);
    }

    public String getVictorEmberParticle() {
        return cfg().getString("victor.ember-particle", "SOUL_FIRE_FLAME");
    }

    public String getVictorEmberParticle2() {
        // Second fire type, randomly mixed with ember-particle so blue + red fire blend.
        return cfg().getString("victor.ember-particle-2", "FLAME");
    }

    public double getVictorLivewireIntensity() {
        // 0 = calm (no surges); 1 = default crackle; higher = more violent. Live-tunable.
        return Math.max(0.0, Math.min(3.0, cfg().getDouble("victor.livewire-intensity", 1.0)));
    }

    public boolean isVictorCombatReactive() {
        return cfg().getBoolean("victor.combat-reactivity", true);
    }

    public boolean isVictorArcsEnabled() {
        return cfg().getBoolean("victor.arcs-enabled", true);
    }

    public boolean isVictorCoronationEnabled() {
        return cfg().getBoolean("victor.coronation-enabled", true);
    }

    public boolean isVictorSurgeSound() {
        return cfg().getBoolean("victor.surge-sound", false);
    }

    public int getVictorEmberCount() {
        return Math.max(0, cfg().getInt("victor.ember-count", 2));
    }

    public boolean isVictorHitEnabled() {
        return cfg().getBoolean("victor.hit-enabled", true);
    }

    public String getVictorHitParticle() {
        return cfg().getString("victor.hit-particle", "PORTAL");
    }

    public int getVictorHitCount() {
        // Kept small on purpose: this fires on every landed hit, so lag matters more than size.
        return Math.max(1, cfg().getInt("victor.hit-count", 10));
    }

    public boolean isVictorLuckPermsMeta() {
        return cfg().getBoolean("victor.luckperms-meta", false);
    }

    public String getVictorEarnedMessage() {
        return cfg().getString("messages.victor-earned",
                "<gold>You are now a Dragonlord — your prestige cosmetics are unlocked.</gold>");
    }

    // ── Hold-time accrual (v1.2, advanced) ────────────────────────────────────

    public int getHoldAccrualTicks() {
        return Math.max(1, cfg().getInt("hold-time.accrual-ticks", 100));
    }

    /**
     * When true, reward and Dragonlord time only accrue while the owner is genuinely with
     * the egg — carrying it, or standing within {@link #getHoldPresenceRadius()} of the
     * placed block. Stops a parked egg from farming time while the owner is elsewhere.
     */
    public boolean isHoldRequirePresence() {
        return cfg().getBoolean("hold-time.require-presence", true);
    }

    public int getHoldPresenceRadius() {
        return Math.max(1, cfg().getInt("hold-time.presence-radius", 16));
    }

    // ── Advanced ────────────────────────────────────────────────────────────

    public String getEndWorldName() {
        return cfg().getString("end-world-name", "");
    }
}
