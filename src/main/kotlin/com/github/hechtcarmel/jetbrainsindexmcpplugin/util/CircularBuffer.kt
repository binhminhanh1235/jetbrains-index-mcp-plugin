package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

class CircularBuffer<T>(private val capacity: Int) {
    private val elements = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0

    val size: Int
        get() = count

    val isEmpty: Boolean
        get() = count == 0

    val isNotEmpty: Boolean
        get() = count > 0

    fun add(element: T) {
        if (capacity == 0) return
        elements[tail] = element
        tail = (tail + 1) % capacity
        if (count < capacity) {
            count++
        } else {
            head = (head + 1) % capacity
        }
    }

    fun removeFirst(): T {
        if (count == 0) throw NoSuchElementException()
        val element = elements[head]
        elements[head] = null
        head = (head + 1) % capacity
        count--
        @Suppress("UNCHECKED_CAST")
        return element as T
    }

    fun toList(): List<T> {
        if (count == 0) return emptyList()
        val result = ArrayList<T>(count)
        var i = head
        for (j in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            result.add(elements[i] as T)
            i = (i + 1) % capacity
        }
        return result
    }

    fun clear() {
        elements.fill(null)
        head = 0
        tail = 0
        count = 0
    }

    fun firstOrNull(predicate: (T) -> Boolean): T? {
        var i = head
        for (j in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            val el = elements[i] as T
            if (predicate(el)) return el
            i = (i + 1) % capacity
        }
        return null
    }

    fun filter(predicate: (T) -> Boolean): List<T> {
        val result = ArrayList<T>()
        var i = head
        for (j in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            val el = elements[i] as T
            if (predicate(el)) result.add(el)
            i = (i + 1) % capacity
        }
        return result
    }

    fun <R> map(transform: (T) -> R): List<R> {
        val result = ArrayList<R>(count)
        var i = head
        for (j in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            val el = elements[i] as T
            result.add(transform(el))
            i = (i + 1) % capacity
        }
        return result
    }
}
