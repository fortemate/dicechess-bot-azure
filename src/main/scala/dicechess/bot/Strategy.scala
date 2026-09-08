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
        val moves     = bot.findBestMove(state).map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
        val offerDraw = ctx.mayOfferDraw() && bot.shouldOfferDraw(state)
        TurnAction(moves.asJava, offerDraw)

  override def onDrawDecision(ctx: DrawDecisionContext): DrawAction =
    withParsedBotState(ctx.dfen(), ctx.seat(), "onDrawDecision", DrawAction.decline()) { botState =>
      if bot.shouldAcceptDraw(botState) then DrawAction.accept() else DrawAction.decline()
    }

  override def onDoubleOpportunity(ctx: DoubleOpportunityContext): DoubleOfferAction =
    withParsedBotState(ctx.dfen(), ctx.seat(), "onDoubleOpportunity", DoubleOfferAction.roll()) { botState =>
      if bot.shouldOfferDouble(botState, Strategy.currentMultiplier(ctx)) then DoubleOfferAction.offer()
      else DoubleOfferAction.roll()
    }

  override def onDoubleDecision(ctx: DoubleDecisionContext): DoubleResponseAction =
    withParsedBotState(ctx.dfen(), ctx.seat(), "onDoubleDecision", DoubleResponseAction.decline()) { botState =>
      if bot.shouldAcceptDouble(botState, Strategy.proposedMultiplier(ctx)) then DoubleResponseAction.accept()
      else DoubleResponseAction.decline()
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
        f(state.withActiveColor(Strategy.seatToColor(seat)))

  /** Helper for backwards compatibility / direct move selection tests. */
  def chooseMoves(dfen: String): Either[String, List[String]] =
    parseState(dfen).map { state =>
      bot.findBestMove(state).map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
    }

  private def parseState(dfen: String): Either[String, GameState] =
    FenParser.parse(dfen)

object Strategy:

  /** Map seat name ("White" or "Black") to the engine's internal [[Color]] (`Color.White` / `Color.Black`). */
  def seatToColor(seat: String): Color =
    if seat != null && seat.equalsIgnoreCase("Black") then Color.Black else Color.White

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
