#!/bin/bash
# Full test run. Needs: python3 with nbtlib + lz4, real region files in $DATA, the user's Iris pack in $PACK.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
DATA=${DATA:-/home/claude/src/villeolof-mca/data}
PACK=${PACK:-/home/claude/inspect/a/rrtspecial}
MCMETA=${MCMETA:-/home/claude/src/mcmeta-1.21.6}
CP_RT="$ROOT/build/harness:$ROOT/build/rt:$ROOT/build/stubs:$ROOT/build/jar"

echo "### 1. region scanner vs independent Python reader (real files 1.2.1 - 1.21.11)"
mkdir -p build/test-classes
javac --release 21 -nowarn -cp build/classes -d build/test-classes test/net/cosmoscraft/chunkfence/ScanCheck.java
python3 tools/compare.py tools $DATA/*.mca | tail -1

echo "### 2. fence behaviour (fake server following Bukkit's event rules)"
W=/tmp/cf-worlds
python3 tools/make_test_worlds.py $DATA $W >/dev/null
python3 tools/expected_allowed.py $W > /tmp/cf-expected.txt
rm -rf build/rt build/harness && mkdir -p build/rt build/harness
javac --release 21 -nowarn -cp build/stubs -d build/rt $(find test-runtime -name '*.java')
javac --release 21 -nowarn -cp build/rt:build/stubs:build/classes -d build/harness test/harness/*.java
java -cp $CP_RT harness.Harness $W /tmp/cf-expected.txt src/main/resources/config.yml 2>/dev/null | tail -1

echo "### 3. recovery of cut-off chunks (simulated exactly like RegionFile writes)"
python3 tools/simulate_damage.py $DATA/1.21.11.mca /tmp/sim
rm -rf /tmp/sim-run && cp -r /tmp/sim /tmp/sim-run
(cd /tmp/sim-run && java -jar $ROOT/build/ChunkFence-*.jar recover world/region --repair | grep -v "^  chunk")
python3 tools/verify_recovery.py /tmp/sim /tmp/sim-run

echo "### 4. startup: recovery + height data pack + guard (server folder with the real Iris pack)"
HC=/tmp/hc
rm -rf $HC && mkdir -p $HC/main/region $HC/main_nether/DIM-1 $HC/new_world/region $HC/plugins/Iris/packs
printf 'level-name=main\nmotd=test\n' > $HC/server.properties
printf 'settings:\n  allow-end: true\nworlds:\n  main:\n    generator: ChunkFence\n' > $HC/bukkit.yml
cp -r $PACK $HC/plugins/Iris/packs/
# a second Iris pack with other heights: the plugin must pick rrtspecial because it matches the saved chunks
mkdir -p $HC/plugins/Iris/packs/otherpack/dimensions
printf '{"dimensionHeight":{"min":0,"max":512},"logicalHeight":300}' > $HC/plugins/Iris/packs/otherpack/dimensions/otherpack.json
# data packs uploaded to plugins/ChunkFence/datapacks
mkdir -p $HC/plugins/ChunkFence/datapacks/testpack/data/test/function
printf '{"pack":{"pack_format":80,"description":"t"}}' > $HC/plugins/ChunkFence/datapacks/testpack/pack.mcmeta
printf 'say hi\n' > $HC/plugins/ChunkFence/datapacks/testpack/data/test/function/hi.mcfunction
(cd $HC/plugins/ChunkFence/datapacks/testpack && python3 -c "import zipfile;z=zipfile.ZipFile('../zippack.zip','w');z.write('pack.mcmeta');z.write('data/test/function/hi.mcfunction');z.close()")
cp /tmp/sim/world/region/r.0.0.mca $HC/main/region/
cp $DATA/1.21.11.mca $HC/new_world/region/r.0.0.mca
mkdir -p $HC/extra/region
python3 tools/make_tall_region.py $DATA/1.21.11.mca $HC/extra/region/r.0.0.mca 79
mkdir -p $HC/oldiris/region
python3 tools/make_biome_region.py $DATA/1.21.11.mca $HC/oldiris/region/r.0.0.mca rrtspecial:hot_beach rrtspecial:tropical_plains otherpack:mystery
for w in main main_nether new_world extra oldiris; do printf '\0' > $HC/$w/level.dat; done
(cd $HC && java -cp $CP_RT harness.HeightHarness $HC $ROOT/src/main/resources/config.yml 2>/tmp/hc-log.txt)
grep -E "Height of|restored|cut off|data pack|START THE|loading with height" /tmp/hc-log.txt | sed 's/^/    log: /' | head -20
rm -rf /tmp/hcv && mkdir /tmp/hcv && ln -s $HC/main /tmp/hcv/world
python3 tools/verify_recovery.py /tmp/sim /tmp/hcv
python3 tools/check_datapack.py $HC/main/datapacks/chunkfence-heights $MCMETA
python3 tools/check_biomes.py $HC/main/datapacks/chunkfence-biomes $MCMETA 1.21.6

echo "### 5. biome data pack from the real Iris pack, checked against Minecraft's own biome files (1.21.6 and 26.1.2 formats)"
mkdir -p build/test-classes && javac --release 21 -nowarn -cp build/classes -d build/test-classes test/net/cosmoscraft/chunkfence/BiomeGen.java
for v in 1.21.6 26.1.2; do rm -rf /tmp/bg-$v; mkdir -p /tmp/bg-$v
  java -cp build/test-classes:build/jar net.cosmoscraft.chunkfence.BiomeGen $v $PACK /tmp/bg-$v | tail -1
  python3 tools/check_biomes.py /tmp/bg-$v/datapacks/chunkfence-biomes /home/claude/src/mcmeta-$v $v
  python3 tools/check_datapack.py /tmp/bg-$v/datapacks/chunkfence-biomes /home/claude/src/mcmeta-$v 2>/dev/null | tail -2
done
