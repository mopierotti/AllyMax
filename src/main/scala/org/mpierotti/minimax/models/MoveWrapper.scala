package org.mpierotti.minimax.models

trait MoveWrapper[A] { // This could be nice to represent as a typeclass instead
  val move: A
}
