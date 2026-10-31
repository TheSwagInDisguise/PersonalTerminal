package dev.personalterminal.ui.theme

import androidx.compose.ui.graphics.Color

/** A terminal colour scheme – 16-ish ANSI-inspired slots. */
data class TerminalPalette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Color,
    val bgAlt: Color,        // panels / cards
    val bgHighlight: Color,  // selection / hover
    val fg: Color,
    val fgDim: Color,        // comments / muted
    val border: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val blue: Color,
    val purple: Color,
    val cyan: Color,
    val orange: Color,
    val pink: Color,
) {
    val accent: Color get() = green
    val prompt: Color get() = green
    val cursor: Color get() = fg

    fun named(name: String): Color = when (name.lowercase()) {
        "red" -> red; "green" -> green; "yellow" -> yellow; "blue" -> blue
        "purple" -> purple; "cyan" -> cyan; "orange" -> orange; "pink" -> pink
        else -> fg
    }
}

/**
 * Theme families; each has a dark and a light variant. [font] is the typeface the theme was designed
 * with – picking a theme applies it (the font can still be overridden afterwards in settings).
 */
enum class ThemeFamily(val id: String, val label: String, val font: String = "jetbrains", val blurb: String = "") {
    DRACULA("dracula", "Dracula", "jetbrains", "the classic purple-on-charcoal"),
    NORD("nord", "Nord", "fira", "arctic blues, low contrast"),
    SOLARIZED("solarized", "Solarized", "source", "ethan schoonover's precision palette"),
    GRUVBOX("gruvbox", "Gruvbox", "plex", "retro groove, warm paper tones"),
    MONOKAI("monokai", "Monokai", "roboto", "sublime text's neon on grey"),
    CATPPUCCIN("catppuccin", "Catppuccin", "victor", "soothing pastels, mocha & latte"),
    TOKYO_NIGHT("tokyonight", "Tokyo Night", "jetbrains", "neon city at 2 am"),
    ONE_DARK("onedark", "One Dark", "source", "atom's editor default"),
    ROSE_PINE("rosepine", "Rosé Pine", "victor", "soho vibes, muted rose & gold"),
    EVERFOREST("everforest", "Everforest", "plex", "green-tinted comfort, easy on the eyes"),
    AMBER("amber", "Amber CRT", "vt323", "1978 phosphor terminal, crt recommended"),
    MATRIX("matrix", "Matrix", "vt323", "green phosphor / paper terminal"),
    HACKER("hacker", "Hacker", "space", "pure black, white text, one red accent"),
    SYNTHWAVE("synthwave", "Synthwave '84", "space", "outrun magenta & cyan"),
    SIGNAL("signal", "Signal", "jetbrains", "classified broadsheet, one lime signal");

    companion object {
        fun fromId(id: String): ThemeFamily = entries.firstOrNull { it.id == id } ?: DRACULA
    }
}

object Palettes {
    val colorNames = listOf("green", "cyan", "blue", "purple", "pink", "red", "orange", "yellow")

    /** Theme id used in settings for an imported colour scheme. */
    const val CUSTOM_ID = "custom"

    /**
     * Resolves the palette for [themeId]: a built-in family, or [CUSTOM_ID] backed by the user's
     * imported scheme (falls back to Dracula when the JSON is missing or invalid).
     */
    fun resolve(themeId: String, dark: Boolean, customJson: String): TerminalPalette {
        if (themeId == CUSTOM_ID) {
            CustomPalette.parse(customJson)?.let { c -> return if (dark) c.dark else c.light }
        }
        return get(ThemeFamily.fromId(themeId), dark)
    }

