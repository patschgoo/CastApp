package io.github.patschgoo.castapp.remote

/**
 * The HID side of the Bluetooth remote: one report descriptor with two reports,
 * a keyboard (report 1) and a consumer-control / media-key report (report 2),
 * plus the mapping from [RemoteKey] and typed text to those reports.
 *
 * Plain Kotlin (no Android types) so it is tested on the JVM.
 */
object HidReports {
    const val ID_KEYBOARD = 1
    const val ID_CONSUMER = 2

    /** Bytes of each report as handed to `BluetoothHidDevice.sendReport` (without the report id). */
    const val KEYBOARD_SIZE = 8
    const val CONSUMER_SIZE = 2

    const val MOD_LEFT_SHIFT = 0x02

    /** Composite descriptor: a boot-style keyboard and a 16-bit consumer-control array. */
    val descriptor: ByteArray = bytes(
        // Keyboard
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x06,        // Usage (Keyboard)
        0xA1, 0x01,        // Collection (Application)
        0x85, ID_KEYBOARD, //   Report ID (1)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,        //   Usage Minimum (Left Control)
        0x29, 0xE7,        //   Usage Maximum (Right GUI)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x08,        //   Report Count (8)
        0x81, 0x02,        //   Input (Data, Variable, Absolute): modifier bits
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x01,        //   Input (Constant): reserved byte
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x06,        //   Report Count (6)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x65,        //   Logical Maximum (101)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,        //   Usage Minimum (0)
        0x29, 0x65,        //   Usage Maximum (101, Application)
        0x81, 0x00,        //   Input (Data, Array, Absolute): up to 6 keys
        0xC0,              // End Collection
        // Consumer control (media keys, AC Home / AC Back)
        0x05, 0x0C,        // Usage Page (Consumer)
        0x09, 0x01,        // Usage (Consumer Control)
        0xA1, 0x01,        // Collection (Application)
        0x85, ID_CONSUMER, //   Report ID (2)
        0x15, 0x00,        //   Logical Minimum (0)
        0x26, 0xFF, 0x03,  //   Logical Maximum (0x3FF)
        0x19, 0x00,        //   Usage Minimum (0)
        0x2A, 0xFF, 0x03,  //   Usage Maximum (0x3FF)
        0x75, 0x10,        //   Report Size (16)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x00,        //   Input (Data, Array, Absolute)
        0xC0,              // End Collection
    )

    /** What one remote button sends. */
    sealed interface Key {
        data class Keyboard(val usage: Int, val modifiers: Int = 0) : Key
        data class Consumer(val usage: Int) : Key
    }

    // Keyboard usages (HID Usage Tables, page 0x07)
    const val KB_ENTER = 0x28
    const val KB_BACKSPACE = 0x2A
    const val KB_TAB = 0x2B
    const val KB_SPACE = 0x2C
    const val KB_RIGHT = 0x4F
    const val KB_LEFT = 0x50
    const val KB_DOWN = 0x51
    const val KB_UP = 0x52

    // Consumer usages (page 0x0C), and what Linux/Android turn them into
    const val CC_MENU = 0x40          // KEY_MENU -> KEYCODE_MENU
    const val CC_CHANNEL_UP = 0x9C    // KEY_CHANNELUP -> KEYCODE_CHANNEL_UP
    const val CC_CHANNEL_DOWN = 0x9D  // KEY_CHANNELDOWN -> KEYCODE_CHANNEL_DOWN
    const val CC_FAST_FORWARD = 0xB3  // KEY_FASTFORWARD -> KEYCODE_MEDIA_FAST_FORWARD
    const val CC_REWIND = 0xB4        // KEY_REWIND -> KEYCODE_MEDIA_REWIND
    const val CC_NEXT = 0xB5          // KEY_NEXTSONG -> KEYCODE_MEDIA_NEXT
    const val CC_PREVIOUS = 0xB6      // KEY_PREVIOUSSONG -> KEYCODE_MEDIA_PREVIOUS
    const val CC_STOP = 0xB7          // KEY_STOPCD -> KEYCODE_MEDIA_STOP
    const val CC_PLAY_PAUSE = 0xCD    // KEY_PLAYPAUSE -> KEYCODE_MEDIA_PLAY_PAUSE
    const val CC_MUTE = 0xE2          // KEY_MUTE -> KEYCODE_VOLUME_MUTE
    const val CC_VOLUME_UP = 0xE9     // KEY_VOLUMEUP -> KEYCODE_VOLUME_UP
    const val CC_VOLUME_DOWN = 0xEA   // KEY_VOLUMEDOWN -> KEYCODE_VOLUME_DOWN
    const val CC_AC_HOME = 0x223      // KEY_HOMEPAGE -> KEYCODE_HOME
    const val CC_AC_BACK = 0x224      // KEY_BACK -> KEYCODE_BACK

