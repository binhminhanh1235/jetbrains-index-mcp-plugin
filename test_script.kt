import java.util.concurrent.ConcurrentHashMap

fun main() {
    val start = System.nanoTime()
    for (i in 0..1000000) {
        "com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch".substringAfterLast('.').lowercase()
    }
    val end = System.nanoTime()
    println("Time: ${(end - start) / 1000000} ms")
}
