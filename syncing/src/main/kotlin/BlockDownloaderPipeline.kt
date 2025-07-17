/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
import maru.core.BeaconBlock
import maru.core.Protocol

interface BlockDownloaderPipeline : Protocol {
  fun setPivotBeaconBlock(beaconBlock: BeaconBlock)

  fun setOnCompleteHandler(handler: () -> Unit)

  /**
   * Use this information to update the SyncStaus
   */
  fun getLastImportedBeaconBlockNumber(): ULong
}