    /**
     * Buttons the Bluetooth remote supports. Arrows and Enter are what Android TV and Fire OS
     * treat as D-pad and OK for keyboards; Back/Home/media keys are the consumer usages the
     * official Bluetooth remotes send. POWER is left out: Consumer Power only puts the box to
     * sleep and can't wake it reliably over Bluetooth. INFO, GUIDE, INPUT and SETTINGS have
     * no usage that every TV box maps.
     */
    val keyMap: Map<RemoteKey, Key> = buildMap {
        put(RemoteKey.UP, Key.Keyboard(KB_UP))
        put(RemoteKey.DOWN, Key.Keyboard(KB_DOWN))
        put(RemoteKey.LEFT, Key.Keyboard(KB_LEFT))
        put(RemoteKey.RIGHT, Key.Keyboard(KB_RIGHT))
        put(RemoteKey.OK, Key.Keyboard(KB_ENTER))
        put(RemoteKey.BACK, Key.Consumer(CC_AC_BACK))
        put(RemoteKey.HOME, Key.Consumer(CC_AC_HOME))
        put(RemoteKey.MENU, Key.Consumer(CC_MENU))
        put(RemoteKey.VOLUME_UP, Key.Consumer(CC_VOLUME_UP))
        put(RemoteKey.VOLUME_DOWN, Key.Consumer(CC_VOLUME_DOWN))
        put(RemoteKey.MUTE, Key.Consumer(CC_MUTE))
        put(RemoteKey.CHANNEL_UP, Key.Consumer(CC_CHANNEL_UP))
        put(RemoteKey.CHANNEL_DOWN, Key.Consumer(CC_CHANNEL_DOWN))
        put(RemoteKey.PLAY_PAUSE, Key.Consumer(CC_PLAY_PAUSE))
        put(RemoteKey.STOP, Key.Consumer(CC_STOP))
        put(RemoteKey.REWIND, Key.Consumer(CC_REWIND))
        put(RemoteKey.FAST_FORWARD, Key.Consumer(CC_FAST_FORWARD))
        put(RemoteKey.PREVIOUS, Key.Consumer(CC_PREVIOUS))
        put(RemoteKey.NEXT, Key.Consumer(CC_NEXT))
        put(RemoteKey.NUM_0, Key.Keyboard(0x27))
        val digits = listOf(
            RemoteKey.NUM_1, RemoteKey.NUM_2, RemoteKey.NUM_3, RemoteKey.NUM_4, RemoteKey.NUM_5,
            RemoteKey.NUM_6, RemoteKey.NUM_7, RemoteKey.NUM_8, RemoteKey.NUM_9,
        )
        digits.forEachIndexed { i, k -> put(k, Key.Keyboard(0x1E + i)) }
    }

    /** Keyboard report: modifier byte, reserved byte, up to 6 key usages. */
    fun keyboard(modifiers: Int = 0, vararg usages: Int): ByteArray {
        require(usages.size <= 6) { "at most 6 keys" }
        val r = ByteArray(KEYBOARD_SIZE)
        r[0] = modifiers.toByte()
        usages.forEachIndexed { i, u -> r[2 + i] = u.toByte() }
        return r
    }

    /** Consumer report: one 16-bit usage, little endian; 0 = released. */
    fun consumer(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())

    /** (report id, key-down report, key-up report) for a key. */
    fun pressReports(key: Key): List<Pair<Int, ByteArray>> = when (key) {
        is Key.Keyboard -> listOf(
            ID_KEYBOARD to keyboard(key.modifiers, key.usage),
            ID_KEYBOARD to keyboard(),
        )
        is Key.Consumer -> listOf(
            ID_CONSUMER to consumer(key.usage),
            ID_CONSUMER to consumer(0),
        )
    }

    private const val SHIFTED_DIGITS = ")!@#$%^&*("
    private const val PUNCT = "-=[]\\;'`,./"
    private const val PUNCT_SHIFTED = "_+{}|:\"~<>?"
    private val PUNCT_USAGES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)

    /** The key for one character on a US keyboard layout, or null if it can't be typed. */
    fun keyFor(c: Char): Key.Keyboard? = when (c) {
        in 'a'..'z' -> Key.Keyboard(0x04 + (c - 'a'))
        in 'A'..'Z' -> Key.Keyboard(0x04 + (c - 'A'), MOD_LEFT_SHIFT)
        in '1'..'9' -> Key.Keyboard(0x1E + (c - '1'))
        '0' -> Key.Keyboard(0x27)
        ' ' -> Key.Keyboard(KB_SPACE)
        '\n' -> Key.Keyboard(KB_ENTER)
        '\t' -> Key.Keyboard(KB_TAB)
        '\b' -> Key.Keyboard(KB_BACKSPACE)
        else -> {
            val shiftedDigit = SHIFTED_DIGITS.indexOf(c)
            val p = PUNCT.indexOf(c)
            val ps = PUNCT_SHIFTED.indexOf(c)
            when {
                shiftedDigit == 0 -> Key.Keyboard(0x27, MOD_LEFT_SHIFT)
                shiftedDigit > 0 -> Key.Keyboard(0x1E + shiftedDigit - 1, MOD_LEFT_SHIFT)
                p >= 0 -> Key.Keyboard(PUNCT_USAGES[p])
                ps >= 0 -> Key.Keyboard(PUNCT_USAGES[ps], MOD_LEFT_SHIFT)
                else -> null
            }
        }
    }

    /** Key strokes for [text]; characters a US keyboard can't type (e.g. accents, emoji) are skipped. */
    fun keysFor(text: String): List<Key.Keyboard> = text.mapNotNull { keyFor(it) }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
