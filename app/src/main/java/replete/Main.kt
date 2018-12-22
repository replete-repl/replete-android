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
import android.os.Handler
import android.os.Message
import android.os.Looper

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

    var th: Handler? = null
    var vmt: Thread? = null
    var adapter: HistoryAdapter? = null
    var uiHandler: UIHandler? = null
    var isVMLoaded = false

    class UIHandler(private val adapter: HistoryAdapter, val onVMLoaded: () -> Unit) : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                0 -> adapter.update(msg.obj as Item)
                1 -> onVMLoaded()
            }
        }
    }

    class VMThreadHandler(val vm: V8) : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                0 -> vm.executeScript(msg.obj as String)
            }
        }
    }

    private fun createVMThread() {
        val ctx = this
        val t = object : Thread() {
            override fun run() {
                Looper.prepare()
                val vm = V8.createV8Runtime()
                th = VMThreadHandler(vm)
                bootstrap(ctx, vm, uiHandler!!)
                Looper.loop()
            }
        }
        t.start()
        vmt = t
    }

    private fun killVMThread() {
        vmt!!.interrupt()
    }

    private fun executeScript(s: String) {
        th!!.sendMessage(th!!.obtainMessage(0, s))
    }

    fun bundleGetContents(path: String): String {
        return assets.open("out/$path").bufferedReader().readText()
    }

    fun getClojureScriptVersion(): String {
        val s = bundleGetContents("replete/bundle.js")
        return s.substring(29, s.length).takeWhile { c -> c != " ".toCharArray()[0] }
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

    override fun onDestroy() {
        super.onDestroy()
        killVMThread()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val inputField = findViewById<EditText>(R.id.input)
        val replHistory = findViewById<ListView>(R.id.repl_history)
        val evalButton = findViewById<Button>(R.id.eval_button)

        inputField.hint = "Type in here"
        inputField.setHintTextColor(Color.GRAY)

        evalButton.isEnabled = false
        evalButton.setTextColor(Color.GRAY)

        adapter = HistoryAdapter(this, R.layout.list_item, replHistory)

        uiHandler = UIHandler(adapter as HistoryAdapter) { isVMLoaded = true }

        createVMThread()

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

        var isParinferChange = false

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

            }
        })

        evalButton.setOnClickListener { v ->
            val input = inputField.text.toString()
            inputField.text.clear()
            adapter!!.update(Item(input, ItemType.INPUT))

            try {
                executeScript("""replete.repl.read_eval_print("${input.replace("\"", "\\\"")}");""")
            } catch (e: Exception) {
                adapter!!.update(Item(e.toString(), ItemType.ERROR))
            }

        }
    }
}
