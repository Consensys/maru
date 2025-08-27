/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.beaconchain.pipeline

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maru.consensus.blockimport.SealedBeaconBlockImporter
import maru.core.SealedBeaconBlock
import maru.core.ext.DataGenerators.randomSealedBeaconBlock
import maru.p2p.MaruPeer
import maru.p2p.PeerLookup
import maru.p2p.ValidationResult
import maru.p2p.messages.BeaconBlocksByRangeResponse
import maru.syncing.beaconchain.pipeline.DataGenerators.randomStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture

class BeaconChainDownloadPipelineFactoryTest {
  private lateinit var blockImporter: SealedBeaconBlockImporter<ValidationResult>
  private lateinit var peerLookup: PeerLookup
  private lateinit var factory: BeaconChainDownloadPipelineFactory
  private lateinit var executorService: ExecutorService
  private lateinit var syncTargetProvider: () -> ULong

  @BeforeEach
  fun setUp() {
    blockImporter = mock()
    peerLookup = mock()
    executorService = Executors.newCachedThreadPool()
    syncTargetProvider = mock()
    factory =
      BeaconChainDownloadPipelineFactory(
        blockImporter = blockImporter,
        metricsSystem = NoOpMetricsSystem(),
        peerLookup = peerLookup,
        config = BeaconChainDownloadPipelineFactory.Config(),
        syncTargetProvider = syncTargetProvider,
      )
  }

  @AfterEach
  fun tearDown() {
    executorService.shutdownNow()
  }

  @Test
  fun `pipeline processes blocks in correct ranges`() {
    val peer = mock<MaruPeer>()
    whenever(peerLookup.getPeers()).thenReturn(listOf(peer))

    val rangeResponses = mutableMapOf<Pair<ULong, ULong>, List<SealedBeaconBlock>>()

    // Ranges: [100, 291], [292, 483], [484, 490]
    rangeResponses[100uL to 192uL] = (100uL..291uL).map { randomSealedBeaconBlock(it) }
    rangeResponses[292uL to 192uL] = (292uL..483uL).map { randomSealedBeaconBlock(it) }
    rangeResponses[484uL to 7uL] = (484uL..490uL).map { randomSealedBeaconBlock(it) }

    rangeResponses.forEach { (range, blocks) ->
      val response = mock<BeaconBlocksByRangeResponse>()
      whenever(response.blocks).thenReturn(blocks)
      whenever(peer.sendBeaconBlocksByRange(range.first, range.second)).thenReturn(completedFuture(response))
      whenever(peer.getStatus()).thenReturn(randomStatus(490uL))
    }

    whenever(blockImporter.importBlock(any())).thenReturn(
      completedFuture(ValidationResult.Companion.Valid),
    )
    whenever(syncTargetProvider.invoke()).thenReturn(490uL)

    val pipeline = factory.createPipeline(100uL)
    val completionFuture = pipeline.pipeline.start(executorService)

    // Wait for completion
    completionFuture.get(5, TimeUnit.SECONDS)

    // Verify all blocks were imported
    val numberOfImportedBlocks = 490 - 100 + 1 // Total blocks from 100 to 125 inclusive
    assertThat(pipeline.target()).isEqualTo(490uL)
    verify(blockImporter, times(numberOfImportedBlocks)).importBlock(any())
  }

  @Test
  fun `pipeline adapts to increased syncTarget during execution`() {
    val peer = mock<MaruPeer>()
    whenever(peerLookup.getPeers()).thenReturn(listOf(peer))

    val rangeResponses = mutableMapOf<Pair<ULong, ULong>, List<SealedBeaconBlock>>()

    // Ranges: [100, 291], [292, 483], [484, 490]
    rangeResponses[100uL to 192uL] = (100uL..291uL).map { randomSealedBeaconBlock(it) }
    rangeResponses[292uL to 192uL] = (292uL..483uL).map { randomSealedBeaconBlock(it) }
    rangeResponses[484uL to 7uL] = (484uL..490uL).map { randomSealedBeaconBlock(it) }

    rangeResponses.forEach { (range, blocks) ->
      val response = mock<BeaconBlocksByRangeResponse>()
      whenever(response.blocks).thenReturn(blocks)
      whenever(peer.sendBeaconBlocksByRange(range.first, range.second)).thenReturn(completedFuture(response))
      whenever(peer.getStatus()).thenReturn(randomStatus(490uL))
    }

    whenever(blockImporter.importBlock(any())).thenReturn(
      completedFuture(ValidationResult.Companion.Valid),
    )
    // the initial sync target is 383, but we will change it to 390 during execution
    whenever(syncTargetProvider.invoke()).thenReturn(383uL, 490uL, 490uL)

    val pipeline = factory.createPipeline(100uL)
    val completionFuture = pipeline.pipeline.start(executorService)

    // Wait for completion
    completionFuture.get(5, TimeUnit.SECONDS)

    // Verify all blocks were imported
    val numberOfImportedBlocks = 490 - 100 + 1 // Total blocks from 100 to 490 inclusive
    assertThat(pipeline.target()).isEqualTo(490uL)
    verify(blockImporter, times(numberOfImportedBlocks)).importBlock(any())
  }

