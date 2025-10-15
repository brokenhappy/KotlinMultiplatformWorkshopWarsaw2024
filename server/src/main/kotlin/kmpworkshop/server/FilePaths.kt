package kmpworkshop.server

import java.nio.file.Path
import kotlin.io.path.Path

val serverEventBackupDirectory: Path? = System.getenv("SERVER_EVENT_BACKUP_DIRECTORY")
    ?.let(::Path)
    ?.also { it.toFile().mkdirs() }

val bugDirectory: Path? = System.getenv("BUG_DIRECTORY")?.let(::Path)