    fun get(family: ThemeFamily, dark: Boolean): TerminalPalette = when (family) {
        ThemeFamily.DRACULA -> if (dark) draculaDark else draculaLight
        ThemeFamily.SIGNAL -> if (dark) signalDark else signalLight
        ThemeFamily.NORD -> if (dark) nordDark else nordLight
        ThemeFamily.SOLARIZED -> if (dark) solarizedDark else solarizedLight
        ThemeFamily.GRUVBOX -> if (dark) gruvboxDark else gruvboxLight
        ThemeFamily.MONOKAI -> if (dark) monokaiDark else monokaiLight
        ThemeFamily.CATPPUCCIN -> if (dark) catppuccinMocha else catppuccinLatte
        ThemeFamily.TOKYO_NIGHT -> if (dark) tokyoNight else tokyoDay
        ThemeFamily.ONE_DARK -> if (dark) oneDark else oneLight
        ThemeFamily.ROSE_PINE -> if (dark) rosePine else rosePineDawn
        ThemeFamily.EVERFOREST -> if (dark) everforestDark else everforestLight
        ThemeFamily.AMBER -> if (dark) amberDark else amberLight
        ThemeFamily.MATRIX -> if (dark) matrixDark else matrixLight
        ThemeFamily.HACKER -> if (dark) hackerDark else hackerLight
        ThemeFamily.SYNTHWAVE -> if (dark) synthwaveDark else synthwaveLight
    }

    // ---- Dracula ----------------------------------------------------------------
    val draculaDark = TerminalPalette(
        id = "dracula", name = "Dracula", dark = true,
        bg = Color(0xFF282A36), bgAlt = Color(0xFF21222C), bgHighlight = Color(0xFF44475A),
        fg = Color(0xFFF8F8F2), fgDim = Color(0xFF6272A4), border = Color(0xFF44475A),
        red = Color(0xFFFF5555), green = Color(0xFF50FA7B), yellow = Color(0xFFF1FA8C), blue = Color(0xFF8BE9FD),
        purple = Color(0xFFBD93F9), cyan = Color(0xFF8BE9FD), orange = Color(0xFFFFB86C), pink = Color(0xFFFF79C6),
    )
    val draculaLight = TerminalPalette( // "Alucard" – official Dracula light
        id = "dracula", name = "Dracula (Alucard)", dark = false,
        bg = Color(0xFFFFFBEB), bgAlt = Color(0xFFF5F0DC), bgHighlight = Color(0xFFE8E2CC),
        fg = Color(0xFF1F1F1F), fgDim = Color(0xFF6C664B), border = Color(0xFFD9D3BC),
        red = Color(0xFFCB3A2A), green = Color(0xFF14710A), yellow = Color(0xFF846E15), blue = Color(0xFF036A96),
        purple = Color(0xFF644AC9), cyan = Color(0xFF036A96), orange = Color(0xFFA34D14), pink = Color(0xFFA3144D),
    )

    // ---- Nord -------------------------------------------------------------------
    val nordDark = TerminalPalette(
        id = "nord", name = "Nord", dark = true,
        bg = Color(0xFF2E3440), bgAlt = Color(0xFF3B4252), bgHighlight = Color(0xFF434C5E),
        fg = Color(0xFFECEFF4), fgDim = Color(0xFF7B88A1), border = Color(0xFF4C566A),
        red = Color(0xFFBF616A), green = Color(0xFFA3BE8C), yellow = Color(0xFFEBCB8B), blue = Color(0xFF81A1C1),
        purple = Color(0xFFB48EAD), cyan = Color(0xFF88C0D0), orange = Color(0xFFD08770), pink = Color(0xFFB48EAD),
    )
    val nordLight = TerminalPalette(
        id = "nord", name = "Nord Light", dark = false,
        bg = Color(0xFFECEFF4), bgAlt = Color(0xFFE5E9F0), bgHighlight = Color(0xFFD8DEE9),
        fg = Color(0xFF2E3440), fgDim = Color(0xFF6B7489), border = Color(0xFFD8DEE9),
        red = Color(0xFFBF616A), green = Color(0xFF6F8F5C), yellow = Color(0xFFB08D3B), blue = Color(0xFF5E81AC),
        purple = Color(0xFF8F6A8A), cyan = Color(0xFF3F8FA8), orange = Color(0xFFC0693F), pink = Color(0xFF8F6A8A),
    )

