package replete

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import java.io.*
import java.nio.file.Paths

fun setTextSpanColor(s: SpannableString, color: Int, start: Int, end: Int) {
    return s.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
}

fun markString(s: String): SpannableString {

    var idx = 0
    var rs = s as CharSequence
    var ps = mutableListOf<Int>()

    while (idx != -1) {
        idx = rs.indexOfFirst { c -> c == "\u001B"[0] }
        if (idx != -1) {
            val color = when (rs.substring(idx + 2, idx + 4).toInt()) {
                34 -> Color.BLUE
                32 -> Color.rgb(0, 191, 0)
                35 -> Color.rgb(191, 0, 191)
                31 -> Color.rgb(255, 84, 84)
                else -> null
            }
            rs = rs.substring(0, idx).plus(rs.substring(idx + 5, rs.length))

            if (color != null) {
                ps.add(color)
            }
            ps.add(idx)
        }
    }

    val srs = SpannableString(rs)

    while (srs.isNotEmpty() && ps.size >= 3) {
        setTextSpanColor(srs, ps[0], ps[1], ps[2])
        ps = ps.subList(3, ps.size)
    }

    return srs
}

@TargetApi(Build.VERSION_CODES.O)
class MainActivity : AppCompatActivity() {

    private var isVMLoaded = false

    private var adapter: HistoryAdapter? = null

    private fun bundleGetContents(path: String): String? {
        return try {
            val reader = assets.open("out/$path").bufferedReader()
            val ret = reader.readText()
            reader.close()
            ret
        } catch (e: IOException) {
            null
        }
    }

    private fun getClojureScriptVersion(): String {
        val s = bundleGetContents("replete/bundle.js")
        return s?.substring(29, s.length)?.takeWhile { c -> c != " ".toCharArray()[0] } ?: ""
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

    private fun applyParinfer(args: Array<*>) {
        val s = inputField!!.text
        val origText = args[0] as String
        val text = args[1] as String
        val cursor = args[2] as Int

        if (s.toString() == origText) {
            s.replace(0, s.length, text)
            inputField!!.setSelection(cursor)
        }
    }

    private fun displayError(error: String) {
        adapter!!.update(Item(SpannableString(error), ItemType.ERROR))
    }

    private fun displayInput(input: String) {
        adapter!!.update(Item(SpannableString(input), ItemType.INPUT))
    }

    private fun displayOutput(output: SpannableString) {
        if (!suppressPrinting) {
            adapter!!.update(Item(output, ItemType.OUTPUT))
        }
    }

    private fun toAbsolutePath(path: String): File {
        return filesDir.resolve(if (path.startsWith("/")) path.drop(1) else path)
    }

    private var selectedPosition = -1
    private var selectedView: View? = null

    private fun isRequire(s: String): Boolean {
        return s.trimStart().startsWith("(require")
    }

    private fun isMacro(s: String): Boolean {
        val _s = s.trimStart()
        return _s.startsWith("(defmacro") || _s.startsWith("(defmacfn")
    }

    private var consentedToChivorcam = false
    private var suppressPrinting = false

    private fun defmacroCalled(s: String) {
        if (consentedToChivorcam) {
            suppressPrinting = true
            eval("(require '[chivorcam.core :refer [defmacro defmacfn]])")
            eval(s)
        } else {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enable REPL\nMacro Definitions?")
            builder.setMessage(
                "ClojureScript macros must be defined in a separate namespace and required appropriately." +
                        "\n\nFor didactic purposes, we can support defining macros directly in the Replete REPL. " +
                        "\n\nAny helper functions called during macroexpansion must be defined using defmacfn in lieu of defn."
            )
            builder.setPositiveButton(
                "OK"
            ) { dialog, id ->
                consentedToChivorcam = true
                suppressPrinting = true
                eval("(require '[chivorcam.core :refer [defmacro defmacfn]])")
                eval(s)
            }
            builder.setNegativeButton(
                "Cancel"
            ) { dialog, id ->
                dialog.cancel()
            }
            builder.show()
        }
    }

    var isExecutingTask = false

    private fun disableEvalButton() {
        isExecutingTask = true
        evalButton!!.isEnabled = false
        evalButton!!.setTextColor(Color.GRAY)
    }

    private fun enableEvalButton(withCheck: Boolean = false) {
        val shouldEnable = if (withCheck) inputField!!.text.isNotEmpty() else true
        if (shouldEnable) {
            evalButton!!.isEnabled = true
            evalButton!!.setTextColor(Color.rgb(0, 153, 204))
            isExecutingTask = false
        }
    }

    private fun eval(input: String) {
        disableEvalButton()
        sendThMessage(Messages.EVAL, input)
    }

    private fun updateWidth() {
        if (isVMLoaded) {
            val replHistory: ListView = findViewById(R.id.repl_history)
            val width: Double = (replHistory.width / 29).toDouble()
            sendThMessage(Messages.SET_WIDTH, width)
        }
    }

    private var deviceType: String? = null

    private fun setDeviceType() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        val yInches = metrics.heightPixels / metrics.ydpi;
        val xInches = metrics.widthPixels / metrics.xdpi;
        val diagonalInches = Math.sqrt((xInches * xInches + yInches * yInches).toDouble());
        deviceType = if (diagonalInches >= 6.5) {
            "iPad"
        } else {
            "iPhone"
        }
    }

