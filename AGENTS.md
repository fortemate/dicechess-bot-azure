# Dice Chess Bot — Azure Functions (GraalVM Native Image) — AI Agent Guidelines

## Architecture Overview
- **Domain**: Scala 3 webhook bot powered by the engine's aggressive king-hunt search + opening book, compiled to a GraalVM native image for Azure Functions.
- **Engine**: Cross-platform Scala 3 engine (`com.fortemate:dicechess-engine` 0.4.1).
- **Runtime**: `com.fortemate:dicechess-bot-runtime` (Java 25) with Webhook HMAC verification and JDK `CustomHandlerServer`.
- **Search**: `AggressiveSearch` + `OpeningBookBot` loading `opening_book.tsv` (147 master opening lines).

## Developer Workflows
- **Core Tool**: Use `mise` / `sbt` for all development activities.
- **Format**: `mise run format` / `scalafmt`.
- **Validation**: `mise run check` (runs format check and `sbt clean test`).
- **Tests**: `mise run test` (runs Munit test suite).
- **Native Image**: `sbt nativeImage` (produces native executable `target/native-image/dicechess-bot`).

## Branch & Issue Guidelines
- **Branches**: `<type>/<short-desc>` (e.g. `feat/12-upgrade-engine`, `fix/handshake-status`, `task/update-book`).
- **GitHub Issues**: Use native GitHub Issue Types (`Feature`, `Task`, `Bug`) rather than issue labels.
- **PR Description**: Reference closed issues with `Closes #ID`.
