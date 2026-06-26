# DragonReign

A lightweight Paper plugin that enforces a server's special **Dragon Egg** rules on
an SMP. There is one conceptual egg, and DragonReign makes sure it can't be hidden,
duped, hoarded, or lost to an inactive player — while keeping a full audit trail of
everything that happens to it.

Built for **Paper / Minecraft 26.1.2** (API level 1.21), Java 21+ bytecode.

---

## What it does

The protections apply to **every** `dragon_egg` item and block — not a single
NBT-tagged "real" egg. That makes the rules impossible to launder by crafting or
duping a look-alike. The *tracking* (who owns it, where it sits) follows the
conceptual single egg and is persisted in `data.yml`.

### The rules

1. **No containers** *(default on)* — the egg may only sit in a player's own
   inventory or be placed as a block. Any attempt to move it into a chest, barrel,
   shulker, dropper/dispenser, furnace, hopper, the **ender chest**, or a **bundle**
   (the sneaky one — bundles live in your own inventory) is cancelled. Covers cursor
   placement, shift-click, number-key / hotbar / offhand swaps, double-click collect,
   click-drag, and hopper transport.
2. **No drop** *(default on)* — you can't drop the egg on the ground.
3. **Ender-chest sweep** *(default on)* — a periodic safety net that pulls any dragon
   egg out of online players' ender chests and hands it back.
4. **Respawn on inactivity** *(default on)* — if the egg's owner is gone for
   `inactivity-days` (default 14), the egg is erased and a fresh one spawns on top of
   the End exit-portal fountain, exactly as if the dragon had just been killed. The
   old owner's eggs are stripped on their next login.
5. **Announce** *(default on)* — when the egg respawns, everyone gets a configurable
   chat broadcast, a screen title, and a sound (lightning, the dragon's death roar,
   both, none, or a custom sound key).
6. **Respawn countdown** *(default on)* — the respawn from rule 4 isn't instant. When
   the keeper goes inactive, a single timed countdown begins (default 5 minutes) with
   warnings at configurable marks (chat + title + sound) so players can race to the End
   and fight over the egg. If the absent keeper logs back in during the countdown it's
   cancelled and the egg stays put.
7. **Strict ownership + alt detection** *(optional, off by default)* — when on, the
   plugin watches for the egg being handed to an inactive account or to an **IP-linked
   alt** of the previous keeper, and alerts staff. It also makes the inactivity clock
   *group-aware*: a ring of alts sharing one connection can't keep the egg alive while
   the real person is gone. IP matching is imperfect, so the default is to **flag**, not
   enforce. Only salted IP **hashes** are stored, never raw addresses. (See the FAQ.)
8. **Staff inbox** *(default on)* — a persisted, in-game alert queue for admins: alt/IP
   flags, dupe cleanups, respawn countdowns starting/firing, eggs erased. Online admins
   are DM'd immediately; everything is queued for `/dragonreign inbox`.
9. **History log** *(always on)* — every meaningful event (placed, broken, picked up,
   blocked attempts, sweeps, respawns, countdowns, transfers, admin actions) is appended
   to a capped, persistent log you can browse in-game.

Players with `dragonreign.bypass` ignore all protections.

---

## Commands

| Command | Description |
|---|---|
| `/dragonreign gui` | Open the live config GUI (admin). |
| `/dragonreign log` *(alias `history`)* | Open the paginated history GUI (admin). |
| `/dragonreign inbox` | Open the staff inbox GUI (admin). |
| `/dragonreign respawn` | Start the respawn countdown for the current keeper now (admin). |
| `/dragonreign respawn force` | Skip the countdown and respawn the egg immediately (admin). |
| `/dragonreign cancel` | Cancel the active respawn countdown — the egg stays (admin). |
| `/dragonreign reload` | Reload `config.yml` and reschedule tasks (admin). |
| `/dragonreign info` | Public: owner, rule states, countdown status. Admins additionally see the egg's exact location, last-activity, strict-ownership posture, and unread inbox count. |
| `/giveegg <player>` | Hand the egg you're holding to another online player (transfers ownership). |

Short alias: **`/dr`** (also `/dreign`, `/degg`) — e.g. `/dr gui`, `/dr respawn force`.
Tab-complete only suggests the subcommands you have permission for, and running one without
its permission does **nothing**: no error, no reply.

