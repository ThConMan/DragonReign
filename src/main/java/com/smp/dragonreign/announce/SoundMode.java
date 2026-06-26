package com.smp.dragonreign.announce;

/** How the respawn announcement makes noise. */
public enum SoundMode {
    LIGHTNING,
    DRAGON_DEATH,
    BOTH,
    NONE,
    CUSTOM;

    /** Next value in the cycle, for the config GUI button. */
    public SoundMode next() {
        SoundMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Lenient parse that falls back to BOTH on garbage input. */
    public static SoundMode parse(String s) {
        if (s == null) {
            return BOTH;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BOTH;
        }
    }
}
