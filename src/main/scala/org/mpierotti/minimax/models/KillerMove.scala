package org.mpierotti.minimax.models

import org.mpierotti.minimax.QuickFold.Score

object KillerMove {
  implicit def ordering[M]: Ordering[KillerMove[M]] =
    Ordering.by(km => km.score)
}

case class KillerMove[Move](move: Move)(val score: Score)
