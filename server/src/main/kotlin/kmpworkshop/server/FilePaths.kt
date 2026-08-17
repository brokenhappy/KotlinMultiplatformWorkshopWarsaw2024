package kmpworkshop.server

import kotlin.io.path.Path

val bugDirectory = System.getenv("BUG_DIRECTORY")?.let(::Path)
