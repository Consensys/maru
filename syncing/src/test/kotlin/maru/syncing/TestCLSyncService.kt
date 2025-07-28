package maru.syncing

class TestCLSyncService : CLSyncService {
  var lastSyncTarget: ULong? = null
  private val syncCompleteHandlers = mutableListOf<(ULong) -> Unit>()

  override fun setSyncTarget(syncTarget: ULong) {
    lastSyncTarget = syncTarget
  }

  override fun onSyncComplete(handler: (ULong) -> Unit) {
    syncCompleteHandlers.add(handler)
  }

  fun triggerSyncComplete(syncTarget: ULong) {
    syncCompleteHandlers.forEach { it(syncTarget) }
  }
}
