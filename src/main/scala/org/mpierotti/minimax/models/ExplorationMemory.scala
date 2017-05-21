package org.mpierotti.minimax.models

import scala.collection.immutable.SortedSet
import scala.collection.mutable

case class ExplorationMemory[GameState, Move](
    nodeMemory: mutable.Map[GameState, GameStateCacheEntry[Move, GameState]],
    killerMovesMemory: mutable.Map[Option[GameState], SortedSet[KillerMove[Move]]]
)
