package maru.beacon.spec

import org.hyperledger.besu.ethereum.core.Block


interface ExecutionPayload {
  val executionBlock: Block
}
