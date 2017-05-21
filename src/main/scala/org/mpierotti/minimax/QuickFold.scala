package org.mpierotti.minimax

import java.util.concurrent.atomic.LongAdder

import com.typesafe.scalalogging.Logger
import monix.execution.atomic._
import org.mpierotti.minimax.SimpleMiniMax.oneLevelDeepMinimax
import org.mpierotti.minimax.models._

import scala.collection.immutable.SortedSet
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

object QuickFold {
  type RemainingDepth = Int
  type GameDepth = Int
  type Score = Int
  type IsPlayerNext = Boolean
  type ReportedMove[Move] = (MoveWrapper[Move], Score)

  val logger = Logger(this.getClass)

  case class RunStatistics(nodesVisited: Long,
                          avgBranchingFactor: Double)

  case class Result[Move](bestMove: Option[ReportedMove[Move]],
                          statistics: RunStatistics)

  case class BatchingState(intermediateTasksExpected: Option[Int],
                           finalTasksExpected: Option[Int],
                           tasksReturned: Int)

  case class ChildMovesData[GameState, Move](bestMoveSoFar: Option[ReportedMove[Move]],
                                             numFavoredMoves: Int,
                                             enrichedMoveStream: Stream[(MoveWrapper[Move], GameState)],
                                             rawMovesStream: Stream[(MoveWrapper[Move], GameState)])

  case class NodeContinuationState[GameState, Move](infoFromParent: Option[InfoFromParent[GameState, Move]],
                                                    currentGameState: GameState,
                                                    remainingDepthToExplore: Int,
                                                    alphas: Vector[AtomicInt],
                                                    beta: Int,
                                                    parentBeta: Int,
                                                    isPlayerOneNext: Boolean,
                                                    childMovesData: ChildMovesData[GameState, Move],
                                                    batchingState: BatchingState,
                                                    hasBeenCompleted: Boolean)

