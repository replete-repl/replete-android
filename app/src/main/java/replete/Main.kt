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
import android.widget.TextView
import android.widget.ArrayAdapter
import android.content.ClipData
import android.content.ClipboardManager

enum class ItemType {
    INPUT, OUTPUT, ERROR
}

data class Item(val text: String, val type: ItemType)

class HistoryAdapter(context: Context, id: Int, val parent: ListView) :
    ArrayAdapter<Item>(context, id) {

    fun update(item: Item) {
        this.add(item)
        parent.smoothScrollToPosition(this.count - 1)
    }

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
                    ItemType.INPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
                    ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.BLACK)
                    ItemType.ERROR -> viewHolder.item.setTextColor(Color.RED)
                }
            }

            return _itemView
        } else {
            val viewHolder = itemView.tag as ViewHolder

            if (item != null) {
                viewHolder.item.text = item.text
                when (item.type) {
                    ItemType.INPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
                    ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.BLACK)
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

    fun bundle_get_contents(path: String): String {
        return assets.open("out/$path").bufferedReader().readText()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val vm = V8.createV8Runtime()

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

        val REPLETE_LOAD = JavaCallback { receiver, parameters ->
            if (parameters.length() > 0) {
                val arg = parameters.get(0)
                val path = arg.toString()

                if (arg is Releasable) {
                    arg.release()
                }

                return@JavaCallback this.bundle_get_contents(path)
            } else {

            }
        }

        val REPLETE_PRINT_FN = JavaVoidCallback { receiver, parameters ->
            if (parameters.length() > 0) {
                val msg = parameters.get(0)

                adapter.update(Item(markString(msg.toString()), ItemType.OUTPUT))

                if (msg is Releasable) {
                    msg.release()
                }
            }
        }

        val loadedLibs = mutableSetOf<String>()

        val AMBLY_IMPORT_SCRIPT = JavaVoidCallback { receiver, parameters ->
            if (parameters.length() > 0) {
                val arg = parameters.get(0)
                var path = arg.toString()

                if (!loadedLibs.contains(path)) {

                    loadedLibs.add(path)

                    if (path.startsWith("goog/../")) {
                        path = path.substring(8, path.length)
                    }


                    vm.executeScript(this.bundle_get_contents(path))
                }

                if (arg is Releasable) {
                    arg.release()
                }
            }
        }

        vm.registerJavaMethod(REPLETE_LOAD, "REPLETE_LOAD");
        vm.registerJavaMethod(REPLETE_PRINT_FN, "REPLETE_PRINT_FN");
        vm.registerJavaMethod(AMBLY_IMPORT_SCRIPT, "AMBLY_IMPORT_SCRIPT");

        try {
            val deps_file_path = "main.js"
            val goog_base_path = "goog/base.js"

            vm.executeScript("var global = this;")

            vm.executeScript("CLOSURE_IMPORT_SCRIPT = function(src) { AMBLY_IMPORT_SCRIPT('goog/' + src); return true; }")

            vm.executeScript(this.bundle_get_contents(goog_base_path))
            vm.executeScript(this.bundle_get_contents(deps_file_path))


            vm.executeScript("goog.isProvided_ = function(x) { return false; };")
            vm.executeScript("goog.require = function (name) { return CLOSURE_IMPORT_SCRIPT(goog.dependencies_.nameToPath[name]); };")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript(
                "cljs.core._STAR_loaded_libs_STAR_ = cljs.core.into.call(null, cljs.core.PersistentHashSet.EMPTY, [\"cljs.core\"]);\n" +
                        "goog.require = function (name, reload) {\n" +
                        "    if(!cljs.core.contains_QMARK_(cljs.core._STAR_loaded_libs_STAR_, name) || reload) {\n" +
                        "        var AMBLY_TMP = cljs.core.PersistentHashSet.EMPTY;\n" +
                        "        if (cljs.core._STAR_loaded_libs_STAR_) {\n" +
                        "            AMBLY_TMP = cljs.core._STAR_loaded_libs_STAR_;\n" +
                        "        }\n" +
                        "        cljs.core._STAR_loaded_libs_STAR_ = cljs.core.into.call(null, AMBLY_TMP, [name]);\n" +
                        "        CLOSURE_IMPORT_SCRIPT(goog.dependencies_.nameToPath[name]);\n" +
                        "    }\n" +
                        "};"
            )

            vm.executeScript("goog.provide('cljs.user');")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript("goog.require('replete.repl');")
            vm.executeScript("replete.repl.setup_cljs_user();")
            vm.executeScript("replete.repl.init_app_env({'debug-build': false, 'target-simulator': false, 'user-interface-idiom': 'iPhone'});")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("var window = global;")

            adapter.update(
                Item(
                    "\nClojureScript ${this.getClojureScriptVersion()}\n" +
                            "    Docs: (doc function-name)\n" +
                            "          (find-doc \"part-of-name\")\n" +
                            "  Source: (source function-name)\n" +
                            " Results: Stored in *1, *2, *3,\n" +
                            "          an exception in *e\n", ItemType.INPUT
                )
            )
        } catch (e: Exception) {
            adapter.update(Item(e.toString(), ItemType.ERROR))
        }

    }

    fun getClojureScriptVersion(): String {
        val s = this.bundle_get_contents("replete/bundle.js")
        return s.substring(29, s.length).takeWhile { c -> c != " ".toCharArray()[0] }
    }
}
