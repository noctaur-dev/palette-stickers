package com.noctaur.palettestickers

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager

/**
 * Quick Settings tile → keyboard picker. The Samsung Keyboard has no "switch keyboard" button,
 * so this is the one-tap way to jump to the sticker keyboard (and back) from any chat.
 */
class KeyboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Peelbox keyboard"
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, PickKeyboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}

/**
 * Invisible activity: Android only shows the keyboard picker for the app in focus, so this
 * briefly takes focus, opens the picker, and closes itself once you've chosen.
 */
class PickKeyboardActivity : Activity() {
    private var pickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm.enabledInputMethodList.none { it.packageName == packageName }) {
            Dialogs.toast(this, "Turn on \"${getString(R.string.app_name)}\" here first")
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !pickerShown) {
            pickerShown = true
            Bg.main.postDelayed({ getSystemService(InputMethodManager::class.java).showInputMethodPicker() }, 150)
        } else if (hasFocus && pickerShown) {
            finish() // picker closed — back to whatever app you were in
        }
    }

    override fun onPause() {
        super.onPause()
        if (pickerShown) finish()
    }
}