    val fadeIn = AlphaAnimation(0.3f, 1.0f)
    val fadeOut = AlphaAnimation(1.0f, 0.3f)

    private fun stopLoadingItem() {
        fadeIn.cancel()
        fadeIn.reset()
        fadeOut.reset()
    }

    private fun startLoadingItem(view: View) {
        val duration: Long = 500

        fadeIn.duration = duration
        fadeIn.fillAfter = true

        fadeOut.duration = duration
        fadeOut.fillAfter = true

        fadeOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(animation: Animation?) {
                view.startAnimation(fadeIn)
            }

            override fun onAnimationStart(animation: Animation?) {

            }

            override fun onAnimationRepeat(animation: Animation?) {

            }
        })

        fadeIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(animation: Animation?) {
                view.startAnimation(fadeOut)
            }

            override fun onAnimationStart(animation: Animation?) {

            }

            override fun onAnimationRepeat(animation: Animation?) {

            }
        })

        view.startAnimation(fadeOut)
    }

    override fun onConfigurationChanged(cfg: Configuration) {
        if (resources.configuration.orientation != cfg.orientation) {
            updateWidth()
        }
    }

    var evalButton: Button? = null
    var inputField: EditText? = null

    enum class Messages(val value: Int) {
        INIT_VM(0),
        INIT_ENV(1),
        BOOTSTRAP_ENV(2),
        EVAL(3),
        ADD_ERROR_ITEM(4),
        ADD_OUTPUT_ITEM(5),
        ADD_INPUT_ITEM(6),
        ENABLE_EVAL(7),
        ENABLE_PRINTING(8),
        UPDATE_WIDTH(9),
        SET_WIDTH(10),
        VM_LOADED(11),
        CALL_FN(12),
        RELEASE_OBJ(13),
        RUN_PARINFER(14),
        APPLY_PARINFER(15),
    }

    var uiHandler: Handler? = null
    var thHandler: Handler? = null
    var ht: HandlerThread? = null

    private fun sendThMessage(what: Messages, obj: Any? = null) {
        if (obj != null) {
            thHandler!!.sendMessage(thHandler!!.obtainMessage(what.value, obj))
        } else {
            thHandler!!.sendMessage(thHandler!!.obtainMessage(what.value))
        }
    }

    private fun sendUIMessage(what: Messages, obj: Any? = null) {
        if (obj != null) {
            uiHandler!!.sendMessage(uiHandler!!.obtainMessage(what.value, obj))
        } else {
            uiHandler!!.sendMessage(uiHandler!!.obtainMessage(what.value))
        }
    }

    private fun initializeVMThread() {
        ht = HandlerThread("VMThread")
        ht!!.start()
        thHandler = VMHandler(
            ht!!,
            { what, obj -> sendUIMessage(what, obj) },
            { s -> bundleGetContents(s) },
            { s -> toAbsolutePath(s) }
        )
        sendThMessage(Messages.INIT_VM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uiHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    Messages.ADD_INPUT_ITEM.value -> displayInput(msg.obj as String)
                    Messages.ADD_OUTPUT_ITEM.value -> displayOutput(msg.obj as SpannableString)
                    Messages.ADD_ERROR_ITEM.value -> displayError(msg.obj as String)
                    Messages.ENABLE_EVAL.value -> enableEvalButton()
                    Messages.ENABLE_PRINTING.value -> suppressPrinting = false
                    Messages.UPDATE_WIDTH.value -> updateWidth()
                    Messages.VM_LOADED.value -> isVMLoaded = true
                    Messages.APPLY_PARINFER.value -> applyParinfer(msg.obj as Array<*>)
                }
            }
        }

        initializeVMThread()
        setDeviceType()
        setContentView(R.layout.activity_main)

        inputField = findViewById(R.id.input)
        val replHistory: ListView = findViewById(R.id.repl_history)
        evalButton = findViewById(R.id.eval_button)

        inputField!!.hint = "Type in here"
        inputField!!.setHintTextColor(Color.GRAY)

        evalButton!!.isEnabled = false
        evalButton!!.setTextColor(Color.GRAY)

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
                            return when (item.itemId) {
                                R.id.copy_action -> {
                                    val sitem = parent.getItemAtPosition(selectedPosition) as Item
                                    clipboard.primaryClip = ClipData.newPlainText("input", sitem.text)
                                    selectedPosition = -1
                                    (selectedView as View).setBackgroundColor(Color.rgb(255, 255, 255))
                                    mode.finish()
                                    true
                                }
                                else -> false
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

        inputField!!.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s != null) {
                    evalButton!!.isEnabled = !s.isNullOrEmpty() and isVMLoaded and !isExecutingTask
                    if (evalButton!!.isEnabled) {
                        evalButton!!.setTextColor(Color.rgb(0, 153, 204))
                    } else {
                        evalButton!!.setTextColor(Color.GRAY)
                    }
                    if (!s.isNullOrEmpty() && !isParinferChange) {
                        isParinferChange = true

                        if (isVMLoaded) {
                            val cursorPos = inputField!!.selectionStart
                            thHandler!!.sendMessageAtFrontOfQueue(
                                thHandler!!.obtainMessage(
                                    Messages.RUN_PARINFER.value,
                                    arrayOf(s.toString(), enterPressed, cursorPos)
                                )
                            )
                            enterPressed = false
                        } else {
                            runPoorMansParinfer(inputField!!, s)
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

        evalButton!!.setOnClickListener { v ->
            val input = inputField!!.text.toString()
            inputField!!.text.clear()
            sendUIMessage(Messages.ADD_INPUT_ITEM, input)

            try {
                if (isMacro(input)) {
                    defmacroCalled(input)
                } else {
                    eval(input)
                }
            } catch (e: Exception) {
                sendUIMessage(Messages.ADD_ERROR_ITEM, e.toString())
            }

        }

        sendUIMessage(
            Messages.ADD_INPUT_ITEM, "\nClojureScript ${getClojureScriptVersion()}\n" +
                    "    Docs: (doc function-name)\n" +
                    "          (find-doc \"part-of-name\")\n" +
                    "  Source: (source function-name)\n" +
                    " Results: Stored in *1, *2, *3,\n" +
                    "          an exception in *e\n"
        )

        sendThMessage(Messages.INIT_ENV)
        sendThMessage(Messages.BOOTSTRAP_ENV, deviceType)
    }
}

