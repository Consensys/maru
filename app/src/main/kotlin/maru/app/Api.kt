/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import io.vertx.core.Vertx
import maru.core.Protocol
import net.consensys.linea.async.get
import net.consensys.linea.vertx.ObservabilityServer

class Api(
  private val config: Config,
  private val vertx: Vertx,
) : Protocol {
  data class Config(
    val observabilityPort: UInt,
  )

  private var observabilityServerId: String? = null

  override fun start() {
    val observabilityServer =
      ObservabilityServer(ObservabilityServer.Config("maru", config.observabilityPort.toInt()))
    observabilityServerId = vertx.deployVerticle(observabilityServer).get()
  }

  override fun stop() {
    this.observabilityServerId?.let { vertx.undeploy(it).get() }
  }
}
