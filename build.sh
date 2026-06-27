#!/usr/bin/env bash
set -e; cd "$(dirname "$0")"

# The staged libs/paper-server-26.1.2.jar is a Paperclip launcher — it does not
# contain the Bukkit API on its own. The first time we build we have Paperclip
# extract the real server jar plus all of Paper's dependency jars (API, Adventure,
# Guava, bungee-chat, …) into ./libraries, then we compile against those. This keeps
# the build self-contained and reproducible from just the staged launcher.
if [ ! -d libraries ]; then
  echo "Bootstrapping Paper API + dependency jars (one-time)…"
  java -Dpaperclip.patchonly=true -jar libs/paper-server-26.1.2.jar
fi

# Compile classpath: everything we staged in libs/ (Paper API + Adventure + the
# JetBrains annotations) plus every dependency jar Paperclip unpacked.
CP=$(ls libs/*.jar 2>/dev/null | tr '\n' ';')
CP="$CP$(find libraries -name '*.jar' | tr '\n' ';')"

rm -rf build/classes && mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -cp "$CP" -d build/classes @build/sources.txt
cp -r src/main/resources/* build/classes/ 2>/dev/null || true
(cd build/classes && jar cf ../DragonReign-1.2.1.jar .)
echo "BUILD OK -> build/DragonReign-1.2.1.jar"
