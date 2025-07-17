import maru.core.Protocol
import maru.syncing.SyncStatus
import maru.syncing.SyncStatusProvider

class UnsafeSyncService: Protocol, SyncStatusProvider {
  override fun start() {
    TODO("Not yet implemented")
  }

  override fun stop() {
    TODO("Not yet implemented")
  }

  override fun get(): SyncStatus {
    TODO("Not yet implemented")
  }
}
