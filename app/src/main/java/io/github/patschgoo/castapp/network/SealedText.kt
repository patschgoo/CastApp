package io.github.patschgoo.castapp.network

/**
 * Text kept on the phone encrypted with [Secrets]: "castapp-enc1:" plus the cipher text. Text saved by
 * older versions without the prefix is still read as it is, so callers can move it to the sealed form.
 */
object SealedText {
    const val PREFIX = "castapp-enc1:"

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** [plain] encrypted; [plain] itself only when this phone's Keystore can't encrypt (broken Keystores exist). */
    fun seal(plain: String, encrypt: (String) -> String = Secrets::encrypt): String = try {
        val cipher = encrypt(plain)
        if (cipher.isEmpty() && plain.isNotEmpty()) plain else PREFIX + cipher
    } catch (_: Exception) {
        plain
    }

    /** The plain text of [stored]; null when it can't be decrypted (e.g. restored onto another phone). */
    fun open(stored: String, decrypt: (String) -> String = Secrets::decrypt): String? {
        if (!isSealed(stored)) return stored
        val cipher = stored.removePrefix(PREFIX)
        if (cipher.isEmpty()) return ""
        return decrypt(cipher).ifEmpty { null }
    }
}
