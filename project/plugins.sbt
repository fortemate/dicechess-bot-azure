// GraalVM native-image packaging: `sbt nativeImage` produces the standalone binary the
// Azure Functions custom handler runs (see host.json / .github/workflows/ci.yml).
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.5.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt"     % "2.5.4")
