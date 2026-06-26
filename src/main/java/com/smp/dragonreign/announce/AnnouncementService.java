package com.smp.dragonreign.announce;

import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.util.EndPortalEggSpawner;
import com.smp.dragonreign.util.Msg;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Fires the full respawn fanfare: chat broadcast, a screen title for everyone, and
 * the configured sound effect (lightning, the dragon's death roar, both, or custom).
 */
public final class AnnouncementService {

    private final ConfigManager config;
    private final EndPortalEggSpawner spawner;
    private final Logger logger;

    public AnnouncementService(ConfigManager config, EndPortalEggSpawner spawner, Logger logger) {
        this.config = config;
        this.spawner = spawner;
        this.logger = logger;
    }

    public void broadcastRespawn(EggLocation eggLocation) {
        if (!config.isAnnounceEnabled()) {
            return;
        }
        broadcastChat();
        showTitleToAll();
        playSound(eggLocation);
    }

    // ── Countdown messaging (feature v2.1) ─────────────────────────────────────
    // Deliberately NOT gated on announce.enabled: the whole point of the countdown is
    // to summon players to contest the egg at the End, so they must always hear it.

    /** A warn mark — chat line, screen title, and the configured tick sound for everyone. */
    public void broadcastCountdownWarn(int secondsLeft) {
        String line = config.getCountdownWarnMessage().replace("<seconds>", Integer.toString(secondsLeft));
        Bukkit.broadcast(Msg.prefixed(config.getPrefix(), line));

        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(800), Duration.ofMillis(200));
        String seconds = Integer.toString(secondsLeft);
        Title title = Title.title(
                Msg.mm(config.getCountdownTitle().replace("<seconds>", seconds)),
                Msg.mm(config.getCountdownSubtitle().replace("<seconds>", seconds)),
                times);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
        playTickSound();
    }

    /** Announced once when a countdown begins. */
    public void broadcastCountdownStarted(int totalSeconds) {
        String line = config.getCountdownStartedMessage().replace("<seconds>", Integer.toString(totalSeconds));
        Bukkit.broadcast(Msg.prefixed(config.getPrefix(), line));
    }

    /** The keeper came back in time and aborted the countdown. */
    public void broadcastKeeperReturned(String name) {
        String line = config.getKeeperReturnedMessage().replace("<player>", Msg.escape(name == null ? "?" : name));
        Bukkit.broadcast(Msg.prefixed(config.getPrefix(), line));
    }

    private void playTickSound() {
        String key = config.getCountdownTickSound();
        if (key == null || key.isEmpty()) {
            return;
        }
        Sound sound;
        try {
            sound = Sound.sound(Key.key(key), Sound.Source.MASTER, 1.0f, 1.0f);
        } catch (Exception ex) {
            logger.warning("Invalid countdown tick-sound key '" + key + "': " + ex.getMessage());
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(sound);
        }
    }

    private void broadcastChat() {
        for (String line : config.getAnnounceChat()) {
            Bukkit.broadcast(Msg.mm(line));
        }
    }

    private void showTitleToAll() {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(config.getTitleFadeIn() * 50L),
                Duration.ofMillis(config.getTitleStay() * 50L),
                Duration.ofMillis(config.getTitleFadeOut() * 50L));
        Title title = Title.title(Msg.mm(config.getTitleText()), Msg.mm(config.getSubtitleText()), times);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
    }

    private void playSound(EggLocation eggLocation) {
        SoundMode mode = config.getSoundMode();
        switch (mode) {
            case NONE -> {
                // intentionally silent
            }
            case LIGHTNING -> doLightning(eggLocation);
            case DRAGON_DEATH -> doDragonDeath();
            case BOTH -> {
                doLightning(eggLocation);
                doDragonDeath();
            }
            case CUSTOM -> doCustom();
        }
    }

    private void doLightning(EggLocation eggLocation) {
        // Visual + local thunder at the portal...
        Location strike = resolveStrikeLocation(eggLocation);
        if (strike != null && strike.getWorld() != null) {
            strike.getWorld().strikeLightningEffect(strike);
        }
        // ...and a thunder clap for everyone, wherever they are.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        }
    }

    private void doDragonDeath() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 1.0f, 1.0f);
        }
    }

    private void doCustom() {
        String key = config.getCustomSoundKey();
        Sound sound;
        try {
            sound = Sound.sound(Key.key(key), Sound.Source.MASTER, 1.0f, 1.0f);
        } catch (Exception ex) {
            logger.warning("Invalid custom sound key '" + key + "': " + ex.getMessage());
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(sound);
        }
    }

    private Location resolveStrikeLocation(EggLocation eggLocation) {
        if (eggLocation != null) {
            Optional<Location> loc = eggLocation.toBukkit();
            if (loc.isPresent()) {
                return loc.get();
            }
        }
        // Fall back to the End world's portal column.
        Optional<World> end = spawner.findEndWorld();
        if (end.isPresent()) {
            World w = end.get();
            if (w.getEnderDragonBattle() != null && w.getEnderDragonBattle().getEndPortalLocation() != null) {
                return w.getEnderDragonBattle().getEndPortalLocation();
            }
            return new Location(w, 0, w.getHighestBlockYAt(0, 0) + 1, 0);
        }
        return null;
    }
}
