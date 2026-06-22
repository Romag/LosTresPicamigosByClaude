#!/usr/bin/env bash
# Launch the Picamigos MCP server. Builds the jar first if it is missing.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jar="$here/target/picamigos-mcp.jar"
if [ ! -f "$jar" ]; then
  "$here/mvnw" -q -f "$here/pom.xml" clean package -DskipTests
fi
exec java -jar "$jar" "$@"
