package org.mpierotti.minimax

import monix.execution.atomic._
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import org.mpierotti.minimax.QuickFold._
import org.mpierotti.minimax.models.MoveWrapper

/*
  Implementation of the efficient MTD(f) minimax search algorithm. The main insight is that a traditional minimax can be
  called repeatedly using zero windows in order to establish lower and upper score bounds, eventually establishing an
  exact bound.

  See http://people.csail.mit.edu/plaat/mtdf.html for more information.
 */

object MTDF {
  val logger = Logger(this.getClass)

  def mtdf[Move](f: Score,
                 shouldStopHandle: AtomicBoolean,
                 miniMax: (Int, Int) => Future[Result[Move]])
                (implicit ec: ExecutionContext): Future[Option[(MoveWrapper[Move], Score)]] = {
    logger.info(s"Starting guess is $f")

    def mtdfInner(g: Int, lowerBound: Int, upperBound: Int): Future[Option[(MoveWrapper[Move], Score)]] = {
      val beta = if (g == lowerBound) g + 1 else g
      val alpha = beta - 1

      logger.info(s"Running minimax with alpha $alpha and beta $beta")
      miniMax(alpha, beta).flatMap { miniMaxResult =>
        miniMaxResult.bestMove.map { case (bestMoveConcrete, scoreConcrete) =>
          logger.info(s"Visited ${miniMaxResult.statistics.nodesVisited} nodes with estimated branching factor ${miniMaxResult.statistics.avgBranchingFactor}")
          val newG = scoreConcrete
          val (newLowerBound, newUpperBound) = if (newG < beta) {
            logger.info(s"Found upperbound, score: ${newG}, move: ${bestMoveConcrete.move}")
            (lowerBound, newG)
          } else {
            logger.info(s"Found lowerbound, score: ${newG}, move: ${bestMoveConcrete.move}")
            (newG, upperBound)
          }
          if (!(newLowerBound < newUpperBound && !shouldStopHandle.get)) {
            Future.successful(Some((bestMoveConcrete, newG)))
          } else {
            mtdfInner(newG, newLowerBound, newUpperBound)
          }
        }.getOrElse(Future.successful(None))
      }
    }

    if (shouldStopHandle.get) {
      Future.successful(None)
    } else {
      mtdfInner(f, Int.MinValue, Int.MaxValue)
    }
  }
}
