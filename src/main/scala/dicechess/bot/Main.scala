package dicechess.bot

import com.sun.net.httpserver.HttpServer
import com.fortemate.dicechess.runtime.{CustomHandlerServer, TurnContext, WebhookHandler}

import java.nio.file.Path
import java.util.function.{Function => JFunction}
import scala.jdk.CollectionConverters.*

/** The Azure Functions custom-handler process. All webhook/HTTP-server plumbing —
  * HMAC verification, the ownership handshake, the JDK `HttpServer` itself — now lives in
  * `dicechess-bot-runtime` (`lv.id.jc:dicechess-bot-runtime`); this object only wires our
  * engine-backed [[Strategy]] into it.
  *
  * Configuration (App Settings on Azure, plain env vars locally):
  *   - `DICECHESS_WEBHOOK_SECRET` — the per-bot signing key from webhook registration. Absent, only the registration
  *     handshake succeeds (deliberate: registration happens before the secret exists — deploy → register → set secret).
  *   - `DICECHESS_BOOK_PATH` — opening-book JSON, default `opening_book.json` in the package root. A file on disk, not
  *     a baked-in resource: swap the book without rebuilding the native image.
  */
object Main:

  def main(args: Array[String]): Unit =
    val secret = sys.env.getOrElse("DICECHESS_WEBHOOK_SECRET", "")
    if secret.isEmpty then
      System.err.println("[bot] DICECHESS_WEBHOOK_SECRET is not set — only the verification handshake will succeed")
    val strategy = Strategy.fromBookFile(Path.of(sys.env.getOrElse("DICECHESS_BOOK_PATH", "opening_book.tsv")))

    val server = CustomHandlerServer.startFromEnvironment(new WebhookHandler(secret, adapt(strategy)))
    println(s"[bot] aggressive+book custom handler listening on :${server.getAddress.getPort}")
    Thread.currentThread().join() // serve until the host stops the process

  /** Start the server (exposed for the end-to-end test; port 0 = ephemeral). */
  def start(port: Int, secret: String, strategy: Strategy): HttpServer =
    CustomHandlerServer.start(port, "/api/webhook", new WebhookHandler(secret, adapt(strategy)))

  /** `dicechess-bot-runtime`'s strategy shape is a plain `java.util.function.Function` — a Scala
    * lambda converts to it via SAM automatically, so this adapter is the entire cost of reusing
    * the library from a Scala bot. `AggressiveSearch` needs nothing beyond the position (no time
    * management, no `legalMoves` from the wire), so only `ctx.dfen` is read here.
    */
  private def adapt(strategy: Strategy): JFunction[TurnContext, java.util.List[String]] =
    (ctx: TurnContext) =>
      strategy.chooseMoves(ctx.dfen()) match
        case Left(reason) =>
          System.err.println(s"[bot] unusable dfen: $reason")
          java.util.List.of()
        case Right(moves) => moves.asJava
