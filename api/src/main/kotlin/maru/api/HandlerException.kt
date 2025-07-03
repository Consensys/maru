/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.api

import io.javalin.http.Context
import io.javalin.http.Handler

data class ApiExceptionResponse(
  val code: Int,
  val message: String,
)

class HandlerException(
  val code: Int,
  override val message: String,
) : Exception(message),
  Handler {
  override fun handle(ctx: Context) {
    ctx.status(code).json(ApiExceptionResponse(code, message))
  }
}