    // ---- Solarized --------------------------------------------------------------
    val solarizedDark = TerminalPalette(
        id = "solarized", name = "Solarized Dark", dark = true,
        bg = Color(0xFF002B36), bgAlt = Color(0xFF073642), bgHighlight = Color(0xFF0A4452),
        fg = Color(0xFF93A1A1), fgDim = Color(0xFF586E75), border = Color(0xFF0E4B5A),
        red = Color(0xFFDC322F), green = Color(0xFF859900), yellow = Color(0xFFB58900), blue = Color(0xFF268BD2),
        purple = Color(0xFF6C71C4), cyan = Color(0xFF2AA198), orange = Color(0xFFCB4B16), pink = Color(0xFFD33682),
    )
    val solarizedLight = TerminalPalette(
        id = "solarized", name = "Solarized Light", dark = false,
        bg = Color(0xFFFDF6E3), bgAlt = Color(0xFFEEE8D5), bgHighlight = Color(0xFFE4DDC6),
        fg = Color(0xFF586E75), fgDim = Color(0xFF93A1A1), border = Color(0xFFD9D2BD),
        red = Color(0xFFDC322F), green = Color(0xFF859900), yellow = Color(0xFFB58900), blue = Color(0xFF268BD2),
        purple = Color(0xFF6C71C4), cyan = Color(0xFF2AA198), orange = Color(0xFFCB4B16), pink = Color(0xFFD33682),
    )

    // ---- Gruvbox ----------------------------------------------------------------
    val gruvboxDark = TerminalPalette(
        id = "gruvbox", name = "Gruvbox Dark", dark = true,
        bg = Color(0xFF282828), bgAlt = Color(0xFF3C3836), bgHighlight = Color(0xFF504945),
        fg = Color(0xFFEBDBB2), fgDim = Color(0xFF928374), border = Color(0xFF504945),
        red = Color(0xFFFB4934), green = Color(0xFFB8BB26), yellow = Color(0xFFFABD2F), blue = Color(0xFF83A598),
        purple = Color(0xFFD3869B), cyan = Color(0xFF8EC07C), orange = Color(0xFFFE8019), pink = Color(0xFFD3869B),
    )
    val gruvboxLight = TerminalPalette(
        id = "gruvbox", name = "Gruvbox Light", dark = false,
        bg = Color(0xFFFBF1C7), bgAlt = Color(0xFFEBDBB2), bgHighlight = Color(0xFFD5C4A1),
        fg = Color(0xFF3C3836), fgDim = Color(0xFF928374), border = Color(0xFFD5C4A1),
        red = Color(0xFF9D0006), green = Color(0xFF79740E), yellow = Color(0xFFB57614), blue = Color(0xFF076678),
        purple = Color(0xFF8F3F71), cyan = Color(0xFF427B58), orange = Color(0xFFAF3A03), pink = Color(0xFF8F3F71),
    )

    // ---- Monokai ----------------------------------------------------------------
    val monokaiDark = TerminalPalette(
        id = "monokai", name = "Monokai", dark = true,
        bg = Color(0xFF272822), bgAlt = Color(0xFF1E1F1C), bgHighlight = Color(0xFF3E3D32),
        fg = Color(0xFFF8F8F2), fgDim = Color(0xFF75715E), border = Color(0xFF49483E),
        red = Color(0xFFF92672), green = Color(0xFFA6E22E), yellow = Color(0xFFE6DB74), blue = Color(0xFF66D9EF),
        purple = Color(0xFFAE81FF), cyan = Color(0xFF66D9EF), orange = Color(0xFFFD971F), pink = Color(0xFFF92672),
    )
    val monokaiLight = TerminalPalette(
        id = "monokai", name = "Monokai Light", dark = false,
        bg = Color(0xFFFAFAFA), bgAlt = Color(0xFFF0F0F0), bgHighlight = Color(0xFFE0E0E0),
        fg = Color(0xFF49483E), fgDim = Color(0xFF9A9A8E), border = Color(0xFFDADADA),
        red = Color(0xFFD01F5C), green = Color(0xFF5C9A00), yellow = Color(0xFF9A8A00), blue = Color(0xFF0089A8),
        purple = Color(0xFF7C4DFF), cyan = Color(0xFF0089A8), orange = Color(0xFFD97A00), pink = Color(0xFFD01F5C),
    )

