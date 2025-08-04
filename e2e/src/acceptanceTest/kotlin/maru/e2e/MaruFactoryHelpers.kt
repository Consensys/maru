/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.e2e

const val VALIDATOR_PRIVATE_KEY_WITH_PREFIX =
  "0x080212201dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"

object MaruFactoryHelpers {
  /**
   * "follower-besu" = { endpoint = "http://localhost:9550" }
   * "follower-erigon" = { endpoint = "http://localhost:11551", jwt-secret-path = "../docker/jwt" }
   * "follower-nethermind" = { endpoint = "http://localhost:10550", jwt-secret-path = "../docker/jwt" }
   * "follower-geth" = { endpoint = "http://localhost:8561", jwt-secret-path = "../docker/jwt" }
   */

  private fun renderTemplate(
    template: String,
    pragueTime: Long,
  ): String = template.replace("%PRAGUE_TIME%", pragueTime.toString())
}