  @Test
  fun `pipeline handles single block range`() {
    val peer = mock<MaruPeer>()
    whenever(peerLookup.getPeers()).thenReturn(listOf(peer))

    val blocks = listOf(randomSealedBeaconBlock(42uL))
    val response = mock<BeaconBlocksByRangeResponse>()
    whenever(response.blocks).thenReturn(blocks)
    whenever(peer.sendBeaconBlocksByRange(42uL, 1uL)).thenReturn(completedFuture(response))
    whenever(peer.getStatus()).thenReturn(randomStatus(43uL))

    whenever(blockImporter.importBlock(any())).thenReturn(
      completedFuture(ValidationResult.Companion.Valid),
    )
    whenever(syncTargetProvider.invoke()).thenReturn(42uL)

    val pipeline = factory.createPipeline(42uL)
    val completionFuture = pipeline.pipeline.start(executorService)

    completionFuture.get(5, TimeUnit.SECONDS)

    assertThat(pipeline.target()).isEqualTo(42uL)
    verify(peer).sendBeaconBlocksByRange(42uL, 1uL)
    verify(blockImporter).importBlock(blocks[0])
  }

  @Test
  fun `pipeline with large request size processes correct ranges`() {
    val largeRequestSizeFactory =
      BeaconChainDownloadPipelineFactory(
        blockImporter = blockImporter,
        metricsSystem = NoOpMetricsSystem(),
        peerLookup = peerLookup,
        config = BeaconChainDownloadPipelineFactory.Config(blocksBatchSize = 100u),
        syncTargetProvider = { 50uL },
      )

    val peer = mock<MaruPeer>()
    whenever(peerLookup.getPeers()).thenReturn(listOf(peer))

    // Create blocks for range [0, 50]
    val blocks = (0uL..50uL).map { randomSealedBeaconBlock(it) }
    val response = mock<BeaconBlocksByRangeResponse>()
    whenever(response.blocks).thenReturn(blocks)
    whenever(peer.sendBeaconBlocksByRange(0uL, 51uL)).thenReturn(completedFuture(response))
    whenever(peer.getStatus()).thenReturn(randomStatus(50uL))

    whenever(blockImporter.importBlock(any())).thenReturn(
      completedFuture(ValidationResult.Companion.Valid),
    )

    val pipeline = largeRequestSizeFactory.createPipeline(0uL)
    val completionFuture = pipeline.pipeline.start(executorService)

    completionFuture.get(5, TimeUnit.SECONDS)

    // Should make only one request since request size (100) is larger than range
    verify(peer).sendBeaconBlocksByRange(0uL, 51uL)
  }

  @Test
  fun `factory creates multiple independent pipelines`() {
    val pipeline1 = factory.createPipeline(100uL)
    val pipeline2 = factory.createPipeline(100uL)

    assertThat(pipeline1).isNotNull()
    assertThat(pipeline2).isNotNull()
    assertThat(pipeline1).isNotSameAs(pipeline2)
  }

  @Test
  fun `factory construction throws when requestSize is zero`() {
    assertThatThrownBy {
      BeaconChainDownloadPipelineFactory(
        blockImporter = blockImporter,
        metricsSystem = NoOpMetricsSystem(),
        peerLookup = peerLookup,
        config = BeaconChainDownloadPipelineFactory.Config(blocksBatchSize = 0u),
        syncTargetProvider = { 0uL },
      )
    }.isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("Request size must be greater than 0")
  }

  @Test
  fun `pipeline handles ranges near ULong MAX_VALUE without overflow`() {
    val peer = mock<MaruPeer>()
    whenever(peerLookup.getPeers()).thenReturn(listOf(peer))

    val block1 = randomSealedBeaconBlock(ULong.MAX_VALUE - 2uL)
    val block2 = randomSealedBeaconBlock(ULong.MAX_VALUE - 1uL)

    // Test with a range very close to ULong.MAX_VALUE
    val startBlock = ULong.MAX_VALUE - 2uL

    // The expected ranges with request size 2
    whenever(
      peer.sendBeaconBlocksByRange(startBlock, 2uL),
    ).thenReturn(completedFuture(BeaconBlocksByRangeResponse(listOf(block1))))

    whenever(
      peer.sendBeaconBlocksByRange(startBlock + 1uL, 1uL),
    ).thenReturn(completedFuture(BeaconBlocksByRangeResponse(listOf(block2))))

    whenever(peer.getStatus()).thenReturn(randomStatus(ULong.MAX_VALUE))
    whenever(blockImporter.importBlock(any())).thenReturn(completedFuture(ValidationResult.Companion.Valid))
    whenever(syncTargetProvider.invoke()).thenReturn(ULong.MAX_VALUE - 1uL)

    val pipeline = factory.createPipeline(startBlock)
    val completionFuture = pipeline.pipeline.start(executorService)

    // Should complete without overflow errors
    completionFuture.get(5, TimeUnit.SECONDS)
    assertThat(completionFuture).isCompleted
  }
}