---

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `dragonreign.bypass` | op | Ignores **all** protections. |
| `dragonreign.giveegg` | everyone | `/giveegg`. |
| `dragonreign.command.info` | everyone | `/dr info` (the public readout). |
| `dragonreign.command.gui` | op | Open the config GUI. |
| `dragonreign.command.history` | op | Open the history GUI (`/dr log`). |
| `dragonreign.command.inbox` | op | Open the staff inbox GUI. |
| `dragonreign.command.respawn` | op | `/dr respawn [force]`. |
| `dragonreign.command.cancel` | op | `/dr cancel`. |
| `dragonreign.command.reload` | op | `/dr reload`. |
| `dragonreign.gui.teleport` | op | Use the teleport-to-egg button in the config GUI. |
| `dragonreign.admin` | op | Parent node — grants every `command.*` and `gui.*` node above. |

Every capability is its own node, so you can hand a helper exactly one (e.g. only
`dragonreign.command.history`) via LuckPerms; `dragonreign.admin` rolls them all up.
**Anyone missing the relevant node gets no response at all** — the subcommand is hidden
from tab-complete and silently does nothing.

`/dr info` stays public by default (`dragonreign.command.info` is on for everyone) but only
shows a lightweight view — owner, which rules are on, countdown status. The egg's exact
coordinates and staff detail stay gated behind `dragonreign.admin` so the keeper's base
can't be looked up on demand. Revoke `dragonreign.command.info` to hide the readout too.

---

## GUIs

- **Config GUI** — one wool toggle per rule (lime = on, red = off, click to flip and
  persist), including the **Respawn Countdown** and **Strict Ownership** toggles; a clock
  to bump `inactivity-days` (left +1 / right −1, shift = ±7); a button to cycle the sound
  mode; **Open Inbox** and **Open History** buttons; a **Teleport to Egg** button that warps
  you to the egg when it's placed (greyed out while it's carried or unclaimed); and a
  **Close** button. Every button carries explanatory hover lore, the empty slots are framed
  with panes, and the whole GUI reflects live config on every click.
- **History GUI** — newest-first, paginated. Each entry's icon hints at the event
  type; the name shows the type, who, and a relative time; the lore carries the full
  detail, exact `world x,y,z`, the absolute timestamp, and the actor's UUID. Like the
  Inbox GUI, it has **« Back to menu** and **Close** buttons in the footer.
- **Inbox GUI** — newest-first, paginated, severity-coloured (green INFO / gold WARN /
  red CRITICAL). Unread alerts glow. **Left-click** marks an alert read; **shift-click**
  dismisses it. Reachable from `/dragonreign inbox` or the Config GUI button.

---

## Configuration

`config.yml` (all of it is editable live from the GUI, or by hand + `/dragonreign reload`):

```yaml
no-containers: true        # block chests/ender chest/bundles/hoppers/etc.
no-drop: true              # cancel dropping the egg

enderchest-sweep:
  enabled: true
  interval-minutes: 5

respawn-on-inactivity:
  enabled: true
  inactivity-days: 14
  check-interval-minutes: 5

announce:
  enabled: true
  chat:                    # MiniMessage lines
    - "<dark_purple>The Dragon Egg has returned to the End!</dark_purple>"
  title:
    title: "<gradient:#5e2b97:#b388ff>The Egg Reigns Again</gradient>"
    subtitle: "<gray>A new keeper is needed</gray>"
    fade-in-ticks: 10
    stay-ticks: 70
    fade-out-ticks: 20
  sound:
    mode: BOTH             # LIGHTNING | DRAGON_DEATH | BOTH | NONE | CUSTOM
    custom-key: "minecraft:entity.ender_dragon.growl"

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
  max-ip-group: 8            # ignore IP hashes shared by more than N accounts (CGNAT/VPN); 0 = no cap
  ip-hash-salt: ""           # auto-generated on first run; never change or share it

inbox:
  enabled: true
  notify-on-join: true
  max-entries: 200

history:
  max-entries: 1000

messages:
  prefix: "<dark_purple>[DragonReign]</dark_purple> "
  blocked-container: "<red>The Dragon Egg can't go in there.</red>"
  blocked-bundle: "<red>The Dragon Egg can't go in a bundle.</red>"
  blocked-drop: "<red>You can't drop the Dragon Egg.</red>"
  countdown-started: "<gold>The egg's keeper has vanished — it returns to the End in <seconds>s unless they return!</gold>"
  countdown-warn: "<yellow>The Dragon Egg respawns in <white><seconds>s</white> — get to the End!</yellow>"
  countdown-title: "<gradient:#b388ff:#5e2b97>Egg Respawn</gradient>"
  countdown-subtitle: "<yellow><seconds>s</yellow> <gray>until it returns to the End</gray>"
  keeper-returned: "<green><player> returned in time — the Dragon Egg stays where it is.</green>"

log-blocks-to-history: true   # false = keep nudges, skip logging blocked attempts
end-world-name: ""            # blank = auto-detect the first THE_END world
```

