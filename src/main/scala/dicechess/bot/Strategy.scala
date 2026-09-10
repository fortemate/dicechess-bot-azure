package dicechess.bot

import com.fortemate.dicechess.runtime.{
  BotStrategy,
  DoubleDecisionContext,
  DoubleOfferAction,
  DoubleOpportunityContext,
  DoubleResponseAction,
  DrawAction,
  DrawDecisionContext,
  TurnAction,
  TurnContext
}
import dicechess.engine.domain.{Color, FenParser, GameState, Move}
import dicechess.engine.search.{AggressiveSearch, OpeningBookBot, OpeningBookParser, SearchAlgorithm}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The decision-making brain: wraps an engine [[SearchAlgorithm]] (e.g. [[AggressiveSearch]] decorated with the opening
  * book) and implements the runtime v2 [[BotStrategy]] interface directly.
  *
  * All decisions (turns, draw offers/responses, double opportunities/responses) receive the envelope's position, parse
  * the DFEN, adapt to the bot's active-color perspective, and delegate to the wrapped engine policy without inventing
  * new policy thresholds. Parse/policy failures fail closed without interrupting the game.
  */
final class Strategy(val bot: SearchAlgorithm) extends BotStrategy:

  override def onTurn(ctx: TurnContext): TurnAction =
    parseState(ctx.dfen()) match
      case Left(reason) =>
        System.err.println(s"[bot] unusable dfen in onTurn: $reason")
        TurnAction(java.util.List.of(), false)
      case Right(state) =>
        val botState = state.withActiveColor(Strategy.seatToColor(ctx.seat()))
        try
          val moves     = bot.findBestMove(botState).map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
          val offerDraw = ctx.mayOfferDraw() && (try bot.shouldOfferDraw(botState)
          catch
            case scala.util.control.NonFatal(ex) =>
              System.err.println(s"[bot] policy evaluation failed in onTurn: ${ex.getMessage}")
              false)
          TurnAction(moves.asJava, offerDraw)
        catch
          case scala.util.control.NonFatal(ex) =>
            System.err.println(s"[bot] turn evaluation failed: ${ex.getMessage}")
            TurnAction(java.util.List.of(), false)

  override def onDrawDecision(ctx: DrawDecisionContext): DrawAction =
    if shouldAcceptDraw(ctx.dfen(), ctx.seat()) then DrawAction.accept()
    else DrawAction.decline()

  override def onDoubleOpportunity(ctx: DoubleOpportunityContext): DoubleOfferAction =
    if shouldOfferDouble(ctx.dfen(), ctx.seat(), Strategy.currentMultiplier(ctx)) then DoubleOfferAction.offer()
    else DoubleOfferAction.roll()

  override def onDoubleDecision(ctx: DoubleDecisionContext): DoubleResponseAction =
    if shouldAcceptDouble(ctx.dfen(), ctx.seat(), Strategy.proposedMultiplier(ctx)) then DoubleResponseAction.accept()
    else DoubleResponseAction.decline()

  /** Evaluates whether to accept an incoming draw offer from the bot's active-color perspective. */
  def shouldAcceptDraw(dfen: String, seat: String): Boolean =
    withParsedBotState(dfen, seat, "onDrawDecision", fallback = false) { botState =>
      bot.shouldAcceptDraw(botState)
    }

  /** Evaluates whether to offer a double with the current stake multiplier and bot active-color perspective. */
  def shouldOfferDouble(dfen: String, seat: String, currentMultiplier: Int): Boolean =
    withParsedBotState(dfen, seat, "onDoubleOpportunity", fallback = false) { botState =>
      bot.shouldOfferDouble(botState, currentMultiplier)
    }

  /** Evaluates whether to accept an opponent's double offer with proposed multiplier and bot perspective. */
  def shouldAcceptDouble(dfen: String, seat: String, proposedMultiplier: Int): Boolean =
    withParsedBotState(dfen, seat, "onDoubleDecision", fallback = false) { botState =>
      bot.shouldAcceptDouble(botState, proposedMultiplier)
    }

  private def withParsedBotState[A](
      dfen: String,
      seat: String,
      contextName: String,
      fallback: A
  )(f: GameState => A): A =
    parseState(dfen) match
      case Left(reason) =>
        System.err.println(s"[bot] unusable dfen in $contextName: $reason")
        fallback
      case Right(state) =>
        try f(state.withActiveColor(Strategy.seatToColor(seat)))
        catch
          case scala.util.control.NonFatal(ex) =>
            System.err.println(s"[bot] policy evaluation failed in $contextName: ${ex.getMessage}")
            fallback

  /** Helper for backwards compatibility / direct move selection tests. */
  def chooseMoves(dfen: String): Either[String, List[String]] =
    parseState(dfen).map { state =>
      bot.findBestMove(state).map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
    }

  private def parseState(dfen: String): Either[String, GameState] =
    FenParser.parse(dfen)

object Strategy:

  /** Map seat name ("White" or "Black") to the engine's internal [[Color]] (`Color.White` / `Color.Black`). */
  def seatToColor(seat: String | Null): Color =
    if Option(seat).exists(_.equalsIgnoreCase("Black")) then Color.Black else Color.White

  /** Compute current stake multiplier relative to initial stake (or fallback to cubeValue). */
  def currentMultiplier(ctx: DoubleOpportunityContext): Int =
    if ctx.initialStake() > 0 then (ctx.currentStake() / ctx.initialStake()).toInt
    else ctx.cubeValue()

  /** Compute proposed stake multiplier relative to initial stake (or fallback to cubeValue * 2). */
  def proposedMultiplier(ctx: DoubleDecisionContext): Int =
    if ctx.initialStake() > 0 then (ctx.proposedStake() / ctx.initialStake()).toInt
    else ctx.cubeValue() * 2

  /** UCI for a search-layer `Move` (which has no notation of its own) — the same recipe play-api's `EngineOps` uses. */
  def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

  /** Build the aggressive+book strategy from an `opening_book.tsv` on disk. A missing or malformed book degrades to
    * bookless aggressive play with a loud stderr note.
    */
  def fromBookFile(path: Path): Strategy =
    val book =
      if Files.exists(path) then
        OpeningBookParser.parse(Files.readString(path)) match
          case Right(entries) =>
            println(s"[bot] opening book loaded: ${entries.size} entries from $path")
            entries
          case Left(error) =>
            System.err.println(s"[bot] opening book at $path is malformed ($error) — playing bookless")
            Map.empty[String, String]
      else
        System.err.println(s"[bot] no opening book at $path — playing bookless")
        Map.empty[String, String]
    new Strategy(OpeningBookBot.decorate(AggressiveSearch, book))
