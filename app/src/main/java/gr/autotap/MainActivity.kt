package gr.autotap

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity

/** Αρχική οθόνη: εξηγεί και ανοίγει τις ρυθμίσεις Προσβασιμότητας. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "AutoTap"; textSize = 30f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad)
            text = "1. Πάτα «Ενεργοποίηση» και άνοιξε το AutoTap στις Εγκατεστημένες εφαρμογές " +
                    "της Προσβασιμότητας.\n\n2. Θα εμφανιστεί η πλωτή μπάρα δεξιά στην οθόνη.\n\n" +
                    "3. Πάτα REC και άγγιξε / σύρε στην οθόνη με τη σειρά που θέλεις. Πάτα ξανά REC για τέλος.\n\n" +
                    "4. Πάτα ▶ για εκτέλεση."
        })
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Ενεργοποίηση"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = TapService.instance != null
        status.text = if (on) "✔ Η υπηρεσία είναι ενεργή" else "✖ Η υπηρεσία είναι ανενεργή"
        status.setTextColor(if (on) Color.rgb(0, 150, 70) else Color.RED)
    }
}