    // ---- Catppuccin -------------------------------------------------------------
    val catppuccinMocha = TerminalPalette(
        id = "catppuccin", name = "Catppuccin Mocha", dark = true,
        bg = Color(0xFF1E1E2E), bgAlt = Color(0xFF181825), bgHighlight = Color(0xFF313244),
        fg = Color(0xFFCDD6F4), fgDim = Color(0xFF6C7086), border = Color(0xFF45475A),
        red = Color(0xFFF38BA8), green = Color(0xFFA6E3A1), yellow = Color(0xFFF9E2AF), blue = Color(0xFF89B4FA),
        purple = Color(0xFFCBA6F7), cyan = Color(0xFF94E2D5), orange = Color(0xFFFAB387), pink = Color(0xFFF5C2E7),
    )
    val catppuccinLatte = TerminalPalette(
        id = "catppuccin", name = "Catppuccin Latte", dark = false,
        bg = Color(0xFFEFF1F5), bgAlt = Color(0xFFE6E9EF), bgHighlight = Color(0xFFCCD0DA),
        fg = Color(0xFF4C4F69), fgDim = Color(0xFF9CA0B0), border = Color(0xFFBCC0CC),
        red = Color(0xFFD20F39), green = Color(0xFF40A02B), yellow = Color(0xFFDF8E1D), blue = Color(0xFF1E66F5),
        purple = Color(0xFF8839EF), cyan = Color(0xFF179299), orange = Color(0xFFFE640B), pink = Color(0xFFEA76CB),
    )

    // ---- Tokyo Night ------------------------------------------------------------
    val tokyoNight = TerminalPalette(
        id = "tokyonight", name = "Tokyo Night", dark = true,
        bg = Color(0xFF1A1B26), bgAlt = Color(0xFF16161E), bgHighlight = Color(0xFF292E42),
        fg = Color(0xFFC0CAF5), fgDim = Color(0xFF565F89), border = Color(0xFF3B4261),
        red = Color(0xFFF7768E), green = Color(0xFF9ECE6A), yellow = Color(0xFFE0AF68), blue = Color(0xFF7AA2F7),
        purple = Color(0xFFBB9AF7), cyan = Color(0xFF7DCFFF), orange = Color(0xFFFF9E64), pink = Color(0xFFFF75A0),
    )
    val tokyoDay = TerminalPalette(
        id = "tokyonight", name = "Tokyo Day", dark = false,
        bg = Color(0xFFE1E2E7), bgAlt = Color(0xFFD5D6DB), bgHighlight = Color(0xFFC4C8DA),
        fg = Color(0xFF3760BF), fgDim = Color(0xFF848CB5), border = Color(0xFFA8AECB),
        red = Color(0xFFF52A65), green = Color(0xFF587539), yellow = Color(0xFF8C6C3E), blue = Color(0xFF2E7DE9),
        purple = Color(0xFF9854F1), cyan = Color(0xFF007197), orange = Color(0xFFB15C00), pink = Color(0xFFD20065),
    )

