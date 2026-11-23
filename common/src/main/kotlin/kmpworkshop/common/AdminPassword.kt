package kmpworkshop.common

fun requireAdminPassword(password: String?): String {
    check(!password.isNullOrBlank()) { "ADMIN_PASSWORD must be set to a non-blank value." }
    return password
}
