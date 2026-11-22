package io.github.patschgoo.castapp.remote

import io.github.patschgoo.castapp.remote.RemoteKey.*

/** One TV brand's infrared codes. A key with two codes alternates between them (separate Pause/Play buttons). */
class IrBrand(
    val id: String,
    val brand: String,
    /** True for the variant meant for older models of the brand. */
    val older: Boolean = false,
    val buttons: Map<RemoteKey, List<IrCode>>,
)

/**
 * Infrared code tables, from the well-known values in LIRC, IRremote and the Flipper IRDB.
 * Only keys we're confident about are listed; see the notes per brand for what's left out.
 */
object IrCodes {
    private fun numbers(first: (Int) -> IrCode): Map<RemoteKey, List<IrCode>> =
        listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)
            .mapIndexed { i, k -> k to listOf(first(i)) }.toMap()

    /** Samsung TVs: Samsung32, address 0x07 (codes 0xE0E0xxxx). */
    val samsung: IrBrand = run {
        fun s(cmd: Int) = listOf<IrCode>(IrCode.Samsung(0x07, cmd))
        val digits = intArrayOf(0x11, 0x04, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E)
        IrBrand(
            "samsung", "Samsung",
            buttons = mapOf(
                POWER to s(0x02), INPUT to s(0x01),
                VOLUME_UP to s(0x07), VOLUME_DOWN to s(0x0B), MUTE to s(0x0F),
                CHANNEL_UP to s(0x12), CHANNEL_DOWN to s(0x10),
                UP to s(0x60), DOWN to s(0x61), LEFT to s(0x65), RIGHT to s(0x62), OK to s(0x68),
                BACK to s(0x58), HOME to s(0x79), MENU to s(0x1A), INFO to s(0x1F), GUIDE to s(0x4F),
                // Separate Pause (0x4A) and Play (0x47) buttons: alternate, pause first.
                PLAY_PAUSE to s(0x4A) + s(0x47),
                STOP to s(0x46), REWIND to s(0x45), FAST_FORWARD to s(0x48),
            ) + numbers { IrCode.Samsung(0x07, digits[it]) },
        )
    }

    /** LG TVs: NEC, address 0x04 (codes 0x20DFxxxx). */
    val lg: IrBrand = run {
        fun n(cmd: Int) = listOf<IrCode>(IrCode.Nec(0x04, cmd))
        IrBrand(
            "lg", "LG",
            buttons = mapOf(
                POWER to n(0x08), INPUT to n(0x0B),
                VOLUME_UP to n(0x02), VOLUME_DOWN to n(0x03), MUTE to n(0x09),
                CHANNEL_UP to n(0x00), CHANNEL_DOWN to n(0x01),
                UP to n(0x40), DOWN to n(0x41), LEFT to n(0x07), RIGHT to n(0x06), OK to n(0x44),
                BACK to n(0x28), HOME to n(0x7C),
                // "Menu" on older remotes, "Settings" (gear) on newer ones: same code.
                MENU to n(0x43), SETTINGS to n(0x43),
                INFO to n(0xAA), GUIDE to n(0xAB),
                PLAY_PAUSE to n(0xBA) + n(0xB0), // Pause / Play
                STOP to n(0xB1), REWIND to n(0x8F), FAST_FORWARD to n(0x8E),
            ) + numbers { IrCode.Nec(0x04, 0x10 + it) },
        )
    }

    /**
     * Sony TVs (Bravia): SIRC 12-bit device 1 for the basic keys, SIRC 15-bit device 0x97
     * for Return, Options and the media keys (same as Bravia's IRCC list). Guide is SIRC
     * 15-bit device 0xA4. Older non-Bravia Sony TVs may ignore the 15-bit keys.
     */
    val sony: IrBrand = run {
        fun s12(cmd: Int) = listOf<IrCode>(IrCode.Sirc(12, 0x01, cmd))
        fun s15(cmd: Int) = listOf<IrCode>(IrCode.Sirc(15, 0x97, cmd))
        IrBrand(
            "sony", "Sony",
            buttons = mapOf(
                POWER to s12(0x15), INPUT to s12(0x25),
                VOLUME_UP to s12(0x12), VOLUME_DOWN to s12(0x13), MUTE to s12(0x14),
                CHANNEL_UP to s12(0x10), CHANNEL_DOWN to s12(0x11),
                UP to s12(0x74), DOWN to s12(0x75), LEFT to s12(0x34), RIGHT to s12(0x33), OK to s12(0x65),
                HOME to s12(0x60), INFO to s12(0x3A),
                BACK to s15(0x23), MENU to s15(0x36), // Return, Options
                GUIDE to listOf(IrCode.Sirc(15, 0xA4, 0x5B)),
                PLAY_PAUSE to s15(0x19) + s15(0x1A), // Pause / Play
                STOP to s15(0x18), REWIND to s15(0x1B), FAST_FORWARD to s15(0x1C),
                PREVIOUS to s15(0x3C), NEXT to s15(0x3D),
            ) + numbers { IrCode.Sirc(12, 0x01, if (it == 0) 0x09 else it - 1) },
        )
    }

    /**
     * Philips TVs from about 2008 on: RC6 mode 0, address 0. Left out (not sure enough):
     * Options/Menu, Guide, Rewind and Fast-forward, which differ between models.
     */
    val philips: IrBrand = run {
        fun r(cmd: Int) = listOf<IrCode>(IrCode.Rc6(0x00, cmd))
        IrBrand(
            "philips", "Philips",
            buttons = mapOf(
                POWER to r(0x0C), INPUT to r(0x38),
                VOLUME_UP to r(0x10), VOLUME_DOWN to r(0x11), MUTE to r(0x0D),
                CHANNEL_UP to r(0x4C), CHANNEL_DOWN to r(0x4D),
                UP to r(0x58), DOWN to r(0x59), LEFT to r(0x5A), RIGHT to r(0x5B), OK to r(0x5C),
                BACK to r(0x0A), HOME to r(0x54), INFO to r(0x0F),
                PLAY_PAUSE to r(0x30) + r(0x2C), // Pause / Play
                STOP to r(0x31),
            ) + numbers { IrCode.Rc6(0x00, it) },
        )
    }

    /**
     * Older Philips TVs (and many European sets of that era): RC5 system 0. Only the
     * standard RC5 TV commands; menu navigation differs per model and is left out.
     */
    val philipsRc5: IrBrand = run {
        fun r(cmd: Int) = listOf<IrCode>(IrCode.Rc5(0x00, cmd))
        IrBrand(
            "philips-rc5", "Philips", older = true,
            buttons = mapOf(
                POWER to r(12), MUTE to r(13), VOLUME_UP to r(16), VOLUME_DOWN to r(17),
                CHANNEL_UP to r(32), CHANNEL_DOWN to r(33), INFO to r(15),
            ) + numbers { IrCode.Rc5(0x00, it) },
        )
    }

    /**
     * Panasonic TVs (Viera): Kaseikyo vendor 0x4004, data 0x0100CCXX where XX = 0x01 xor CC.
     * Left out (not sure enough): Return/Back, Exit, Home/Apps, Info, Guide and media keys.
     */
    val panasonic: IrBrand = run {
        fun code(cmd: Int): IrCode = IrCode.Panasonic(0x01000000L or (cmd.toLong() shl 8) or (0x01 xor cmd).toLong())
        fun p(cmd: Int) = listOf(code(cmd))
        val digits = intArrayOf(0x98, 0x08, 0x88, 0x48, 0xC8, 0x28, 0xA8, 0x68, 0xE8, 0x18)
        IrBrand(
            "panasonic", "Panasonic",
            buttons = mapOf(
                POWER to p(0xBC), INPUT to p(0xA0),
                VOLUME_UP to p(0x04), VOLUME_DOWN to p(0x84), MUTE to p(0x4C),
                CHANNEL_UP to p(0x2C), CHANNEL_DOWN to p(0xAC),
                UP to p(0x52), DOWN to p(0xD2), LEFT to p(0x72), RIGHT to p(0xF2), OK to p(0x92),
                MENU to p(0x4A),
            ) + numbers { code(digits[it]) },
        )
    }

    /**
     * Toshiba TVs: NEC, address 0x40 (codes 0x02FDxxxx). Only power, volume, channels and
     * numbers; the menu keys differ between model years and are left out.
     */
    val toshiba: IrBrand = run {
        fun n(cmd: Int) = listOf<IrCode>(IrCode.Nec(0x40, cmd))
        IrBrand(
            "toshiba", "Toshiba",
            buttons = mapOf(
                POWER to n(0x12),
                VOLUME_UP to n(0x1A), VOLUME_DOWN to n(0x1E), MUTE to n(0x10),
                CHANNEL_UP to n(0x1B), CHANNEL_DOWN to n(0x1F),
            ) + numbers { IrCode.Nec(0x40, it) },
        )
    }

    // Not included: TCL, Hisense, Sharp, Vizio and NVIDIA Shield. Their codes vary by
    // model/platform (or, for the 2019 Shield, aren't documented well enough to be sure).
    val all: List<IrBrand> = listOf(samsung, lg, sony, philips, philipsRc5, panasonic, toshiba)
}