    // ---- One Dark ---------------------------------------------------------------
    val oneDark = TerminalPalette(
        id = "onedark", name = "One Dark", dark = true,
        bg = Color(0xFF282C34), bgAlt = Color(0xFF21252B), bgHighlight = Color(0xFF3E4451),
        fg = Color(0xFFABB2BF), fgDim = Color(0xFF5C6370), border = Color(0xFF3E4451),
        red = Color(0xFFE06C75), green = Color(0xFF98C379), yellow = Color(0xFFE5C07B), blue = Color(0xFF61AFEF),
        purple = Color(0xFFC678DD), cyan = Color(0xFF56B6C2), orange = Color(0xFFD19A66), pink = Color(0xFFE06C9F),
    )
    val oneLight = TerminalPalette(
        id = "onedark", name = "One Light", dark = false,
        bg = Color(0xFFFAFAFA), bgAlt = Color(0xFFF0F0F1), bgHighlight = Color(0xFFDBDBDC),
        fg = Color(0xFF383A42), fgDim = Color(0xFFA0A1A7), border = Color(0xFFD3D3D3),
        red = Color(0xFFE45649), green = Color(0xFF50A14F), yellow = Color(0xFFC18401), blue = Color(0xFF4078F2),
        purple = Color(0xFFA626A4), cyan = Color(0xFF0184BC), orange = Color(0xFF986801), pink = Color(0xFFCA1243),
    )

    // ---- Rosé Pine --------------------------------------------------------------
    val rosePine = TerminalPalette(
        id = "rosepine", name = "Rosé Pine", dark = true,
        bg = Color(0xFF191724), bgAlt = Color(0xFF1F1D2E), bgHighlight = Color(0xFF26233A),
        fg = Color(0xFFE0DEF4), fgDim = Color(0xFF6E6A86), border = Color(0xFF403D52),
        red = Color(0xFFEB6F92), green = Color(0xFF9CCFD8), yellow = Color(0xFFF6C177), blue = Color(0xFF31748F),
        purple = Color(0xFFC4A7E7), cyan = Color(0xFF9CCFD8), orange = Color(0xFFEA9A97), pink = Color(0xFFEBBCBA),
    )
    val rosePineDawn = TerminalPalette(
        id = "rosepine", name = "Rosé Pine Dawn", dark = false,
        bg = Color(0xFFFAF4ED), bgAlt = Color(0xFFFFFAF3), bgHighlight = Color(0xFFF2E9E1),
        fg = Color(0xFF575279), fgDim = Color(0xFF9893A5), border = Color(0xFFDFDAD9),
        red = Color(0xFFB4637A), green = Color(0xFF56949F), yellow = Color(0xFFEA9D34), blue = Color(0xFF286983),
        purple = Color(0xFF907AA9), cyan = Color(0xFF56949F), orange = Color(0xFFD7827E), pink = Color(0xFFD7827E),
    )

    // ---- Everforest -------------------------------------------------------------
    val everforestDark = TerminalPalette(
        id = "everforest", name = "Everforest", dark = true,
        bg = Color(0xFF2D353B), bgAlt = Color(0xFF272E33), bgHighlight = Color(0xFF3D484D),
        fg = Color(0xFFD3C6AA), fgDim = Color(0xFF859289), border = Color(0xFF475258),
        red = Color(0xFFE67E80), green = Color(0xFFA7C080), yellow = Color(0xFFDBBC7F), blue = Color(0xFF7FBBB3),
        purple = Color(0xFFD699B6), cyan = Color(0xFF83C092), orange = Color(0xFFE69875), pink = Color(0xFFD699B6),
    )
    val everforestLight = TerminalPalette(
        id = "everforest", name = "Everforest Light", dark = false,
        bg = Color(0xFFFDF6E3), bgAlt = Color(0xFFF4F0D9), bgHighlight = Color(0xFFEFEBD4),
        fg = Color(0xFF5C6A72), fgDim = Color(0xFF939F91), border = Color(0xFFE0DCC7),
        red = Color(0xFFF85552), green = Color(0xFF8DA101), yellow = Color(0xFFDFA000), blue = Color(0xFF3A94C5),
        purple = Color(0xFFDF69BA), cyan = Color(0xFF35A77C), orange = Color(0xFFF57D26), pink = Color(0xFFDF69BA),
    )

