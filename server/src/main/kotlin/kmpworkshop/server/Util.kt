package kmpworkshop.server

internal fun <K, V> Map<K, V>.put(key: K, value: V): Map<K, V> = this + (key to value)