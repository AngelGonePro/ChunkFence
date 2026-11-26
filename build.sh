#!/bin/bash
# Builds ChunkFence.jar against compile-only API stubs (signatures verified against Paper source).
set -euo pipefail
cd "$(dirname "$0")"
VERSION=1.5.0
rm -rf build && mkdir -p build/stubs build/classes build/jar
javac --release 21 -nowarn -d build/stubs $(find stubs -name '*.java')
javac --release 21 -Xlint:all,-serial -cp build/stubs -d build/classes $(find src/main/java -name '*.java')
cp -r build/classes/. build/jar/
sed "s/\${version}/$VERSION/" src/main/resources/plugin.yml > build/jar/plugin.yml
cp src/main/resources/config.yml build/jar/config.yml
cp -r src/main/resources/vanilla-dimension-types build/jar/
printf 'Main-Class: net.cosmoscraft.chunkfence.Cli\n' > build/manifest.txt
(cd build/jar && jar --create --file ../ChunkFence-$VERSION.jar --manifest ../manifest.txt .)
echo "built build/ChunkFence-$VERSION.jar"