    // ---- Amber CRT (single-hue phosphor, like Matrix but 1978 orange) ------------
    val amberDark = TerminalPalette(
        id = "amber", name = "Amber CRT", dark = true,
        bg = Color(0xFF0A0700), bgAlt = Color(0xFF130D00), bgHighlight = Color(0xFF2A1C00),
        fg = Color(0xFFFFB000), fgDim = Color(0xFF9A6A00), border = Color(0xFF3F2C00),
        red = Color(0xFFFF8C00), green = Color(0xFFFFB000), yellow = Color(0xFFFFD75F), blue = Color(0xFFE09A00),
        purple = Color(0xFFE09A00), cyan = Color(0xFFFFD75F), orange = Color(0xFFFF8C00), pink = Color(0xFFFFC966),
    )
    val amberLight = TerminalPalette(
        id = "amber", name = "Amber Paper", dark = false,
        bg = Color(0xFFFBF3E1), bgAlt = Color(0xFFF3E8CF), bgHighlight = Color(0xFFE8D9B5),
        fg = Color(0xFF5A3A00), fgDim = Color(0xFFA0803F), border = Color(0xFFD9C79A),
        red = Color(0xFF9A3B00), green = Color(0xFF7A5000), yellow = Color(0xFFB07500), blue = Color(0xFF6E4A00),
        purple = Color(0xFF6E4A00), cyan = Color(0xFF8A6200), orange = Color(0xFF9A3B00), pink = Color(0xFF8A6200),
    )

    // ---- Hacker (monochrome + one red accent) ----------------------------------------
    val hackerDark = TerminalPalette(
        id = "hacker", name = "Hacker", dark = true,
        bg = Color(0xFF000000), bgAlt = Color(0xFF0A0A0A), bgHighlight = Color(0xFF1A1A1A),
        fg = Color(0xFFEDEDED), fgDim = Color(0xFF6B6B6B), border = Color(0xFF2A2A2A),
        red = Color(0xFFFF3B30), green = Color(0xFFEDEDED), yellow = Color(0xFFBDBDBD), blue = Color(0xFF9E9E9E),
        purple = Color(0xFFBDBDBD), cyan = Color(0xFFD6D6D6), orange = Color(0xFFFF3B30), pink = Color(0xFFBDBDBD),
    )
    val hackerLight = TerminalPalette(
        id = "hacker", name = "Hacker Light", dark = false,
        bg = Color(0xFFFFFFFF), bgAlt = Color(0xFFF4F4F4), bgHighlight = Color(0xFFE4E4E4),
        fg = Color(0xFF111111), fgDim = Color(0xFF8A8A8A), border = Color(0xFFD0D0D0),
        red = Color(0xFFD1231A), green = Color(0xFF111111), yellow = Color(0xFF444444), blue = Color(0xFF555555),
        purple = Color(0xFF444444), cyan = Color(0xFF333333), orange = Color(0xFFD1231A), pink = Color(0xFF444444),
    )

