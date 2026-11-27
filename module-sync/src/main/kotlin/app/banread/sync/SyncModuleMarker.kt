package app.banread.sync

import app.banread.model.SyncCoordinator

/**
 * Placeholder entry point for `:module-sync` (Slice 6 implementation).
 * Desktop HTTP gateway DTOs and WebDAV/S3 shard clients stay `internal`.
 */
object SyncModuleMarker {
    val contractClass = SyncCoordinator::class
}
