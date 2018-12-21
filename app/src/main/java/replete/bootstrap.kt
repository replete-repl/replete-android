package replete

import android.support.v7.app.AppCompatActivity
import com.eclipsesource.v8.*


fun bootstrap(ctx: AppCompatActivity, vm: V8, adapter: HistoryAdapter) {

    fun bundleGetContents(path: String): String {
        return ctx.assets.open("out/$path").bufferedReader().readText()
    }

    fun getClojureScriptVersion(): String {
        val s = bundleGetContents("replete/bundle.js")
        return s.substring(29, s.length).takeWhile { c -> c != " ".toCharArray()[0] }
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


                vm.executeScript(bundleGetContents(path))
            }

            if (arg is Releasable) {
                arg.release()
            }
        }
    }

    val REPLETE_HIGH_RES_TIMER = JavaCallback { receiver, parameters ->
        return@JavaCallback System.nanoTime() / 1e6
    }

    val REPLETE_LOAD = JavaCallback { receiver, parameters ->
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

    var timeoutId: Long = 0
    val timeouts: MutableMap<Long, Runnable> = mutableMapOf()

    fun setTimeout(callback: () -> Unit, t: Long): Long {

        if (timeoutId == 9007199254740991) {
            timeoutId = 0;
        } else {
            ++timeoutId;
        }

        val runnable = Runnable { callback() }
        timeouts.set(timeoutId, runnable)
        android.os.Handler().postDelayed(runnable, t)

        return timeoutId
    }

    fun cancelTimeout(tid: Long) {
        if (timeouts.contains(tid)) {
            android.os.Handler().removeCallbacks(timeouts.get(tid))
            timeouts.remove(tid)
        }
    }

    val REPLETE_SET_TIMEOUT = JavaCallback { receiver, parameters ->
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

    val REPLETE_CANCEL_TIMEOUT = JavaVoidCallback { receiver, parameters ->
        if (parameters.length() > 0) {
            val arg1 = parameters.get(0)

            cancelTimeout(arg1 as Long)

            if (arg1 is Releasable) {
                arg1.release()
            }
        } else {
        }
    }

    vm.registerJavaMethod(REPLETE_LOAD, "REPLETE_LOAD");
    vm.registerJavaMethod(REPLETE_PRINT_FN, "REPLETE_PRINT_FN");
    vm.registerJavaMethod(AMBLY_IMPORT_SCRIPT, "AMBLY_IMPORT_SCRIPT");
    vm.registerJavaMethod(REPLETE_HIGH_RES_TIMER, "REPLETE_HIGH_RES_TIMER");
    vm.registerJavaMethod(REPLETE_SET_TIMEOUT, "setTimeout");
    vm.registerJavaMethod(REPLETE_CANCEL_TIMEOUT, "clearTimeout");

    try {
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

        adapter.update(
            Item(
                "\nClojureScript ${getClojureScriptVersion()}\n" +
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
