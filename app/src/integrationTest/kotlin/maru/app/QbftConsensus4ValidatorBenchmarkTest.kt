/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import io.libp2p.etc.types.fromHex
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import maru.core.SealedBeaconBlock
import maru.crypto.SecpCrypto
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.ThreadBesuNodeRunner
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import tech.pegasys.teku.infrastructure.async.SafeFuture
import testutils.PeeringNodeNetworkStack
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

/**
 * Benchmark measuring QBFT consensus latency with 4 validators on the local JVM — no containers
 *
 * Topology: full mesh — all 6 bidirectional connections among 4 validators.
 * Gossipsub requires D≥4 peers to form a proper MESH and forward received messages. With fewer peers,
 * nodes fall into flood-publish-only mode (only self-originated messages propagate), which means
 * PREPARE/COMMIT messages from non-proposing validators are never forwarded and quorum is never reached.
 * Hub-and-spoke and chain topologies both fail for this reason — only full mesh reliably works for
 * 4 validators in-JVM.
 *
 * Run with:
 *   ./gradlew :app:integrationTest --tests "maru.app.QbftConsensus4ValidatorBenchmarkTest"
 */
class QbftConsensus4ValidatorBenchmarkTest {
  // Keys match the K3S helm values exactly (protobuf-encoded secp256k1 private keys, no 0x prefix)
  // validator-0 → 0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0
  private val key0 = "080212201dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae".fromHex()

  // validator-1 → 0x9f2b437a74f98d9a4247393a5858de5473eb9e10
  private val key1 = "0802122100abb81ba53518eb0a206dfe80f2a973182e5d66c98cd31d00bf7471fcd5514157".fromHex()

  // validator-2 → 0xf64aeac8556931af7350d7b7e5e4c91003a84f0a
  private val key2 = "080212202fec0750fe3edc7e8272d4814a36b632921fc5e835d20a2de874471e8ad9ad0b".fromHex()

  // validator-3 → 0x504c58969274660b176d65daffdee3f8b1967b27
  private val key3 = "080212207a19c01ce2246b94b48ed778d9bfb3b76eaabe8193c468d6751f4e4d1adf98a8".fromHex()

  private lateinit var cluster: Cluster
  private lateinit var stack0: PeeringNodeNetworkStack
  private lateinit var stack1: PeeringNodeNetworkStack
  private lateinit var stack2: PeeringNodeNetworkStack
  private lateinit var stack3: PeeringNodeNetworkStack

  private val log = LogManager.getLogger(this.javaClass)

  private val maruFactory0 = MaruFactory(validatorPrivateKey = key0)
  private val maruFactory1 = MaruFactory(validatorPrivateKey = key1)
  private val maruFactory2 = MaruFactory(validatorPrivateKey = key2)
  private val maruFactory3 = MaruFactory(validatorPrivateKey = key3)