All message fields use [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
formatting. `<seconds>` (countdown messages) and `<player>` (keeper-returned) are
substituted at send time.

### Full key reference

| Key | Default | Meaning |
|---|---|---|
| `no-containers` | `true` | Block the egg from any non-player inventory, ender chest, and bundles. |
| `no-drop` | `true` | Cancel dropping the egg. |
| `enderchest-sweep.enabled` | `true` | Periodic safety-net sweep of online ender chests. |
| `enderchest-sweep.interval-minutes` | `5` | How often the sweep runs. |
| `respawn-on-inactivity.enabled` | `true` | Respawn the egg when the keeper goes inactive. |
| `respawn-on-inactivity.inactivity-days` | `14` | Days of keeper absence before respawn. |
| `respawn-on-inactivity.check-interval-minutes` | `5` | How often inactivity is checked. |
| `respawn-countdown.enabled` | `true` | Use a timed contest window instead of an instant respawn. |
| `respawn-countdown.duration-seconds` | `300` | Length of the countdown. |
| `respawn-countdown.warn-at-seconds` | `[300,60,30,10,5,4,3,2,1]` | Seconds-remaining marks that broadcast a warning. |
| `respawn-countdown.abort-on-owner-return` | `true` | Keeper logging in cancels the countdown. |
| `respawn-countdown.tick-sound` | `minecraft:block.note_block.pling` | Sound played at each warn mark. |
| `strict-ownership.enabled` | `false` | Master switch for transfer alt/inactivity checks. |
| `strict-ownership.min-receiver-active-days` | `7` | Receiver idle longer than this → flagged on transfer. |
| `strict-ownership.check-ip-alts` | `true` | Use IP-link checks and group-aware last-seen. |
| `strict-ownership.auto-enforce-ip-links` | `false` | If true, an alt hand-off won't reset the respawn timer (pins the inactivity clock to the previous keeper's last activity). |
| `strict-ownership.max-ip-group` | `8` | Ignore IP hashes shared by more than this many accounts (CGNAT/VPN/shared LAN). `0` = no cap. |
| `strict-ownership.ip-hash-salt` | *auto* | Auto-generated salt for IP hashing. Never change or share. |
| `inbox.enabled` | `true` | Enable the staff alert queue. |
| `inbox.notify-on-join` | `true` | Tell admins their unread count on join. |
| `inbox.max-entries` | `200` | Cap on stored alerts (oldest evicted). |
| `history.max-entries` | `1000` | Cap on stored history entries. |
| `announce.*` | — | Respawn chat/title/sound (see block above). |
| `messages.*` | — | All player/staff strings (MiniMessage, prefixed). |
| `log-blocks-to-history` | `true` | If false, blocked attempts are not logged (nudges remain). |
| `end-world-name` | `""` | Force a specific End world; blank auto-detects. |

### Data files

`config.yml` holds settings. State lives in three separate files so a config reload
never clobbers it: `data.yml` (egg owner/location + history), `players.yml` (salted
login-IP hashes + last-login per player), and `inbox.yml` (the alert queue). All three
are written atomically (temp file then swap).

---

## FAQ

**Does this store my players' IP addresses?**
No. When strict ownership's IP checks are on, the plugin reads the login IP only to
compute a **salted SHA-256 hash**, and stores *only that hash* in `players.yml`. The raw
address is never written to disk. The salt is generated randomly on first run, so the
hashes can't be reversed with a rainbow table and aren't comparable across servers. If
you find the feature unnecessary, leave `strict-ownership.enabled: false` (the default).

**Why does strict ownership only *flag* alts instead of blocking them?**
Because IP matching is genuinely unreliable. People sharing a house, a dorm, school or
office Wi-Fi, a mobile carrier behind CGNAT, or the same VPN exit node will all look
"linked" despite being different people. Auto-blocking those would punish innocent
players. So the safe default is to post a staff-inbox alert and let a human judge. If you
understand the trade-off you can set `auto-enforce-ip-links: true`, which stops an
alt-to-alt hand-off from resetting the inactivity timer (it never deletes anyone's egg on
its own). Even with enforcement off, the *group-aware last-seen* still quietly protects
you: alts on one connection can't keep the egg alive once the real person stops logging in.

**What are the limits of the IP/alt detection?**
Two big ones, both inherent to IP matching — know them before you rely on it:

