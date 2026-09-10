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
    assertEquals(Strategy.seatToColor(None), Color.White)

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
    val turnContext = new TurnContext("g1", "White", 1, TestHelpers.InvalidDfen, clock, java.util.List.of(), true)
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
    val drawCtx  = new DrawDecisionContext("g1", "White", 1, TestHelpers.InvalidDfen, clock)
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
    val oppCtx   = new DoubleOpportunityContext("g1", "White", 1, TestHelpers.InvalidDfen, clock, dState)
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
    val decCtx       = new DoubleDecisionContext("g1", "Black", 1, TestHelpers.InvalidDfen, clock, dState)
    assert(!strategy.onDoubleDecision(decCtx).acceptDouble())

  test("policy exceptions fail closed safely without bubbling"):
    class CrashingSearch extends SearchAlgorithm:
      override def findBestMove(state: GameState)                           = sys.error("boom")
      override def shouldOfferDraw(state: GameState): Boolean               = sys.error("draw boom")
      override def shouldAcceptDraw(state: GameState): Boolean              = sys.error("accept draw boom")
      override def shouldOfferDouble(state: GameState, mult: Int): Boolean  = sys.error("double boom")
      override def shouldAcceptDouble(state: GameState, mult: Int): Boolean = sys.error("accept double boom")

    val strat   = new Strategy(new CrashingSearch)
    val turnCtx = new TurnContext("g1", "White", 1, initialNbk, clock, java.util.List.of(), true)
    val turnAct = strat.onTurn(turnCtx)
    assertEquals(turnAct.moves().size(), 0)
    assert(!turnAct.offerDraw())

    val drawCtx = new DrawDecisionContext("g1", "White", 1, noDiceFen, clock)
    assert(!strat.onDrawDecision(drawCtx).acceptDraw())

    val oppCtx = new DoubleOpportunityContext("g1", "White", 1, noDiceFen, clock, TestHelpers.doublingState())
    assert(!strat.onDoubleOpportunity(oppCtx).offerDouble())

    val respDecision = new DoublingDecision.Response("double_1", "Black", "White", 200L)
    val dState       = TestHelpers.doublingState(mayOfferDouble = false, decision = respDecision)
    val decCtx       = new DoubleDecisionContext("g1", "Black", 1, noDiceFen, clock, dState)
    assert(!strat.onDoubleDecision(decCtx).acceptDouble())

  test("draw offer policy exception in onTurn does not corrupt legal turn moves"):
    class CrashingDrawSearch extends SearchAlgorithm:
      override def findBestMove(state: GameState)             = AggressiveSearch.findBestMove(state)
      override def shouldOfferDraw(state: GameState): Boolean = sys.error("draw policy boom")

    val strat   = new Strategy(new CrashingDrawSearch)
    val turnCtx = new TurnContext("g1", "White", 1, initialNbk, clock, java.util.List.of(), true)
    val action  = strat.onTurn(turnCtx)
    assert(action.moves().size() > 0, "legal moves must still be returned")
    assert(!action.offerDraw(), "offerDraw must fail closed to false")

  test("policy helpers and direct entry points behave predictably"):
    assertEquals(Strategy.seatToColor("Black"), Color.Black)
    assertEquals(Strategy.seatToColor("black"), Color.Black)
    assertEquals(Strategy.seatToColor("White"), Color.White)
    assertEquals(Strategy.seatToColor("WHITE"), Color.White)
    assertEquals(Strategy.seatToColor("other"), Color.White)
    assertEquals(Strategy.seatToColor(Some("Black")), Color.Black)
    assertEquals(Strategy.seatToColor(None), Color.White)

    val oppCtx = new DoubleOpportunityContext(
      "g1",
      "White",
      1,
      noDiceFen,
      clock,
      TestHelpers.doublingState(currentStake = 400L, cubeValue = 4)
    )
    assertEquals(Strategy.currentMultiplier(oppCtx), 4)

    val respDecision = new DoublingDecision.Response("double_1", "Black", "White", 400L)
    val dState       = TestHelpers.doublingState(mayOfferDouble = false, decision = respDecision)
    val decCtx       = new DoubleDecisionContext("g1", "Black", 1, noDiceFen, clock, dState)
    assertEquals(Strategy.proposedMultiplier(decCtx), 4)

    val strat = new Strategy(AggressiveSearch)
    assertEquals(strat.shouldAcceptDraw(TestHelpers.InvalidDfen, "White"), false)
    assertEquals(strat.shouldOfferDouble(TestHelpers.InvalidDfen, "White", 1), false)
    assertEquals(strat.shouldAcceptDouble(TestHelpers.InvalidDfen, "White", 2), false)

    assertEquals(strat.shouldAcceptDraw(noDiceFen, "White"), false)
    assertEquals(strat.shouldOfferDouble(noDiceFen, "White", 1), false)
    assertEquals(strat.shouldAcceptDouble(noDiceFen, "White", 2), true)

  test("fromBookFile degrades gracefully on malformed on-disk book file"):
    val tempFile = java.nio.file.Files.createTempFile("malformed_book", ".tsv")
    try
      java.nio.file.Files.writeString(tempFile, "invalid line without tab\n")
      val strategy = Strategy.fromBookFile(tempFile)
      val result   = strategy.chooseMoves(initialNbk)
      assert(result.isRight)
      assert(result.toOption.get.nonEmpty)
    finally java.nio.file.Files.deleteIfExists(tempFile)

  test("fromBookFile loads well-formed on-disk book file and returns booked move"):
    val tempFile = java.nio.file.Files.createTempFile("well_formed_book", ".tsv")
    try
      val state  = FenParser.parse(initialNbk).toOption.get
      val key    = OpeningBook.key(state).getOrElse(fail("a rolled position must have a book key"))
      val booked = TurnGenerator.generateAllLegalTurnPaths(state).head.map(Strategy.toUci)
      java.nio.file.Files.writeString(tempFile, s"$key\t${booked.mkString(",")}\n")
      val strategy = Strategy.fromBookFile(tempFile)
      val moves    = strategy.chooseMoves(initialNbk).toOption.get
      assertEquals(moves.sorted, booked.sorted, "the booked turn must win (matched by move multiset)")
    finally java.nio.file.Files.deleteIfExists(tempFile)
