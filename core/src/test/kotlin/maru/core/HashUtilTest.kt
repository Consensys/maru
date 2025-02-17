package maru.core

import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.ext.DataGenerators.randomBeaconBlockBody
import maru.core.ext.DataGenerators.randomBeaconBlockHeader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HashUtilTest {

  @Test
  fun `can hash body ignoring commit seals`() {
    val beaconBlockBody1 = randomBeaconBlockBody()
    val beaconBlockBody2 = beaconBlockBody1.copy(commitSeals = (1..3).map { Seal(Random.nextBytes(96)) })
    assertThat(HashUtil.bodyRoot(beaconBlockBody1)).isEqualTo(HashUtil.bodyRoot(beaconBlockBody2))
  }

  @Test
  fun `can hash body with empty prev commit seals`() {
    val beaconBlockBody = randomBeaconBlockBody().copy(prevCommitSeals = emptyList())
    assertThat(HashUtil.bodyRoot(beaconBlockBody)).isNotEqualTo("")
  }

  @Test
  fun `can hash header for onchain omitting round number`() {
    val beaconBlockHeader1 = randomBeaconBlockHeader(Random.nextULong())
    val beaconBlockHeader2 = beaconBlockHeader1.copy(round = Random.nextULong())
    assertThat(HashUtil.headerOnChainHash(beaconBlockHeader1)).isEqualTo(HashUtil.headerOnChainHash(beaconBlockHeader2))
  }

  @Test
  fun `can hash header for commit seal including round number`() {
    val beaconBlockHeader1 = randomBeaconBlockHeader(Random.nextULong())
    val beaconBlockHeader2 = beaconBlockHeader1.copy(round = Random.nextULong())
    assertThat(HashUtil.headerCommittedSealHash(beaconBlockHeader1)).isNotEqualTo(HashUtil.headerOnChainHash(beaconBlockHeader2))
  }
}
