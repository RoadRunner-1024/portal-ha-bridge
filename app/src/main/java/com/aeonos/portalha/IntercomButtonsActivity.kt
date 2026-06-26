package com.aeonos.portalha

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// Configure the floating talk buttons: add/name/remove them and pick who each one
// announces to (Everyone or a specific Portal). Built programmatically since it's a
// small dynamic list. Saved on pause; the overlay rebuilds from the new config.
class IntercomButtonsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private val rows = mutableListOf<Row>()
    private var targetLabels = listOf<String>()
    private var targetValues = listOf<String>()
    private var oldButtons = listOf<IntercomButton>()

    private class Row(val view: View, val nameEt: EditText, val spinner: Spinner)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        oldButtons = prefs.getIntercomButtons()
        buildTargetOptions()

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply { text = "←  Back"; setOnClickListener { finish() } })
        header.addView(TextView(this).apply {
            text = "Talk Buttons"; textSize = 20f
            setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(16), 0, 0, 0)
        })
        col.addView(header)

        col.addView(TextView(this).apply {
            text = "Floating push-to-talk buttons shown on the dashboard. Name each one and pick " +
                "who it talks to. On screen: hold to talk; double-tap a button to move it, then drag."
            textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, dp(8), 0, dp(16))
        })

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(container)

        col.addView(Button(this).apply {
            text = "+  Add talk button"
            setOnClickListener { addRow(IntercomButton("Talk", "all")) }
        })

        setContentView(root)

        if (oldButtons.isEmpty()) addRow(IntercomButton("Talk", "all"))
        else oldButtons.forEach { addRow(it) }
    }

    // Everyone + currently-online Portals, plus any saved targets that are offline.
    private fun buildTargetOptions() {
        val labels = mutableListOf("Everyone")
        val values = mutableListOf("all")
        BridgeService.intercomPeers().forEach { labels.add(it.name); values.add(it.id) }
        oldButtons.forEach { b ->
            if (b.target != "all" && b.target !in values) {
                labels.add("Portal ${b.target.take(6)}… (offline)"); values.add(b.target)
            }
        }
        targetLabels = labels; targetValues = values
    }

    private fun addRow(b: IntercomButton) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val nameEt = EditText(this).apply {
            setText(b.name); hint = "Name"
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF777777.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@IntercomButtonsActivity, R.layout.spinner_item_light, targetLabels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val idx = targetValues.indexOf(b.target); if (idx >= 0) setSelection(idx)
        }
        val del = Button(this).apply {
            text = "✕"
            setOnClickListener { container.removeView(row); rows.removeAll { it.view === row } }
        }
        row.addView(nameEt); row.addView(spinner); row.addView(del)
        container.addView(row)
        rows.add(Row(row, nameEt, spinner))
    }

    private fun save() {
        val list = rows.map { r ->
            val name = r.nameEt.text.toString().trim().ifEmpty { "Talk" }
            val target = targetValues.getOrElse(r.spinner.selectedItemPosition) { "all" }
            // Keep a button's saved position if it's unchanged (matched by name+target).
            val old = oldButtons.firstOrNull { it.name == name && it.target == target }
            IntercomButton(name, target, old?.x ?: -1, old?.y ?: -1)
        }
        prefs.setIntercomButtons(list)
        BridgeService.applyIntercomOverlay(this)
    }

    override fun onPause() {
        super.onPause()
        save()
    }
}
