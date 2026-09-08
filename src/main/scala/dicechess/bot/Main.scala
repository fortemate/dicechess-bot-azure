package dicechess.bot

import com.sun.net.httpserver.HttpServer
import com.fortemate.dicechess.runtime.{CustomHandlerServer, WebhookHandler}

import java.nio.file.Path

/** The Azure Functions custom-handler process. All webhook/HTTP-server plumbing — HMAC verification, the ownership
  * handshake, the JDK `HttpServer` itself — lives in `dicechess-bot-runtime` (`com.fortemate:dicechess-bot-runtime`);
  * this object wires our engine-backed [[Strategy]] directly as a `BotStrategy`.
  *
  * Configuration (App Settings on Azure, plain env vars locally):
  *   - `DICECHESS_WEBHOOK_SECRET` — the per-bot signing key from webhook registration. Absent, only the registration
  *     handshake succeeds (deliberate: registration happens before the secret exists — deploy → register → set secret).
  *   - `DICECHESS_BOOK_PATH` — opening-book TSV, default `opening_book.tsv` in the package root. A file on disk, not a
  *     baked-in resource: swap the book without rebuilding the native image.
  */
object Main:

  def main(args: Array[String]): Unit =
    val secret = sys.env.getOrElse("DICECHESS_WEBHOOK_SECRET", "")
    if secret.isEmpty then
      System.err.println("[bot] DICECHESS_WEBHOOK_SECRET is not set — only the verification handshake will succeed")
    val strategy = Strategy.fromBookFile(Path.of(sys.env.getOrElse("DICECHESS_BOOK_PATH", "opening_book.tsv")))

    val server = CustomHandlerServer.startFromEnvironment(new WebhookHandler(secret, strategy))
    println(s"[bot] aggressive+book custom handler listening on :${server.getAddress.getPort}")
    Thread.currentThread().join() // serve until the host stops the process

  /** Start the server (exposed for the end-to-end test; port 0 = ephemeral). */
  def start(port: Int, secret: String, strategy: Strategy): HttpServer =
    CustomHandlerServer.start(port, "/api/webhook", new WebhookHandler(secret, strategy))
