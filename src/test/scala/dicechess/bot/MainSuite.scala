package dicechess.bot

import com.fortemate.dicechess.runtime.{Signatures, WebhookHandler}
import dicechess.engine.domain.{FenParser, GameState}
import dicechess.engine.search.{AggressiveSearch, ScoredSequence, SearchAlgorithm, TurnGenerator}
import io.circe.parser.parse

/** Proves `Main`'s wiring — `WebhookHandler`/`CustomHandlerServer` talking to our real engine-backed `Strategy` and
  * policy stub wrappers, end to end over a real socket with HMAC signing.
  */
class MainSuite extends munit.FunSuite:

  private val Secret     = "test-webhook-secret"
  private val strategy   = new Strategy(AggressiveSearch)
  private val initialNbk = FenParser.InitialPosition + " NBK"
  private val noDiceFen  = FenParser.InitialPosition

  private def withServer(strat: Strategy)(testCode: (java.net.http.HttpClient, String) => Unit): Unit =
    val server = Main.start(port = 0, secret = Secret, strategy = strat)
    try
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
      val client = java.net.http.HttpClient.newHttpClient()
      testCode(client, base)
    finally server.stop(0)

  private def postSigned(
      client: java.net.http.HttpClient,
      url: String,
      body: String
  ): java.net.http.HttpResponse[String] =
    val ts  = System.currentTimeMillis() / 1000
    val req = java.net.http.HttpRequest
      .newBuilder(java.net.URI.create(url))
      .header(WebhookHandler.TIMESTAMP_HEADER, ts.toString)
      .header(WebhookHandler.SIGNATURE_HEADER, Signatures.sign(Secret, ts, body))
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
      .build()
    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())

  test("end to end over real HTTP: a signed turn returns a path the engine itself considers legal"):
    withServer(strategy) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true)
      val res  = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)

      val json  = parse(res.body()).toOption.get
      val moves = json.hcursor.get[List[String]]("moves").toOption.get
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")

      val state      = FenParser.parse(initialNbk).toOption.get
      val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
      assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")
    }

  test("end to end over real HTTP: turn offers draw when permitted and engine policy returns true"):
    val drawStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = true))
    withServer(drawStrat) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true, mayOfferDraw = true)
      val res  = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)

      val json      = parse(res.body()).toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(offerDraw, "offerDraw should be true when permitted and policy agrees")
    }

  test("end to end over real HTTP: draw decision accept/decline responses"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = false))

    val body = TestHelpers.makeEnvelope("drawDecision", "White", noDiceFen, drawOfferPending = true)

    withServer(acceptStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(true))
    }

    withServer(declineStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(false))
    }

  test("end to end over real HTTP: double opportunity offer/roll responses"):
    val offerStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = true))
    val rollStrat  = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = false))

    val body = TestHelpers.makeEnvelope(
      "doubleOpportunity",
      "White",
      noDiceFen,
      doublingState = TestHelpers.doublingJson("offer", "White")
    )

    withServer(offerStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("offerDouble"), Right(true))
    }

    withServer(rollStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("offerDouble"), Right(false))
    }

  test("end to end over real HTTP: double decision take/drop responses"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = false))

    val body = TestHelpers.makeEnvelope(
      "doubleDecision",
      "Black",
      noDiceFen,
      activeSeat = "Black",
      doublingState = TestHelpers.doublingJson("response", "Black", offeredBy = "White", mayOfferDouble = false)
    )

    withServer(acceptStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("acceptDouble"), Right(true))
    }

    withServer(declineStrat) { (client, url) =>
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean]("acceptDouble"), Right(false))
    }

  test("end to end over real HTTP: malformed DFEN fails closed with safe defaults"):
    withServer(strategy) { (client, url) =>
      val turnBody = TestHelpers.makeEnvelope("yourTurn", "White", "invalid-dfen", dicePending = true)
      val turnRes  = postSigned(client, url, turnBody)
      assertEquals(turnRes.statusCode(), 200)
      assertEquals(parse(turnRes.body()).toOption.get.hcursor.get[List[String]]("moves"), Right(Nil))

      val drawBody = TestHelpers.makeEnvelope(
        "drawDecision",
        "White",
        "rnbqkbnr/8/8/8/8/8/8/RNBQKBNR w - - 0 1",
        drawOfferPending = true
      )
      val drawRes = postSigned(client, url, drawBody)
      assertEquals(drawRes.statusCode(), 200)
      assertEquals(parse(drawRes.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(false))
    }
