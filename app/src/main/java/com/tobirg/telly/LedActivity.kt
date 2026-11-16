package com.tobirg.telly

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.concurrent.Executors

/**
 * Power LED settings on top of the ledctl kernel module (Led.kt): install/update/remove the module, the LED mode in
 * standby and while on, and brightness. Every change is applied at once and kept for the next boots, so
 * there is no "Done" button (Back closes the screen). Root work runs on one background thread, in order.
 */
class LedActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var installBtn: MaterialButton
    private lateinit var removeBtn: MaterialButton
    private lateinit var sleepGroup: MaterialButtonToggleGroup
    private lateinit var onGroup: MaterialButtonToggleGroup

    /** button id -> module value. While on, "Off" is the firmware's own state (on_led -1, the firmware keeps it dark). */
    private val sleepModes = mapOf(R.id.ledSleepOff to 0, R.id.ledSleepOn to 1)
    private val onModes = mapOf(R.id.ledOnOff to -1, R.id.ledOnOn to 1)
    private val levels = (1..20).map { it * 5 }

    private var state: Led.State? = null
    private val values = Led.DEFAULTS.toMutableMap()
    private var lastInstallError: String? = null
    /** true while the groups are being set from the module state, so their listeners do not write it back */
    private var updating = false

    private val stepperIds = listOf(
        R.id.ledSleepMinus, R.id.ledSleepPlus, R.id.ledOnMinus, R.id.ledOnPlus,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_led)

        status = findViewById(R.id.ledStatus)
        installBtn = findViewById(R.id.ledInstall)
        removeBtn = findViewById(R.id.ledRemove)
        sleepGroup = findViewById(R.id.ledSleepGroup)
        onGroup = findViewById(R.id.ledOnGroup)

        installBtn.setOnClickListener { install() }
        removeBtn.setOnClickListener { confirmRemove() }

        bindGroup(sleepGroup, sleepModes, "sleep_led")
        bindGroup(onGroup, onModes, "on_led")
        bindStepper(R.id.ledSleepMinus, R.id.ledSleepPlus, "sleep_level", levels)
        bindStepper(R.id.ledOnMinus, R.id.ledOnPlus, "on_level", levels)

        (listOf(R.id.ledInstall, R.id.ledRemove) + stepperIds).forEach { TvFocus.apply(findViewById(it)) }
        focusSegments(listOf(R.id.ledSleepOff, R.id.ledSleepOn))
        focusSegments(listOf(R.id.ledOnOff, R.id.ledOnOn))

        setControlsEnabled(false)
    }

    private var lastRefresh = 0L

    override fun onResume() {
        super.onResume()
        // the TV may flip the home screen in and out after wake; refreshing on every resume made the status flicker
        val now = SystemClock.elapsedRealtime()
        if (state == null || now - lastRefresh > 5000) refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun focusSegments(ids: List<Int>) = ids.forEachIndexed { i, id ->
        val corners = when (i) {
            0 -> TvFocus.Corners.START
            ids.lastIndex -> TvFocus.Corners.END
            else -> TvFocus.Corners.NONE
        }
        TvFocus.apply(findViewById(id), corners = corners)
    }

    private fun bindGroup(group: MaterialButtonToggleGroup, modes: Map<Int, Int>, key: String) {
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updating) return@addOnButtonCheckedListener
            val v = modes[checkedId] ?: return@addOnButtonCheckedListener
            values[key] = v
            showRows()
            apply(key, v)
        }
    }

    private fun bindStepper(minusId: Int, plusId: Int, key: String, steps: List<Int>) {
        fun step(dir: Int) {
            val cur = values.getValue(key)
            val next = if (dir > 0) steps.firstOrNull { it > cur } ?: steps.last()
            else steps.lastOrNull { it < cur } ?: steps.first()
            if (next == cur) return
            values[key] = next
            showRows()
            apply(key, next)
        }
        findViewById<View>(minusId).setOnClickListener { step(-1) }
        findViewById<View>(plusId).setOnClickListener { step(1) }
    }

    private fun apply(key: String, value: Int) {
        worker.execute {
            val ok = Led.set(key, value)
            if (!ok) runOnUiThread {
                Toast.makeText(this, R.string.led_set_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun refresh() {
        lastRefresh = SystemClock.elapsedRealtime()
        if (state == null) {
            status.setText(R.string.led_status_checking)
            status.setTextColor(getColor(R.color.text_secondary))
        }
        worker.execute {
            val s = Led.read(this)
            runOnUiThread { if (!isDestroyed) show(s) }
        }
    }

    private fun show(s: Led.State) {
        state = s
        val (text, color) = when {
            !s.root -> getString(R.string.led_status_no_root) to R.color.warning
            !s.installed && lastInstallError != null -> getString(R.string.led_status_failed, lastInstallError) to R.color.danger
            !s.installed -> getString(R.string.led_status_not_installed) to R.color.warning
            !s.loaded -> getString(R.string.led_status_not_loaded, s.error ?: "?") to R.color.danger
            s.needsUpdate -> getString(R.string.led_status_update, s.installedVersion ?: "?") to R.color.warning
            else -> getString(R.string.led_status_ok, s.installedVersion ?: Led.BUNDLED_VERSION) to R.color.accent
        }
        status.text = text
        status.setTextColor(getColor(color))

        installBtn.visibility = if (s.root && (!s.installed || !s.loaded || s.needsUpdate)) View.VISIBLE else View.GONE
        installBtn.setText(
            when {
                !s.installed -> R.string.led_install
                s.needsUpdate -> R.string.led_update
                else -> R.string.led_reload
            }
        )
        removeBtn.visibility = if (s.root && s.installed) View.VISIBLE else View.GONE

        values.putAll(s.values)
        // blink (2) was removed: an older saved value becomes "On"
        for (k in listOf("sleep_led", "on_led")) if (values[k] == 2) { values[k] = 1; apply(k, 1) }
        // MaterialButton ignores setChecked while disabled: enable first, select, then apply the real state
        setControlsEnabled(true)
        updating = true
        sleepModes.entries.firstOrNull { it.value == values["sleep_led"] }?.let { sleepGroup.check(it.key) }
        // on_led 0 (forced dark, set by older builds) looks the same as the firmware's dark state
        val on = values.getValue("on_led").let { if (it == 0) -1 else it }
        onModes.entries.firstOrNull { it.value == on }?.let { onGroup.check(it.key) }
        updating = false
        showRows()
        setControlsEnabled(s.root && s.installed && s.loaded)
    }

    private fun showRows() {
        text(R.id.ledSleepLevel, getString(R.string.led_level, values.getValue("sleep_level")))
        text(R.id.ledOnLevel, getString(R.string.led_level, values.getValue("on_level")))
        visible(R.id.ledSleepLevelRow, values.getValue("sleep_led") == 1)
        visible(R.id.ledOnLevelRow, values.getValue("on_led") == 1)
    }

    private fun text(id: Int, value: String) { findViewById<TextView>(id).text = value }

    private fun visible(id: Int, show: Boolean) {
        findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        (sleepModes.keys + onModes.keys + stepperIds).forEach { findViewById<View>(it).isEnabled = enabled }
        sleepGroup.alpha = if (enabled) 1f else 0.5f
        onGroup.alpha = if (enabled) 1f else 0.5f
    }

    private fun install() {
        installBtn.isEnabled = false
        status.setText(R.string.led_status_installing)
        status.setTextColor(getColor(R.color.text_secondary))
        worker.execute {
            val err = Led.install(this)
            runOnUiThread {
                installBtn.isEnabled = true
                lastInstallError = err
                refresh()
            }
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setMessage(R.string.led_remove_confirm)
            .setPositiveButton(R.string.led_remove_yes) { _, _ ->
                worker.execute {
                    val ok = Led.remove()
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, R.string.led_set_failed, Toast.LENGTH_SHORT).show()
                        lastInstallError = null
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.led_cancel, null)
            .show()
    }
}
