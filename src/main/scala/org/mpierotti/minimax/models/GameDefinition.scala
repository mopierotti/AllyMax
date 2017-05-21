package org.mpierotti.minimax.models

import org.mpierotti.minimax.AllyMax.{IsPlayerNext, Score}

case class GameDefinition[GameState, Move](
    heuristic: (GameState, IsPlayerNext) => Score,
    isEndState: GameState => Boolean,
    validateMove: (GameState, Move) => Option[MoveWrapper[Move]],
    applyMoveUnsafe: (GameState, MoveWrapper[Move]) => GameState,
    generateValidMoves: GameState => Stream[(MoveWrapper[Move], GameState)]
)
