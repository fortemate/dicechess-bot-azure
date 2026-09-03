package dicechess.bot

import dicechess.engine.domain.GameState
import dicechess.engine.search.{AggressiveSearch, ScoredSequence, SearchAlgorithm}

object TestHelpers:

  final class ConfigurableSearch(
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

  def doublingJson(
      kind: String,
      seat: String,
      offeredBy: String = null,
      cubeOwner: String = null,
      mayOfferDouble: Boolean = true
  ): String =
    val offeredByProp = if offeredBy != null then s""", "offeredBy": "$offeredBy"""" else ""
    val cubeOwnerProp = if cubeOwner != null then s""""$cubeOwner"""" else "null"
    s"""{
       |  "currency": "PLAY_CREDIT",
       |  "initialStake": 100,
       |  "currentStake": 100,
       |  "cubeValue": 1,
       |  "cubeOwner": $cubeOwnerProp,
       |  "maximumMultiplier": 64,
       |  "mayOfferDouble": $mayOfferDouble,
       |  "turnSeat": "White",
       |  "decision": {"id": "double_1", "kind": "$kind", "seat": "$seat"$offeredByProp, "proposedStake": 200}
       |}""".stripMargin

  def makeEnvelope(
      eventType: String,
      seat: String,
      dfen: String,
      activeSeat: String = "White",
      dicePending: Boolean = false,
      mayOfferDraw: Boolean = false,
      drawOfferPending: Boolean = false,
      doublingState: String = null
  ): String =
    val drawOfferProp = if drawOfferPending then """, "drawOffer": {"pending": true}""" else ""
    val mayOfferProp  = if mayOfferDraw then """, "mayOfferDraw": true""" else ""
    val doublingProp  = if doublingState != null then s""", "doubling": $doublingState""" else ""
    s"""{
       |  "type": "$eventType",
       |  "gameId": "g1",
       |  "seat": "$seat",
       |  "state": {
       |    "version": 1,
       |    "dfen": "$dfen",
       |    "activeSeat": "$activeSeat",
       |    "dicePending": $dicePending$mayOfferProp$drawOfferProp$doublingProp
       |  }
       |}""".stripMargin
