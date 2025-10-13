package workshop.adminaccess

inline fun <T : R, R> T.applyIf(predicate: (T) -> Boolean, mapper: (T) -> R): R = if (predicate(this)) mapper(this) else this