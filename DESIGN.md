# DragonReign — Implementation Design

Paper 26.1.2 (MC 26.1.2) plugin that enforces a single special Dragon Egg's rules on an SMP.
Compiled `--release 21`, runs on Java 25. Package root: `com.smp.dragonreign`.

This document is the source of truth for the class breakdown, the data-store and config
schemas, and the handful of correctness traps that actually matter. Implementers should
not deviate from the schemas without updating this file.

---

## 1. Guiding principles

- **One egg, all items.** We never try to mark "the" egg item with an NBT tag — the rules
  apply to *every* `DRAGON_EGG` item and block, full stop. That makes the protections
  stateless and impossible to launder by crafting/duping a "fake" egg. The *tracking*
  state (owner/location) is about the conceptual single egg, kept in `data.yml`.
- **Cancel early, log once.** Protection listeners run at `HIGHEST` priority and cancel.
  Tracking listeners run at `MONITOR` (read-only, react to the final outcome).
- **Main-thread discipline.** All Bukkit world/inventory/entity mutation happens on the
  main thread. The only things allowed off-thread are file reads/writes of yaml snapshots.
- **Null-safe everywhere.** Owner can be null (unowned egg). Location can be null (held).
  The End world may not be loaded. `OfflinePlayer.getLastPlayed()` can be 0.

---

## 2. Class breakdown

All under `com.smp.dragonreign` unless noted. Sub-packages: `.config`, `.store`, `.listener`,
`.task`, `.gui`, `.command`, `.announce`, `.util`, `.model`.

### 2.1 Bootstrap / wiring

**`DragonReign`** (main, `extends JavaPlugin`)
- `onEnable`: save default config + resources, build `ConfigManager`, load `EggDataStore`,
  construct `HistoryLog`, `AnnouncementService`, `EndPortalEggSpawner`, register all
  listeners, register the command executor + tab completer, schedule `InactivityTask` and
  `EnderChestSweepTask`, schedule the periodic autosave.
- `onDisable`: cancel tasks, flush `EggDataStore` + `HistoryLog` to disk **synchronously**
  (disable is not a safe place for async), close any open GUIs.
- Holds the singletons and exposes typed getters. No game logic lives here.

### 2.2 Config

**`config.ConfigManager`**
- Wraps the `FileConfiguration`. Exposes strongly-typed accessors (`isNoContainers()`,
  `getInactivityDays()`, `getSoundMode()`, `getAnnounceLines()`, title timings, etc.).
- `setX(...)` mutators used by the Config GUI; each mutator writes into the in-memory
  config and calls `save()`. We keep an in-memory mirror so reads never touch disk.
- `reload()` re-reads `config.yml` from disk (used by `/dragonreign reload`) and
  re-validates. Bad enum/number values fall back to documented defaults with a warning.
- Owns the `SoundMode` enum parse/cycle helper.

### 2.3 Persistent state + model

**`model.EggState`** — plain data holder: `UUID ownerUuid` (nullable), `EggLocation location`
(nullable), `long lastActivity`, `Map<UUID, Long> lastSeen`, `Set<UUID> pendingErase`.

**`model.EggLocation`** — `world (UUID + name), x, y, z`. Serialize to a compact map; resolve
to a Bukkit `Location` lazily (world may be unloaded → returns `Optional`).

**`model.HistoryEntry`** — `long epochMillis`, `EventType type`, `String playerName`,
`UUID playerUuid` (nullable), `EggLocation location` (nullable), `String detail`.

**`model.EventType`** (enum) — `PLACED, BROKEN, PICKED_UP, OWNER_CHANGED, BLOCKED_CONTAINER,
BLOCKED_BUNDLE, BLOCKED_DROP, ENDERCHEST_RETURN, RESPAWN_TRIGGERED, EGG_ERASED, EGG_SPAWNED,
TRANSFER, ADMIN`. Carries a display name + a `Material` icon for the History GUI.

**`store.EggDataStore`**
- Loads/saves `data.yml` (separate file from config so a config reload never clobbers state).
- Holds the live `EggState`. Thread-safety: all mutators are called from the main thread;
  the only cross-thread access is the **snapshot-and-write** path (see §6.4). Internally the
  owner/location/timestamps are guarded so the async writer reads a consistent snapshot —
  we copy into an immutable snapshot object on the main thread, then hand that to the async
  task. The live maps (`lastSeen`, `pendingErase`) are `ConcurrentHashMap`-backed sets/maps
  to make the snapshot copy cheap and safe.
- API: `getOwner()/setOwner(uuid, reason)`, `getLocation()/setLocation()/clearLocation()`,
  `touchActivity()`, `markSeen(uuid)`, `getLastSeen(uuid)`, `addPendingErase(uuid)`,
  `consumePendingErase(uuid)` (returns + removes), `snapshot()`.
