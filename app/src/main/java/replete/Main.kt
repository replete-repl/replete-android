package replete

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.eclipsesource.v8.*
import android.content.ClipData
import android.content.ClipboardManager

fun markString(s: String): String {
    // black
    // 34 blue
    // 32 red: 0.0, green: 0.75, blue: 0.0
    // 35 red: 0.75, green: 0.0, blue: 0.75
    // 31 red: 1, green: 0.33, blue: 0.33
    // 30 reset


    return s.replace(Regex("\\u001B\\[(34|32|35|31|30)m"), "")
}

class MainActivity : AppCompatActivity() {

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val vm: V8 = time<V8>("VM INIT", {V8.createV8Runtime()})

        val inputField = findViewById<EditText>(R.id.input)
        val replHistory = findViewById<ListView>(R.id.repl_history)
        val evalButton = findViewById<Button>(R.id.eval_button)

        val adapter = HistoryAdapter(this, R.layout.list_item, replHistory)

        replHistory.adapter = adapter
        replHistory.divider = null

        var selectedPosition = -1
        var selectedView: View? = null

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        replHistory.setOnItemClickListener { parent, view, position, id ->
            val item = parent.getItemAtPosition(position) as Item
            if (item.type == ItemType.INPUT) {
                if (position == selectedPosition) {
                    selectedPosition = -1
                    view.setBackgroundColor(Color.rgb(255, 255, 255))
                } else {
                    if (selectedPosition != -1 && selectedView != null) {
                        (selectedView as View).setBackgroundColor(Color.rgb(255, 255, 255))
                    }
                    selectedPosition = position
                    view.setBackgroundColor(Color.rgb(219, 220, 255))
                    selectedView = view

                    (selectedView as View).startActionMode(object : ActionMode.Callback {

                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val inflater = mode.menuInflater
                            inflater.inflate(R.menu.menu_actions, menu)
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                            return false
                        }

                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            when (item.itemId) {
                                R.id.copy_action -> {
                                    val sitem = parent.getItemAtPosition(selectedPosition) as Item
                                    clipboard.primaryClip = ClipData.newPlainText("input", sitem.text)
                                    selectedPosition = -1
                                    (selectedView as View).setBackgroundColor(Color.rgb(255, 255, 255))
                                    mode.finish()
                                    return true
                                }
                                else -> return false
                            }
                        }

                        override fun onDestroyActionMode(mode: ActionMode?) {

                        }
                    }, ActionMode.TYPE_FLOATING)
                }
            }
        }

        inputField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                val s = inputField.text.toString()
                evalButton.isEnabled = s.isNotBlank()
            }
        })

        inputField.isSelected = true

        evalButton.isEnabled = false

        evalButton.setOnClickListener { v ->
            val input = inputField.text.toString()
            inputField.text.clear()
            adapter.update(Item(input, ItemType.INPUT))

            try {
                vm.executeScript("""replete.repl.read_eval_print("${input.replace("\"", "\\\"")}");""")
            } catch (e: Exception) {
                adapter.update(Item(e.toString(), ItemType.ERROR))
            }

        }

        bootstrap(this, vm, adapter)
    }
}
