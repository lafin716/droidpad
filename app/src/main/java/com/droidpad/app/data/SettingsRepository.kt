package com.droidpad.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.droidpad.app.hid.HidQosPreset
import com.droidpad.app.hid.HidReportSender
import com.droidpad.app.input.keyboard.LanguageToggleKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** PRESET uses the built-in touchpad + keys screen; CUSTOM uses the user's grid layout. */
enum class LayoutMode { PRESET, CUSTOM }

/** How text is typed: the phone's own IME, or DroidPad's full-screen virtual keyboard. */
enum class KeyboardInputMode { SYSTEM_IME, VIRTUAL_FULLSCREEN }

data class AppSettings(
    val pointerSpeed: Float = 1.2f,
    val acceleration: Boolean = true,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = true,
    val tapToClick: Boolean = true,
    val haptics: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoLanguageSwitch: Boolean = true,
    val languageToggleKey: LanguageToggleKey = LanguageToggleKey.SHIFT_SPACE,
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = true,
    val lastHostAddress: String? = null,
    val lastHostName: String? = null,
    /** Puts the keys on the left and the touchpad on the right in wide layouts. */
    val swapPanes: Boolean = false,
    val layoutMode: LayoutMode = LayoutMode.PRESET,
    val keyboardInputMode: KeyboardInputMode = KeyboardInputMode.SYSTEM_IME,
    /** Custom layouts keyed by [CustomLayout.gridKey], so each screen size keeps its own. */
    val customLayouts: Map<String, CustomLayout> = emptyMap(),
    val airMouseSpeed: Float = 1.0f,
    /** Air mouse only moves the pointer while the move pad is held (clutch). */
    val airMouseHoldToMove: Boolean = true,
    /** Labs: shows the experimental "slide the phone on the desk" mouse. */
    val labDeskMouse: Boolean = false,
    /** Labs: Bluetooth QoS declared when registering the HID app. */
    val labQosPreset: HidQosPreset = HidQosPreset.STANDARD,
    /** Labs: minimum gap between pointer movement reports (0 = unpaced). */
    val labMoveIntervalMs: Int = HidReportSender.MOVE_INTERVAL_MS.toInt(),
    /** Labs: delay used to spread pointer motion evenly over reports (0 = off). */
    val labMoveSmoothingMs: Int = HidReportSender.MOVE_SMOOTHING_MS.toInt(),
) {
    companion object {
        const val MIN_POINTER_SPEED = 0.3f
        const val MAX_POINTER_SPEED = 3.0f
        const val MIN_SCROLL_SPEED = 0.3f
        const val MAX_SCROLL_SPEED = 3.0f
    }
}

