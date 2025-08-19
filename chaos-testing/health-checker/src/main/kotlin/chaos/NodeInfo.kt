package chaos

data class NodeInfo<T>(val label: String, val value: T) {
  fun <R> map(fn: (T) -> R): NodeInfo<R> = NodeInfo(label, fn(value))
  fun <R> map(newValue: R): NodeInfo<R> = NodeInfo(label, newValue)
}
