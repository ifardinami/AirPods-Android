#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/airpods-parser-test"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/com/airpods/manager/AirPodsBleParser.java" \
  "$ROOT/tools/ParserSelfTest.java"
java -cp "$OUT" ParserSelfTest
