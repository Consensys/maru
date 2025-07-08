/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import maru.p2p.messages.Status
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManagerTest {
  @Test
  fun `disconnects peer if status not received within timeout`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val mockScheduledFuture = mock<ScheduledFuture<*>>()
    val runnableCaptor = argumentCaptor<Runnable>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(false)
    whenever(maruPeer.getStatus()).thenReturn(null)
    doReturn(
      mockScheduledFuture,
    ).whenever(mockScheduler).schedule(runnableCaptor.capture(), eq(10L), eq(TimeUnit.SECONDS))

    val manager = MaruPeerManager(mockScheduler, maruPeerFactory)
    manager.onConnect(peer)

    // Simulate timeout by executing the captured runnable
    runnableCaptor.firstValue.run()

    verify(maruPeer).disconnectImmediately(any(), eq(false))
  }

  @Test
  fun `does not disconnect peer if status is received before timeout`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val mockScheduledFuture = mock<ScheduledFuture<*>>()
    val runnableCaptor = argumentCaptor<Runnable>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val status = mock<Status>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(false)
    whenever(maruPeer.getStatus()).thenReturn(status)
    doReturn(
      mockScheduledFuture,
    ).whenever(mockScheduler).schedule(runnableCaptor.capture(), eq(10L), eq(TimeUnit.SECONDS))

    val manager = MaruPeerManager(mockScheduler, maruPeerFactory)
    manager.onConnect(peer)

    // Simulate timeout by executing the captured runnable
    runnableCaptor.firstValue.run()

    verify(maruPeer, never()).disconnectImmediately(any(), any())
  }

  @Test
  fun `does not schedule timeout when connection is initiated locally`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)

    val manager = MaruPeerManager(mockScheduler, maruPeerFactory)
    manager.onConnect(peer)

    verify(mockScheduler, never()).schedule(any<Runnable>(), any(), any())
    verify(maruPeer).sendStatus()
  }
}
