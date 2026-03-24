/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.util.concurrent.ConcurrentHashMap
import maru.core.SealedBeaconBlock
import maru.metrics.MaruMetricsCategory
import net.consensys.linea.metrics.MetricsFacade
import net.consensys.linea.metrics.Tag

/**
 * Micrometer-based consensus metrics that record QBFT phase latencies as histograms,
 * labeled by [role] (proposer vs non-proposer).
 *
 * Role detection: if a PROPOSAL was received via P2P for a given block, this node was a non-proposer;
 * otherwise it was the proposer (proposers create the PROPOSAL locally and never receive it back via gossipsub).
 *
 * Lifecycle: create once per [MaruApp] init (when `config.qbft != null`), then call the `record*` methods
 * from the observer callbacks. Stale per-block state is cleaned up automatically.
 */
class ConsensusMetrics(
  metricsFacade: MetricsFacade,
) {
  companion object {
    // QBFT V1 message codes (from org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1)
    private const val MSG_PROPOSAL = 0x12
    private const val MSG_PREPARE = 0x13
    private const val MSG_COMMIT = 0x14

    private const val ROLE_PROPOSER = "proposer"
    private const val ROLE_NON_PROPOSER = "non_proposer"
  }

  // ── per-block timestamp state (wall-clock ms) ──────────────────────────────

  private val timerFireTimes = ConcurrentHashMap<Long, Long>()
  private val importStartTimes = ConcurrentHashMap<Long, Long>()
  private val proposalTimes = ConcurrentHashMap<Long, Long>()
  private val firstPrepareTimes = ConcurrentHashMap<Long, Long>()
  private val lastPrepareTimes = ConcurrentHashMap<Long, Long>()
  private val firstCommitTimes = ConcurrentHashMap<Long, Long>()
  private val lastCommitTimes = ConcurrentHashMap<Long, Long>()

  // ── Micrometer histograms (one per role) ────────────────────────────────────

  private fun histogram(
    metricsFacade: MetricsFacade,
    name: String,
    description: String,
    role: String,
  ) = metricsFacade.createHistogram(
    category = MaruMetricsCategory.CONSENSUS,
    name = name,
    description = description,
    tags = listOf(Tag("role", role)),
  )

  // Total consensus latency: timer-fire to block committed (ms).
  private val blockLatencyProposer =
    histogram(metricsFacade, "block.latency", "Total consensus latency (ms)", ROLE_PROPOSER)
  private val blockLatencyNonProposer =
    histogram(metricsFacade, "block.latency", "Total consensus latency (ms)", ROLE_NON_PROPOSER)

  // Phase: timer → PROPOSAL received (non-proposer only).
  private val phaseProposal =
    histogram(metricsFacade, "phase.proposal", "Timer fire to PROPOSAL received (ms)", ROLE_NON_PROPOSER)

  // Phase: start → first PREPARE received.
  private val phaseFirstPrepareProposer =
    histogram(metricsFacade, "phase.prepare.first", "Start to first PREPARE (ms)", ROLE_PROPOSER)
  private val phaseFirstPrepareNonProposer =
    histogram(metricsFacade, "phase.prepare.first", "Start to first PREPARE (ms)", ROLE_NON_PROPOSER)

  // Phase: first → last PREPARE (spread).
  private val phasePrepareSpreadProposer =
    histogram(metricsFacade, "phase.prepare.spread", "First to last PREPARE (ms)", ROLE_PROPOSER)
  private val phasePrepareSpreadNonProposer =
    histogram(metricsFacade, "phase.prepare.spread", "First to last PREPARE (ms)", ROLE_NON_PROPOSER)

  // Phase: last PREPARE → first COMMIT.
  private val phaseFirstCommitProposer =
    histogram(metricsFacade, "phase.commit.first", "Last PREPARE to first COMMIT (ms)", ROLE_PROPOSER)
  private val phaseFirstCommitNonProposer =
    histogram(metricsFacade, "phase.commit.first", "Last PREPARE to first COMMIT (ms)", ROLE_NON_PROPOSER)

  // Phase: first → last COMMIT (spread).
  private val phaseCommitSpreadProposer =
    histogram(metricsFacade, "phase.commit.spread", "First to last COMMIT (ms)", ROLE_PROPOSER)
  private val phaseCommitSpreadNonProposer =
    histogram(metricsFacade, "phase.commit.spread", "First to last COMMIT (ms)", ROLE_NON_PROPOSER)

  // Phase: last COMMIT → block committed.
  private val phaseImportProposer =
    histogram(metricsFacade, "phase.import", "Last COMMIT to block committed (ms)", ROLE_PROPOSER)
  private val phaseImportNonProposer =
    histogram(metricsFacade, "phase.import", "Last COMMIT to block committed (ms)", ROLE_NON_PROPOSER)

  // ── recording methods ──────────────────────────────────────────────────────

  /** Called when BLOCK_TIMER_EXPIRY fires on this validator. */
  fun recordTimerFire(blockNumber: Long) {
    timerFireTimes[blockNumber] = System.currentTimeMillis()
    cleanupOldEntries(blockNumber)
  }

  /**
   * Called when a QBFT message arrives from the P2P network.
   * Tracks per-message-type timestamps for phase breakdown.
   */
  fun recordMessageReceived(
    msgCode: Int,
    sequenceNumber: Long,
  ) {
    if (sequenceNumber <= 0) return
    val now = System.currentTimeMillis()
    when (msgCode) {
      MSG_PROPOSAL -> proposalTimes.putIfAbsent(sequenceNumber, now)
      MSG_PREPARE -> {
        firstPrepareTimes.putIfAbsent(sequenceNumber, now)
        lastPrepareTimes[sequenceNumber] = now
      }
      MSG_COMMIT -> {
        firstCommitTimes.putIfAbsent(sequenceNumber, now)
        lastCommitTimes[sequenceNumber] = now
      }
    }
  }

  /**
   * Called when a sealed beacon block is committed to the database.
   * Determines the role (proposer vs non-proposer) and records all phase durations
   * with the appropriate role tag.
   */
  fun recordBlockCommitted(sealedBlock: SealedBeaconBlock) {
    val commitTime = System.currentTimeMillis()
    val blockNumber =
      sealedBlock.beaconBlock.beaconBlockHeader.number
        .toLong()
    if (blockNumber <= 0) return

    val timerFire = timerFireTimes[blockNumber] ?: return
    val totalLatency = commitTime - timerFire
    if (totalLatency !in 0..5000) return

    // Determine role: if a PROPOSAL was received via P2P, this node was a non-proposer.
    val proposalTime = proposalTimes[blockNumber]
    val isProposer = proposalTime == null

    if (isProposer) {
      blockLatencyProposer.record(totalLatency.toDouble())
    } else {
      blockLatencyNonProposer.record(totalLatency.toDouble())
    }

    // Phase: timer → PROPOSAL (non-proposer only)
    if (!isProposer) {
      val duration = proposalTime!! - timerFire
      if (duration >= 0) phaseProposal.record(duration.toDouble())
    }

    // Phase: start → first PREPARE
    // "start" is either PROPOSAL received (non-proposer) or timer fire (proposer)
    val phaseStart = proposalTime ?: timerFire
    val firstPrepare = firstPrepareTimes[blockNumber]
    if (firstPrepare != null) {
      val duration = firstPrepare - phaseStart
      if (duration >= 0) {
        (if (isProposer) phaseFirstPrepareProposer else phaseFirstPrepareNonProposer).record(duration.toDouble())
      }
    }

    // Phase: first → last PREPARE
    val lastPrepare = lastPrepareTimes[blockNumber]
    if (firstPrepare != null && lastPrepare != null) {
      val duration = lastPrepare - firstPrepare
      if (duration >= 0) {
        (if (isProposer) phasePrepareSpreadProposer else phasePrepareSpreadNonProposer).record(duration.toDouble())
      }
    }

    // Phase: last PREPARE → first COMMIT
    val firstCommit = firstCommitTimes[blockNumber]
    if (lastPrepare != null && firstCommit != null) {
      val duration = firstCommit - lastPrepare
      if (duration >= 0) {
        (if (isProposer) phaseFirstCommitProposer else phaseFirstCommitNonProposer).record(duration.toDouble())
      }
    }

    // Phase: first → last COMMIT
    val lastCommit = lastCommitTimes[blockNumber]
    if (firstCommit != null && lastCommit != null) {
      val duration = lastCommit - firstCommit
      if (duration >= 0) {
        (if (isProposer) phaseCommitSpreadProposer else phaseCommitSpreadNonProposer).record(duration.toDouble())
      }
    }

    // Phase: last COMMIT → committed
    if (lastCommit != null) {
      val duration = commitTime - lastCommit
      if (duration >= 0) {
        (if (isProposer) phaseImportProposer else phaseImportNonProposer).record(duration.toDouble())
      }
    }
  }

  /**
   * Remove older entries to prevent memory leaks.
   */
  private fun cleanupOldEntries(currentBlockNumber: Long) {
    val threshold = currentBlockNumber - 1
    if (threshold <= 0) return
    listOf(
      timerFireTimes,
      proposalTimes,
      firstPrepareTimes,
      lastPrepareTimes,
      firstCommitTimes,
      lastCommitTimes,
    ).forEach { map ->
      map.keys.removeAll { it < threshold }
    }
  }
}