- **Off-network alts evade everything.** An alt on a *different* connection (phone hotspot,
  a VPN, a second house) is not IP-linked, so handing it the egg is treated as a normal
  transfer and resets the inactivity clock. Strict ownership cannot catch this; it only
  sees same-IP relationships. DragonReign does add a couple of heuristics that don't rely
  on IP — it flags a transfer to an account that was **dormant** before the hand-off, and
  flags a **rapid back-and-forth juggle** (the egg bounced straight back between the same
  two accounts within 24h) — but a patient off-network juggle will still slip through. Treat
  strict ownership as a tripwire, not a wall.
- **Shared IPs can *extend* egg life, not just shorten it.** Because group-aware last-seen
  takes the newest login across an IP group, an inactive keeper who logs in once through a
  busy CGNAT/mobile/VPN/shared-LAN endpoint would otherwise inherit strangers' fresh logins
  forever and become immortal. `max-ip-group` (default 8) defends against this by ignoring
  any IP hash shared by more than that many distinct accounts — a hallmark of a public
  endpoint rather than a real alt ring. Lower it on a server where players are mostly on
  unique connections; raise it (or set `0`) only if you understand the immortality risk.

**What's the "proxy hold" trade-off with the countdown?**
The countdown deliberately gives players a window to gather at the End and contest the
egg, rather than respawning it the instant the keeper times out. The upside is dramatic,
fair PvP over the egg; the downside is that the egg's fate is decided in a short live
window — if nobody shows up, it simply respawns unclaimed on the fountain. If you'd rather
it respawn instantly with no contest, set `respawn-countdown.enabled: false`.

**Can someone abort another player's countdown by logging in?**
No. Only the absent keeper (or, when `check-ip-alts` is on, one of their IP-linked alts)
aborts the countdown by returning. An unrelated player joining does nothing to it.

**The countdown disappeared after a restart — is that a bug?**
No. The countdown is in-memory by design. After a restart the inactivity check simply
re-evaluates on its next tick and starts a fresh countdown if the keeper is still gone.
There's no persisted countdown state to corrupt.

---

## Install

1. Drop `DragonReign-1.0.0.jar` into your server's `plugins/` folder.
2. Start (or `/reload`) the server. `config.yml` is generated on first run (including a
   fresh random `ip-hash-salt`); `data.yml`, `players.yml`, and `inbox.yml` are created
   and maintained automatically.
3. Adjust rules live with `/dragonreign gui`, or edit `config.yml` and run
   `/dragonreign reload`.

---

## Building from source

You need JDK 21+ and a Paper (or Spigot) server jar for 1.21+ to compile against.

1. Drop a server jar into `libs/` named `paper-server-26.1.2.jar` (or edit the path
   at the top of `build.sh` to match yours). It isn't bundled — Mojang/Paper
   licensing, and it's too big for the repo.
2. Run:

```bash
bash build.sh
```

It compiles with `--release 21` and produces `build/DragonReign-1.0.0.jar`. A green
build prints `BUILD OK`.

---

## How it stays correct

- Protection listeners run at `HIGHEST` and cancel; tracking listeners run at
  `MONITOR` and only observe — the two concerns never tangle.
- Bundle insertion is over-blocked on purpose: any click that would bring a dragon
  egg and a bundle into contact is cancelled. A false positive is a minor annoyance;
  a false negative would be a permanent dupe/hide exploit.
- All world/inventory mutation happens on the main thread. The only off-thread work
  is the periodic save, which snapshots state on the main thread first and then
  writes the YAML asynchronously.
- Blocked-attempt logging and nudges are throttled per player so a hammering hopper
  or a spam-clicker can't flood the log or chat. The cancellation itself is never
  throttled.
- Ownership transfers (place / pickup / `/giveegg`) all funnel through a single
  data-store hook, so strict-ownership evaluation lives in exactly one place rather than
  being duplicated across listeners.
- Only one respawn countdown runs at a time; a `firing` guard makes the natural
  zero-tick and an admin `respawn force` mutually exclusive, so the egg can never respawn
  twice from the same countdown.
- The three persisted files each save through one atomic temp-then-swap helper with
  per-file locking, so concurrent or crashing writers can't leave a half-written file.
  Snapshots are taken on the main thread and drained by a single-thread writer in
  submission order, so a slow earlier save can never be overtaken and persist stale state.
- On enable the plugin reconciles the tracked egg: if `data.yml` says an unowned egg
  should sit at a known block but the block is missing (e.g. a crash landed between the
  data save and the world's autosave), it re-places it so the unique egg can't be stranded.
