package com.smp.dragonreign.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Atomic "write to a temp file, then swap it in" save helper. v2 grew from one
 * persisted file to three (data.yml, players.yml, inbox.yml); rather than copy the
 * temp-then-rename dance into each store, they all funnel through here.
 *
 * <p>Each path gets its own lock so two async writers can never interleave on the
 * same file and leave a half-written yaml behind — but writes to *different* files
 * proceed in parallel.
 */
public final class Yaml {

    // One lock per absolute path. We never remove entries: the set of files we own
    // is tiny and fixed, so the map never meaningfully grows.
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private Yaml() {
    }

    public static void saveAtomic(File file, YamlConfiguration yaml, Logger logger) {
        Object lock = LOCKS.computeIfAbsent(file.getAbsolutePath(), k -> new Object());
        synchronized (lock) {
            try {
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                yaml.save(tmp);
                Path tmpPath = tmp.toPath();
                Path target = file.toPath();
                try {
                    Files.move(tmpPath, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicEx) {
                    // Some filesystems (and cross-device temp dirs) can't move atomically;
                    // a plain replace is still far safer than writing the target in place.
                    Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                logger.severe("Failed to write " + file.getName() + ": " + ex.getMessage());
            }
        }
    }
}
