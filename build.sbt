ThisBuild / organization         := "com.fortemate"
ThisBuild / organizationName     := "Fortemate"
ThisBuild / organizationHomepage := Some(url("https://fortemate.com"))
ThisBuild / homepage             := Some(url("https://fortemate.com"))
ThisBuild / startYear            := Some(2026)
ThisBuild / version              := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion         := "3.9.0"

ThisBuild / description := "Dice Chess webhook bot in Scala: the engine's aggressive search + opening book, compiled to a GraalVM native image for Azure Functions."
ThisBuild / licenses := List("AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.txt"))

ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/fortemate/dicechess-engine"
ThisBuild / resolvers += "GitHub Packages (dicechess-bot-runtime)" at
  "https://maven.pkg.github.com/fortemate/dicechess-bot-runtime"

def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion     = "0.6.0"
val DiceChessBotRuntimeVersion = "1.0.1"
val MunitVersion               = "1.3.5"

lazy val root = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name                := "dicechess-bot-azure",
    Compile / mainClass := Some("dicechess.bot.Main"),
    libraryDependencies ++= Seq(
      "com.fortemate" %% "dicechess-engine"      % DiceChessEngineVersion,
      "com.fortemate"  % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "io.circe"      %% "circe-parser"          % "0.14.16"    % Test,
      "org.scalameta" %% "munit"                 % MunitVersion % Test
    ),
    nativeImageInstalled := true,
    nativeImageOptions ++= List("--no-fallback", "--install-exit-handlers"),
    nativeImageOutput := target.value / "native-image" / "dicechess-bot"
  )
