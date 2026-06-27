package com.smp.dragonreign;

import com.smp.dragonreign.afk.AfkCheck;
import com.smp.dragonreign.announce.AnnouncementService;
import com.smp.dragonreign.command.DragonReignCommand;
import com.smp.dragonreign.command.GiveEggCommand;
import com.smp.dragonreign.config.ConfigManager;
import com.smp.dragonreign.gui.ConfigGui;
import com.smp.dragonreign.gui.CosmeticsGui;
import com.smp.dragonreign.gui.GuiListener;
import com.smp.dragonreign.gui.HistoryGui;
import com.smp.dragonreign.gui.InboxGui;
import com.smp.dragonreign.hook.LuckPermsHook;
import com.smp.dragonreign.inbox.Inbox;
import com.smp.dragonreign.listener.ContainerProtectionListener;
import com.smp.dragonreign.listener.DropProtectionListener;
import com.smp.dragonreign.listener.EggTrackingListener;
import com.smp.dragonreign.ownership.IpRegistry;
import com.smp.dragonreign.ownership.OwnershipPolicy;
import com.smp.dragonreign.papi.DragonReignExpansion;
import com.smp.dragonreign.reward.RewardManager;
import com.smp.dragonreign.store.EggDataStore;
import com.smp.dragonreign.store.HistoryLog;
import com.smp.dragonreign.task.CompassTask;
import com.smp.dragonreign.task.CountdownManager;
import com.smp.dragonreign.task.EnderChestSweepTask;
import com.smp.dragonreign.task.HoldTimeTask;
import com.smp.dragonreign.task.InactivityTask;
import com.smp.dragonreign.task.ParticleTask;
import com.smp.dragonreign.task.RespawnSequence;
import com.smp.dragonreign.task.VoidGuardian;
import com.smp.dragonreign.model.EggLocation;
import com.smp.dragonreign.util.EndPortalEggSpawner;
import com.smp.dragonreign.util.Msg;
import com.smp.dragonreign.util.Scheduling;
import com.smp.dragonreign.victor.VictorManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DragonReign — keeps a server's one special Dragon Egg honest. Wiring only; the
 * game logic lives in the listeners, tasks, GUIs, and services this bootstraps.
 */
public final class DragonReign extends JavaPlugin {

    private ConfigManager config;
    private EggDataStore store;
    private HistoryLog history;
    private EndPortalEggSpawner spawner;
    private AnnouncementService announce;
    private ConfigGui configGui;
    private HistoryGui historyGui;

    // v2 services
    private IpRegistry ipRegistry;
    private OwnershipPolicy ownership;
    private Inbox inbox;
    private CountdownManager countdown;
    private InboxGui inboxGui;

    // v1.2 services
    private AfkCheck afk;
    private RewardManager rewards;
    private VictorManager victors;
    private LuckPermsHook luckPerms;
    private CosmeticsGui cosmeticsGui;
    private VoidGuardian voidGuardian;

    private BukkitTask inactivityTask;
    private BukkitTask sweepTask;
    private BukkitTask autosaveTask;
    private BukkitTask compassTask;
    private BukkitTask particleTask;
    private BukkitTask holdTimeTask;
    private BukkitTask voidTask;

    // Single-thread writer: snapshots are taken on the main thread in call order, and
    // this executor drains them in that same submission order so a slow earlier write
    // can never be overtaken by a later one and persist a stale snapshot.
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DragonReign-save");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void onEnable() {
        this.config = new ConfigManager(this);

        this.history = new HistoryLog(config, getLogger());
        File dataFile = new File(getDataFolder(), "data.yml");
        this.store = new EggDataStore(dataFile, getLogger(), history);
        this.store.load();

        this.spawner = new EndPortalEggSpawner(config, getLogger());
        this.announce = new AnnouncementService(config, spawner, getLogger());

        // v2 services. IP registry + inbox each own their own file; the ownership policy
        // and countdown manager are pure logic that lean on the singletons above.
        this.ipRegistry = new IpRegistry(new File(getDataFolder(), "players.yml"), getLogger());
        this.ipRegistry.load();
        this.inbox = new Inbox(config, new File(getDataFolder(), "inbox.yml"), getLogger());
        this.inbox.load();
        this.ownership = new OwnershipPolicy(this);
        this.countdown = new CountdownManager(this);

