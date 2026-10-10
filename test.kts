import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object ReflectionCache {
    private val NULL_SENTINEL: Method = ReflectionCache::class.java.getDeclaredMethod("nullSentinel")

    @Suppress("unused")
    private fun nullSentinel() {}

    private class MethodSignature(val name: String, val paramTypes: Array<out Class<*>>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MethodSignature
            if (name != other.name) return false
            return paramTypes.contentEquals(other.paramTypes)
        }
        override fun hashCode(): Int {
            return 31 * name.hashCode() + paramTypes.contentHashCode()
        }
    }

    private val cache = object : ClassValue<ConcurrentHashMap<Any, Method>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<Any, Method> = ConcurrentHashMap()
    }

    fun getMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val key = if (paramTypes.isEmpty()) name else MethodSignature(name, paramTypes)
        var method = cache.get(clazz)[key]
        if (method == null) {
            method = try {
                clazz.getMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                NULL_SENTINEL
            } catch (_: SecurityException) {
                NULL_SENTINEL
            }
            cache.get(clazz)[key] = method!!
        }
        return if (method === NULL_SENTINEL) null else method
    }
}
println("Success")
