package com.fikesfarm.Replete

import android.os.*
import com.eclipsesource.v8.*
import com.eclipsesource.v8.utils.V8Runnable
import java.io.*
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

data class InitFailedPayload(val message: String, val vm: V8)

class TimeoutThread(val callback: () -> Unit, val t: Long) : Thread() {
    var isTimeoutCanceled = false
    override fun run() {
        Thread.sleep(t)
        if (!isTimeoutCanceled) {
            callback()
        }
    }
}

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

class VMHandler(
    val mainLooper: Looper,
    val sendUIMessage: (Messages, Any?) -> Unit,
    val bundleGetContents: (String) -> String?,
    val toAbsolutePath: (String) -> File?
) : Handler(mainLooper) {
    var vm: V8? = null

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            Messages.INIT_ENV.value -> _initEnv(msg.obj as String)
            Messages.EVAL.value -> eval(msg.obj as String)
            Messages.SET_WIDTH.value -> setWidth(msg.obj as Double)
            Messages.CALL_FN.value -> callFn(msg.obj as V8Function)
            Messages.RELEASE_OBJ.value -> releaseObject(msg.obj as V8Object)
        }
    }

    private fun _initEnv(deviceType: String) {
        thread {
            vm = V8.createV8Runtime()
            populateEnv(vm!!)
            bootstrapEnv(vm!!, deviceType)
        }
    }

    private fun callFn(fn: V8Function) {
        fn.call(fn, V8Array(vm))
    }

    private fun releaseObject(obj: V8Object) {
        obj.release()
    }

    private fun setWidth(width: Double) {
        vm!!.getObject("replete").getObject("repl").executeFunction("set_width", V8Array(vm).push(width))
    }

    private fun bootstrapEnv(vm: V8, deviceType: String) {
        val deps_file_path = "main.js"
        val goog_base_path = "goog/base.js"

        try {
            vm.executeScript("var global = this;")

            vm.executeScript("CLOSURE_IMPORT_SCRIPT = function(src) { AMBLY_IMPORT_SCRIPT('goog/' + src); return true; }")

            val googBaseScript = bundleGetContents(goog_base_path)
            val depsScript = bundleGetContents(deps_file_path)
            if (googBaseScript != null) {
                vm.executeScript(googBaseScript)
                if (depsScript != null) {
                    vm.executeScript(depsScript)
                }
            }
            
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript("goog.isProvided_ = function(x) { return false; };")
            vm.executeScript(
                "goog.require__ = goog.require;\n" +
                "goog.require = (src, reload) => {\n" +
                "  if (reload === \"reload-all\") {\n" +
                "    goog.cljsReloadAll_ = true;\n" +
                "  }\n" +
                "  if (reload || goog.cljsReloadAll_) {\n" +
                "    if (goog.debugLoader_) {\n" +
                "      let path = goog.debugLoader_.getPathFromDeps_(src);\n" +
                "      goog.object.remove(goog.debugLoader_.written_, path);\n" +
                "      goog.object.remove(goog.debugLoader_.written_, goog.basePath + path);\n" +
                "    } else {\n" +
                "      let path = goog.object.get(goog.dependencies_.nameToPath, src);\n" +
                "      goog.object.remove(goog.dependencies_.visited, path);\n" +
                "      goog.object.remove(goog.dependencies_.written, path);\n" +
                "      goog.object.remove(goog.dependencies_.visited, goog.basePath + path);\n" +
                "    }\n" +
                "  }\n" +
                "  let ret = goog.require__(src);\n" +
                "  if (reload === \"reload-all\") {\n" +
                "    goog.cljsReloadAll_ = false;\n" +
                "  }\n" +
                "  if (goog.isInModuleLoader_()) {\n" +
                "    return goog.module.getInternal_(src);\n" +
                "  } else {\n" +
                "    return ret;\n" +
                "  }\n" +
                "};"
            )

            vm.executeScript("goog.provide('cljs.user');")
            vm.executeScript("goog.require('cljs.core');")
            vm.executeScript("goog.require('replete.repl');")
            vm.executeScript("replete.repl.setup_cljs_user();")
            vm.executeScript("replete.repl.init_app_env({'debug-build': false, 'target-simulator': false, 'user-interface-idiom': '$deviceType'});")
            vm.executeScript("cljs.core.system_time = REPLETE_HIGH_RES_TIMER;")
            vm.executeScript("cljs.core.set_print_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("cljs.core.set_print_err_fn_BANG_.call(null, REPLETE_PRINT_FN);")
            vm.executeScript("var window = global;")

            vm.locker.release()

            sendUIMessage(Messages.VM_LOADED, vm)
            sendUIMessage(Messages.UPDATE_WIDTH, null)
            sendUIMessage(Messages.ENABLE_EVAL, null)
        } catch (e: V8ScriptExecutionException) {
            vm.locker.release()
            val baos = ByteArrayOutputStream()
            e.printStackTrace(PrintStream(baos, true, "UTF-8"))
            sendUIMessage(
                Messages.INIT_FAILED,
                InitFailedPayload(
                    String(
                        baos.toByteArray(),
                        StandardCharsets.UTF_8
                    ), vm
                )
            )
        }
    }

    private fun populateEnv(vm: V8) {
        vm.registerJavaMethod(repleteLoad, "REPLETE_LOAD")
        vm.registerJavaMethod(repletePrintFn, "REPLETE_PRINT_FN")
        vm.registerJavaMethod(amblyImportScript, "AMBLY_IMPORT_SCRIPT")
        vm.registerJavaMethod(repleteHighResTimer, "REPLETE_HIGH_RES_TIMER")
        vm.registerJavaMethod(repleteRequest, "REPLETE_REQUEST")

        vm.registerJavaMethod(repleteWriteStdout, "REPLETE_RAW_WRITE_STDOUT")
        vm.registerJavaMethod(repleteFlushStdout, "REPLETE_RAW_FLUSH_STDOUT")
        vm.registerJavaMethod(repleteWriteStderr, "REPLETE_RAW_WRITE_STDERR")
        vm.registerJavaMethod(repleteFlushStderr, "REPLETE_RAW_FLUSH_STDERR")

        vm.registerJavaMethod(repleteIsDirectory, "REPLETE_IS_DIRECTORY")
        vm.registerJavaMethod(repleteListFiles, "REPLETE_LIST_FILES")
        vm.registerJavaMethod(repleteDeleteFile, "REPLETE_DELETE")
        vm.registerJavaMethod(repleteCopyFile, "REPLETE_COPY")
        vm.registerJavaMethod(repleteMakeParentDirectories, "REPLETE_MKDIRS")

        vm.registerJavaMethod(repleteFileReaderOpen, "REPLETE_FILE_READER_OPEN")
        vm.registerJavaMethod(repleteFileReaderRead, "REPLETE_FILE_READER_READ")
        vm.registerJavaMethod(repleteFileReaderClose, "REPLETE_FILE_READER_CLOSE")

        vm.registerJavaMethod(repleteFileWriterOpen, "REPLETE_FILE_WRITER_OPEN")
        vm.registerJavaMethod(repleteFileWriterWrite, "REPLETE_FILE_WRITER_WRITE")
        vm.registerJavaMethod(repleteFileWriterFlush, "REPLETE_FILE_WRITER_FLUSH")
        vm.registerJavaMethod(repleteFileWriterClose, "REPLETE_FILE_WRITER_CLOSE")

        vm.registerJavaMethod(repleteFileInputStreamOpen, "REPLETE_FILE_INPUT_STREAM_OPEN")
        vm.registerJavaMethod(repleteFileInputStreamRead, "REPLETE_FILE_INPUT_STREAM_READ")
        vm.registerJavaMethod(repleteFileInputStreamClose, "REPLETE_FILE_INPUT_STREAM_CLOSE")

        vm.registerJavaMethod(repleteFileOutputStreamOpen, "REPLETE_FILE_OUTPUT_STREAM_OPEN")
        vm.registerJavaMethod(repleteFileOutputStreamWrite, "REPLETE_FILE_OUTPUT_STREAM_WRITE")
        vm.registerJavaMethod(repleteFileOutputStreamFlush, "REPLETE_FILE_OUTPUT_STREAM_FLUSH")
        vm.registerJavaMethod(repleteFileOutputStreamClose, "REPLETE_FILE_OUTPUT_STREAM_CLOSE")

        vm.registerJavaMethod(repleteFStat, "REPLETE_FSTAT")
        vm.registerJavaMethod(repleteSleep, "REPLETE_SLEEP")

        vm.registerJavaMethod(repleteSetTimeout, "setTimeout")
        vm.registerJavaMethod(repleteCancelTimeout, "clearTimeout")

        vm.registerJavaMethod(repleteSetInterval, "setInterval")
        vm.registerJavaMethod(repleteCancelInterval, "clearInterval")
    }

    private fun eval(s: String) {
        vm!!.getObject("replete").getObject("repl").executeFunction("read_eval_print", V8Array(vm).push(s))
        sendUIMessage(Messages.ENABLE_EVAL, null)
        sendUIMessage(Messages.ENABLE_PRINTING, null)
    }

    private var intervalId: Long = 0
    private val intervals: MutableMap<Long, IntervalThread> = mutableMapOf()

    private fun setInterval(callback: V8Function, t: Long): Long {

        if (intervalId == 9007199254740991) {
            intervalId = 0;
        } else {
            ++intervalId;
        }

        val tt = IntervalThread(
            {
                this.sendMessage(this.obtainMessage(Messages.CALL_FN.value, callback))
            },
            { this.sendMessage(this.obtainMessage(Messages.RELEASE_OBJ.value, callback)) },
            t
        )
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
            val tid = setInterval(callback, timeout)

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

    private fun setTimeout(callback: V8Function, t: Long): Long {

        if (timeoutId == 9007199254740991) {
            timeoutId = 0;
        } else {
            ++timeoutId;
        }

        val tt =
            TimeoutThread({
                this.sendMessage(this.obtainMessage(Messages.CALL_FN.value, callback))
                this.sendMessage(this.obtainMessage(Messages.RELEASE_OBJ.value, callback))
            }, t)
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
            val tid = setTimeout(callback, timeout)

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
        if (parameters.length() == 1 && parameters.get(0) is V8Object) {
            val opts = parameters.getObject(0)

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

            val followRedirects = try {
                opts.getBoolean("follow-redirects")
            } catch (e: V8ResultUndefined) {
                false
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
                conn.instanceFollowRedirects = followRedirects

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
        if (parameters.length() == 1) {
            val path = parameters.getString(0)
            return@JavaCallback bundleGetContents(path)
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val loadedLibs = mutableSetOf<String>()

    private val amblyImportScript = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            var path = parameters.getString(0)

            if (!loadedLibs.contains(path)) {

                if (path.startsWith("goog/../")) {
                    path = path.substring(8, path.length)
                }

                val script = bundleGetContents(path)

                if (script != null) {
                    loadedLibs.add(path)
                    vm!!.executeScript(script)
                }
            }
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repletePrintFn = JavaCallback { receiver, parameters ->
        if (parameters.length() == 1) {
            val msg = parameters.getString(0)

            sendUIMessage(Messages.ADD_OUTPUT_ITEM, markString(msg))
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteWriteStdout = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val s = params.getString(0)
            System.out.write(s.toByteArray())
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStdout = JavaCallback { receiver, params ->
        System.out.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteWriteStderr = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val s = params.getString(0)
            System.err.write(s.toByteArray())
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteFlushStderr = JavaCallback { receiver, params ->
        System.err.flush()
        return@JavaCallback V8.getUndefined()
    }

    private val repleteIsDirectory = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = toAbsolutePath(params.getString(0))

            if (path != null) {
                return@JavaCallback path.isDirectory
            } else {
                return@JavaCallback V8.getUndefined()
            }

        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteListFiles = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = toAbsolutePath(params.getString(0))
            val ret = V8Array(vm)

            path?.list()?.forEach { p -> ret.push(p.toString()) }

            return@JavaCallback ret
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteDeleteFile = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                toAbsolutePath(path)?.delete()
            } catch (e: IOException) {
                sendUIMessage(Messages.ADD_ERROR_ITEM, e.toString())
            }

        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteCopyFile = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val fromPath = params.getString(0)
            val toPath = params.getString(1)
            val fromStream = toAbsolutePath(fromPath)?.inputStream()
            val toStream = toAbsolutePath(toPath)?.outputStream()

            if (fromStream != null && toStream != null) {
                try {
                    fromStream.copyTo(toStream)
                    fromStream.close()
                    toStream.close()
                } catch (e: IOException) {
                    fromStream.close()
                    toStream.close()
                    sendUIMessage(Messages.ADD_ERROR_ITEM, e.toString())
                }
            }
        }
        return@JavaCallback V8.getUndefined()
    }

    private val repleteMakeParentDirectories = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val absPath = toAbsolutePath(path)

            try {
                if (absPath != null && !absPath.exists()) {
                    absPath.mkdirs()
                }
            } catch (e: Exception) {
                sendUIMessage(Messages.ADD_ERROR_ITEM, e.toString())
            }

        }
        return@JavaCallback V8.getUndefined()
    }

    private val openOutputStreams = mutableMapOf<String, FileOutputStream>()

    private val repleteFileOutputStreamOpen = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val append = params.getBoolean(1)

            openOutputStreams[path] = FileOutputStream(toAbsolutePath(path), append)

            return@JavaCallback path
        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileOutputStreamWrite = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val bytesArray = params.getArray(1)

            try {
                val bytes = ByteArray(bytesArray.length())
                for (idx in 0 until bytes.size - 1) {
                    bytes[idx] = bytesArray[idx] as Byte
                }
                openOutputStreams[path]!!.write(bytes)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 2 arguments"
        }
    }

    private val repleteFileOutputStreamFlush = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openOutputStreams[path]!!.flush()
            } catch (e: Exception) {
                return@JavaCallback e.message
            }
            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val repleteFileOutputStreamClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            try {
                openOutputStreams[path]!!.close()
                openOutputStreams.remove(path)
            } catch (e: Exception) {
                return@JavaCallback e.message
            }

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback "This functions accepts 1 argument"
        }
    }

    private val repleteFStat = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val item = toAbsolutePath(path)
            val ret = V8Object(vm)

            if (item != null) {
                val itemType = if (item.isFile) "file" else if (item.isDirectory) "directory" else "unknown"
                ret.add("type", itemType)
                ret.add("modified", item.lastModified().toDouble())
            }

            return@JavaCallback ret
        } else {

        }
    }

    private val repleteSleep = JavaVoidCallback { receiver, params ->
        if (params.length() == 1 || params.length() == 2) {
            val ms = params.getDouble(0).toLong()
            val ns = if (params.length() == 1) 0 else params.getDouble(1).toInt()

            Thread.sleep(ms, ns)
        }
    }

    private val openWriteFiles = mutableMapOf<String, OutputStreamWriter>()

    private val repleteFileWriterOpen = JavaCallback { receiver, params ->
        if (params.length() == 3) {
            val path = params.getString(0)
            val append = params.getBoolean(1)
            val encoding = params.getString(2)

            openWriteFiles[path] =
                FileOutputStream(toAbsolutePath(path), append).writer(Charsets.UTF_8)
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

    private val openInputStreams = mutableMapOf<String, FileInputStream>()

    private val repleteFileInputStreamOpen = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val apath = toAbsolutePath(path)

            if (apath != null) {
                openInputStreams[path] = apath.inputStream()
                return@JavaCallback path
            } else {
                return@JavaCallback "0"
            }

        } else {
            return@JavaCallback "0"
        }
    }

    private val repleteFileInputStreamRead = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)
            val bytes = ByteArray(1024)
            val bytesWritten = openInputStreams[path]!!.read(bytes)

            if (bytesWritten == -1) {
                return@JavaCallback V8.getUndefined()
            } else {
                val ret = V8Array(vm)
                bytes.forEach { b -> ret.push(b) }
                return@JavaCallback ret
            }
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val repleteFileInputStreamClose = JavaCallback { receiver, params ->
        if (params.length() == 1) {
            val path = params.getString(0)

            openInputStreams[path]!!.close()
            openInputStreams.remove(path)

            return@JavaCallback V8.getUndefined()
        } else {
            return@JavaCallback V8.getUndefined()
        }
    }

    private val openReadFiles = mutableMapOf<String, InputStreamReader>()

    private val repleteFileReaderOpen = JavaCallback { receiver, params ->
        if (params.length() == 2) {
            val path = params.getString(0)
            val apath = toAbsolutePath(path)
            val encoding = params.getString(1)

            if (apath != null) {
                openReadFiles[path] = apath.inputStream().reader(Charsets.UTF_8)
                return@JavaCallback path
            } else {
                return@JavaCallback "0"
            }
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
}
