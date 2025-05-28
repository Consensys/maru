package maru.p2p

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.infrastructure.async.SafeFuture

class SubscriptionManager<E> {
  private val log = LogManager.getLogger(this.javaClass)
  private val nextSubscriptionId = AtomicInteger()
  private val subscriptions: MutableMap<Int, (E) -> SafeFuture<ValidationResult>> = mutableMapOf()

  fun hasSubscriptions(): Boolean = subscriptions.isNotEmpty()

  @Synchronized
  fun subscribeToBlocks(subscriber: (E) -> SafeFuture<ValidationResult>): Int {
    val subscriptionId = nextSubscriptionId.get()
    subscriptions[subscriptionId] = subscriber
    nextSubscriptionId.incrementAndGet()
    return subscriptionId
  }

  @Synchronized
  fun unsubscribe(subscriptionId: Int) {
    subscriptions.remove(subscriptionId)
  }

  fun handleEvent(event: E): SafeFuture<ValidationResult> {
    val handlerFutures =
      subscriptions.map { (subscriptionId, handler) ->
        try {
          handler(event)
        } catch (th: Throwable) {
          log.debug(
            Supplier<String> { "Error from subscription=$subscriptionId while handling event=$event!" },
            th,
          )
          SafeFuture.failedFuture(th)
        }
      }
    return if (subscriptions.isNotEmpty()) {
      SafeFuture.collectAll(handlerFutures.stream()).thenApply {
        it.reduce { acc: ValidationResult, next: ValidationResult ->
          when {
            acc is ValidationResult.Companion.Failed -> acc
            next is ValidationResult.Companion.Failed -> next
            acc is ValidationResult.Companion.KindaFine -> acc
            next is ValidationResult.Companion.KindaFine -> next
            else -> acc
          }
        }
      }
    } else {
      SafeFuture.completedFuture(
        ValidationResult.Companion.KindaFine(
          "No subscription to imply message validity",
        ),
      )
    }
  }
}
