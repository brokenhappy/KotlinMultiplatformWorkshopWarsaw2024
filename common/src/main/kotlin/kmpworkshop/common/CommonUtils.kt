package kmpworkshop.common

inline fun <T> T.sideEffect(function: (T) -> Unit) {
    function(this)
}