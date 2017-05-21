package org.mpierotti.minimax.models

import org.mpierotti.minimax.AllyMax.{RemainingDepth, Score}

case class StartingState[GameState](
    initialGameState: GameState,
    remainingDepth: RemainingDepth,
    alpha: Score,
    beta: Score,
    isPlayerOneNext: Boolean
)