        // One hook, evaluated on every real ownership change (place / pickup / giveegg).
        this.store.setOwnerChangedHook(ownership::evaluateTransfer);
        // Losing the egg resets the hold-reward ladder unless the server turned that off.
        this.store.setRewardResetPolicy(() -> config.isRewardResetOnLoss());

        // v1.2 services. The AFK check and reward/victor managers are pure logic; the
        // LuckPerms hook and PlaceholderAPI expansion are soft and guard themselves.
        this.afk = new AfkCheck(this);
        this.luckPerms = new LuckPermsHook(this);
        this.rewards = new RewardManager(this);
        this.victors = new VictorManager(this, new File(getDataFolder(), "victors.yml"), getLogger());
        this.victors.load();

        this.configGui = new ConfigGui(this);
        this.historyGui = new HistoryGui(this);
        this.inboxGui = new InboxGui(this);
        this.cosmeticsGui = new CosmeticsGui(this);

        getServer().getPluginManager().registerEvents(new ContainerProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new DropProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new EggTrackingListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        this.voidGuardian = new VoidGuardian(this, new RespawnSequence(this));
        getServer().getPluginManager().registerEvents(voidGuardian, this);

        // Register the PlaceholderAPI expansion only when PlaceholderAPI is present.
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new DragonReignExpansion(this).register();
                getLogger().info("Registered the PlaceholderAPI expansion (dragonreign).");
            } catch (Throwable t) {
                getLogger().warning("Could not register the PlaceholderAPI expansion: " + t.getMessage());
            }
        }

        DragonReignCommand main = new DragonReignCommand(this);
        if (getCommand("dragonreign") != null) {
            getCommand("dragonreign").setExecutor(main);
            getCommand("dragonreign").setTabCompleter(main);
        }
        GiveEggCommand give = new GiveEggCommand(this);
        if (getCommand("giveegg") != null) {
            getCommand("giveegg").setExecutor(give);
            getCommand("giveegg").setTabCompleter(give);
        }

        startTasks();
        // Self-heal: a crash between the data.yml flush and the world's own autosave can
        // leave data.yml saying "unowned egg at <loc>" with no egg block actually there
        // (or a stray block left behind). Re-place the missing fountain egg one tick in,
        // once worlds/chunks are certainly ready. Cheap, and recoverable by no command.
        Scheduling.later(this, this::reconcileEgg, 1L);
        getLogger().info("DragonReign enabled — the egg is under watch.");
    }

    /**
     * If tracking says the egg is unowned and sitting at a known block but that block
     * isn't a DRAGON_EGG, put it back. Only acts on the unowned case: an owned egg is
     * held by a player, not expected as a world block.
     */
    private void reconcileEgg() {
        if (store.getOwner() != null) {
            return;
        }
        EggLocation loc = store.getLocation();
        if (loc == null) {
            return;
        }
        Optional<Location> bukkit = loc.toBukkit();
        if (bukkit.isEmpty()) {
            return; // End world not loaded yet — leave it; nothing to safely do
        }
        Block block = bukkit.get().getBlock();
        if (block.getType() != Material.DRAGON_EGG) {
            block.setType(Material.DRAGON_EGG, false);
            getLogger().warning("Reconciled missing fountain egg at " + loc.compact()
                    + " (data.yml expected an unowned egg there but the block was "
                    + block.getType() + ").");
            inbox.post(com.smp.dragonreign.inbox.Severity.WARN, "Egg reconciled on startup",
                    "The tracked egg block at " + loc.compact() + " was missing after a restart "
                            + "and has been re-placed.", (java.util.UUID[]) null);
            saveAsync();
        }
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (countdown != null) {
            countdown.shutdown(); // stop the per-second ticker; it's in-memory only
        }
        Msg.clearThrottle();
        // Stop accepting new async writes and let any in-flight one finish before the
        // synchronous flush below, so ordering is preserved right to the end.
        saveExecutor.shutdown();
        try {
            saveExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // Disable isn't a safe place for async work — flush all three files synchronously.
        if (store != null) {
            store.saveSync();
        }
        if (ipRegistry != null) {
            ipRegistry.saveSync();
        }
        if (inbox != null) {
            inbox.saveSync();
        }
        if (victors != null) {
            victors.saveSync();
        }
        getLogger().info("DragonReign disabled — state saved.");
    }

    // ── Task lifecycle ────────────────────────────────────────────────────────

    private void startTasks() {
        long checkPeriod = Scheduling.minutesToTicks(config.getCheckIntervalMinutes());
        inactivityTask = new InactivityTask(this).runTaskTimer(this, checkPeriod, checkPeriod);

        long sweepPeriod = Scheduling.minutesToTicks(config.getEnderSweepIntervalMinutes());
        sweepTask = new EnderChestSweepTask(this).runTaskTimer(this, sweepPeriod, sweepPeriod);

        // Periodic snapshot-and-write so a crash never loses more than a few minutes.
        long autosavePeriod = Scheduling.minutesToTicks(5);
        autosaveTask = Scheduling.timer(this, this::saveAsync, autosavePeriod, autosavePeriod);

        // v1.2 interval tasks. Each early-returns when there's nothing to do.
        long compassPeriod = config.getCompassUpdateTicks();
        compassTask = new CompassTask(this).runTaskTimer(this, compassPeriod, compassPeriod);

        long particlePeriod = config.getVictorParticleIntervalTicks();
        particleTask = new ParticleTask(this).runTaskTimer(this, particlePeriod, particlePeriod);

        long holdPeriod = config.getHoldAccrualTicks();
        holdTimeTask = new HoldTimeTask(this).runTaskTimer(this, holdPeriod, holdPeriod);

        long voidPeriod = config.getVoidCheckTicks();
        voidTask = Scheduling.timer(this, voidGuardian::tick, voidPeriod, voidPeriod);
    }

    private void stopTasks() {
        if (inactivityTask != null) {
            inactivityTask.cancel();
            inactivityTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (compassTask != null) {
            compassTask.cancel();
            compassTask = null;
        }
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        if (holdTimeTask != null) {
            holdTimeTask.cancel();
            holdTimeTask = null;
        }
        if (voidTask != null) {
            voidTask.cancel();
            voidTask = null;
        }
    }

    /** Re-read config.yml and restart tasks so any changed intervals take effect. */
    public void reloadEverything() {
        config.reload();
        victors.invalidateTitleCache(); // title text may have changed; drop the PAPI hot-path cache
        stopTasks();
        startTasks();
    }

    /**
     * Snapshot all three persisted files on the main thread (cheap copies), then write
     * them off-thread. Snapshotting first means the async writer never reads live state.
     */
    public void saveAsync() {
        YamlConfiguration eggYaml = store.buildSnapshotYaml();
        YamlConfiguration playersYaml = ipRegistry.buildSnapshotYaml();
        YamlConfiguration inboxYaml = inbox.buildSnapshotYaml();
        YamlConfiguration victorsYaml = victors.buildSnapshotYaml();
        // Drain on the single-thread writer in submission order (NOT the Bukkit async
        // pool, which gives no ordering guarantee and could persist a stale snapshot).
        if (saveExecutor.isShutdown()) {
            return; // disabling — onDisable flushes synchronously
        }
        try {
            saveExecutor.execute(() -> {
                store.write(eggYaml);
                ipRegistry.write(playersYaml);
                inbox.write(inboxYaml);
                victors.write(victorsYaml);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Raced with shutdown; the synchronous flush in onDisable covers it.
        }
    }

    // ── Singletons ──────────────────────────────────────────────────────────

    public ConfigManager config() {
        return config;
    }

    public EggDataStore store() {
        return store;
    }

    public HistoryLog history() {
        return history;
    }

    public EndPortalEggSpawner spawner() {
        return spawner;
    }

    public AnnouncementService announce() {
        return announce;
    }

    public ConfigGui configGui() {
        return configGui;
    }

    public HistoryGui historyGui() {
        return historyGui;
    }

    public IpRegistry ipRegistry() {
        return ipRegistry;
    }

    public OwnershipPolicy ownership() {
        return ownership;
    }

    public Inbox inbox() {
        return inbox;
    }

    public CountdownManager countdown() {
        return countdown;
    }

    public InboxGui inboxGui() {
        return inboxGui;
    }

    public AfkCheck afk() {
        return afk;
    }

    public RewardManager rewards() {
        return rewards;
    }

    public VictorManager victors() {
        return victors;
    }

    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public CosmeticsGui cosmeticsGui() {
        return cosmeticsGui;
    }
}