  @BeforeEach
  fun setUp() {
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )
    val besuBuilder = { BesuFactory.buildTestBesu(validator = false) }
    stack0 = PeeringNodeNetworkStack(besuBuilder)
    stack1 = PeeringNodeNetworkStack(besuBuilder)
    stack2 = PeeringNodeNetworkStack(besuBuilder)
    stack3 = PeeringNodeNetworkStack(besuBuilder)
    PeeringNodeNetworkStack.startBesuNodes(cluster, stack0, stack1, stack2, stack3)
  }

  @AfterEach
  fun tearDown() {
    runCatching { stack3.maruApp.stop().get() }
    runCatching { stack2.maruApp.stop().get() }
    runCatching { stack1.maruApp.stop().get() }
    runCatching { stack0.maruApp.stop().get() }
    runCatching { stack3.maruApp.close() }
    runCatching { stack2.maruApp.close() }
    runCatching { stack1.maruApp.close() }
    runCatching { stack0.maruApp.close() }
    cluster.close()
  }

  /** Key for the shared send-time map: (blockNumber, QBFT message code). */
  data class SendKey(
    val blockNumber: Long,
    val msgCode: Int,
  )

  /**
   * Per-block sample collected on app0 (the observed node).
   *
   * Phase timestamps (all wall-clock ms):
   *  timerFireMs        — when BLOCK_TIMER_EXPIRY fired on app0 (t=0 reference for both roles)
   *  proposalReceivedMs — when the PROPOSAL was dequeued by app0 (-1 = app0 was the proposer)
   *  firstPrepareMs     — when the 1st PREPARE message was dequeued by app0's event loop
   *  lastPrepareMs      — when the last PREPARE needed to reach quorum was dequeued
   *  firstCommitMs      — when the 1st COMMIT message was dequeued by app0's event loop
   *  lastCommitMs       — when the last COMMIT needed to reach quorum was dequeued
   *  commitWallClockMs  — when beaconChain subscriber fired (block persisted)
   *
   * Role detection: if [proposalReceivedMs] > 0, app0 received the PROPOSAL via P2P → non-proposer.
   * Proposers create the PROPOSAL themselves and do not receive it back through gossipsub.
   *
   * Proposer phases:
   *  timerToFirstPrepare  = finishBlockBuilding + PROPOSAL gossip + fastest_validator newPayload + PREPARE gossip
   *  firstToLastPrepare   = parallel newPayload time spread across other validators
   *  lastPrepareToFirstCommit = PREPARE→COMMIT state machine + COMMIT gossip
   *  firstToLastCommit    = COMMIT spread
   *  lastCommitToImport   = engine_forkchoiceUpdated + DB write
   *
   * Non-proposer phases (superset of proposer phases, plus two new ones):
   *  timerToProposal      = proposer's finishBlockBuilding + PROPOSAL gossip to app0
   *  proposalToFirstPrepare = app0's engine_newPayload (validateBlock) + PREPARE gossip + another validator's validate + PREPARE gossip back
   *  (then same lastPrepare/Commit/import phases)
   */
  data class Sample(
    val blockNumber: ULong,
    val clTimestampMs: Long,
    val timerFireMs: Long,
    val proposalReceivedMs: Long, // -1 = app0 was the proposer (no P2P PROPOSAL received)
    val firstPrepareMs: Long, // -1 = not observed
    val lastPrepareMs: Long, // last PREPARE needed for quorum (-1 = not observed)
    val firstCommitMs: Long, // -1 = not observed
    val lastCommitMs: Long, // last COMMIT needed for quorum (-1 = not observed)
    val importStartedMs: Long, // when QBFT event loop started import (-1 = not observed)
    val commitWallClockMs: Long,
  ) {
    /** True if app0 was a non-proposer for this block (received PROPOSAL via P2P). */
    val isNonProposer: Boolean get() = proposalReceivedMs > 0

    /** JVM timer-fire jitter: how late the block timer fired vs scheduled slot start. */
    val timerJitterMs: Int get() = (timerFireMs - clTimestampMs).toInt()

    /** True QBFT consensus latency: from timer fire to block committed. */
    val consensusLatencyMs: Int get() = (commitWallClockMs - timerFireMs).toInt()

    // ── proposer-specific ────────────────────────────────────────────────────

    /** [Proposer] timer → first PREPARE received on app0.
     *  = finishBlockBuilding + PROPOSAL gossip + fastest validator's newPayload + PREPARE gossip. */
    val timerToFirstPrepareMs: Int get() = if (firstPrepareMs > 0) (firstPrepareMs - timerFireMs).toInt() else -1

    // ── non-proposer-specific ─────────────────────────────────────────────────

    /** [Non-proposer] timer fire → PROPOSAL received on app0.
     *  ≈ proposer's finishBlockBuilding + PROPOSAL gossip to app0. */
    val timerToProposalMs: Int get() =
      if (proposalReceivedMs > 0) (proposalReceivedMs - timerFireMs).toInt() else -1

    /** [Non-proposer] PROPOSAL received → first PREPARE received on app0.
     *  ≈ app0's engine_newPayload (validateBlock) + gossip round-trip for another validator's PREPARE. */
    val proposalToFirstPrepareMs: Int get() =
      if (proposalReceivedMs > 0 && firstPrepareMs > 0) (firstPrepareMs - proposalReceivedMs).toInt() else -1

    // ── shared phases (both roles) ────────────────────────────────────────────

    /** First PREPARE → last PREPARE (parallel validation spread across validators). */
    val firstToLastPrepareMs: Int get() =
      if (firstPrepareMs > 0 && lastPrepareMs > 0) (lastPrepareMs - firstPrepareMs).toInt() else -1

    /** Last PREPARE → first COMMIT received on app0 (PREPARE→COMMIT state machine + COMMIT gossip). */
    val lastPrepareToFirstCommitMs: Int get() =
      if (lastPrepareMs > 0 && firstCommitMs > 0) (firstCommitMs - lastPrepareMs).toInt() else -1

    /** First COMMIT → last COMMIT (parallel COMMIT spread + gossip jitter). */
    val firstToLastCommitMs: Int get() =
      if (firstCommitMs > 0 && lastCommitMs > 0) (lastCommitMs - firstCommitMs).toInt() else -1

    /** Last COMMIT → block import (full: queue wait + seal verify + state transition + DB write + setHead). */
    val lastCommitToImportMs: Int get() =
      if (lastCommitMs > 0) (commitWallClockMs - lastCommitMs).toInt() else -1

    /** Last COMMIT → import started (BFT event queue wait + QBFT state machine processing). */
    val lastCommitToImportStartMs: Int get() =
      if (lastCommitMs > 0 && importStartedMs > 0) (importStartedMs - lastCommitMs).toInt() else -1

    /** Import started → block committed (seal verify + state transition + DB write + EL setHead). */
    val importStartToCommitMs: Int get() =
      if (importStartedMs > 0) (commitWallClockMs - importStartedMs).toInt() else -1
  }

  @Test
  fun `measure QBFT consensus latency on local JVM with 4 validators`() {
    val validator0 = SecpCrypto.privateKeyToValidator(SecpCrypto.privateKeyBytesWithoutPrefix(key0))
    val validator1 = SecpCrypto.privateKeyToValidator(SecpCrypto.privateKeyBytesWithoutPrefix(key1))
    val validator2 = SecpCrypto.privateKeyToValidator(SecpCrypto.privateKeyBytesWithoutPrefix(key2))
    val validator3 = SecpCrypto.privateKeyToValidator(SecpCrypto.privateKeyBytesWithoutPrefix(key3))
    val initialValidators = setOf(validator0, validator1, validator2, validator3)

    // Shared send-time registry: all 4 validators record when they broadcast each QBFT message.
    // Key = (blockNumber, msgCode), Value = list of wall-clock ms timestamps from all senders.
    // Used to compute P2P transit time = app0's receive time - earliest send time from another validator.
    val sendTimes = ConcurrentHashMap<SendKey, MutableList<Long>>()
    val sharedOnMessageSent: (Int, Long) -> Unit = { msgCode, sequenceNumber ->
      if (sequenceNumber > 0) {
        val wallClockMs = System.currentTimeMillis()
        sendTimes
          .getOrPut(SendKey(sequenceNumber, msgCode)) {
            java.util.Collections.synchronizedList(mutableListOf())
          }.add(wallClockMs)
      }
    }

    // Per-block timing state collected on app0.
    // Keyed by block number (Long). All timestamps are wall-clock ms.
    val timerFireTimes = ConcurrentHashMap<Long, Long>()
    // When the QBFT event loop begins block import (on event loop thread).
    // Fires inside QbftBlockImporterAdapter.importBlock(), before seal verification.
    val importStartTimes = ConcurrentHashMap<Long, Long>()

    // Per-block PROPOSAL arrival times on app0.
    // Non-empty ⟹ app0 is NOT the proposer for that block (proposers create the PROPOSAL themselves
    // and do not receive it back via P2P gossipsub).
    val proposalTimesPerBlock = ConcurrentHashMap<Long, MutableList<Long>>()
    // Per-block PREPARE arrival times on app0: list of wall-clock ms for each PREPARE received.
    // We need (quorum - 1) = 2 PREPAREs from other validators (app0 sends its own locally).
    val prepareTimesPerBlock = ConcurrentHashMap<Long, MutableList<Long>>()
    // Per-block COMMIT arrival times on app0: similarly need 2 COMMITs from others.
    val commitTimesPerBlock = ConcurrentHashMap<Long, MutableList<Long>>()

    // Per-event timing log from the QBFT event loop on app0.
    // Records every event processed with its label, duration, and wall-clock start time.

    val eventLog = ConcurrentLinkedQueue<EventRecord>()

    // Start all 4 validators without static peers — full mesh wired via addPeer after startup.
    val app0 =
      maruFactory0.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = stack0.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = stack0.besuNode.engineRpcUrl().get(),
        dataDir = stack0.tmpDir,
        syncingConfig = MaruFactory.defaultSyncingConfig,
        allowEmptyBlocks = true,
        initialValidators = initialValidators,
      )
    // Register observers BEFORE start() so the first block's events are captured.
    app0.onMessageSent = sharedOnMessageSent
    app0.onBlockTimerFired = { blockNumber -> timerFireTimes[blockNumber] = System.currentTimeMillis() }
    app0.onImportStarted = { blockNumber -> importStartTimes[blockNumber] = System.currentTimeMillis() }
    // Track event loop timing via onBeforeEvent/onAfterEvent.
    // Both callbacks fire on the same single event-loop thread, so ThreadLocal works perfectly.
    val threadMXBean = ManagementFactory.getThreadMXBean()
    val eventStartState = ThreadLocal<Triple<String, Long, Long>>() // (label, wallClockMs, cpuTimeNs)
    app0.onBeforeEvent = { label ->
      eventStartState.set(Triple(label, System.currentTimeMillis(), threadMXBean.currentThreadCpuTime))
    }
    app0.onAfterEvent = { label ->
      val (_, startMs, startCpu) = eventStartState.get()
      val nowMs = System.currentTimeMillis()
      val cpuNow = threadMXBean.currentThreadCpuTime
      eventLog.add(EventRecord(label, nowMs - startMs, startMs, cpuNow - startCpu))
    }
    // Track PROPOSAL, PREPARE, and COMMIT arrivals on app0's event loop by block.
    // sequenceNumber comes from the QBFT message RLP — no racy beacon chain lookup needed.
    app0.onMessageReceived = { msgCode, sequenceNumber ->
      if (sequenceNumber > 0) {
        val wallClockMs = System.currentTimeMillis()
        when (msgCode) {
          QbftV1.PROPOSAL ->
            proposalTimesPerBlock
              .getOrPut(sequenceNumber) {
                java.util.Collections.synchronizedList(mutableListOf())
              }.add(wallClockMs)
          QbftV1.PREPARE ->
            prepareTimesPerBlock
              .getOrPut(sequenceNumber) {
                java.util.Collections.synchronizedList(mutableListOf())
              }.add(wallClockMs)
          QbftV1.COMMIT ->
            commitTimesPerBlock
              .getOrPut(sequenceNumber) {
                java.util.Collections.synchronizedList(mutableListOf())
              }.add(wallClockMs)
          else -> Unit
        }
      }
    }
    stack0.setMaruApp(app0)
    app0.start().get()

    val app1 =
      maruFactory1.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = stack1.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = stack1.besuNode.engineRpcUrl().get(),
        dataDir = stack1.tmpDir,
        syncingConfig = MaruFactory.defaultSyncingConfig,
        allowEmptyBlocks = true,
        initialValidators = initialValidators,
      )
    app1.onMessageSent = sharedOnMessageSent
    stack1.setMaruApp(app1)
    app1.start().get()

    val app2 =
      maruFactory2.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = stack2.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = stack2.besuNode.engineRpcUrl().get(),
        dataDir = stack2.tmpDir,
        syncingConfig = MaruFactory.defaultSyncingConfig,
        allowEmptyBlocks = true,
        initialValidators = initialValidators,
      )
    app2.onMessageSent = sharedOnMessageSent
    stack2.setMaruApp(app2)
    app2.start().get()

    val app3 =
      maruFactory3.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = stack3.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = stack3.besuNode.engineRpcUrl().get(),
        dataDir = stack3.tmpDir,
        syncingConfig = MaruFactory.defaultSyncingConfig,
        allowEmptyBlocks = true,
        initialValidators = initialValidators,
      )
    app3.onMessageSent = sharedOnMessageSent
    stack3.setMaruApp(app3)
    app3.start().get()

    // Wire full mesh: 6 bidirectional connections for 4 nodes (n*(n-1)/2 = 6).
    // Each addPeer call initiates a connection that becomes bidirectional after the libp2p handshake.
    // Gossipsub requires D≥4 peers to form a proper MESH for message forwarding; with fewer peers
    // nodes fall into flood-publish-only mode and PREPARE/COMMIT messages from non-proposers are lost.
    fun peerAddr(app: MaruApp) = "/ip4/127.0.0.1/tcp/${app.p2pPort()}/p2p/${app.p2pNetwork.nodeId}"
    app1.p2pNetwork.addPeer(peerAddr(app0))
    app2.p2pNetwork.addPeer(peerAddr(app0))
    app2.p2pNetwork.addPeer(peerAddr(app1))
    app3.p2pNetwork.addPeer(peerAddr(app0))
    app3.p2pNetwork.addPeer(peerAddr(app1))
    app3.p2pNetwork.addPeer(peerAddr(app2))

    // Wait for full mesh — each validator should see exactly 3 peers.
    // Use 500ms polling to avoid Awaitility's default 10-second poll delay.
    app0.awaitTillMaruHasPeers(3u, pollingInterval = 500.milliseconds)
    app1.awaitTillMaruHasPeers(3u, pollingInterval = 500.milliseconds)
    app2.awaitTillMaruHasPeers(3u, pollingInterval = 500.milliseconds)
    app3.awaitTillMaruHasPeers(3u, pollingInterval = 500.milliseconds)
    log.info("All 4 validators peered in full mesh — starting measurement")

    // Transaction infrastructure: send 1 tx per block so blocks are non-empty.
    // Non-empty blocks bypass Besu's awaitCurrentBuildCompletion() wait in engine_getPayload,
    // removing the hardcoded 100ms sleep floor from the proposer path.
    val txHelper = BesuTransactionsHelper()
    val txRecipient = txHelper.createAccount("benchmark-tx-recipient")
    val txExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "tx-sender") }

    val blocksToMeasure = 100
    val latch = CountDownLatch(blocksToMeasure)
    val samples = ConcurrentLinkedQueue<Sample>()

    app0.beaconChain.addAsyncSubscriber { sealedBlock: SealedBeaconBlock ->
      val commitWallClock = System.currentTimeMillis()
      val header = sealedBlock.beaconBlock.beaconBlockHeader
      if (header.number > 0UL) {
        val blockNum = header.number.toLong()
        val clTimestampMs = header.timestamp.toLong() * 1000
        val timerFireMs = timerFireTimes[blockNum] ?: clTimestampMs

        val consensusMs = (commitWallClock - timerFireMs).toInt()
        val jitterMs = (timerFireMs - clTimestampMs).toInt()
        // Exclude startup artifacts and implausibly large values.
        if (consensusMs in 0..5000 && jitterMs >= -10) {
          // Extract PROPOSAL/PREPARE/COMMIT timestamps collected on app0 for this block.
          // Sort by arrival time so index 0 = first, last = last.
          val proposals = proposalTimesPerBlock[blockNum]?.sorted() ?: emptyList()
          val prepares = prepareTimesPerBlock[blockNum]?.sorted() ?: emptyList()
          val commits = commitTimesPerBlock[blockNum]?.sorted() ?: emptyList()
          samples.add(
            Sample(
              blockNumber = header.number,
              clTimestampMs = clTimestampMs,
              timerFireMs = timerFireMs,
              proposalReceivedMs = proposals.firstOrNull() ?: -1L,
              firstPrepareMs = prepares.firstOrNull() ?: -1L,
              lastPrepareMs = prepares.lastOrNull() ?: -1L,
              firstCommitMs = commits.firstOrNull() ?: -1L,
              lastCommitMs = commits.lastOrNull() ?: -1L,
              importStartedMs = importStartTimes[blockNum] ?: -1L,
              commitWallClockMs = commitWallClock,
            ),
          )
          // Submit one transaction asynchronously so the NEXT block is non-empty.
          // This bypasses Besu's awaitCurrentBuildCompletion() in engine_getPayload,
          // which only fires for empty blocks and introduces a hardcoded 100ms wait.
          txExecutor.submit {
            runCatching {
              txHelper.run {
                stack0.besuNode.sendTransaction(
                  logger = log,
                  recipient = txRecipient,
                  amount = Amount.ether(1),
                )
              }
            }.onFailure { log.warn("tx submission failed for block {}: {}", blockNum, it.message) }
          }
          latch.countDown()
        }
      }
      SafeFuture.completedFuture(Unit)
    }

    assertThat(latch.await(blocksToMeasure * 3L, TimeUnit.SECONDS))
      .withFailMessage("Timed out waiting for $blocksToMeasure blocks — check validator logs")
      .isTrue()

    txExecutor.shutdown()

    printResults(
      samples.toList(),
      sendTimes,
      proposalTimesPerBlock,
      prepareTimesPerBlock,
      commitTimesPerBlock,
      eventLog.toList(),
    )
  }

  data class EventRecord(
    val eventLabel: String,
    val durationMs: Long,
    val wallClockStartMs: Long,
    val cpuTimeNs: Long,
  )

  private fun printResults(
    samples: List<Sample>,
    sendTimes: ConcurrentHashMap<SendKey, MutableList<Long>> = ConcurrentHashMap(),
    proposalTimesPerBlock: ConcurrentHashMap<Long, MutableList<Long>> = ConcurrentHashMap(),
    prepareTimesPerBlock: ConcurrentHashMap<Long, MutableList<Long>> = ConcurrentHashMap(),
    commitTimesPerBlock: ConcurrentHashMap<Long, MutableList<Long>> = ConcurrentHashMap(),
    eventLog: List<EventRecord> = emptyList(),
  ) {
    if (samples.size < 2) {
      log.warn("Not enough samples")
      return
    }
    val ordered = samples.sortedBy { it.blockNumber }

    fun stats(values: List<Int>): String {
      val s = values.sorted()
      return "min=${s.first()}ms avg=${s.average().toInt()}ms " +
        "p50=${s[s.size / 2]}ms p95=${s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)]}ms max=${s.last()}ms"
    }

    fun phaseStats(
      values: List<Int>,
      label: String,
    ): String {
      val valid = values.filter { it >= 0 }
      return if (valid.isEmpty()) {
        "$label: n/a (no samples)"
      } else {
        val s = valid.sorted()
        "$label (n=${s.size}): min=${s.first()}ms avg=${s.average().toInt()}ms " +
          "p50=${s[s.size / 2]}ms p95=${s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)]}ms max=${s.last()}ms"
      }
    }

    val proposerSamples = ordered.filter { !it.isNonProposer }
    val nonProposerSamples = ordered.filter { it.isNonProposer }

    val consensusLatency = ordered.map { it.consensusLatencyMs }
    val timerJitter = ordered.map { it.timerJitterMs }
    val commitGaps = ordered.zipWithNext { a, b -> (b.commitWallClockMs - a.commitWallClockMs).toInt() - 1000 }

    log.info("==========================================================")
    log.info("  QBFT Consensus Latency — local JVM, real libp2p, NO K3S — 1 tx/block")
    log.info(
      "  Validators: 4  |  Blocks: ${ordered.size}  " +
        "(proposer=${proposerSamples.size}, non-proposer=${nonProposerSamples.size})",
    )
    log.info("")
    log.info("  consensusLatency (all)  {}", stats(consensusLatency))
    log.info("    ^ timer-fire → block committed; comparable to K3S avg≈161ms")
    log.info("  timerJitter             {}", stats(timerJitter))
    log.info("    ^ how late the block timer fired vs the scheduled CL slot start")
    log.info("  commitGap - 1000ms      {}", stats(commitGaps))
    log.info("    ^ change in latency between consecutive blocks; avg≈0 means steady throughput")
    log.info("")
    log.info("  ══ PROPOSER PATH (app0 built the block) — {} blocks ══", proposerSamples.size)
    log.info(
      "  Phase 1: {}",
      phaseStats(
        proposerSamples.map { it.timerToFirstPrepareMs },
        "timer → 1st PREPARE",
      ),
    )
    log.info("    ^ = finishBlockBuilding + PROPOSAL gossip + fastest validator's newPayload + PREPARE gossip")
    log.info(
      "  Phase 2: {}",
      phaseStats(
        proposerSamples.map { it.firstToLastPrepareMs },
        "1st → last PREPARE",
      ),
    )
    log.info("    ^ = spread in newPayload latency across other validators (parallelism + contention)")
    log.info(
      "  Phase 3: {}",
      phaseStats(
        proposerSamples.map { it.lastPrepareToFirstCommitMs },
        "last PREPARE → 1st COMMIT",
      ),
    )
    log.info("    ^ = PREPARE→COMMIT state machine step + COMMIT gossip")
    log.info(
      "  Phase 4: {}",
      phaseStats(
        proposerSamples.map { it.firstToLastCommitMs },
        "1st → last COMMIT",
      ),
    )
    log.info("    ^ = parallel COMMIT spread + gossip jitter")
    log.info(
      "  Phase 5: {}",
      phaseStats(
        proposerSamples.map { it.lastCommitToImportMs },
        "last COMMIT → import (total)",
      ),
    )
    log.info(
      "    Phase 5a: {}",
      phaseStats(
        proposerSamples.map { it.lastCommitToImportStartMs },
        "last COMMIT → import started",
      ),
    )
    log.info("      ^ = BFT event queue wait + QBFT state machine processing")
    log.info(
      "    Phase 5b: {}",
      phaseStats(
        proposerSamples.map { it.importStartToCommitMs },
        "import started → committed",
      ),
    )
    log.info("      ^ = seal verify + state transition + DB write + EL setHead (blocking)")
    log.info(
      "  Consensus: {}",
      if (proposerSamples.isEmpty()) {
        "n/a"
      } else {
        stats(proposerSamples.map { it.consensusLatencyMs })
      },
    )
    log.info("")
    log.info("  ══ NON-PROPOSER PATH (app0 received PROPOSAL via P2P) — {} blocks ══", nonProposerSamples.size)
    log.info(
      "  Phase A: {}",
      phaseStats(
        nonProposerSamples.map { it.timerToProposalMs },
        "timer → PROPOSAL received",
      ),
    )
    log.info("    ^ = proposer's finishBlockBuilding + PROPOSAL gossip to app0")
    log.info(
      "  Phase B: {}",
      phaseStats(
        nonProposerSamples.map { it.proposalToFirstPrepareMs },
        "PROPOSAL → 1st PREPARE received",
      ),
    )
    log.info("    ^ = app0's engine_newPayload (validateBlock) + another validator's same + PREPARE gossip")
    log.info(
      "  Phase C: {}",
      phaseStats(
        nonProposerSamples.map { it.firstToLastPrepareMs },
        "1st → last PREPARE",
      ),
    )
    log.info("    ^ = parallel validation spread")
    log.info(
      "  Phase D: {}",
      phaseStats(
        nonProposerSamples.map { it.lastPrepareToFirstCommitMs },
        "last PREPARE → 1st COMMIT",
      ),
    )
    log.info(
      "  Phase E: {}",
      phaseStats(
        nonProposerSamples.map { it.firstToLastCommitMs },
        "1st → last COMMIT",
      ),
    )
    log.info(
      "  Phase F: {}",
      phaseStats(
        nonProposerSamples.map { it.lastCommitToImportMs },
        "last COMMIT → import (total)",
      ),
    )
    log.info(
      "    Phase F1: {}",
      phaseStats(
        nonProposerSamples.map { it.lastCommitToImportStartMs },
        "last COMMIT → import started",
      ),
    )
    log.info("      ^ = BFT event queue wait + QBFT state machine processing")
    log.info(
      "    Phase F2: {}",
      phaseStats(
        nonProposerSamples.map { it.importStartToCommitMs },
        "import started → committed",
      ),
    )
    log.info("      ^ = seal verify + state transition + DB write + EL setHead (blocking)")
    log.info(
      "  Consensus: {}",
      if (nonProposerSamples.isEmpty()) {
        "n/a"
      } else {
        stats(nonProposerSamples.map { it.consensusLatencyMs })
      },
    )
    log.info("")
    log.info(
      "  consensusLatency per block: {}",
      ordered.joinToString(" ") {
        "${it.blockNumber}:${it.consensusLatencyMs}ms(${if (it.isNonProposer) "NP" else "P"})"
      },
    )
    log.info("")
    log.info("  ══ P2P GOSSIP TRANSIT TIME (send → receive on app0) ══")
    log.info("    transit = app0 receive_time - earliest send_time (from any validator)")

    // Compute transit times per message type for each block.
    // For each (block, msgCode), transit = app0's first receive time - earliest send time.
    fun transitTimesFor(
      msgCode: Int,
      receiveTimesPerBlock: ConcurrentHashMap<Long, MutableList<Long>>,
    ): List<Int> =
      ordered.mapNotNull { sample ->
        val blockNum = sample.blockNumber.toLong()
        val sendList = sendTimes[SendKey(blockNum, msgCode)]
        val receiveList = receiveTimesPerBlock[blockNum]
        if (sendList != null && receiveList != null && sendList.isNotEmpty() && receiveList.isNotEmpty()) {
          val earliestSend = sendList.min()
          val firstReceive = receiveList.min()
          (firstReceive - earliestSend).toInt()
        } else {
          null
        }
      }
    val proposalTransit = transitTimesFor(QbftV1.PROPOSAL, proposalTimesPerBlock)
    val prepareTransit = transitTimesFor(QbftV1.PREPARE, prepareTimesPerBlock)
    val commitTransit = transitTimesFor(QbftV1.COMMIT, commitTimesPerBlock)
    log.info("  {}", phaseStats(proposalTransit, "PROPOSAL transit"))
    log.info("    ^ earliest send of PROPOSAL by any validator → first PROPOSAL received on app0")
    log.info("  {}", phaseStats(prepareTransit, "PREPARE transit"))
    log.info("    ^ earliest send of PREPARE by any validator → first PREPARE received on app0")
    log.info("  {}", phaseStats(commitTransit, "COMMIT transit"))
    log.info("    ^ earliest send of COMMIT by any validator → first COMMIT received on app0")
    log.info("")
    log.info("----------------------------------------------------------")
    log.info("  ══ EVENT LOOP TIMELINE (what app0's event loop was doing per block) ══")
    if (eventLog.isNotEmpty()) {
      // Group events by block: for each sample, find all events that occurred between
      // the timer fire of this block and the timer fire of the next block (or commit time for last block).
      val sortedSamples = ordered.sortedBy { it.timerFireMs }
      for ((idx, sample) in sortedSamples.withIndex()) {
        val windowStart = sample.timerFireMs
        val windowEnd =
          if (idx + 1 <
            sortedSamples.size
          ) {
            sortedSamples[idx + 1].timerFireMs
          } else {
            sample.commitWallClockMs + 100
          }
        val blockEvents =
          eventLog
            .filter { it.wallClockStartMs in windowStart until windowEnd }
            .sortedBy { it.wallClockStartMs }
        if (blockEvents.isNotEmpty()) {
          val role = if (sample.isNonProposer) "NP" else "P"
          val totalEventMs = blockEvents.sumOf { it.durationMs }
          val totalCpuMs = blockEvents.sumOf { it.cpuTimeNs } / 1_000_000.0
          val timeline =
            blockEvents.joinToString(" → ") {
              val cpuMs = it.cpuTimeNs / 1_000_000.0
              "${it.eventLabel}(${it.durationMs}ms/cpu=${"%.1f".format(cpuMs)}ms)"
            }
          log.info(
            "    block {} ({}): events={} wallMs={} cpuMs={} ratio={} | {}",
            sample.blockNumber,
            role,
            blockEvents.size,
            totalEventMs,
            "%.1f".format(totalCpuMs),
            "%.0f%%".format(totalCpuMs / totalEventMs * 100),
            timeline,
          )
        }
      }
      // Aggregate: average processing time per event type across all blocks
      log.info("")
      log.info("  ══ EVENT LOOP AGGREGATE (avg processing time per event type) ══")
      log.info("    wallMs=wall clock duration, cpuMs=actual CPU time on event loop thread")
      log.info("    ratio=cpuMs/wallMs — low ratio means thread was descheduled (waiting for CPU)")
      val byType = eventLog.groupBy { it.eventLabel }
      for ((label, events) in byType.entries.sortedByDescending { it.value.map { e -> e.durationMs }.average() }) {
        val durations = events.map { it.durationMs }
        val cpuDurations = events.map { it.cpuTimeNs / 1_000_000.0 }
        val s = durations.sorted()
        val avgWallMs = s.average()
        val avgCpuMs = cpuDurations.average()
        val ratio = if (avgWallMs > 0) avgCpuMs / avgWallMs * 100 else 0.0
        log.info(
          "    {} (n={}): wall=[min={}ms avg={}ms p50={}ms p95={}ms max={}ms] cpu=[avg={}ms] ratio={}",
          label,
          s.size,
          s.first(),
          s.average().toInt(),
          s[s.size / 2],
          s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)],
          s.last(),
          "%.1f".format(avgCpuMs),
          "%.0f%%".format(ratio),
        )
      }
      log.info("")
      log.info("  ══ CPU vs WALL TIME SUMMARY ══")
      log.info("    If ratio is low (<50%), the event loop thread is being descheduled by the OS")
      log.info("    (CPU contention from 4 Besu + 4 Maru instances sharing cores)")
      log.info("    If ratio is high (>80%), the thread is CPU-bound (actual work, not scheduling)")
      val allWallMs = eventLog.sumOf { it.durationMs }
      val allCpuMs = eventLog.sumOf { it.cpuTimeNs } / 1_000_000.0
      val overallRatio = if (allWallMs > 0) allCpuMs / allWallMs * 100 else 0.0
      log.info(
        "    Total wall time: {}ms  Total CPU time: {}ms  Overall ratio: {}",
        allWallMs,
        "%.1f".format(allCpuMs),
        "%.0f%%".format(overallRatio),
      )
    } else {
      log.info("    (no event loop data captured)")
    }
    log.info("")
    log.info("  K3S baseline (4 validators): proposal→import median≈161ms, E2E≈749ms")
    log.info("==========================================================")
  }
}