- `setOwner` automatically records `markSeen` for the new owner and logs `OWNER_CHANGED`
  (caller passes a reason string; PICKED_UP/TRANSFER/etc. logged by the caller separately).

**`store.HistoryLog`**
- A capped, append-only deque of `HistoryEntry` (`max-history`, default 1000; oldest evicted).
- `append(type, player, location, detail)` — convenience overloads (player can be `Player`,
  `OfflinePlayer`, or null/"SYSTEM"). Appends in memory; marks dirty.
- Persists to `data.yml` under a `history:` list (or a sibling `history.yml` if we want to
  keep `data.yml` small — **decision: keep it in `data.yml`** for one-file simplicity; the
  cap keeps it bounded).
- `recentNewestFirst(int page, int pageSize)` for the GUI.

> `EggDataStore` and `HistoryLog` share the same `data.yml` file object and a single
> `saveAll()` entry point to avoid two writers racing on the same file.

### 2.4 Listeners

**`listener.ContainerProtectionListener`** (feature 1 — the big one)
- `InventoryClickEvent` @HIGHEST: covers cursor-place into a blocked inventory, hotbar
  swap / number-key, `MOVE_TO_OTHER_INVENTORY` shift-click, and `COLLECT_TO_CURSOR`.
- `InventoryDragEvent` @HIGHEST: dragging egg stacks across slots into a blocked inventory.
- `InventoryMoveItemEvent` @HIGHEST: hopper/dropper/dispenser item transport.
- **Bundle insertion** is handled inside the click/drag handlers because it happens in the
  player's *own* inventory (see §6.1).
- Respects `dragonreign.bypass`. On cancel, logs `BLOCKED_CONTAINER` or `BLOCKED_BUNDLE`
  (throttled — see §6.5) and sends a configurable action-bar/chat nudge.

**`listener.EnderChestListener`** (part of feature 1)
- The ender chest is a per-player inventory but is explicitly disallowed. Same click/drag
  coverage; detect `InventoryType.ENDER_CHEST`. Logged as `BLOCKED_CONTAINER` (detail
  "ender chest"). (The sweep task in §2.5 is the safety net for any path we miss or for
  eggs placed before install.)

**`listener.DropProtectionListener`** (feature 2)
- `PlayerDropItemEvent` @HIGHEST: cancel if the dropped item is `DRAGON_EGG` and drop
  protection is on and player lacks bypass. Log `BLOCKED_DROP` (throttled).

**`listener.EggTrackingListener`** (tracking, @MONITOR)
- `BlockPlaceEvent`: if `DRAGON_EGG` placed → `setLocation(block)`, `setOwner(player)` if
  owner changes, `touchActivity`, log `PLACED`.
- `BlockBreakEvent` / block physics fall: dragon egg teleports when hit and can fall as a
  `FallingBlock`. We watch `EntityChangeBlockEvent` (FallingBlock landing) and
  `BlockBreakEvent` to keep `location` current; on pickup the item event below fires.
- `EntityPickupItemEvent` (player) + `InventoryPickupItemEvent`: on a player picking up a
  dragon egg item → `setOwner(player)`, `clearLocation()`, `touchActivity`, log `PICKED_UP`.
- `PlayerJoinEvent`: (a) `markSeen(now)` for the joining player; (b) if their UUID is in
  `pendingErase`, strip all dragon eggs from their inventory + ender chest, consume the
  flag, log `EGG_ERASED` (see §6.2).
- `PlayerQuitEvent`: `markSeen(now)` so offline last-seen is accurate without relying solely
  on `OfflinePlayer`.

> Splitting "protection" (cancel) from "tracking" (observe) into different classes keeps the
> priority/cancellation logic from tangling with the bookkeeping.

### 2.5 Scheduled tasks

**`task.InactivityTask`** (feature 4) — `BukkitRunnable`, period = `check-interval-minutes`.
- Runs on the main thread (it inspects/mutates worlds). Cheap: one owner, one comparison.
- Compute `lastSeen`: if owner online → `now`; else `store.getLastSeen(owner)`, else
  `Bukkit.getOfflinePlayer(owner).getLastPlayed()` (0 → treat as "very old" only if we
  actually have a non-null owner; guard against 0 meaning "never joined this server").
- If `owner != null` and `now - lastSeen >= inactivityDays`: invoke `RespawnSequence`.

