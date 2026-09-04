package com.smp.dragonreign.model;

/**
 * What shape the one egg is currently in.
 *
 * <p>This exists because a dragon egg changes form constantly and each form is
 * visible to a different check. Carried lives in an inventory, placed is a
 * block, loose is a dropped item or a falling block, and pending is the ledger
 * entry that covers the gap between a keeper's death and their respawn. Before
 * this, every "does the egg still exist" question was answered by asking each
 * of those places in turn and concluding the egg was gone when none of them
 * said yes — which is also exactly what an unloaded chunk looks like.
 */
public enum EggForm {

    /** In a player's inventory, ender chest, cursor, or a bundle they hold. */
    CARRIED,
    /** A placed block in the world. Item data does not survive this form. */
    PLACED,
    /** A dropped item or a falling block. */
    LOOSE,
    /** Pulled from death drops, owed back on respawn. Not in the world at all. */
    PENDING,
    /** No egg has been created yet, or the last one was deliberately erased. */
    NONE
}
