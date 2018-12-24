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
import android.os.AsyncTask
import android.os.Handler
import java.net.URL

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

    private val vm: V8 = V8.createV8Runtime()
    private var isVMLoaded = false

    private var adapter: HistoryAdapter? = null

    private fun bundleGetContents(path: String): String {
        return assets.open("out/$path").bufferedReader().readText()
    }

    private fun getClojureScriptVersion(): String {
        val s = bundleGetContents("replete/bundle.js")
        return s.substring(29, s.length).takeWhile { c -> c != " ".toCharArray()[0] }
    }

    private var timeoutId: Long = 0
    private val timeouts: MutableMap<Long, Runnable> = mutableMapOf()

    private fun setTimeout(callback: () -> Unit, t: Long): Long {

        if (timeoutId == 9007199254740991) {
            timeoutId = 0;
        } else {
            ++timeoutId;
        }

        val runnable = Runnable { callback() }
        timeouts.set(timeoutId, runnable)
        Handler().postDelayed(runnable, t)

        return timeoutId
    }

    private fun cancelTimeout(tid: Long) {
        if (timeouts.contains(tid)) {
            Handler().removeCallbacks(timeouts.get(tid))
            timeouts.remove(tid)
        }
    }

    private val repleteSetTimeout = JavaCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg1 = parameters.get(0)
            val arg2 = parameters.get(1)

            val tid = setTimeout(fun() {
                val callback = arg1 as V8Function
                callback.call(callback, V8Array(vm))
                arg1.release()
                if (arg2 is Releasable) {
                    arg2.release()
                }
            }, arg2 as Long or 4)

            return@JavaCallback tid
        } else {
        }
    }

    private val repleteCancelTimeout = JavaVoidCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg1 = parameters.get(0)

            cancelTimeout(arg1 as Long)

            if (arg1 is Releasable) {
                arg1.release()
            }
        } else {
        }
    }

    private val repleteHighResTimer = JavaCallback { receiver, parameters ->
        System.nanoTime() / 1e6
    }

    private val repleteRequest = JavaCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg1 = parameters.get(0)
            val arg2 = parameters.get(1)

            val ret = URL(arg1.toString()).readText()

            if (arg1 is Releasable) {
                arg1.release()
            }

            if (arg2 is Releasable) {
                arg2.release()
            }

            return@JavaCallback ret
        } else {

        }
    }

    private val repleteLoad = JavaCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg = parameters.get(0)
            val path = arg.toString()

            if (arg is Releasable) {
                arg.release()
            }

            return@JavaCallback bundleGetContents(path)
        } else {

        }
    }

    private val loadedLibs = mutableSetOf<String>()

    private val amblyImportScript = JavaVoidCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg = parameters.get(0)
            var path = arg.toString()

            if (!loadedLibs.contains(path)) {

                loadedLibs.add(path)

                if (path.startsWith("goog/../")) {
                    path = path.substring(8, path.length)
                }


                vm.executeScript(bundleGetContents(path))
            }

            if (arg is Releasable) {
                arg.release()
            }
        }
    }

    private val repletePrintFn = JavaVoidCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val msg = parameters.get(0)

            runOnUiThread {
                adapter!!.update(Item(markString(msg.toString()), ItemType.OUTPUT))
            }

            if (msg is Releasable) {
                msg.release()
            }
        }
    }

    private fun runPoorMansParinfer(inputField: EditText, s: Editable) {
        val cursorPos = inputField.selectionStart
        if (cursorPos == 1) {
            when (s.toString()) {
                "(" -> s.append(")")
                "[" -> s.append("]")
                "{" -> s.append("}")
            }
            inputField.setSelection(cursorPos)
        }
    }

    private fun runParinfer(inputField: EditText, s: Editable, enterPressed: Boolean) {
        val cursorPos = inputField.selectionStart
        val params = V8Array(vm).push(s.toString()).push(cursorPos).push(enterPressed)
        val ret = vm.getObject("replete").getObject("repl").executeArrayFunction("format", params)
        val text = ret[0] as String
        val cursor = ret[1] as Int

        s.replace(0, s.length, text)
        inputField.setSelection(cursor)

        params.release()
        ret.release()
    }

    private var selectedPosition = -1
    private var selectedView: View? = null

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val inputField: EditText = findViewById(R.id.input)
        val replHistory: ListView = findViewById(R.id.repl_history)
        val evalButton: Button = findViewById(R.id.eval_button)

        inputField.hint = "Type in here"
        inputField.setHintTextColor(Color.GRAY)

        evalButton.isEnabled = false
        evalButton.setTextColor(Color.GRAY)

        adapter = HistoryAdapter(this, R.layout.list_item, replHistory)

        replHistory.adapter = adapter
        replHistory.divider = null

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

        var isParinferChange = false
        var enterPressed = false

        inputField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s != null) {
                    evalButton.isEnabled = !s.isNullOrEmpty() and isVMLoaded
                    if (evalButton.isEnabled) {
                        evalButton.setTextColor(Color.rgb(0, 153, 204))
                    } else {
                        evalButton.setTextColor(Color.GRAY)
                    }
                    if (!s.isNullOrEmpty() and !isParinferChange) {
                        isParinferChange = true

                        if (isVMLoaded) {
                            runParinfer(inputField, s, enterPressed)
                            enterPressed = false
                        } else {
                            runPoorMansParinfer(inputField, s)
                        }
                    } else {
                        isParinferChange = false
                    }
                }
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                if (p0 != null && p0.length > p1 && p0[p1] == "\n"[0]) {
                    enterPressed = true
                }
            }
        })

        evalButton.setOnClickListener { v ->
            val input = inputField.text.toString()
            inputField.text.clear()
            adapter!!.update(Item(input, ItemType.INPUT))

            try {
                ExecuteScriptTask(vm).execute("""replete.repl.read_eval_print(`${input.replace("\"", "\\\"")}`);""")
            } catch (e: Exception) {
                adapter!!.update(Item(e.toString(), ItemType.ERROR))
            }

        }

        adapter!!.update(
            Item(
                "\nClojureScript ${getClojureScriptVersion()}\n" +
                        "    Docs: (doc function-name)\n" +
                        "          (find-doc \"part-of-name\")\n" +
                        "  Source: (source function-name)\n" +
                        " Results: Stored in *1, *2, *3,\n" +
                        "          an exception in *e\n", ItemType.INPUT
            )
        )

        vm.registerJavaMethod(repleteLoad, "REPLETE_LOAD");
        vm.registerJavaMethod(repletePrintFn, "REPLETE_PRINT_FN");
        vm.registerJavaMethod(amblyImportScript, "AMBLY_IMPORT_SCRIPT");
        vm.registerJavaMethod(repleteHighResTimer, "REPLETE_HIGH_RES_TIMER");
        vm.registerJavaMethod(repleteRequest, "REPLETE_REQUEST");
        vm.registerJavaMethod(repleteSetTimeout, "setTimeout");
        vm.registerJavaMethod(repleteCancelTimeout, "clearTimeout");

        BootstrapTask(
            vm,
            adapter!!,
            { isVMLoaded = true },
            { s -> bundleGetContents(s) }).execute()
    }
}

