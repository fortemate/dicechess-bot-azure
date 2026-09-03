package dicechess.bot

import com.fortemate.dicechess.runtime.{
  DoubleDecisionContext,
  DoubleOpportunityContext,
  DoublingDecision,
  DoublingState,
  DrawDecisionContext,
  GameClock,
  TurnContext
}
import dicechess.engine.domain.{Color, FenParser, GameState}
import dicechess.engine.search.{
  AggressiveSearch,
  OpeningBook,
  OpeningBookBot,
  OpeningBookParser,
  ScoredSequence,
  SearchAlgorithm,
  TurnGenerator
}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The brain, hermetically: legal play from a bare DFEN, book-hit precedence, runtime v2 decision bridging,
  * active-color perspective adaptation, and graceful error handling.
  */
class StrategySuite extends munit.FunSuite:

  private val initialNbk = FenParser.InitialPosition + " NBK"
  private val noDiceFen  = FenParser.InitialPosition
  private val clock      = new GameClock(60000, 60000, java.lang.Long.valueOf(1000))

  test("aggressive play from a bare DFEN yields one of the engine's own legal paths"):
    val strategy   = new Strategy(AggressiveSearch)
    val moves      = strategy.chooseMoves(initialNbk).toOption.get
    val state      = FenParser.parse(initialNbk).toOption.get
    val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
    assert(legalPaths.contains(moves), s"$moves must be a legal full turn")

  test("a booked position plays the booked continuation, not the search's choice"):
    val state    = FenParser.parse(initialNbk).toOption.get
    val key      = OpeningBook.key(state).getOrElse(fail("a rolled position must have a book key"))
    val booked   = TurnGenerator.generateAllLegalTurnPaths(state).head.map(Strategy.toUci)
    val strategy = new Strategy(OpeningBookBot.decorate(AggressiveSearch, Map(key -> booked.mkString(","))))
    val moves    = strategy.chooseMoves(initialNbk).toOption.get
    assertEquals(moves.sorted, booked.sorted, "the booked turn must win (matched by move multiset)")

  test("an unusable DFEN is an error value in chooseMoves"):
    assert(new Strategy(AggressiveSearch).chooseMoves("this is not a dfen").isLeft)

  test("the shipped opening_book.tsv parses and is non-trivial"):
    val tsv  = java.nio.file.Files.readString(Path.of("opening_book.tsv"))
    val book = OpeningBookParser.parse(tsv).toOption.get
    assert(book.sizeIs > 100, s"expected the real exported book, got ${book.size} entries")

  test("fromBookFile survives a missing book (bookless aggressive still plays)"):
    val strategy = Strategy.fromBookFile(Path.of("no-such-file.json"))
    assert(strategy.chooseMoves(initialNbk).toOption.get.nonEmpty)

  test("seatToColor maps White and Black correctly"):
    assertEquals(Strategy.seatToColor("White"), Color.White)
    assertEquals(Strategy.seatToColor("white"), Color.White)
    assertEquals(Strategy.seatToColor("Black"), Color.Black)
    assertEquals(Strategy.seatToColor("black"), Color.Black)
    assertEquals(Strategy.seatToColor(null), Color.White)

  test("onTurn offers a draw only when permitted by server and requested by policy"):
    val strategyDraw   = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = true))
    val strategyNoDraw = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = false))

    val turnPermitted = new TurnContext("g1", "White", 1, initialNbk, clock, java.util.List.of(), true)
    val turnForbidden = new TurnContext("g1", "White", 1, initialNbk, clock, java.util.List.of(), false)

    assert(strategyDraw.onTurn(turnPermitted).offerDraw(), "must offer draw when permitted and policy says yes")
    assert(!strategyDraw.onTurn(turnForbidden).offerDraw(), "must not offer draw when server forbids")
    assert(!strategyNoDraw.onTurn(turnPermitted).offerDraw(), "must not offer draw when policy says no")

  test("onTurn fails closed on malformed DFEN"):
    val strategy    = new Strategy(AggressiveSearch)
    val turnContext = new TurnContext("g1", "White", 1, "invalid dfen", clock, java.util.List.of(), true)
    val action      = strategy.onTurn(turnContext)
    assertEquals(action.moves(), java.util.List.of[String]())
    assert(!action.offerDraw())

  test("onDrawDecision delegates to wrapped engine shouldAcceptDraw from bot's perspective"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = false))

    val drawCtx = new DrawDecisionContext("g1", "Black", 1, noDiceFen, clock)

    assert(acceptStrat.onDrawDecision(drawCtx).acceptDraw())
    assert(!declineStrat.onDrawDecision(drawCtx).acceptDraw())

  test("onDrawDecision fails closed (declines) on malformed DFEN"):
    val strategy = new Strategy(AggressiveSearch)
    val drawCtx  = new DrawDecisionContext("g1", "White", 1, "invalid dfen", clock)
    assert(!strategy.onDrawDecision(drawCtx).acceptDraw())

  test("onDoubleOpportunity bridges shouldOfferDouble with current stake multiplier and bot perspective"):
    val offerStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = true))
    val rollStrat  = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = false))

    val oppCtx = new DoubleOpportunityContext("g1", "White", 1, noDiceFen, clock, TestHelpers.doublingState())

    assert(offerStrat.onDoubleOpportunity(oppCtx).offerDouble())
    assert(!rollStrat.onDoubleOpportunity(oppCtx).offerDouble())

  test("onDoubleOpportunity fails closed (rolls) on malformed DFEN"):
    val strategy = new Strategy(AggressiveSearch)
    val dState   = TestHelpers.doublingState(currentStake = 100L, cubeValue = 1, cubeOwner = null)
    val oppCtx   = new DoubleOpportunityContext("g1", "White", 1, "bad dfen", clock, dState)
    assert(!strategy.onDoubleOpportunity(oppCtx).offerDouble())

  test("onDoubleDecision bridges shouldAcceptDouble with proposed stake multiplier and bot perspective"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = false))

    val respDecision = new DoublingDecision.Response("double_1", "Black", "White", 200L)
    val dState       = TestHelpers.doublingState(mayOfferDouble = false, decision = respDecision)

    val decCtx = new DoubleDecisionContext("g1", "Black", 1, noDiceFen, clock, dState)

    assert(acceptStrat.onDoubleDecision(decCtx).acceptDouble())
    assert(!declineStrat.onDoubleDecision(decCtx).acceptDouble())

  test("onDoubleDecision fails closed (declines) on malformed DFEN"):
    val strategy     = new Strategy(AggressiveSearch)
    val respDecision = new DoublingDecision.Response("double_1", "Black", "White", 200L)
    val dState       = TestHelpers.doublingState(mayOfferDouble = false, decision = respDecision)
    val decCtx       = new DoubleDecisionContext("g1", "Black", 1, "bad dfen", clock, dState)
    assert(!strategy.onDoubleDecision(decCtx).acceptDouble())
