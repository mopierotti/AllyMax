package org.mpierotti.minimax

import org.mpierotti.minimax.QuickFold.{RemainingDepth, safeNegate}
import org.mpierotti.minimax.models._

import scala.collection.immutable.SortedSet

object SimpleMiniMax {

  def oneLevelDeepMinimax[GameState, Move](
      gameDefinition: GameDefinition[GameState, Move],
      alpha: Int,
      beta: Int,
      isPlayerOneNext: Boolean,
      currentGameState: GameState,
      orderedMovesToTry: Stream[(MoveWrapper[Move], GameState)])
    : (Option[(MoveWrapper[Move], Int)], Int) = {
    var a = alpha
    var bestMove: Option[MoveWrapper[Move]] = None
    var visitedMoves = 0
    val moves = orderedMovesToTry.toIterator
    val result = {
      var v = Int.MinValue
      while (beta > a && moves.hasNext) {
        val (move, gameState) = moves.next()
        visitedMoves += 1
        val newVal = gameDefinition.heuristic(gameState, isPlayerOneNext)
        if (newVal > v) {
          v = newVal
          bestMove = Some(move)
        }
        a = math.max(a, v)
      }
      v
    }
    (bestMove.map(bm => (bm, result)), visitedMoves)
  }

  def simpleMinimax[GameState, Move](
      gameDefinition: GameDefinition[GameState, Move],
      startingState: StartingState[GameState]): (Option[MoveWrapper[Move]], Int) = {

    def simpleMinimaxInner(alpha: Int,
                           beta: Int,
                           isPlayerOneNext: Boolean,
                           currentGameState: GameState,
                           remainingDepth: RemainingDepth): (Option[MoveWrapper[Move]], Int) = {
      if (gameDefinition.isEndState(currentGameState) || remainingDepth == 0) {
        val value = gameDefinition.heuristic(currentGameState, isPlayerOneNext)
        (None, value)
      } else {
        val movesToTry = gameDefinition.generateValidMoves(currentGameState)
        var a = alpha
        val b = beta
        var bestMove: Option[MoveWrapper[Move]] = None
        var visitedMoves = 0
        val moves = movesToTry.toIterator
        val result = {
          var v = Int.MinValue
          while (b > a && moves.hasNext) {
            val (move, gameState) = moves.next()
            visitedMoves += 1
            val newVal = safeNegate(
              simpleMinimaxInner(safeNegate(b),
                                 safeNegate(a),
                                 !isPlayerOneNext,
                                 gameState,
                                 remainingDepth - 1)._2)
            if (newVal > v) {
              v = newVal
              bestMove = Some(move)
            }
            a = math.max(a, v)
          }
          v
        }
        (bestMove, result)
      }
    }

    simpleMinimaxInner(startingState.alpha,
                       startingState.beta,
                       startingState.isPlayerOneNext,
                       startingState.initialGameState,
                       startingState.remainingDepth)
  }
}