class ExecuteScriptTask(val vm: V8) : AsyncTask<String, Unit, Unit>() {
    override fun onPreExecute() {
        vm.locker.release()
    }

    override fun doInBackground(vararg params: String) {
        vm.locker.acquire()
        vm.executeScript(params[0])
        vm.locker.release()
    }

    override fun onPostExecute(result: Unit?) {
        vm.locker.acquire()
    }
}

class BootstrapTask(
    val vm: V8,
    val adapter: HistoryAdapter,
    val onVMLoaded: () -> Unit,
    val bundleGetContents: (String) -> String
) :
    AsyncTask<Unit, Unit, Exception?>() {

    override fun onPreExecute() {
        vm.locker.release()
    }

    override fun doInBackground(vararg params: Unit?): Exception? {

        try {

            vm.locker.acquire()

            val deps_file_path = "main.js"
            val goog_base_path = "goog/base.js"

            vm.executeScript("var global = this;")

            vm.executeScript("CLOSURE_IMPORT_SCRIPT = function(src) { AMBLY_IMPORT_SCRIPT('goog/' + src); return true; }")

            vm.executeScript(bundleGetContents(goog_base_path))
            vm.executeScript(bundleGetContents(deps_file_path))


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
            vm.executeScript("cljs.core.system_time = REPLETE_HIGH_RES_TIMER;")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("var window = global;")

            vm.locker.release()

            return null
        } catch (e: Exception) {
            if (vm.locker.hasLock()) {
                vm.locker.release()
            }
            return e
        }
    }

    override fun onPostExecute(error: Exception?) {
        vm.locker.acquire()
        if (error != null) {
            adapter.update(Item(error.toString(), ItemType.ERROR))
        }
        onVMLoaded()
    }
}
