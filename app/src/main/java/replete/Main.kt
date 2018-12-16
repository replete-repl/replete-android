package replete

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import com.eclipsesource.v8.*
import android.widget.TextView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter


enum class ItemType {
    INPUT, OUTPUT, ERROR
}

data class Item(val text: String, val type: ItemType)

class HistoryAdapter(context: Context, id: Int) :
    ArrayAdapter<Item>(context, id) {

    private class ViewHolder(val item: TextView)

    override fun getView(position: Int, itemView: View?, parent: ViewGroup): View {

        val item = getItem(position)

        if (itemView == null) {
            val _itemView = LayoutInflater.from(this.context).inflate(R.layout.list_item, parent, false)
            val viewHolder = ViewHolder(_itemView.findViewById(R.id.history_item))

            _itemView.tag = viewHolder

            if (item != null) {
                viewHolder.item.text = item.text
                when (item.type) {
                    ItemType.INPUT -> viewHolder.item.setTextColor(Color.BLACK)
                    ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
                    ItemType.ERROR -> viewHolder.item.setTextColor(Color.RED)
                }
            }

            return _itemView
        } else {
            val viewHolder = itemView.tag as ViewHolder

            if (item != null) {
                viewHolder.item.text = item.text
                when (item.type) {
                    ItemType.INPUT -> viewHolder.item.setTextColor(Color.BLACK)
                    ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
                    ItemType.ERROR -> viewHolder.item.setTextColor(Color.RED)
                }
            }

            return itemView
        }
    }
}

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val vm = V8.createV8Runtime()

        val inputField = findViewById<EditText>(R.id.input)
        val replHisotry = findViewById<ListView>(R.id.repl_history)
        val evalButton = findViewById<Button>(R.id.eval_button)

        val adapter = HistoryAdapter(this, R.layout.list_item)

        replHisotry.adapter = adapter
        replHisotry.divider = null
        replHisotry.dividerHeight = 0

        inputField.typeface = Typeface.MONOSPACE
        inputField.textSize = 14f
        inputField.setTextColor(Color.parseColor("#000000"))

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

        val loadingTextView = TextView(this)
        loadingTextView.textSize = 14f
        loadingTextView.typeface = Typeface.MONOSPACE

        evalButton.isEnabled = false

        evalButton.setOnClickListener { v ->
            val input = inputField.text.toString()
            adapter.add(Item(input, ItemType.INPUT))

            try {
                vm.executeScript("replete.repl.read_eval_print(\"$input\");")
            } catch (e: Exception) {
                adapter.add(Item(e.toString(), ItemType.ERROR))
            }

        }

        val REPLETE_PRINT_FN = JavaVoidCallback { receiver, parameters ->
            if (parameters.length() > 0) {
                val arg1 = parameters.get(0)

                adapter.add(Item(markString(arg1.toString()), ItemType.OUTPUT))

                inputField.text?.clear()

                if (arg1 is Releasable) {
                    arg1.release()
                }
            }
        }

        vm.registerJavaMethod(REPLETE_PRINT_FN, "REPLETE_PRINT_FN");

        try {
            val cljsMain = application.assets.open("main.js").bufferedReader().use { it.readText() }

            vm.executeScript("REPLETE_LOAD = () => null;") // placeholder
            vm.executeScript(cljsMain)
            vm.executeScript("goog.provide('cljs.user');")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript("replete.repl.setup_cljs_user();")
            vm.executeScript("replete.repl.init_app_env({'debug-build': false, 'target-simulator': false, 'user-interface-idiom': 'iPhone'});")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")

            adapter.add(
                Item(
                    "\nClojureScript 1.10.439\n" +
                            "    Docs: (doc function-name)\n" +
                            "          (find-doc \"part-of-name\")\n" +
                            "  Source: (source function-name)\n" +
                            " Results: Stored in *1, *2, *3,\n" +
                            "          an exception in *e\n", ItemType.INPUT
                )
            )
        } catch (e: Exception) {
            adapter.add(Item(e.toString(), ItemType.ERROR))
        }

        adapter.notifyDataSetChanged()
        adapter.notifyDataSetInvalidated()

    }
}
