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
import java.util.Optional
import net.consensys.linea.async.get
import net.consensys.linea.metrics.MetricsFacade
import net.consensys.linea.vertx.ObservabilityServer
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.metrics.MetricsService
import org.hyperledger.besu.metrics.prometheus.MetricsConfiguration
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory as BesuMetricsCategory

class MetricsServer(
  val vertx: Vertx,
  val config: Config,
  metricsFacade: MetricsFacade,
  val metricsSystem: MetricsSystem,
) {
  data class Config(
    val portForMetricsFacade: Int,
    val portForMetricsSystem: Int,
    val enabledMetricsSystemCategories: Set<BesuMetricsCategory>,
  )

  private val log = LogManager.getLogger(MetricsServer::class.java)

  private var observabilityServerDeploymentId: String? = null
  private val observabilityServer =
    ObservabilityServer(ObservabilityServer.Config(applicationName = "maru", port = config.portForMetricsFacade))
  private val metricsService: Optional<MetricsService> =
    MetricsService.create(
      MetricsConfiguration
        .builder()
        .enabled(config.enabledMetricsSystemCategories.isNotEmpty())
        .port(config.portForMetricsSystem)
        .metricCategories(config.enabledMetricsSystemCategories)
        .prometheusJob("maru")
        .build(),
      metricsSystem,
    )

  fun start() {
    try {
      observabilityServerDeploymentId = vertx.deployVerticle(observabilityServer).get()
    } catch (th: Throwable) {
      log.error("Error while trying to start the observability server", th)
      throw th
    }
    try {
      metricsService.ifPresent { it.start().get() }
    } catch (th: Throwable) {
      log.error("Error while trying to start the metrics service", th)
      throw th
    }
  }

  fun stop() {
    try {
      observabilityServerDeploymentId.let { vertx.undeploy(it).get() }
    } catch (th: Throwable) {
      log.warn("Error while trying to stop the observability server", th)
    }
    try {
      metricsService.ifPresent { it.stop().get() }
    } catch (th: Throwable) {
      log.warn("Error while trying to stop the metrics service", th)
    }
  }
}
