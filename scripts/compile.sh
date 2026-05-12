#!/bin/bash
# Compile every .java source file under src/ into bin/ on the
# command line. Convenient for running outside Eclipse and for the
# automated stress test. From the project root:
#
#     bash scripts/compile.sh
set -euo pipefail

cd "$(dirname "$0")/.."

JFX_LIB="lib/javafx-sdk-21.0.5/lib"

CP="lib/sqlite-jdbc-3.46.1.3.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar:$JFX_LIB/javafx.base.jar:$JFX_LIB/javafx.controls.jar:$JFX_LIB/javafx.fxml.jar:$JFX_LIB/javafx.graphics.jar"

mkdir -p bin

# Find every .java file and compile in one javac invocation. This
# keeps incremental edits fast and guarantees consistent module
# resolution across the whole tree.
find src -name "*.java" -print0 | xargs -0 \
    javac -d bin -cp "$CP" --release 17

# Copy non-Java resources (CSS, images, etc.) into bin/ so they
# are reachable via Class.getResource() at runtime.
find src -type f ! -name "*.java" | while read f; do
    dest="bin/${f#src/}"
    mkdir -p "$(dirname "$dest")"
    cp "$f" "$dest"
done

echo "Compilation succeeded."
