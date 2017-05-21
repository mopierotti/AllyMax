package org.mpierotti.minimax.models

sealed abstract class NodeType
case object Exact extends NodeType
case object Cut extends NodeType
case object All extends NodeType
