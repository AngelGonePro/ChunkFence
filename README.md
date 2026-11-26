# ChunkFence

Paper plugin (1.21.x and newer, Java 21) with a built-in void generator. It applies to every world
automatically, in one of two modes:

| Mode | Which worlds | Rule |
|---|---|---|
| **Frozen** | worlds whose generator is `ChunkFence` (or listed in `frozen-worlds`) | Only chunks that existed when the world was first indexed can be entered: walking, riding, teleports, pearls, portals. New chunks are generated as empty void. |
| **Live** | every other world | Stepping into a chunk the server hasn't finished generating and loading puts the player back in the chunk they came from until it's ready. Teleports aren't affected. |

## Setup
1. Stop the server and back up everything.
2. Put `ChunkFence-1.3.0.jar` in `plugins/`. It loads at `STARTUP`, so it can serve as a world generator.
3. Set the old world (and its nether/end, if they're old too) to the ChunkFence generator:
   - `bukkit.yml`:
     ```yaml
     worlds:
       old_world:
         generator: ChunkFence
     ```
   - or in Multiverse, set the world's generator to `ChunkFence`.
4. Start the server. Frozen worlds are indexed once from their region files, and the result is saved
   to `plugins/ChunkFence/snapshots/`. Every other world gets the live fence. No exclusions needed.

## World height protection (1.2.0)
Runs at startup, before any world loads:
1. **Recovery**: on the first start, every region folder is checked for chunks saved shorter than the rest
   of their world (cut off by a height change). If an older full-height copy of a chunk is still inside the
   region file, the missing sections, block entities and ticks are restored. Everything inside the chunk's
   current height is kept as it is now. Changed region files are copied to `plugins/ChunkFence/backups/`
   first, and a report lists chunks that could not be restored.
2. **Heights data pack**: `<level-name>/datapacks/chunkfence-heights` sets the overworld, nether and end
   to the height their saved chunks use (or the Iris pack's height). Its files are Minecraft's own
   dimension files for the running version (1.20.5 to 26.3 bundled) with only min_y, height and
   logical_height changed.
3. **Guard**: before a world loads any chunk, its height is compared with its saved chunks. If the chunks
   are taller, saving is turned off for every world and the server is stopped. On the start right after the
   data pack is installed this always happens once; start the server again.

Offline (server stopped): `java -jar ChunkFence-1.3.0.jar heights <region folder>` and
`java -jar ChunkFence-1.3.0.jar recover <region folder> [--repair]`.

## Data pack upload folder (1.3.0)
`plugins/ChunkFence/datapacks/`: put data packs here (a folder with pack.mcmeta, or a .zip). On every start
they are copied into `<level-name>/datapacks`, updated when changed, and removed from the world when
deleted here. They take effect on the start after they were copied. An Iris pack folder (one with a
`dimensions` folder) put here is used as the source for world heights instead of being copied.

## Commands (`chunkfence.admin`, op by default)
`/chunkfence status | check | rescan <world> | reload`. `chunkfence.bypass` (nobody by default) ignores both modes.

## Build / test
`./build.sh` builds the jar. `./run-tests.sh` checks the region scanner against an independent Python
implementation on real region files, then runs the plugin through a fake server that follows Bukkit's event rules.