  def quickFoldSearch[GameState, Move]
                               (gameDefinition: GameDefinition[GameState, Move],
                                explorationMemory: ExplorationMemory[GameState, Move],
                                startingState: StartingState[GameState],
                                stopSwitch: AtomicBoolean,
                                branchingHint: Int
                               )(implicit ec: ExecutionContext): Future[Result[Move]] = {

    val nodesVisited = new LongAdder()
    val rootPromise: Promise[Result[Move]] = Promise()

    @scala.annotation.tailrec
    def reportData(currentScore: Option[Int],
                   bestMoveOneDeep: Option[MoveWrapper[Move]],
                   infoFromParent: Option[InfoFromParent[GameState, Move]]): Unit = {
      // Option.isEmpty is used over Option.map so that this function can be tail recursive
      if (infoFromParent.isEmpty) {
        val resultMove = for {
          score <- currentScore
          bestMove <- bestMoveOneDeep
        } yield (bestMove, score)

        val miniMaxResult = Result(
          resultMove,
          RunStatistics(nodesVisited.sum,
            estimateBranchingFactor(nodesVisited.sum, startingState.remainingDepth))
        )
        rootPromise.complete(Success(miniMaxResult))
      } else {
        val ifp = infoFromParent.get
        val parentContinuationState = ifp.parentContinuationState
        val currentMove = ifp.currentMove
        val finalPcs = {
          // This is structured so that we do as little as possible inside the transform block in order to minimize retries
          val fpcs = {
            currentScore.map { cs =>
              val negatedScore = safeNegate(cs)
              parentContinuationState.transformAndGet { pcs =>
                val newBestMove = pcs.childMovesData.bestMoveSoFar match {
                  case old@Some((oldBestMove, oldBestScore)) =>
                    if (negatedScore > oldBestScore) {
                      Some((currentMove, negatedScore))
                    } else {
                      old
                    }
                  case None => Some((currentMove, negatedScore))
                }
                pcs.copy(childMovesData = pcs.childMovesData.copy(bestMoveSoFar = newBestMove),
                  batchingState = pcs.batchingState.copy(tasksReturned = pcs.batchingState.tasksReturned + 1))
              }
            }.getOrElse {
              parentContinuationState.transformAndGet { pcs =>
                pcs.copy(batchingState = pcs.batchingState.copy(tasksReturned = pcs.batchingState.tasksReturned + 1))
              }
            }
          }
          currentScore.map { cs =>
            val negatedScore = safeNegate(cs)
            setAtomicMax(fpcs.alphas.last, negatedScore)
          }
          fpcs
        }

        val minParentBeta = alternatingAtomicInts(finalPcs.alphas.init, true).min
        val maxParentAlpha = (Vector(safeNegate(finalPcs.parentBeta)) ++ alternatingAtomicInts(finalPcs.alphas.dropRight(2), false)).max
        val currentBeta = finalPcs.beta
        val currentAlpha = finalPcs.alphas.last.get
        val minBeta = math.min(minParentBeta, currentBeta)
        val maxAlpha = math.max(maxParentAlpha, currentAlpha)

        // ENHANCE: probably don't need to recheck alpha/beta if the returning move has no score associated with it (noop return)
        if (minBeta <= maxAlpha
          || (finalPcs.batchingState.finalTasksExpected.map(_ <= finalPcs.batchingState.tasksReturned).getOrElse(false))) {

          val shouldReport = !parentContinuationState.getAndTransform { pcs =>
            pcs.copy(hasBeenCompleted = true)
          }.hasBeenCompleted

          if (shouldReport) {
            storeResults(finalPcs.childMovesData.bestMoveSoFar,
              finalPcs.currentGameState,
              finalPcs.infoFromParent.map(_.parentGameState),
              finalPcs.remainingDepthToExplore,
              maxAlpha,
              currentAlpha,
              maxParentAlpha,
              minBeta,
              currentBeta,
              minParentBeta,
              finalPcs.childMovesData.rawMovesStream
            )

            reportData(
              finalPcs.childMovesData.bestMoveSoFar.map(_._2),
              finalPcs.childMovesData.bestMoveSoFar.map(_._1),
              finalPcs.infoFromParent
            )
          }
        } else {
          val (isDone, numToStart, newPcs) = parentContinuationState.transformAndExtract { pcs =>
            val assessment @ (_, _, newPcs) = assessAndMarkBatchingState(pcs)
            (assessment, newPcs)
          }

          if (isDone) {
            reportData(
              newPcs.childMovesData.bestMoveSoFar.map(_._2),
              newPcs.childMovesData.bestMoveSoFar.map(_._1),
              newPcs.infoFromParent
            )
          } else if (numToStart > 0) {
            finalPcs.childMovesData.enrichedMoveStream.drop(newPcs.batchingState.tasksReturned).take(numToStart).toList.map { m =>
              Future {
                startSubtreeFromParentContinuationState(parentContinuationState, finalPcs, m)
              }
            }
          }
        }
      }
    }

    // Decides whether this node is finished, and if not, how many additional moves to to explore in the next batch
    def assessAndMarkBatchingState(pcs: NodeContinuationState[GameState, Move]): (Boolean, Int, NodeContinuationState[GameState, Move]) = {
      if (pcs.hasBeenCompleted) {
        (false, 0, pcs)
      } else {
        val batchingState = pcs.batchingState
        val desiredTasksToStart = if (batchingState.finalTasksExpected.isDefined) {
          0
        } else {
          if (batchingState.intermediateTasksExpected.map(_ == batchingState.tasksReturned).getOrElse(false)) {
            branchingHint
          } else if (batchingState.intermediateTasksExpected.isEmpty){
            if (pcs.childMovesData.numFavoredMoves != 0) pcs.childMovesData.numFavoredMoves else branchingHint
          } else 0
        }

        if (desiredTasksToStart == 0) {
          (false, 0, pcs)
        } else {
          val numStartable = pcs.childMovesData.enrichedMoveStream.slice(batchingState.tasksReturned, batchingState.tasksReturned + desiredTasksToStart).toList.size

          if (numStartable == 0) {
            (true, 0, pcs.copy(hasBeenCompleted = true))
          } else if (numStartable != desiredTasksToStart) {
            val newPcs = pcs.copy(batchingState = batchingState.copy(finalTasksExpected = Some(batchingState.tasksReturned + numStartable)))
            (false, numStartable, newPcs)
          } else {
            val newPcs = pcs.copy(batchingState = batchingState.copy(intermediateTasksExpected = Some(batchingState.tasksReturned + numStartable)))
            (false, numStartable, newPcs)
          }
        }
      }
    }

    def startSubtreeFromParentContinuationState(continuationStateRef: Atomic[NodeContinuationState[GameState, Move]],
                                                continuationState: NodeContinuationState[GameState, Move],
                                                moveTuple: (MoveWrapper[Move], GameState)): Unit = {
      initializeAndStartSubtreeSafe(
        Some(InfoFromParent(moveTuple._1, continuationState.currentGameState, continuationStateRef)),
        moveTuple._2,
        continuationState.remainingDepthToExplore - 1,
        continuationState.alphas,
        continuationState.beta,
        !continuationState.isPlayerOneNext
      )
    }

    // Enhance: move the try-catch into the non-safe version if the non safe version becomes small enough to be readable
    def initializeAndStartSubtreeSafe(infoFromParent: Option[InfoFromParent[GameState, Move]],
                                     currentGameState: GameState,
                                     remainingDepthToExplore: RemainingDepth,
                                     parentAlphas: Vector[AtomicInt],
                                     parentBeta: Int,
                                     isPlayerOneNext: Boolean
                                    ): Unit = {
      try {
        initializeAndStartSubtree(infoFromParent,
          currentGameState,
          remainingDepthToExplore,
          parentAlphas,
          parentBeta,
          isPlayerOneNext)
      } catch {
        case e: Exception => {
          // Stop the entire search if an exception is found on any subtree
          stopSwitch.set(true)
          rootPromise.failure(e)
        }
      }
    }

    def initializeAndStartSubtree(infoFromParent: Option[InfoFromParent[GameState, Move]],
                                  currentGameState: GameState,
                                  remainingDepthToExplore: RemainingDepth,
                                  parentAlphas: Vector[AtomicInt],
                                  parentBeta: Int,
                                  isPlayerOneNext: Boolean
                                 ): Unit = { // Return type is Unit because results are communicated via atomics and promises
      nodesVisited.increment()
      if (stopSwitch()) {
        val miniMaxResult = Result[Move](
          None,
          RunStatistics(nodesVisited.sum, estimateBranchingFactor(nodesVisited.sum, startingState.remainingDepth))
        )
        rootPromise.tryComplete(Success(miniMaxResult))
        Unit
      } else {
        val alpha = (Vector(safeNegate(parentBeta)) ++ alternatingAtomicInts(parentAlphas.init, false)).max
        val beta = alternatingAtomicInts(parentAlphas, true).min
        if (beta <= alpha) {
          // If the alpha-beta window has already closed, there is no possible move this subtree can return
          reportData(None, None, infoFromParent)
        } else if (remainingDepthToExplore == 0 || gameDefinition.isEndState(currentGameState)) {
          val heuristicValue = gameDefinition.heuristic(currentGameState, isPlayerOneNext)
          reportData(Some(heuristicValue), None, infoFromParent)
        } else {
          val memoryLookup = explorationMemory.nodeMemory.get(currentGameState)

          val (newAlpha, newBeta, oldBestMove, oldBestScore, oldMoves) = memoryLookup.map { case GameStateCacheEntry(depthSearched, nodeType, oldBestMove, oldBestScore, oldMoves) =>
            nodeType match {
              case Exact if depthSearched == remainingDepthToExplore => {
                (oldBestScore, oldBestScore, Some(oldBestMove), Some(oldBestScore), Some(oldMoves))
              }
              case Cut if depthSearched == remainingDepthToExplore => {
                (math.max(alpha, oldBestScore), beta, Some(oldBestMove), Some(oldBestScore), Some(oldMoves))
              }
              case All if depthSearched == remainingDepthToExplore => {
                (alpha, math.min(beta, oldBestScore), Some(oldBestMove), Some(oldBestScore), Some(oldMoves))
              }
              case _ => (alpha, beta, Some(oldBestMove), Some(oldBestScore), Some(oldMoves))
            }
          }.getOrElse((alpha, beta, None, None, None))

          if (newBeta <= newAlpha) {
            reportData(oldBestScore, oldBestMove, infoFromParent)
          } else {
            val localAlpha = AtomicInt(newAlpha)
            val rawMovesStream = oldMoves.getOrElse(gameDefinition.generateValidMoves(currentGameState))

            val (orderedMovesToTry: Stream[(MoveWrapper[Move], GameState)], numFavoredMoves) = {
              val killerMovesWithScore: SortedSet[KillerMove[Move]] =
                explorationMemory.killerMovesMemory.get(infoFromParent.map(_.parentGameState)).getOrElse(SortedSet.empty[KillerMove[Move]])
              val movesToTryFirst: Vector[(MoveWrapper[Move], GameState)] = {
                val killerMovesOriented = killerMovesWithScore.toSeq.reverse
                val massagedKillerMoves = killerMovesOriented.map(_.move).flatMap(gameDefinition.validateMove(currentGameState, _))
                oldBestMove.toVector.++(massagedKillerMoves)
              }.map(m => (m, gameDefinition.applyMoveUnsafe(currentGameState, m)))

              val moveStream = movesToTryFirst.toStream ++ rawMovesStream

              val massagedMoveStream = moveStream.distinct
              (massagedMoveStream, movesToTryFirst.size)
            }

            if (remainingDepthToExplore == 1) {
              val alpha = newAlpha
              val beta = newBeta
              val (bmOpt, numVisited) = oneLevelDeepMinimax(gameDefinition, alpha, beta, isPlayerOneNext, currentGameState, orderedMovesToTry)
              val bm@(move, score) = bmOpt.get
              val localAlpha = math.max(alpha, score)
              val localBeta = beta
              storeResults(Some((move, score)),
                currentGameState,
                infoFromParent.map(_.parentGameState),
                remainingDepthToExplore,
                alpha,
                localAlpha,
                localAlpha,
                beta,
                localBeta,
                localBeta,
                rawMovesStream)
              nodesVisited.add(numVisited)
              reportData(Some(score), Some(move), infoFromParent)
            } else {
              val newNodeContinuationState = NodeContinuationState(
                infoFromParent,
                currentGameState,
                remainingDepthToExplore,
                parentAlphas.:+(localAlpha),
                newBeta,
                parentBeta,
                isPlayerOneNext,
                ChildMovesData(None,
                  numFavoredMoves,
                  orderedMovesToTry,
                  rawMovesStream),
                BatchingState(None, None, 0),
                false
              )
              val newNodeContinuationStateRef = Atomic(newNodeContinuationState)
              orderedMovesToTry.headOption.map { firstMove =>
                startSubtreeFromParentContinuationState(newNodeContinuationStateRef, newNodeContinuationState, firstMove)
              }.getOrElse(reportData(Some(gameDefinition.heuristic(currentGameState, isPlayerOneNext)), None, infoFromParent))
            }
          }
        }
      }
    }

    def storeResults(bestMove: Option[(MoveWrapper[Move], Int)],
                     currentGameState: GameState,
                     parentGameState: Option[GameState],
                     remainingDepthToExplore: RemainingDepth,
                     maxAlpha: Int,
                     currentAlpha: Int,
                     maxParentAlpha: Int,
                     minBeta: Int,
                     currentBeta: Int,
                     minParentBeta: Int,
                     rawMovesStream: Stream[(MoveWrapper[Move], GameState)]
                    ) = {
      bestMove.map { case (localBestMoveConcrete, localBestScoreConcrete) =>
        val oldCachedData = explorationMemory.nodeMemory.get(currentGameState)

        val nodeType = if (localBestScoreConcrete > maxParentAlpha && localBestScoreConcrete < minParentBeta) {
          Exact
        } else if (localBestScoreConcrete >= minBeta) {
          Cut
        } else {
          All
        }

        if (oldCachedData.map(_.depthSearched).getOrElse(0) <= remainingDepthToExplore) {
          explorationMemory.nodeMemory.put(currentGameState,
            GameStateCacheEntry(remainingDepthToExplore, nodeType, localBestMoveConcrete, localBestScoreConcrete, rawMovesStream))
        }

        if (nodeType == Cut) {
          val killerMovesFresh = explorationMemory.killerMovesMemory.get(parentGameState)

          val killerMovesToStore = killerMovesFresh.map { killerMovesConcrete =>
            val km = KillerMove(localBestMoveConcrete.move)(localBestScoreConcrete)
            if (killerMovesConcrete.contains(km)) {
              None
            } else {
              val newKms = killerMovesConcrete.+(km)
              val trimmedKms = if (newKms.size > 2) {
                newKms.drop(1)
              } else newKms
              Some(trimmedKms)
            }
          }.getOrElse {
            Some(SortedSet(KillerMove(localBestMoveConcrete.move)(localBestScoreConcrete)))
          }
          killerMovesToStore.map { kmts =>
            explorationMemory.killerMovesMemory.put(parentGameState, kmts)
          }
        }
      }
    }

    val alphaAtomic = AtomicInt(startingState.alpha)

    initializeAndStartSubtreeSafe(
      infoFromParent = None,
      currentGameState = startingState.initialGameState,
      remainingDepthToExplore = startingState.remainingDepth,
      parentAlphas = Vector(alphaAtomic),
      parentBeta = startingState.beta,
      isPlayerOneNext = startingState.isPlayerOneNext
    )

    rootPromise.future
  }

  case class InfoFromParent[GameState, Move](
                             currentMove: MoveWrapper[Move],
                             parentGameState: GameState,
                             parentContinuationState: Atomic[NodeContinuationState[GameState, Move]]
                           )

  def estimateBranchingFactor(nodesVisited: Long, depthSearched: Int) =
    math.pow(nodesVisited.toDouble, 1.toDouble / depthSearched)

  // Enhance: use a longAccumulator instead? (max would be the reducer)
  private def setAtomicMax(atom: AtomicInt, potentialNewVal: Int) = atom.transformAndGet { value =>
    math.max(value, potentialNewVal)
  }

  // Enhance: use a function that doesn't alternate so that both alphas and betas can be checked in one pass
  private def alternatingAtomicInts(ints: Vector[AtomicInt], negated: Boolean): Vector[Int] = {
    val reversed = ints.reverseIterator
    if (reversed.isEmpty) Vector.empty else {
      reversed.sliding(1, 2).map { i =>
        val ic = i.head.get
        if (negated) {
          safeNegate(ic)
        } else ic
      }.toVector
    }
  }

  def safeNegate(i: Int) = {
    if (i == Int.MinValue) -(i + 1) else -i
  }
}
