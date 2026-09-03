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

  final private class CustomPolicyStrategy(
      offerDraw: Boolean = false,
      acceptDraw: Boolean = false,
      offerDouble: Boolean = false,
      acceptDouble: Boolean = false
  ) extends SearchAlgorithm:
    override def findBestMove(state: GameState): Option[ScoredSequence]   = AggressiveSearch.findBestMove(state)
    override def shouldOfferDraw(state: GameState): Boolean               = offerDraw
    override def shouldAcceptDraw(state: GameState): Boolean              = acceptDraw
    override def shouldOfferDouble(state: GameState, mult: Int): Boolean  = offerDouble
    override def shouldAcceptDouble(state: GameState, mult: Int): Boolean = acceptDouble

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
      val body =
        s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"version":1,"dfen":"$initialNbk","activeSeat":"White","dicePending":true}}"""
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)

      val json  = parse(res.body()).toOption.get
      val moves = json.hcursor.get[List[String]]("moves").toOption.get
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")

      val state      = FenParser.parse(initialNbk).toOption.get
      val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
      assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")
    }

  test("end to end over real HTTP: turn offers draw when permitted and engine policy returns true"):
    val drawStrat = new Strategy(new CustomPolicyStrategy(offerDraw = true))
    withServer(drawStrat) { (client, url) =>
      val body =
        s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"version":1,"dfen":"$initialNbk","activeSeat":"White","dicePending":true,"mayOfferDraw":true}}"""
      val res = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)

      val json      = parse(res.body()).toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(offerDraw, "offerDraw should be true when permitted and policy agrees")
    }

  test("end to end over real HTTP: draw decision accept/decline responses"):
    val acceptStrat  = new Strategy(new CustomPolicyStrategy(acceptDraw = true))
    val declineStrat = new Strategy(new CustomPolicyStrategy(acceptDraw = false))

    val body =
      s"""{"type":"drawDecision","gameId":"g1","seat":"White","state":{"version":1,"dfen":"$noDiceFen","activeSeat":"White","dicePending":false,"drawOffer":{"pending":true}}}"""

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
    val offerStrat = new Strategy(new CustomPolicyStrategy(offerDouble = true))
    val rollStrat  = new Strategy(new CustomPolicyStrategy(offerDouble = false))

    val body =
      s"""{
         |  "type": "doubleOpportunity",
         |  "gameId": "g1",
         |  "seat": "White",
         |  "state": {
         |    "version": 1,
         |    "dfen": "$noDiceFen",
         |    "activeSeat": "White",
         |    "dicePending": false,
         |    "doubling": {
         |      "currency": "PLAY_CREDIT",
         |      "initialStake": 100,
         |      "currentStake": 100,
         |      "cubeValue": 1,
         |      "maximumMultiplier": 64,
         |      "mayOfferDouble": true,
         |      "turnSeat": "White",
         |      "decision": {"id": "double_1", "kind": "offer", "seat": "White", "proposedStake": 200}
         |    }
         |  }
         |}""".stripMargin

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
    val acceptStrat  = new Strategy(new CustomPolicyStrategy(acceptDouble = true))
    val declineStrat = new Strategy(new CustomPolicyStrategy(acceptDouble = false))

    val body =
      s"""{
         |  "type": "doubleDecision",
         |  "gameId": "g1",
         |  "seat": "Black",
         |  "state": {
         |    "version": 1,
         |    "dfen": "$noDiceFen",
         |    "activeSeat": "Black",
         |    "dicePending": false,
         |    "doubling": {
         |      "currency": "PLAY_CREDIT",
         |      "initialStake": 100,
         |      "currentStake": 100,
         |      "cubeValue": 1,
         |      "cubeOwner": null,
         |      "maximumMultiplier": 64,
         |      "mayOfferDouble": false,
         |      "turnSeat": "White",
         |      "decision": {"id": "double_1", "kind": "response", "seat": "Black", "offeredBy": "White", "proposedStake": 200}
         |    }
         |  }
         |}""".stripMargin

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
      val turnBody =
        """{"type":"yourTurn","gameId":"g1","seat":"White","state":{"version":1,"dfen":"invalid-dfen","activeSeat":"White","dicePending":true}}"""
      val turnRes = postSigned(client, url, turnBody)
      assertEquals(turnRes.statusCode(), 200)
      assertEquals(parse(turnRes.body()).toOption.get.hcursor.get[List[String]]("moves"), Right(Nil))

      val drawBody =
        """{"type":"drawDecision","gameId":"g1","seat":"White","state":{"version":1,"dfen":"rnbqkbnr/8/8/8/8/8/8/RNBQKBNR w - - 0 1","activeSeat":"White","dicePending":false,"drawOffer":{"pending":true}}}"""
      val drawRes = postSigned(client, url, drawBody)
      assertEquals(drawRes.statusCode(), 200)
      assertEquals(parse(drawRes.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(false))
    }