    // ---- Synthwave '84 ----------------------------------------------------------
    val synthwaveDark = TerminalPalette(
        id = "synthwave", name = "Synthwave '84", dark = true,
        bg = Color(0xFF262335), bgAlt = Color(0xFF1E1B2E), bgHighlight = Color(0xFF3B2F5C),
        fg = Color(0xFFF4EEE4), fgDim = Color(0xFF8A7FA8), border = Color(0xFF4B3F73),
        red = Color(0xFFFE4450), green = Color(0xFF72F1B8), yellow = Color(0xFFFEDE5D), blue = Color(0xFF36F9F6),
        purple = Color(0xFFB893FF), cyan = Color(0xFF36F9F6), orange = Color(0xFFFF8B39), pink = Color(0xFFFF7EDB),
    )
    val synthwaveLight = TerminalPalette(
        id = "synthwave", name = "Synthwave Dawn", dark = false,
        bg = Color(0xFFF6F0FA), bgAlt = Color(0xFFEDE4F4), bgHighlight = Color(0xFFDCCDEA),
        fg = Color(0xFF3B2F5C), fgDim = Color(0xFF9282B0), border = Color(0xFFCDBEDF),
        red = Color(0xFFD9203A), green = Color(0xFF1E9E6A), yellow = Color(0xFFB08A00), blue = Color(0xFF0E8F8D),
        purple = Color(0xFF7A4FD6), cyan = Color(0xFF0E8F8D), orange = Color(0xFFD86A1B), pink = Color(0xFFD1409E),
    )

    // ---- Signal (classified broadsheet, one lime signal) ------------------------
    val signalDark = TerminalPalette(
        id = "signal", name = "Signal", dark = true,
        bg = Color(0xFF060606), bgAlt = Color(0xFF1F1F1F), bgHighlight = Color(0xFF252525),
        fg = Color(0xFFE5E5E5), fgDim = Color(0xFF7A7A7A), border = Color(0xFF313131),
        red = Color(0xFFE5484D), green = Color(0xFFC5FF4A), yellow = Color(0xFFD8D8D8),
        blue = Color(0xFF9C9C9C), purple = Color(0xFFB5B5B5), cyan = Color(0xFFCFCFCF),
        orange = Color(0xFFC5FF4A), pink = Color(0xFFA6A6A6),
    )
    val signalLight = TerminalPalette(
        id = "signal", name = "Signal", dark = false,
        bg = Color(0xFFEFEFE9), bgAlt = Color(0xFFE3E3DB), bgHighlight = Color(0xFFD7D7CD),
        fg = Color(0xFF1A1A1A), fgDim = Color(0xFF6B6B62), border = Color(0xFFC9C9BE),
        red = Color(0xFFB3322E), green = Color(0xFF597321), yellow = Color(0xFF595952),
        blue = Color(0xFF7A7A72), purple = Color(0xFF8F8F86), cyan = Color(0xFF454540),
        orange = Color(0xFF597321), pink = Color(0xFF8A8A80),
    )

    // ---- Matrix (pure green phosphor) -------------------------------------------
    val matrixDark = TerminalPalette(
        id = "matrix", name = "Matrix", dark = true,
        bg = Color(0xFF000000), bgAlt = Color(0xFF060A06), bgHighlight = Color(0xFF0D1F0D),
        fg = Color(0xFF00FF41), fgDim = Color(0xFF008F11), border = Color(0xFF0D3B0D),
        red = Color(0xFF00FF41), green = Color(0xFF00FF41), yellow = Color(0xFF7CFC9A), blue = Color(0xFF00C832),
        purple = Color(0xFF00C832), cyan = Color(0xFF7CFC9A), orange = Color(0xFF00FF41), pink = Color(0xFF7CFC9A),
    )
    val matrixLight = TerminalPalette(
        id = "matrix", name = "Paper Terminal", dark = false,
        bg = Color(0xFFF4F4EF), bgAlt = Color(0xFFE9E9E1), bgHighlight = Color(0xFFD8D8CC),
        fg = Color(0xFF0F3D0F), fgDim = Color(0xFF6E8B6E), border = Color(0xFFC6CCBE),
        red = Color(0xFF0F3D0F), green = Color(0xFF117A11), yellow = Color(0xFF2B6B2B), blue = Color(0xFF0F5F0F),
        purple = Color(0xFF0F5F0F), cyan = Color(0xFF2B6B2B), orange = Color(0xFF117A11), pink = Color(0xFF2B6B2B),
    )
}
