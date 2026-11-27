#!/bin/sh
# Compiles and runs the checks of the Android engine's pure-Java parts (no Android SDK needed).
set -e
A=plugins/plusnet-engine/android/src
OUT=${TMPDIR:-/tmp}/plusnet-jc
rm -rf "$OUT" && mkdir -p "$OUT"
java -m jdk.compiler/com.sun.tools.javac.Main -nowarn -d "$OUT" \
  $A/main/java/app/plusnet/engine/ChunkMap.java $A/main/java/app/plusnet/engine/Limiter.java \
  $A/main/java/app/plusnet/engine/Lt.java $A/main/java/app/plusnet/engine/Torrents.java \
  $(find $A/test/java -name '*.java')
java -ea -cp "$OUT" app.plusnet.engine.CoreCheck
java -ea -cp "$OUT" app.plusnet.engine.TorrentsCheck
