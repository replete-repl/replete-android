package replete

fun <T>time(msg: String, block: () -> T): T {
    val start = System.currentTimeMillis()
    val ret = block()
    val t = System.currentTimeMillis() - start
    println("====== $msg: $t")
    return ret
}
