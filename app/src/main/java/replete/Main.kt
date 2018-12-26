package replete

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.support.annotation.RequiresApi
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.eclipsesource.v8.*
import android.content.ClipData
import android.content.ClipboardManager
import android.os.*
import android.provider.UserDictionary
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.io.*
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

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

    private var intervalId: Long = 0
    private val intervals: MutableMap<Long, IntervalThread> = mutableMapOf()

    class IntervalThread(val callback: () -> Unit, val onCanceled: () -> Unit, val t: Long) : Thread() {
        var isIntervalCanceled = false
        override fun run() {
            while (true) {
                Thread.sleep(t)
                if (isIntervalCanceled) {
                    onCanceled()
                    break
                } else {
                    callback()
                }
            }
        }
    }

    private fun setInterval(callback: () -> Unit, onCanceled: () -> Unit, t: Long): Long {

        if (intervalId == 9007199254740991) {
            intervalId = 0;
        } else {
            ++intervalId;
        }

        val tt = IntervalThread({ runOnUiThread { callback() } }, { runOnUiThread { onCanceled() } }, t)
        intervals[intervalId] = tt

        tt.start()

        return intervalId
    }

    private fun cancelInterval(tid: Long) {
        if (intervals.contains(tid)) {
            intervals[tid]!!.isIntervalCanceled = true
            intervals.remove(tid)
        }
    }

    private val repleteSetInterval = JavaCallback { receiver, parameters ->
        if (parameters.length() == 2) {
            val callback = parameters.get(0) as V8Function
            val timeout = parameters.getDouble(1).toLong()
            val tid = setInterval(fun() {
                callback.call(callback, V8Array(vm))
            }, { callback.release() }, timeout)

            return@JavaCallback tid.toDouble()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteCancelInterval = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val tid = parameters.getInteger(0).toLong()
            cancelInterval(tid)
        }
        return@JavaCallback V8.getUndefined()
    }

    private var timeoutId: Long = 0
    private val timeouts: MutableMap<Long, TimeoutThread> = mutableMapOf()

    class TimeoutThread(val callback: () -> Unit, val t: Long) : Thread() {
        var isTimeoutCanceled = false
        override fun run() {
            Thread.sleep(t)
            if (!isTimeoutCanceled) {
                callback()
            }
        }
    }

    private fun setTimeout(callback: () -> Unit, t: Long): Long {

        if (timeoutId == 9007199254740991) {
            timeoutId = 0;
        } else {
            ++timeoutId;
        }

        val tt = TimeoutThread({ runOnUiThread { callback() } }, t)
        timeouts[timeoutId] = tt

        tt.start()

        return timeoutId
    }

    private fun cancelTimeout(tid: Long) {
        if (timeouts.contains(tid)) {
            timeouts[tid]!!.isTimeoutCanceled = true
            timeouts.remove(tid)
        }
    }

    private val repleteSetTimeout = JavaCallback { receiver, parameters ->
        if (parameters.length() == 2) {
            val callback = parameters.get(0) as V8Function
            val timeout = parameters.getDouble(1).toLong()
            val tid = setTimeout(fun() {
                callback.call(callback, V8Array(vm))
                callback.release()
            }, timeout)

            return@JavaCallback tid.toDouble()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteCancelTimeout = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val tid = parameters.getInteger(0).toLong()
            cancelTimeout(tid)
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteHighResTimer = JavaCallback { receiver, parameters ->
        System.nanoTime() / 1e6
    }

    private val repleteRequest = JavaCallback { receiver, parameters ->
        if (parameters.length() > 0 && parameters.get(0) is V8Object) {
            val opts = parameters.get(0) as V8Object

            val url = try {
                URL(opts.getString("url"))
            } catch (e: V8ResultUndefined) {
                null
            }

            val timeout = try {
                opts.getInteger("timeout") * 1000
            } catch (e: V8ResultUndefined) {
                0
            }

            val binaryResponse = try {
                opts.getBoolean("binary-response")
            } catch (e: V8ResultUndefined) {
                false
            }

            val method = try {
                opts.getString("method")
            } catch (e: V8ResultUndefined) {
                "GET"
            }

            val body = try {
                opts.getString("body")
            } catch (e: V8ResultUndefined) {
                null
            }

            val headers = try {
                opts.getObject("headers")
            } catch (e: V8ResultUndefined) {
                null
            }

            val userAgent = try {
                opts.getString("user-agent")
            } catch (e: V8ResultUndefined) {
                null
            }

            val insecure = try {
                opts.getBoolean("insecure")
            } catch (e: V8ResultUndefined) {
                false
            }

            val socket = try {
                opts.getString("socket")
            } catch (e: V8ResultUndefined) {
                null
            }

            opts.release()

            if (url != null) {
                val conn = url.openConnection() as HttpURLConnection

                conn.allowUserInteraction = false
                conn.requestMethod = method
                conn.readTimeout = timeout
                conn.connectTimeout = timeout

                if (userAgent != null) {
                    conn.setRequestProperty("User-Agent", userAgent)
                }

                if (headers != null) {
                    for (key in headers.keys) {
                        val value = headers.getString(key)
                        conn.setRequestProperty(key, value)
                    }
                }

                if (body != null) {
                    val ba = body.toByteArray()
                    conn.setRequestProperty("Content-Length", ba.size.toString())
                    conn.doInput = true;
                    conn.doOutput = true;
                    conn.useCaches = false;

                    val os = conn.outputStream
                    os.write(body.toByteArray())
                    os.close()
                }

                try {
                    conn.connect()

                    val result = V8Object(vm)

                    val responseBytes = conn.inputStream.readBytes()
                    val responseCode = conn.responseCode
                    val responseHeaders = V8Object(vm)

                    for (entry in conn.headerFields.entries) {
                        val values = StringBuilder()
                        for (value in entry.value) {
                            values.append(value, ",")
                        }
                        if (entry.key != null) {
                            responseHeaders.add(entry.key, values.toString())
                        }
                    }

                    result.add("status", responseCode)
                    result.add("headers", responseHeaders)

                    if (binaryResponse) {
                        result.add("body", V8ArrayBuffer(vm, ByteBuffer.wrap(responseBytes)))
                    } else {
                        result.add("body", String(responseBytes))
                    }

                    return@JavaCallback result
                } catch (e: Exception) {
                    val result = V8Object(vm)
                    result.add("error", e.message)
                    return@JavaCallback result
                }
            } else {
                return@JavaCallback V8.getUndefined()
            }
        } else {
            return@JavaCallback V8.getUndefined()
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
            return@JavaCallback V8.getUndefined()
        }
    }

    private val loadedLibs = mutableSetOf<String>()

    private val amblyImportScript = JavaCallback { receiver, parameters ->
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
        return@JavaCallback V8.getUndefined()
    }

    private val repletePrintFn = JavaCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val msg = parameters.get(0)

            if (!suppressPrinting) {
                runOnUiThread {
                    adapter!!.update(Item(markString(msg.toString()), ItemType.OUTPUT))
                }
            }

            if (msg is Releasable) {
                msg.release()
            }
        }
        return@JavaCallback V8.getUndefined()
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

    private fun addWords(words: ArrayList<String>) {
        words.forEach { word ->
            UserDictionary.Words.addWord(this, word, 255, null, Locale.getDefault())
        }
    }

    private val repleteWriteStdout = JavaCallback { receiver, params ->
        if (params.length() > 0) {
            val s = params.get(0)
            System.out.printf(s.toString())
            if (s is Releasable) {
                s.release()
            }
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStdout = JavaCallback { receiver, params ->
        System.out.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteWriteStderr = JavaCallback { receiver, params ->
        if (params.length() > 0) {
            val s = params.get(0)
            System.err.printf(s.toString())
            if (s is Releasable) {
                s.release()
            }
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStderr = JavaCallback { receiver, params ->
        System.err.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteIsDirectory = JavaCallback { receiver, params ->
        if (params.length() > 0) {
            val path = params.get(0) as V8Value
            val ret = Files.isDirectory(Paths.get(path.toString()))
            path.release()
            return@JavaCallback ret
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteListFiles = JavaCallback { receiver, params ->
        if (params.length() > 0) {
            val path = params.get(0) as V8Value
            val ret = V8Array(vm)

            Files.list(Paths.get(path.toString())).forEach { p -> ret.push(p.toString()) }

            path.release()

            return@JavaCallback ret
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val openWriteFiles = mutableMapOf<String, OutputStreamWriter>()

    private val repleteFileWriterOpen = JavaCallback { receiver, params ->
        if (params.length() == 3) {
            val path = params.getString(0)
            val append = params.getBoolean(1)
            val encoding = params.getString(2)
            val out =
                openFileOutput(path, if (append) Context.MODE_APPEND else Context.MODE_PRIVATE).writer(Charsets.UTF_8)

            openWriteFiles[path] = out

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileWriterWrite = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val content = params.getString(1)

            try {
                openWriteFiles[path]!!.write(content)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 2 arguments"
        }
    }

    private val repleteFileWriterFlush = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openWriteFiles[path]!!.flush()
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val repleteFileWriterClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openWriteFiles[path]!!.close()
                openWriteFiles.remove(path)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val openReadFiles = mutableMapOf<String, InputStreamReader>()

    private val repleteFileReaderOpen = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val encoding = params.getString(1)
            val out = openFileInput(path).reader(Charsets.UTF_8)

            openReadFiles[path] = out

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileReaderRead = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val content = openReadFiles[path]!!.read()

            if (content == -1) {
                return@JavaCallback V8Array(vm).push(V8.getUndefined()).push(V8.getUndefined())
            } else {
                return@JavaCallback V8Array(vm).push(content.toChar().toString()).push(V8.getUndefined())
            }
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteFileReaderClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            openReadFiles[path]!!.close()
            openReadFiles.remove(path)

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private var selectedPosition = -1
    private var selectedView: View? = null

    private fun isMacro(s: String): Boolean {
        val _s = s.trimStart()
        return _s.startsWith("(defmacro") || _s.startsWith("(defmacfn")
    }

    private fun chivorcamReferred(): Boolean {
        return vm.getObject("replete").getObject("repl").executeBooleanFunction("chivorcam_referred", V8Array(vm))
    }

    private var consentedToChivorcam = false
    private var suppressPrinting = false

    private fun defmacroCalled(s: String) {
        if (consentedToChivorcam) {
            suppressPrinting = true
            eval("(require '[chivorcam.core :refer [defmacro defmacfn]])", true)
            suppressPrinting = false
            eval(s, true)
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
                eval("(require '[chivorcam.core :refer [defmacro defmacfn]])", true)
                suppressPrinting = false
                eval(s, true)
            }
            builder.setNegativeButton(
                "Cancel"
            ) { dialog, id ->
                dialog.cancel()
            }
            builder.show()
        }
    }

    private fun eval(input: String, mainThread: Boolean = false) {
        val s = """replete.repl.read_eval_print(`${input.replace("\"", "\\\"")}`);"""
        if (mainThread) {
            vm.executeScript(s)
        } else {
            ExecuteScriptTask(vm).execute(s)
        }
    }

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
            adapter!!.update(Item(SpannableString(input), ItemType.INPUT))

            try {
                if (isMacro(input) && !chivorcamReferred()) {
                    defmacroCalled(input)
                } else {
                    eval(input)
                }
            } catch (e: Exception) {
                adapter!!.update(Item(SpannableString(e.toString()), ItemType.ERROR))
            }

        }

        adapter!!.update(
            Item(
                SpannableString(
                    "\nClojureScript ${getClojureScriptVersion()}\n" +
                            "    Docs: (doc function-name)\n" +
                            "          (find-doc \"part-of-name\")\n" +
                            "  Source: (source function-name)\n" +
                            " Results: Stored in *1, *2, *3,\n" +
                            "          an exception in *e\n"
                )
                , ItemType.INPUT
            )
        )

        vm.registerJavaMethod(repleteLoad, "REPLETE_LOAD");
        vm.registerJavaMethod(repletePrintFn, "REPLETE_PRINT_FN");
        vm.registerJavaMethod(amblyImportScript, "AMBLY_IMPORT_SCRIPT");
        vm.registerJavaMethod(repleteHighResTimer, "REPLETE_HIGH_RES_TIMER");
        vm.registerJavaMethod(repleteRequest, "REPLETE_REQUEST");

        vm.registerJavaMethod(repleteWriteStdout, "REPLETE_RAW_WRITE_STDOUT");
        vm.registerJavaMethod(repleteFlushStdout, "REPLETE_RAW_FLUSH_STDOUT");

        vm.registerJavaMethod(repleteWriteStderr, "REPLETE_RAW_WRITE_STDERR");
        vm.registerJavaMethod(repleteFlushStderr, "REPLETE_RAW_FLUSH_STDERR");

        vm.registerJavaMethod(repleteIsDirectory, "REPLETE_IS_DIRECTORY");
        vm.registerJavaMethod(repleteListFiles, "REPLETE_LIST_FILES");

        vm.registerJavaMethod(repleteFileReaderOpen, "REPLETE_FILE_READER_OPEN");
        vm.registerJavaMethod(repleteFileReaderRead, "REPLETE_FILE_READER_READ");
        vm.registerJavaMethod(repleteFileReaderClose, "REPLETE_FILE_READER_CLOSE");

        vm.registerJavaMethod(repleteFileWriterOpen, "REPLETE_FILE_WRITER_OPEN");
        vm.registerJavaMethod(repleteFileWriterWrite, "REPLETE_FILE_WRITER_WRITE");
        vm.registerJavaMethod(repleteFileWriterFlush, "REPLETE_FILE_WRITER_FLUSH");
        vm.registerJavaMethod(repleteFileWriterClose, "REPLETE_FILE_WRITER_CLOSE");

        vm.registerJavaMethod(repleteSetTimeout, "setTimeout");
        vm.registerJavaMethod(repleteCancelTimeout, "clearTimeout");

        vm.registerJavaMethod(repleteSetInterval, "setInterval");
        vm.registerJavaMethod(repleteCancelInterval, "clearInterval");

        BootstrapTask(
            vm,
            adapter!!,
            { result: BootstrapTaskResult.Result ->
                isVMLoaded = true
//                addWords(result.words)
            },
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

open class BootstrapTaskResult() {
    class Error(val error: V8ScriptExecutionException) : BootstrapTaskResult()
    class Result(val words: ArrayList<String>) : BootstrapTaskResult()
}

class BootstrapTask(
    val vm: V8,
    val adapter: HistoryAdapter,
    val onVMLoaded: (BootstrapTaskResult.Result) -> Unit,
    val bundleGetContents: (String) -> String
) :
    AsyncTask<Unit, Unit, BootstrapTaskResult>() {

    override fun onPreExecute() {
        vm.locker.release()
    }

    override fun doInBackground(vararg params: Unit?): BootstrapTaskResult {

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
            vm.executeScript("goog.require('replete.core');")
            vm.executeScript("replete.repl.setup_cljs_user();")
            vm.executeScript("replete.repl.init_app_env({'debug-build': false, 'target-simulator': false, 'user-interface-idiom': 'iPhone'});")
            vm.executeScript("cljs.core.system_time = REPLETE_HIGH_RES_TIMER;")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("var window = global;")

            val words = getAllVars()

            vm.locker.release()

            return BootstrapTaskResult.Result(words)
        } catch (e: V8ScriptExecutionException) {
            if (vm.locker.hasLock()) {
                vm.locker.release()
            }
            return BootstrapTaskResult.Error(e)
        }
    }

    private fun getAllVars(): ArrayList<String> {
//        val vars = vm.getObject("replete").getObject("repl").executeArrayFunction("all_vars", V8Array(vm))
        val words = arrayListOf<String>()

//        for (idx in 0 until vars.length()) {
//            words.add(vars[idx] as String)
//        }

//        vars.release()

        return words
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun onPostExecute(result: BootstrapTaskResult) {
        vm.locker.acquire()
        when (result) {
            is BootstrapTaskResult.Error -> {
                val baos = ByteArrayOutputStream()
                result.error.printStackTrace(PrintStream(baos, true, "UTF-8"))
                adapter.update(Item(SpannableString(String(baos.toByteArray(), UTF_8)), ItemType.ERROR))
            }
            is BootstrapTaskResult.Result -> onVMLoaded(result)
        }
    }
}
