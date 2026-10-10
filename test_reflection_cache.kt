class MethodKey(val name: String, val paramTypes: Array<out Class<*>>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MethodKey
        if (name != other.name) return false
        if (!paramTypes.contentEquals(other.paramTypes)) return false
        return true
    }
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + paramTypes.contentHashCode()
        return result
    }
}
