#!/bin/bash
# Start the JavaFX client. JavaFX is no longer bundled with the JDK,
# so we point --module-path at the unpacked OpenJFX SDK and add the
# javafx modules explicitly.
set -euo pipefail

cd "$(dirname "$0")/.."

JFX_LIB="lib/javafx-sdk-21.0.5/lib"
CP="bin:lib/sqlite-jdbc-3.46.1.3.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar"

exec java \
    --module-path "$JFX_LIB" \
    --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base \
    -cp "$CP" \
    com.warehouse.client.InventoryClient "$@"