/** SharedPreferences-backed settings exposed as a [StateFlow]. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("droidpad_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pointerSpeed = prefs.getFloat(K_POINTER, d.pointerSpeed),
            acceleration = prefs.getBoolean(K_ACCEL, d.acceleration),
            scrollSpeed = prefs.getFloat(K_SCROLL, d.scrollSpeed),
            naturalScroll = prefs.getBoolean(K_NATURAL, d.naturalScroll),
            tapToClick = prefs.getBoolean(K_TAP, d.tapToClick),
            haptics = prefs.getBoolean(K_HAPTICS, d.haptics),
            themeMode = enumOr(prefs.getString(K_THEME, null), d.themeMode),
            dynamicColor = prefs.getBoolean(K_DYNAMIC, d.dynamicColor),
            autoLanguageSwitch = prefs.getBoolean(K_AUTO_LANG, d.autoLanguageSwitch),
            languageToggleKey = enumOr(prefs.getString(K_TOGGLE, null), d.languageToggleKey),
            autoReconnect = prefs.getBoolean(K_RECONNECT, d.autoReconnect),
            keepScreenOn = prefs.getBoolean(K_SCREEN, d.keepScreenOn),
            lastHostAddress = prefs.getString(K_HOST_ADDR, null),
            lastHostName = prefs.getString(K_HOST_NAME, null),
            swapPanes = prefs.getBoolean(K_SWAP, d.swapPanes),
            layoutMode = enumOr(prefs.getString(K_LAYOUT_MODE, null), d.layoutMode),
            keyboardInputMode = enumOr(prefs.getString(K_INPUT_MODE, null), d.keyboardInputMode),
            customLayouts = CustomLayout.decodeAll(prefs.getString(K_LAYOUTS, null)),
            airMouseSpeed = prefs.getFloat(K_AIR_SPEED, d.airMouseSpeed),
            airMouseHoldToMove = prefs.getBoolean(K_AIR_HOLD, d.airMouseHoldToMove),
            labDeskMouse = prefs.getBoolean(K_LAB_DESK, d.labDeskMouse),
            labQosPreset = enumOr(prefs.getString(K_LAB_QOS, null), d.labQosPreset),
            labMoveIntervalMs = prefs.getInt(K_LAB_MOVE_INTERVAL, d.labMoveIntervalMs),
            labMoveSmoothingMs = prefs.getInt(K_LAB_MOVE_SMOOTHING, d.labMoveSmoothingMs),
        )
    }

    private fun save(s: AppSettings) = prefs.edit {
        putFloat(K_POINTER, s.pointerSpeed)
        putBoolean(K_ACCEL, s.acceleration)
        putFloat(K_SCROLL, s.scrollSpeed)
        putBoolean(K_NATURAL, s.naturalScroll)
        putBoolean(K_TAP, s.tapToClick)
        putBoolean(K_HAPTICS, s.haptics)
        putString(K_THEME, s.themeMode.name)
        putBoolean(K_DYNAMIC, s.dynamicColor)
        putBoolean(K_AUTO_LANG, s.autoLanguageSwitch)
        putString(K_TOGGLE, s.languageToggleKey.name)
        putBoolean(K_RECONNECT, s.autoReconnect)
        putBoolean(K_SCREEN, s.keepScreenOn)
        putString(K_HOST_ADDR, s.lastHostAddress)
        putString(K_HOST_NAME, s.lastHostName)
        putBoolean(K_SWAP, s.swapPanes)
        putString(K_LAYOUT_MODE, s.layoutMode.name)
        putString(K_INPUT_MODE, s.keyboardInputMode.name)
        putString(K_LAYOUTS, CustomLayout.encodeAll(s.customLayouts))
        putFloat(K_AIR_SPEED, s.airMouseSpeed)
        putBoolean(K_AIR_HOLD, s.airMouseHoldToMove)
        putBoolean(K_LAB_DESK, s.labDeskMouse)
        putString(K_LAB_QOS, s.labQosPreset.name)
        putInt(K_LAB_MOVE_INTERVAL, s.labMoveIntervalMs)
        putInt(K_LAB_MOVE_SMOOTHING, s.labMoveSmoothingMs)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        const val K_POINTER = "pointer_speed"
        const val K_ACCEL = "acceleration"
        const val K_SCROLL = "scroll_speed"
        const val K_NATURAL = "natural_scroll"
        const val K_TAP = "tap_to_click"
        const val K_HAPTICS = "haptics"
        const val K_THEME = "theme_mode"
        const val K_DYNAMIC = "dynamic_color"
        const val K_AUTO_LANG = "auto_language_switch"
        const val K_TOGGLE = "language_toggle_key"
        const val K_RECONNECT = "auto_reconnect"
        const val K_SCREEN = "keep_screen_on"
        const val K_HOST_ADDR = "last_host_address"
        const val K_HOST_NAME = "last_host_name"
        const val K_SWAP = "swap_panes"
        const val K_LAYOUT_MODE = "layout_mode"
        const val K_INPUT_MODE = "keyboard_input_mode"
        const val K_LAYOUTS = "custom_layouts"
        const val K_AIR_SPEED = "air_mouse_speed"
        const val K_AIR_HOLD = "air_mouse_hold_to_move"
        const val K_LAB_DESK = "lab_desk_mouse"
        const val K_LAB_QOS = "lab_qos_preset"
        const val K_LAB_MOVE_INTERVAL = "lab_move_interval_ms"
        const val K_LAB_MOVE_SMOOTHING = "lab_move_smoothing_ms"
    }
}