**`task.EnderChestSweepTask`** (feature 3) — period = a fixed/short config interval
(reuse `check-interval-minutes` or a dedicated `endercchest-sweep-minutes`; **decision:**
dedicated key `enderchest-sweep.interval-minutes`, default 5).
- Iterates **online** players only (offline ender chests aren't loaded/safe to mutate),
  scans `player.getEnderChest()`, removes any dragon egg, returns it to the player's main
  inventory (or drops at feet if full), logs `ENDERCHEST_RETURN`. Cheap and main-thread.

**`RespawnSequence`** (the orchestrator for feature 4's steps a–d; lives in `task`)
A small class (not a runnable) invoked by `InactivityTask`. Steps, in order:
1. **Erase tracked egg**: if `location` set and that block is `DRAGON_EGG`, set it to AIR.
   Add `owner` to `pendingErase`. If owner is online, strip eggs from inv + ender chest now
   and don't bother with the pending flag for them. Log `EGG_ERASED`.
2. **Spawn fresh egg**: `EndPortalEggSpawner.spawnAtExitPortal()` → log `EGG_SPAWNED`.
3. **Announce**: `AnnouncementService.broadcastRespawn()`.
4. **Reset tracking**: `owner = null`, `location = portalEggLocation`, `lastActivity = now`,
   `markSeen` cleared/irrelevant. Log `RESPAWN_TRIGGERED` (with the old owner in detail).
- Whole sequence runs inside one main-thread tick.

### 2.6 End-portal egg spawner

**`util/EndPortalEggSpawner`** (feature 4b)
- Gets the End world (`first world with Environment.THE_END`, configurable name override).
- Preferred path: `world.getEnderDragonBattle()` → `DragonBattle.getEndPortalLocation()`
  gives the portal base; the egg sits on the bedrock fountain column at `x=0,z=0`.
- Fallback if the battle/portal is null (portal not generated yet): scan the column at
  (0, z=0) downward from y≈80 to find the topmost `BEDROCK` of the fountain, place egg on
  the air block directly above it. See §6.3 for the exact algorithm.
- Returns the `EggLocation` where it placed the egg (so `RespawnSequence` can store it).
- Idempotent-ish: if a dragon egg block is already there, treat as already-spawned.

### 2.7 Announcement

**`announce.AnnouncementService`** (feature 5)
- `broadcastRespawn()`:
  - Chat: render each configured MiniMessage line via `MiniMessage.miniMessage()` and
    `Bukkit.broadcast(component)` (Adventure is bundled).
  - Title: `player.showTitle(Title.title(title, subtitle, Times.times(fadeIn, stay, fadeOut)))`
    for every online player; texts are MiniMessage, timings from config (ticks→`Duration`).
  - Sound, by `SoundMode`:
    - `LIGHTNING`: `endWorld.strikeLightningEffect(portalLoc)` (visual+thunder) — effect, not
      a real damaging bolt — plus we can also push thunder sound to players in other worlds.
    - `DRAGON_DEATH`: play `ENTITY_ENDER_DRAGON_DEATH` to every online player at their
      location (so everyone hears it regardless of world).
    - `BOTH`: do both. `NONE`: nothing. `CUSTOM`: `Sound.sound(Key.key(customKey), ...)`.
- **`SoundMode`** enum (`LIGHTNING, DRAGON_DEATH, BOTH, NONE, CUSTOM`) with a `next()` cycle
  for the Config GUI button.

### 2.8 GUIs

**`gui.ConfigGui`** (permission `dragonreign.admin`)
- Chest GUI (27 or 36 slots). One toggle item per rule: lime/green wool = ON, red wool = OFF,
  click flips + persists via `ConfigManager`, then re-renders. Items: no-containers, no-drop,
  enderchest-sweep, respawn-on-inactivity, announce.
- Inactivity-days: an item with left-click `+1` / right-click `-1` (shift = ±7), clamped ≥1.
- Sound-mode cycle button → `ConfigManager.setSoundMode(mode.next())`.
- Reflects live config every render.

**`gui.HistoryGui`** (permission `dragonreign.admin`)
- Paginated, newest-first. Each entry = an item whose `Material` icon comes from `EventType`,
  display name = `type — who`, relative time ("3m ago") in the name/first lore line, lore =
  full detail + exact `world x,y,z` + absolute timestamp. Prev/next page buttons in the
  bottom row; page state stored per-viewer.

**`gui.GuiManager` / `gui.GuiHolder`**
- A single `InventoryHolder` marker per GUI type carrying its page/context, so the shared
  `InventoryClickEvent` handler in a `gui.GuiListener` can `instanceof`-dispatch and always
  `setCancelled(true)` for GUI inventories (no item theft). Avoids title-string matching.

### 2.9 Commands

**`command.DragonReignCommand`** (`CommandExecutor` + `TabCompleter`)
- `/dragonreign` aliases `/dreign`, `/degg`. Subcommands:
  - `gui` → open ConfigGui (admin).
  - `log` | `history` → open HistoryGui (admin).
  - `reload` → `ConfigManager.reload()` + reschedule tasks if intervals changed (admin).
  - `info` → print current owner (name), location, last activity, feature toggles.
- Tab-complete: subcommand names (filtered by permission), and player names for `/giveegg`.

**`command.GiveEggCommand`** (`/giveegg <player>`, permission `dragonreign.giveegg` default true)
- Sender must hold a `DRAGON_EGG` in main hand. Recipient must be online. Moves one egg item
  to the recipient (drop at feet if inventory full), sets `owner = recipient`, `touchActivity`,
  logs `TRANSFER` (detail: from→to). Tab-completes online player names.

### 2.10 Util

**`util.Egg`** — `isDragonEgg(ItemStack)`, `isDragonEgg(Material)`, `isBundle(Material)`
(`name().endsWith("BUNDLE")`), `bundleContainsDragonEgg(ItemStack)` via `BundleMeta`.

**`util.Msg`** — MiniMessage helpers, prefix, action-bar nudge, throttled log helper.

**`util.Scheduling`** — tiny wrappers for `runTask`, `runTaskTimer`, and an async save
helper, so the rest of the code doesn't sprinkle scheduler boilerplate.

---

## 3. data.yml schema (state + history)

```yaml
egg:
  owner: "f4a1...-uuid"        # or omitted/null when unowned
  location:                     # omitted/null when the egg is held, not placed
    world-uuid: "..."
    world-name: "world_the_end"
    x: 0
    y: 64
    z: 0
  last-activity: 1750000000000  # epoch millis
last-seen:                       # per-owner / per-player last-seen, epoch millis
  "uuid-a": 1749990000000
  "uuid-b": 1749000000000
pending-erase:                   # UUIDs whose inventory must be stripped on next join
  - "uuid-c"
history:                         # capped at max-history, appended; index 0 = oldest
  - t: 1750000000000
    type: PLACED
    player: "ThConMan"
    uuid: "uuid-a"
    loc: "world_the_end:0,64,0"  # compact "world:x,y,z" or "-" when none
    detail: "placed on the fountain"
```

Notes:
- UUIDs stored as strings; parse defensively (skip malformed entries with a warn).
- `location` and `loc` use the same compact convention for history; the live `egg.location`
  uses the expanded map so we keep both world UUID (authoritative) and name (for display
  even when the world is unloaded).

---

## 4. config.yml key layout

```yaml
# ── Protection rules (owners can toggle these live) ────────────────
no-containers: true        # block egg into any non-player inventory, ender chest, bundles
no-drop: true              # cancel dropping the dragon egg

enderchest-sweep:
  enabled: true
  interval-minutes: 5      # safety-net scan of online players' ender chests

respawn-on-inactivity:
  enabled: true
  inactivity-days: 14
  check-interval-minutes: 5

# ── Announcement (fires on respawn) ────────────────────────────────
announce:
  enabled: true
  chat:
    - "<dark_purple>The Dragon Egg has returned to the End!</dark_purple>"
    - "<gray>Its last keeper vanished. Claim it if you dare.</gray>"
  title:
    title: "<gradient:#5e2b97:#b388ff>The Egg Reigns Again</gradient>"
    subtitle: "<gray>A new keeper is needed</gray>"
    fade-in-ticks: 10
    stay-ticks: 70
    fade-out-ticks: 20
  sound:
    mode: BOTH             # LIGHTNING | DRAGON_DEATH | BOTH | NONE | CUSTOM
    custom-key: "minecraft:entity.ender_dragon.growl"   # used only when mode: CUSTOM

# ── Bookkeeping ────────────────────────────────────────────────────
history:
  max-entries: 1000

# ── Advanced ───────────────────────────────────────────────────────
end-world-name: ""         # blank = auto-detect first THE_END world
messages:
  prefix: "<dark_purple>[DragonReign]</dark_purple> "
  blocked-container: "<red>The Dragon Egg can't go in there.</red>"
  blocked-bundle: "<red>The Dragon Egg can't go in a bundle.</red>"
  blocked-drop: "<red>You can't drop the Dragon Egg.</red>"
log-blocks-to-history: true   # if false, blocked-* events skip the history log (still nudges)
```

Config GUI writes back into exactly these keys; `reload` re-reads them.

---

## 5. Permissions / commands recap

| Permission             | Default | Grants                                        |
|------------------------|---------|-----------------------------------------------|
| `dragonreign.bypass`   | op      | ignores ALL protections (containers/drop/etc.)|
| `dragonreign.admin`    | op      | GUIs + `gui`/`log`/`reload` subcommands       |
| `dragonreign.giveegg`  | true    | `/giveegg`                                    |

Commands: `/dragonreign|/dreign|/degg [gui|log|history|reload|info]`, `/giveegg <player>`.

---

## 6. Trickiest correctness points

### 6.1 Bundle insertion detection (the key exploit)
A bundle lives in the player's *own* inventory, so the usual "is the target a non-player
container" check never trips. We must catch the egg going **into** a bundle:

- **`InventoryClickEvent`:**
  - Picking up a bundle onto the cursor while a dragon egg is on the cursor, or vice-versa,
    where the click would merge egg→bundle. Concretely: if `cursor` is a dragon egg and the
    clicked slot holds a **bundle**, OR `cursor` is a **bundle** and the clicked slot holds a
    **dragon egg**, cancel. (Vanilla "right-click a bundle with an item on cursor" and
    "right-click an item with a bundle on cursor" both insert.)
  - Number-key/hotbar swap where one side is a bundle and the other a dragon egg.
  - `COLLECT_TO_CURSOR` while cursor is a bundle: cancel if any matching egg would be pulled
    (simplest correct move: if cursor is a bundle and the egg is anywhere reachable, cancel).
- **`InventoryDragEvent`:** dropping a single egg onto a bundle slot.
- Also cancel the inverse direction defensively: never let a bundle that *already contains*
  a dragon egg (legacy/imported item) be relevant — but our real guard is preventing
  insertion. We additionally run `Egg.bundleContainsDragonEgg` in the sweep/erase paths so
  an already-tainted bundle still gets the egg removed on erase.
- Detection of "bundle" is `Material.name().endsWith("BUNDLE")` — covers `BUNDLE` and all
  dyed `*_BUNDLE` variants without hardcoding the color list.
- Because click semantics are fiddly, the rule of thumb in code: **if the action could
  result in a dragon egg and a bundle occupying the interaction at once, cancel.** We favor
  over-blocking the egg↔bundle interaction (the egg is a single unique server artifact;
  a false-positive block is harmless, a false-negative is a permanent dupe/hide exploit).

### 6.2 Offline-owner egg erase
On respawn, the inactive owner is by definition offline, so we can't edit their inventory.
- We set a `pending-erase` flag (their UUID) in `data.yml` and persist it.
- `PlayerJoinEvent` checks `pendingErase`; if present, strips dragon eggs from **main
  inventory + ender chest + any bundles** they carry, consumes the flag, logs `EGG_ERASED`.
- If the owner happens to be online at respawn time (edge: just over the threshold but
  logged in), we strip immediately and skip the pending flag for them.
- Flag survives restarts (it's in `data.yml`), so a player who never logs back in simply
  never re-introduces the old egg — correct outcome.

### 6.3 Locating the End exit portal top
- Primary: `endWorld.getEnderDragonBattle()`; if non-null,
  `DragonBattle.getEndPortalLocation()` returns the portal's reference block. The egg goes on
  the bedrock fountain apex directly above the central column.
- Fallback (portal/battle null — fresh world, portal ungenerated): force-load the chunk at
  (0,0), scan the column x=0,z=0 from a high y (e.g. 80) downward for the **topmost BEDROCK**
  (the fountain tip). Place the egg in the AIR block immediately above it. If the entire
  column up there is empty (no fountain yet), default to placing at the world spawn-ish
  fountain height `y=getHighestBlockYAt(0,0)+1` as a last resort and log a warning.
- Always operate on the configured/auto-detected End world; if no THE_END world is loaded,
  abort the spawn step, log a warning to console + history detail, but still finish the
  rest of the sequence (reset tracking with `location=null`) so we don't wedge.
- Set the block with `block.setType(Material.DRAGON_EGG, false)` (no physics surprises),
  then read back its `Location` for tracking.

### 6.4 Thread-safety of the repeating tasks
- `InactivityTask`, `EnderChestSweepTask`, and the whole `RespawnSequence` run as
  **synchronous** repeating tasks (`runTaskTimer`), because every action they take touches
  worlds/blocks/inventories which is main-thread-only in Bukkit. They are O(1)/O(online)
  and run on a minutes-scale period, so main-thread cost is negligible.
- The **only** async work is persistence: the periodic autosave (`runTaskTimerAsynchronously`)
  takes an immutable `snapshot()` of `EggState` + a copy of the history deque **on the main
  thread first**, then writes yaml off-thread. We never let the async writer read the live
  mutable state. `pending-erase`/`last-seen` use concurrent collections so the snapshot copy
  is consistent.
- `onDisable` saves **synchronously** (async tasks may already be torn down).
- GUI page state is per-viewer and only touched on the main thread (inventory events).

### 6.5 Log spam / throttling
Blocked-action events (hopper hammering a chest, a player spam-clicking a bundle) can fire
many times per second. The history log and chat nudge for `BLOCKED_*` are throttled per
player+type (e.g. once per ~2s) via a small in-memory `Map<key, lastMillis>` in `util.Msg`.
The cancellation itself is never throttled — only the logging/messaging. `log-blocks-to-history`
config can disable history entries for blocks entirely while keeping the nudges.

### 6.6 Owner/last-seen edge cases
- `OfflinePlayer.getLastPlayed()` returns 0 for players who never joined; never treat 0 as
  "infinitely inactive" unless we genuinely have them as the owner with a real prior session.
  We prefer our own `last-seen` map (written on join/quit) over `getLastPlayed()`.
- Unowned egg (`owner == null`) never triggers respawn — guarded explicitly so a
  just-respawned egg sitting on the fountain doesn't immediately re-trigger.

---

## 7. Build / packaging
- `build.sh` (provided) compiles `--release 21` against the staged Paper jar and produces
  `build/DragonReign-1.0.0.jar`. Green == prints `BUILD OK`.
- Resources: `plugin.yml` (name/version/main/api-version 1.21, command + permission decls),
  `config.yml` (the commented default in §4). `data.yml` is created at runtime.
- Deliverables also include `README.md` (rules, config reference, commands, permissions,
  install).

---

## 8. v2 additions — countdown, strict ownership, staff inbox

v2 layers three features onto the existing wiring **without rewriting it**. The rule of
thumb stayed the same: protection/tracking logic is untouched; the new code hooks the
*decision points* that already exist (the inactivity check, the owner-change moment, the
join event) and reuses the existing announce service, history log, data-store snapshot
pattern, command/GUI infra. No protection branch is duplicated.

### 8.1 New sub-packages / classes

| Class | Package | Role |
|-------|---------|------|
| `CountdownManager` | `.task` | Owns the single active respawn countdown; schedules `RespawnSequence` at the end; fires warn marks; handles abort. |
| `IpRegistry` | `.ownership` | Per-player **salted SHA-256** login-IP hashes + last-login millis. Builds the IP-linked "alt group" for any UUID. Persists to `players.yml`. |
| `OwnershipPolicy` | `.ownership` | Evaluates a transfer to a new keeper (active? IP-linked alt?). Also the single source of the **group-aware last-seen** used by the inactivity check. |
| `Inbox` | `.inbox` | Persisted alert queue + routing (DM online admins, else just queue). Persists to `inbox.yml`. |
| `InboxEntry` | `.inbox` | Immutable alert: `Severity, String type, String message, List<UUID> related, long epochMillis, boolean read`. |
| `Severity` | `.inbox` | `INFO / WARN / CRITICAL` enum with a MiniMessage colour + icon `Material`. |
| `InboxGui` | `.gui` | Paginated, severity-coloured inbox browser; click = mark-read, shift-click = dismiss. Mirrors `HistoryGui`. |
| `util.Yaml` | `.util` | Extracted **atomic temp-file-then-swap** save helper (was inline in `EggDataStore.write`). `EggDataStore`, `IpRegistry`, and `Inbox` all save through it — one implementation, three persisted files. |

No new *listener* classes: IP recording, countdown-abort-on-return, and the admin
join notice all hook into the existing `EggTrackingListener.onJoin`. No new permissions:
`dragonreign.admin` already gates everything new.

### 8.2 Persistence layout (why three files now)

`data.yml`'s "one file, one writer" rule existed to stop two writers racing on the **same
path**. v2 keeps egg state + history in `data.yml` untouched and gives the two genuinely
new, independently-growing datasets their own files, each with its own write-lock:

- `players.yml` — `IpRegistry`: `players.<uuid>.hashes: [<hex>...]`, `players.<uuid>.last-login: <millis>`.
- `inbox.yml` — `Inbox`: a capped list of serialized `InboxEntry` maps (same shape convention as history).

All three save through `util.Yaml.saveAtomic(file, yaml)`. `DragonReign.saveAsync()` snapshots
all three on the main thread then writes them off-thread (same pattern as today); `onDisable`
saves all three synchronously.

> **Privacy:** raw IPs are *never* written. On join we read `player.getAddress().getAddress().getHostAddress()`,
> prepend the configured `ip-hash-salt`, SHA-256 it, store only the hex digest. The salt is
> auto-generated on first run (see §8.7) so digests aren't comparable across servers / rainbow-tableable.

### 8.3 Feature 1 — respawn countdown

**Decision point reused:** `InactivityTask.run()` no longer calls `RespawnSequence` directly.
When it decides a respawn is due it calls `plugin.countdown().requestRespawn(owner)`.

`CountdownManager` (single countdown, in-memory — fine across restarts because the inactivity
check just re-triggers):
- `requestRespawn(owner)`:
  - if a countdown is already active → ignore (only one at a time; stops the minute-scale
    inactivity task from stacking countdowns).
  - if `respawn-countdown.enabled` is **false** → run `RespawnSequence.run(owner)` immediately
    (preserves v1 behaviour exactly).
  - else start a countdown: remember `oldOwner` + `endEpoch = now + duration`, schedule a 1s
    repeating `BukkitTask`. Log `RESPAWN_COUNTDOWN_STARTED`; `Inbox.post(WARN, …)`; emit the
    initial warn mark.
- Each second: compute `secondsLeft`; if it is in `warn-at-seconds`, call
  `AnnouncementService.broadcastCountdownWarn(secondsLeft)` (chat line + screen title + the
  configurable `tick-sound`). At `secondsLeft == 0`: cancel the ticker, run
  `RespawnSequence.run(oldOwner)` (which already does the erase/spawn/announce/reset + its own
  inbox/history), clear active state.
- `onOwnerReturn(uuid)` (called from `EggTrackingListener.onJoin`): if `abort-on-owner-return`
  and a countdown is active and `uuid` is the old owner (or in its IP-linked group), cancel it,
  log `RESPAWN_COUNTDOWN_ABORTED`, `Inbox.post(INFO, …)`, and
  `AnnouncementService.broadcastKeeperReturned(name)` — the egg stays put.
- `cancelByAdmin(sender)` / `forceNow(sender)` back the new admin commands.

`AnnouncementService` gains `broadcastCountdownWarn(int secondsLeft)` and
`broadcastKeeperReturned(String name)` so **all** player-facing messaging still lives in the one
service. The tick sound is a plain `Sound.sound(Key.key(tickSoundKey), …)` played to everyone.

New `EventType`s: `RESPAWN_COUNTDOWN_STARTED` (icon `CLOCK`), `RESPAWN_COUNTDOWN_ABORTED`
(icon `LIGHT_BLUE_BANNER` / `BELL`). The countdown *firing* is still the existing
`RESPAWN_TRIGGERED`.

### 8.4 Feature 2 — strict ownership + IP/alt detection

**IP recording (always on, independent of the toggle)** so the data exists when an admin
flips strict mode on: `EggTrackingListener.onJoin` → `IpRegistry.recordLogin(player)` (salted
hash + `last-login = now`).

**Group-aware last-seen (the real anti-alt protection):** `InactivityTask.resolveLastSeen`
moves into `OwnershipPolicy.groupLastSeen(owner)`. When `strict-ownership.check-ip-alts` is on
it returns the **most-recent login across the owner's IP-linked group** (owner + everyone who
shares an IP hash), via `IpRegistry`. So alts on the same connection can't keep the egg alive
while the actual person is inactive — when the whole group goes quiet, the timer expires. When
the toggle is off it behaves exactly as v1 (online=now → tracked last-seen → `OfflinePlayer`).

**Transfer evaluation (only when `strict-ownership.enabled`):** the moment ownership changes is
already centralised in `EggDataStore.setOwner`. We add a single nullable hook
`store.setOwnerChangedHook(BiConsumer<UUID old, UUID new>)`, fired only on a real change to a
non-null new owner. `DragonReign` wires it to `OwnershipPolicy::evaluateTransfer`. One hook, zero
duplication across the three transfer call-sites (place / pickup / `/giveegg`).

`OwnershipPolicy.evaluateTransfer(old, neu)`:
- `not-really-active`: receiver's last login (from `IpRegistry`, fallback `OfflinePlayer`) older
  than `min-receiver-active-days`.
- `linked alt`: `check-ip-alts` and receiver shares an IP hash with `old` (or `old`'s group).
- If either is true → **always** `Inbox.post(...)` (`WARN` for an IP link, `INFO` for merely
  inactive), naming both players in `related`.
- If `auto-enforce-ip-links` is true **and** it's an IP link → treat them as the same entity for
  the clock: the group-aware `groupLastSeen` already shares the timer, and we additionally
  *refuse to reset* the respawn clock by restoring `last-activity` to its pre-transfer value
  (captured at hook time, re-applied one tick later via `Scheduling.later`, after the caller's
  `touchActivity()` runs). Default (`false`) only flags — see the false-positive note below.

> **"Don't spawn problems."** IP matching is imperfect (shared households, CGNAT, mobile, VPN),
> so the default is **flag, not enforce**: post an inbox alert and let a human decide. Documented
> in both `config.yml` and the README FAQ.

### 8.5 Feature 3 — staff inbox

`Inbox` holds a capped (`inbox.max-entries`) list of `InboxEntry`. `post(severity, type, message,
UUID... related)`:
- append (evict oldest when over cap), mark dirty.
- **route:** if any `dragonreign.admin` is online, DM them immediately (prefixed chat +
  "<gray>→ /dragonreign inbox</gray>"); if none online, just queue. Always queued either way.
- `unreadCount()`, `markRead(id)`, `dismiss(id)`, paged accessors for the GUI (mirrors `HistoryLog`).

**Who posts:** countdown started/fired/aborted (`CountdownManager`), IP-link / inactive-receiver
flags (`OwnershipPolicy`), any singleton/dupe cleanup actually performed (`RespawnSequence` when
`stripped > 0`, `EggTrackingListener` pending-erase strip), egg erased (`RespawnSequence`). Services
call `plugin.inbox().post(...)` — no coupling beyond the singleton getter.

**`notify-on-join`:** `EggTrackingListener.onJoin`, if the joiner has `dragonreign.admin` and
`unreadCount() > 0`, sends "DragonReign: N unread alerts → /dragonreign inbox".

**`InboxGui`** (new `GuiHolder.Type.INBOX`, dispatched in `GuiListener`): 54-slot paginated, each
entry an item coloured by `Severity` (`INFO` lime, `WARN` orange, `CRITICAL` red), unread items
glint/enchanted; left-click = mark read + re-render, shift-click = dismiss. Reachable via
`/dragonreign inbox` and a button added to `ConfigGui`.

### 8.6 Commands & GUI changes

`DragonReignCommand` new subcommands (all `dragonreign.admin`):
- `respawn` → `countdown.requestRespawn(owner)` (start the countdown now; errors if unowned).
- `respawn force` → cancel any active countdown, `RespawnSequence.run(owner)` immediately.
- `cancel` → `countdown.cancelByAdmin(sender)`.
- `inbox` → `plugin.inboxGui().open(player, 0)`.
- Tab-complete extended with `respawn`/`cancel`/`inbox`; `respawn` second-arg completes `force`.
- `info` now also prints: **unread inbox count**, **countdown status** (`idle` / `Ns left, keeper
  <name>`), and **strict-ownership** (`on (enforce|flag)` / `off`).

`ConfigGui` expands from 27→36 slots and adds: a **Respawn Countdown** toggle, a **Strict
Ownership** toggle (lore notes the IP/privacy + false-positive tradeoff), and an **Open Inbox**
button (barrel/book) that opens `InboxGui`. Sound-mode cycle and inactivity-days ± are unchanged.
Every button keeps an explanatory hover-lore line.

### 8.7 config.yml v2 keys (auto-generated salt)

```yaml
respawn-countdown:
  enabled: true
  duration-seconds: 300
  warn-at-seconds: [300, 60, 30, 10, 5, 4, 3, 2, 1]
  abort-on-owner-return: true
  tick-sound: "minecraft:block.note_block.pling"
strict-ownership:
  enabled: false
  min-receiver-active-days: 7
  check-ip-alts: true
  auto-enforce-ip-links: false
  ip-hash-salt: ""          # auto-generated on first run; NEVER share it
inbox:
  enabled: true
  notify-on-join: true
  max-entries: 200
```

Plus new `messages.*` lines (all MiniMessage, prefixed, blocked actions still say *why*):
`countdown-warn` (supports a `<seconds>` placeholder), `countdown-aborted`, `keeper-returned`,
`countdown-started`, and the inbox join notice.

**Salt auto-generation:** `ConfigManager` on first read checks `strict-ownership.ip-hash-salt`;
if blank it generates a 32-byte `SecureRandom` Base64 string, writes it back, and saves. This
runs once and is idempotent. Documented as "do not change after first run or existing hashes stop
matching; never share it."

### 8.8 Correctness notes specific to v2

- **One countdown, restart-safe.** The active countdown is in-memory only. A restart drops it,
  but the inactivity task re-evaluates on its next tick and starts a fresh one — no persisted
  countdown state to corrupt.
- **Countdown vs. force race.** `forceNow` and the natural 0-tick both end in `RespawnSequence`;
  `forceNow` cancels the ticker first and guards a `firing` flag so the egg can't respawn twice.
- **Abort group membership.** Abort-on-return checks the IP-linked group only when `check-ip-alts`
  is on; otherwise strictly the owner UUID, so an unrelated player can't abort someone's loss.
- **No raw IPs, ever.** `getAddress()` can be null (offline/edge) → skip recording gracefully.
- **Enforcement is opt-in.** `auto-enforce-ip-links` defaults false; group-aware last-seen is the
  always-on, low-false-positive half of the protection.
