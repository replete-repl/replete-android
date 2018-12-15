package replete

import android.graphics.Color
import android.graphics.Typeface
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.eclipsesource.v8.*

fun markString(s: String): String {
    return s.replace("\u001B\\[".toRegex(), "")
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val vm = V8.createV8Runtime()

        val inputField = findViewById<TextInputEditText>(R.id.input)
        val outputView = findViewById<LinearLayout>(R.id.scroll_view_layout)
        val evalButton = findViewById<Button>(R.id.eval_button)

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

        val loadingTextView = TextView(this)
        loadingTextView.textSize = 14f
        loadingTextView.typeface = Typeface.MONOSPACE

        evalButton.isEnabled = false

        evalButton.setOnClickListener { v ->
            val input = inputField.text.toString()
            val inputTextView = TextView(this)


            inputTextView.textSize = 14f
            inputTextView.typeface = Typeface.MONOSPACE
            inputTextView.text = input
            inputTextView.setTextColor(Color.DKGRAY)

            outputView.addView(inputTextView)

            try {
                vm.executeScript("replete.repl.read_eval_print(\"$input\");")
            } catch (e: Exception) {
                val outputTextView = TextView(this)
                outputTextView.textSize = 14f
                outputTextView.typeface = Typeface.MONOSPACE
                outputTextView.setTextColor(Color.parseColor("#ff0000"))
                outputTextView.text = e.toString()
                outputView.addView(outputTextView)
            }

        }

        val REPLETE_PRINT_FN = JavaVoidCallback { receiver, parameters ->
            if (parameters.length() > 0) {
                val arg1 = parameters.get(0)

                val outputTextView = TextView(this)

                outputTextView.setTextColor(Color.BLACK)
                outputTextView.text = markString(arg1.toString())
                outputTextView.textSize = 14f
                outputTextView.typeface = Typeface.MONOSPACE

                outputView.addView(outputTextView)

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

            loadingTextView.setTextColor(Color.parseColor("#000000"))
            loadingTextView.text = "\nClojureScript 1.10.439\n" +
                    "    Docs: (doc function-name)\n" +
                    "          (find-doc \"part-of-name\")\n" +
                    "  Source: (source function-name)\n" +
                    " Results: Stored in *1, *2, *3,\n" +
                    "          an exception in *e\n"
        } catch (e: Exception) {
            loadingTextView.setTextColor(Color.parseColor("#ff0000"))
            loadingTextView.text = e.toString()
        }

        outputView.addView(loadingTextView)
    }
}
