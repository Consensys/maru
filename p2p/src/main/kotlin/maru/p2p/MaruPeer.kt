/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.stackTraceToString
import maru.p2p.messages.BeaconBlocksByRangeRequest
import maru.p2p.messages.BeaconBlocksByRangeResponse
import maru.p2p.messages.Status
import maru.p2p.messages.StatusMessageFactory
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.DisconnectRequestHandler
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.peer.PeerDisconnectedSubscriber
import tech.pegasys.teku.networking.p2p.reputation.ReputationAdjustment
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcStreamController

private const val STATUS_RENEWAL_SECONDS = 300L // 5 minutes
const val STATUS_TIMEOUT_SECONDS = 10L

interface MaruPeer : Peer {
  fun getStatus(): Status?

  fun sendStatus(): SafeFuture<Status>

  fun updateStatus(status: Status)

  fun sendBeaconBlocksByRange(
    startBlockNumber: ULong,
    count: ULong,
  ): SafeFuture<BeaconBlocksByRangeResponse>

  fun scheduleDisconnectIfStatusNotReceived(delay: Long)
}

interface MaruPeerFactory {
  fun createMaruPeer(delegatePeer: Peer): MaruPeer
}

class DefaultMaruPeerFactory(
  private val rpcMethods: RpcMethods,
  private val statusMessageFactory: StatusMessageFactory,
) : MaruPeerFactory {
  override fun createMaruPeer(delegatePeer: Peer): MaruPeer =
    DefaultMaruPeer(
      delegatePeer = delegatePeer,
      rpcMethods = rpcMethods,
      statusMessageFactory = statusMessageFactory,
    )
}

class DefaultMaruPeer(
  private val delegatePeer: Peer,
  private val rpcMethods: RpcMethods,
  private val statusMessageFactory: StatusMessageFactory,
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : MaruPeer {
  private val log: Logger = LogManager.getLogger(this::javaClass)

  private val status = AtomicReference<Status?>(null)
  private var scheduledDisconnect: Optional<ScheduledFuture<*>> = Optional.empty()

  override fun getStatus(): Status? = status.get()

  override fun sendStatus(): SafeFuture<Status> {
    try {
      val statusMessage = statusMessageFactory.createStatusMessage()
      val sendRpcMessage: SafeFuture<Message<Status, RpcMessageType>> =
        sendRpcMessage(statusMessage, rpcMethods.status())
      scheduleDisconnectIfStatusNotReceived(STATUS_TIMEOUT_SECONDS)
      return sendRpcMessage.thenApply { message -> message.payload }.whenComplete { status, error ->
        if (error != null) {
          this.disconnectImmediately(Optional.of(DisconnectReason.REMOTE_FAULT), false)
          log.debug("Failed to send status message to peer {}: {}", this.id, error.stackTraceToString())
        } else {
          this.updateStatus(status)
          log.debug("Received status message form peer {}: {}", this.id, status)
          scheduler.schedule(this::sendStatus, STATUS_RENEWAL_SECONDS, TimeUnit.SECONDS)
        }
      }
    } catch (e: Exception) {
      log.error("Failed to send status message to peer ${delegatePeer.id}", e)
      return SafeFuture.failedFuture(e)
    }
  }

  override fun updateStatus(status: Status) {
    scheduledDisconnect.ifPresent { it.cancel(false) }
    this.status.set(status)
    log.debug("Received status update from peer {}: {}", delegatePeer.id, status)
    if (this.connectionInitiatedRemotely()) {
      scheduleDisconnectIfStatusNotReceived(STATUS_RENEWAL_SECONDS + 10L)
    }
  }

  override fun scheduleDisconnectIfStatusNotReceived(delay: Long) {
    scheduledDisconnect =
      Optional.of(
        scheduler.schedule({ disconnectCleanly(DisconnectReason.REMOTE_FAULT) }, delay, TimeUnit.SECONDS),
      )
  }

  override fun sendBeaconBlocksByRange(
    startBlockNumber: ULong,
    count: ULong,
  ): SafeFuture<BeaconBlocksByRangeResponse> {
    val request = BeaconBlocksByRangeRequest(startBlockNumber, count)
    val message = Message(RpcMessageType.BEACON_BLOCKS_BY_RANGE, Version.V1, request)
    return sendRpcMessage(message, rpcMethods.beaconBlocksByRange())
      .thenApply { responseMessage -> responseMessage.payload }
  }

  fun <TRequest : Message<*, RpcMessageType>, TResponse : Message<*, RpcMessageType>> sendRpcMessage(
    message: TRequest,
    rpcMethod: MaruRpcMethod<TRequest, TResponse>,
  ): SafeFuture<TResponse> {
    val responseHandler = MaruRpcResponseHandler<TResponse>()
    return sendRequest<MaruOutgoingRpcRequestHandler<TResponse>, TRequest, MaruRpcResponseHandler<TResponse>>(
      rpcMethod,
      message,
      responseHandler,
    ).thenCompose {
      responseHandler.response()
    }
  }

  override fun getAddress(): PeerAddress = delegatePeer.address

  override fun getGossipScore(): Double = delegatePeer.gossipScore

  override fun isConnected(): Boolean = delegatePeer.isConnected

  override fun disconnectImmediately(
    reason: Optional<DisconnectReason>,
    locallyInitiated: Boolean,
  ) = delegatePeer.disconnectImmediately(reason, locallyInitiated)

  override fun disconnectCleanly(reason: DisconnectReason?): SafeFuture<Void> = delegatePeer.disconnectCleanly(reason)

  override fun setDisconnectRequestHandler(handler: DisconnectRequestHandler) =
    delegatePeer.setDisconnectRequestHandler(handler)

  override fun subscribeDisconnect(subscriber: PeerDisconnectedSubscriber) =
    delegatePeer.subscribeDisconnect(subscriber)

  override fun <TOutgoingHandler : RpcRequestHandler, TRequest : Any, RespHandler : RpcResponseHandler<*>> sendRequest(
    rpcMethod: RpcMethod<TOutgoingHandler, TRequest, RespHandler>,
    request: TRequest,
    responseHandler: RespHandler,
  ): SafeFuture<RpcStreamController<TOutgoingHandler>> = delegatePeer.sendRequest(rpcMethod, request, responseHandler)

  override fun connectionInitiatedLocally(): Boolean = delegatePeer.connectionInitiatedLocally()

  override fun connectionInitiatedRemotely(): Boolean = delegatePeer.connectionInitiatedRemotely()

  override fun adjustReputation(adjustment: ReputationAdjustment) = delegatePeer.adjustReputation(adjustment)

  override fun toString(): String =
    "DefaultMaruPeer(id=${id.toBase58()}, status=${status.get()}, address=${getAddress()}, " +
      "gossipScore=${getGossipScore()}, connected=$isConnected)"
}
