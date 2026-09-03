package dicechess.bot

import com.fortemate.dicechess.runtime.{DoublingDecision, DoublingState}
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

  def doublingState(
      currentStake: Long = 200L,
      cubeValue: Int = 2,
      cubeOwner: String = "White",
      mayOfferDouble: Boolean = true,
      decision: DoublingDecision = new DoublingDecision.Offer("double_1", "White", 200L)
  ): DoublingState =
    new DoublingState(
      DoublingState.CURRENCY_PLAY_CREDIT,
      100L,
      currentStake,
      cubeValue,
      cubeOwner,
      64,
      mayOfferDouble,
      "White",
      decision
    )

  def doublingJson(
      kind: String,
      seat: String,
      offeredBy: String = null,
      cubeOwner: String = null,
      mayOfferDouble: Boolean = true
  ): String =
    val optOfferedBy = Option(offeredBy).map(o => s""", "offeredBy": "$o"""").getOrElse("")
    val optOwner     = Option(cubeOwner).map(c => s""""$c"""").getOrElse("null")
    s"""{"currency":"PLAY_CREDIT","initialStake":100,"currentStake":100,"cubeValue":1,"cubeOwner":$optOwner,"maximumMultiplier":64,"mayOfferDouble":$mayOfferDouble,"turnSeat":"White","decision":{"id":"double_1","kind":"$kind","seat":"$seat"$optOfferedBy,"proposedStake":200}}"""

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
    val optDraw     = if drawOfferPending then """, "drawOffer": {"pending": true}""" else ""
    val optMayOffer = if mayOfferDraw then """, "mayOfferDraw": true""" else ""
    val optDoubling = Option(doublingState).map(d => s""", "doubling": $d""").getOrElse("")
    s"""{"type":"$eventType","gameId":"g1","seat":"$seat","state":{"version":1,"dfen":"$dfen","activeSeat":"$activeSeat","dicePending":$dicePending$optMayOffer$optDraw$optDoubling}}"""
