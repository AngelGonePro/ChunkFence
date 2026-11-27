package net.cosmoscraft.chunkfence;

import java.util.UUID;

/** Fence state of one world. Fields are only written on the main thread. */
final class WorldFence {

    enum Mode {
        /** Only chunks recorded in the snapshot may be entered (old world on the void generator). */
        FROZEN,
        /** Only chunks the server has fully loaded right now may be entered (worlds still generating). */
        LIVE
    }

    final UUID uid;
    final String name;
    final Mode mode;
    /** FROZEN only: allowed chunks, or null if no snapshot could be built (world then behaves as LIVE). */
    volatile ChunkSet allowed;
    /** FROZEN only: true while the deep scan runs; {@link #allowed} then holds the header-only set. */
    volatile boolean indexing;
    /** Bumped on every (re)index so results from a superseded scan are discarded. */
    int generation;

    WorldFence(UUID uid, String name, Mode mode) {
        this.uid = uid;
        this.name = name;
        this.mode = mode;
    }
}
