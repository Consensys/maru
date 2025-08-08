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
import kotlin.time.Duration
import maru.config.P2P
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
import tech.pegasys.teku.networking.p2p.peer.PeerDisconnectedException
import tech.pegasys.teku.networking.p2p.peer.PeerDisconnectedSubscriber
import tech.pegasys.teku.networking.p2p.reputation.ReputationAdjustment
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcStreamController

interface MaruPeer : Peer {
  fun getStatus(): Status?

  fun sendStatus(): SafeFuture<Unit>

  fun updateStatus(newStatus: Status)

  fun sendBeaconBlocksByRange(
    startBlockNumber: ULong,
    count: ULong,
  ): SafeFuture<BeaconBlocksByRangeResponse>

  fun scheduleDisconnectIfStatusNotReceived(delay: Duration)
}

interface MaruPeerFactory {
  fun createMaruPeer(delegatePeer: Peer): MaruPeer
}

class DefaultMaruPeerFactory(
  private val rpcMethods: RpcMethods,
  private val statusMessageFactory: StatusMessageFactory,
  private val p2pConfig: P2P,
) : MaruPeerFactory {
  override fun createMaruPeer(delegatePeer: Peer): MaruPeer =
    DefaultMaruPeer(
      delegatePeer = delegatePeer,
      rpcMethods = rpcMethods,
      statusMessageFactory = statusMessageFactory,
      p2pConfig = p2pConfig,
    )
}

class DefaultMaruPeer(
  private val delegatePeer: Peer,
  private val rpcMethods: RpcMethods,
  private val statusMessageFactory: StatusMessageFactory,
  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(
      Thread.ofVirtual().factory(),
    ),
  private val p2pConfig: P2P,
) : MaruPeer {
  init {
    delegatePeer.subscribeDisconnect { _, _ -> scheduler.shutdown() }
  }

  private val log: Logger = LogManager.getLogger(this.javaClass)
  private val status = AtomicReference<Status?>(null)
  private var scheduledDisconnect: Optional<ScheduledFuture<*>> = Optional.empty()

  override fun getStatus(): Status? = status.get()

  override fun sendStatus(): SafeFuture<Unit> {
    try {
      val statusMessage = statusMessageFactory.createStatusMessage()
      val sendRpcMessage: SafeFuture<Message<Status, RpcMessageType>> =
        sendRpcMessage(statusMessage, rpcMethods.status())
      scheduleDisconnectIfStatusNotReceived(p2pConfig.statusUpdate.timeout)
      return sendRpcMessage
        .thenApply { message -> message.payload }
        .whenComplete { status, error ->
          if (error != null) {
            disconnectImmediately(Optional.of(DisconnectReason.REMOTE_FAULT), false)
            if (error.cause !is PeerDisconnectedException) {
              log.debug("Failed to send status message to peer={}: errorMessage={}", this.id, error.message, error)
            }
          } else {
            updateStatus(status)
            scheduler.schedule(
              this::sendStatus,
              p2pConfig.statusUpdate.refreshInterval.inWholeSeconds,
              TimeUnit.SECONDS,
            )
          }
        }.thenApply {}
    } catch (e: Exception) {
      if (e.cause !is PeerDisconnectedException) {
        log.error("Failed to send status message to peer={}", id, e)
      }
      return SafeFuture.failedFuture(e)
    }
  }

  override fun updateStatus(newStatus: Status) {
    scheduledDisconnect.ifPresent { it.cancel(false) }
    status.set(newStatus)
    log.trace("Received status update from peer={}: status={}", id, newStatus)
    if (connectionInitiatedRemotely()) {
      scheduleDisconnectIfStatusNotReceived(
        p2pConfig.statusUpdate.refreshInterval + p2pConfig.statusUpdate.refreshIntervalLeeway,
      )
    }
  }

  override fun scheduleDisconnectIfStatusNotReceived(delay: Duration) {
    scheduledDisconnect.ifPresent { it.cancel(false) }
    if (!scheduler.isShutdown) {
      scheduledDisconnect =
        Optional.of(
          scheduler.schedule(
            {
              log.debug("Disconnecting from peerId={} by timeout", this.id)
              disconnectCleanly(DisconnectReason.REMOTE_FAULT)
            },
            delay.inWholeSeconds,
            TimeUnit.SECONDS,
          ),
        )
    }
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
