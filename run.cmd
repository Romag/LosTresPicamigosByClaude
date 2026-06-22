@echo off
rem Launch the Picamigos MCP server. Builds the jar first if it is missing.
setlocal
set "HERE=%~dp0"
set "JAR=%HERE%target\picamigos-mcp.jar"
if not exist "%JAR%" (
  call "%HERE%mvnw.cmd" -q -f "%HERE%pom.xml" clean package -DskipTests
)
java -jar "%JAR%" %*
