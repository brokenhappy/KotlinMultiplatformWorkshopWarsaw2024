package kmpworkshop.common

fun validateConfiguredAdminPassword(password: String?): String {
    check(!password.isNullOrBlank()) { "ADMIN_PASSWORD must be set to a non-blank value." }
    return password
}
