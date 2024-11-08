import kmpworkshop.client.checkCodePuzzle


fun main() {
    checkCodePuzzle("PalindromeCheck.kt", solution = ::doPalindromeCheckOn)
}

/**
 * Returns true if [input] is a palindrome. Meaning that it would be the same word if it were reversed.
 * ```kt
 * doPalindromeCheckOn("racecar") == true
 * doPalindromeCheckOn("Racecar") == false
 * doPalindromeCheckOn("foo") == false
 * ```
 */
fun doPalindromeCheckOn(input: String): Boolean {
    return true
}