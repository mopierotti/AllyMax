package org.mpierotti.minimax.models

import org.mpierotti.minimax.AllyMax.RemainingDepth

case class GameStateCacheEntry[Move, GameState](
    depthSearched: RemainingDepth,
    nodeType: NodeType,
    bestMove: MoveWrapper[Move],
    bestMoveScore: Int,
    movesStream: Stream[(MoveWrapper[Move], GameState)])
