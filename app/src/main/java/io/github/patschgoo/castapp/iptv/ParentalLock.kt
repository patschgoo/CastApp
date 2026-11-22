package io.github.patschgoo.castapp.iptv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** How a channel or group is protected: shown but asks for the PIN before it plays, or not shown at all. */
enum class Protection {
    NONE,
    LOCKED,
    HIDDEN,
    ;

    /** None, then locked, then hidden, then none again (a tap on a row cycles through them). */
    fun next(): Protection = entries[(ordinal + 1) % entries.size]
}

/** What the lists need to know: the rules, and until when the PIN was entered. */
data class ParentalSnapshot(
    val hasPin: Boolean = false,
    val rules: Map<String, Protection> = emptyMap(),
    /** Time (ms since 1970) until which everything is open; 0 when locked. */
    val unlockedUntil: Long = 0L,
) {
    fun unlocked(now: Long): Boolean = unlockedUntil > now

    private fun ruleOf(key: String) = rules[key] ?: Protection.NONE

    fun groupProtection(group: String?): Protection = if (group == null) Protection.NONE else ruleOf(groupKey(group))

    fun channelProtection(channel: Channel): Protection = ruleOf(channelKey(channel.url))

    /** The stricter of the channel's own rule and its group's. */
    fun protectionOf(channel: Channel): Protection {
        val own = channelProtection(channel)
        val group = groupProtection(channel.group)
        return if (own.ordinal >= group.ordinal) own else group
    }

    /** Hidden channels are not shown while the PIN has not been entered. */
    fun isHidden(channel: Channel, now: Long): Boolean = hasPin && !unlocked(now) && protectionOf(channel) == Protection.HIDDEN

    /** Whether playing (or recording) [channel] asks for the PIN now. */
    fun needsPin(channel: Channel, now: Long): Boolean = hasPin && !unlocked(now) && protectionOf(channel) != Protection.NONE

    companion object {
        fun groupKey(group: String) = "g:$group"
        fun channelKey(url: String) = "c:$url"
    }
}

/** Hashing of a PIN: salted and repeated, so a copy of the settings does not give the PIN away quickly. */
object PinHash {
    private const val ROUNDS = 20_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (salt + ":" + pin).toByteArray()
        repeat(ROUNDS) { d = md.digest(d + salt.toByteArray()) }
        return d.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, salt: String, hash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), hash.toByteArray())
}

/**
 * The parental lock: a PIN, and rules for groups and channels. After the PIN is entered
 * everything is open for [OPEN_FOR_MS]; settings can only be changed then.
 */
class ParentalRules(private val store: TextStore, private val clock: () -> Long = System::currentTimeMillis) {
    private var salt: String = ""
    private var hash: String = ""
    private var rules: Map<String, Protection> = emptyMap()
    private var unlockedUntil = 0L
    private var failures = 0
    private var lockedOutUntil = 0L

    private val _state = MutableStateFlow(ParentalSnapshot())
    val state: StateFlow<ParentalSnapshot> = _state.asStateFlow()

    init {
        load()
        publish()
    }

    val hasPin: Boolean get() = hash.isNotEmpty()

    /** True while changes are allowed: no PIN set yet, or the PIN was entered a moment ago. */
    private fun mayChange() = !hasPin || unlockedUntil > clock()

    /** Seconds to wait after too many wrong PINs, or 0. */
    fun waitSeconds(): Int = ((lockedOutUntil - clock() + 999) / 1000).toInt().coerceAtLeast(0)

    /** Sets the first PIN; to change a PIN use [changePin]. False when there is one already or the PIN is too short. */
    fun setPin(pin: String): Boolean {
        if (hasPin || !validPin(pin)) return false
        applyPin(pin)
        return true
    }

    /** Changes the PIN when [old] is right. */
    fun changePin(old: String, pin: String): Boolean {
        if (!validPin(pin) || !check(old)) return false
        applyPin(pin)
        return true
    }

    /** Removes the PIN and with it all rules. */
    fun removePin(pin: String): Boolean {
        if (!check(pin)) return false
        salt = ""
        hash = ""
        rules = emptyMap()
        unlockedUntil = 0
        save()
        publish()
        return true
    }

    /** Opens everything for [openForMs] when [pin] is right. After 5 wrong tries in a row there is a pause of 30 seconds. */
    fun unlock(pin: String, openForMs: Long = OPEN_FOR_MS): Boolean {
        if (!check(pin)) return false
        unlockedUntil = clock() + openForMs
        publish()
        return true
    }

    fun lock() {
        unlockedUntil = 0
        publish()
    }

    fun setGroup(group: String, protection: Protection): Boolean = setRule(ParentalSnapshot.groupKey(group), protection)

    fun setChannel(url: String, protection: Protection): Boolean = setRule(ParentalSnapshot.channelKey(url), protection)

    private fun setRule(key: String, protection: Protection): Boolean {
        // Rules need a PIN to mean anything, and only the person who knows it may change them.
        if (!hasPin || !mayChange()) return false
        rules = if (protection == Protection.NONE) rules - key else rules + (key to protection)
        save()
        publish()
        return true
    }

    private fun check(pin: String): Boolean {
        val now = clock()
        if (!hasPin) return false
        if (now < lockedOutUntil) return false
        val ok = PinHash.matches(pin, salt, hash)
        if (ok) {
            failures = 0
        } else if (++failures >= MAX_FAILURES) {
            failures = 0
            lockedOutUntil = now + LOCKOUT_MS
        }
        return ok
    }

    private fun applyPin(pin: String) {
        salt = PinHash.newSalt()
        hash = PinHash.hash(pin, salt)
        unlockedUntil = clock() + OPEN_FOR_MS
        save()
        publish()
    }

    private fun publish() {
        _state.value = ParentalSnapshot(hasPin, rules, unlockedUntil)
    }

    private fun load() {
        try {
            val o = JSONObject(store.read() ?: return)
            salt = o.optString("salt")
            hash = o.optString("hash")
            val r = o.optJSONObject("rules")
            val map = HashMap<String, Protection>()
            r?.keys()?.forEach { k -> runCatching { Protection.valueOf(r.getString(k)) }.getOrNull()?.let { map[k] = it } }
            rules = map
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val r = JSONObject()
        rules.forEach { (k, v) -> r.put(k, v.name) }
        store.write(JSONObject().put("salt", salt).put("hash", hash).put("rules", r).toString())
    }

    companion object {
        const val OPEN_FOR_MS = 15 * 60_000L
        const val MIN_PIN = 4
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L

        fun validPin(pin: String) = pin.length >= MIN_PIN && pin.all(Char::isDigit)
    }
}

object ParentalLock {
    @Volatile private var instance: ParentalRules? = null

    fun get(context: Context): ParentalRules =
        instance ?: synchronized(this) {
            instance ?: ParentalRules(PrefsTextStore(context.applicationContext, "iptv_extras", "parental")).also { instance = it }
        }
}
