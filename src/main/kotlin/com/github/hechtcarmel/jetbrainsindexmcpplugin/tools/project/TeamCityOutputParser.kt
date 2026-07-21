package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary

object TeamCityOutputParser {
    private val MESSAGE_PATTERN = Regex("##teamcity\\[([a-zA-Z]+)(.*?)(?<!\\|)]")

    fun parse(rawOutput: String): Pair<TestSummary, List<TestResultInfo>> {
        val testResults = mutableListOf<TestResultInfo>()
        var passed = 0
        var failed = 0
        var ignored = 0
        var total = 0

        class ActiveTest(val name: String, val locationHint: String?) {
            var status = "PASSED"
            var errorMessage: String? = null
            var errorDetails: String? = null
            var durationMs: Long? = null
        }

        val activeTests = mutableMapOf<String, ActiveTest>()

        MESSAGE_PATTERN.findAll(rawOutput).forEach { matchResult ->
            val messageName = matchResult.groupValues[1]
            val attrsStr = matchResult.groupValues[2]

            val attrs = parseAttributes(attrsStr)
            val testName = attrs["name"] ?: return@forEach
            val nodeId = attrs["id"] ?: attrs["nodeId"] ?: testName

            when (messageName) {
                "testStarted" -> {
                    activeTests[nodeId] = ActiveTest(testName, unescape(attrs["locationHint"]))
                }
                "testFailed" -> {
                    val test = activeTests[nodeId]
                    if (test != null) {
                        test.status = "FAILED"
                        test.errorMessage = unescape(attrs["message"])
                        test.errorDetails = unescape(attrs["details"])
                    }
                }
                "testIgnored" -> {
                    val test = activeTests[nodeId]
                    if (test != null) {
                        test.status = "IGNORED"
                        test.errorMessage = unescape(attrs["message"])
                    } else {
                        val newTest = ActiveTest(testName, unescape(attrs["locationHint"]))
                        newTest.status = "IGNORED"
                        newTest.errorMessage = unescape(attrs["message"])
                        activeTests[nodeId] = newTest
                    }
                }
                "testFinished" -> {
                    val test = activeTests.remove(nodeId)
                    if (test != null) {
                        test.durationMs = unescape(attrs["duration"])?.toLongOrNull()

                        total++
                        when (test.status) {
                            "PASSED" -> passed++
                            "FAILED" -> failed++
                            "IGNORED" -> ignored++
                        }

                        testResults.add(TestResultInfo(
                            name = test.name,
                            suite = null,
                            status = test.status,
                            durationMs = test.durationMs,
                            errorMessage = test.errorMessage,
                            stacktrace = test.errorDetails,
                            file = test.locationHint,
                            line = null
                        ))
                    }
                }
            }
        }

        val summary = TestSummary(
            total = total,
            passed = passed,
            failed = failed,
            ignored = ignored,
            runConfigName = "TeamCity Output"
        )

        return summary to testResults
    }

    private fun parseAttributes(str: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        var i = 0
        while (i < str.length) {
            while (i < str.length && str[i].isWhitespace()) i++
            if (i >= str.length) break

            val startKey = i
            while (i < str.length && str[i] != '=') i++
            if (i >= str.length) break
            val key = str.substring(startKey, i).trim()
            i++ // skip '='

            while (i < str.length && str[i] != '\'') i++
            if (i >= str.length) break
            i++ // skip quote

            val sb = StringBuilder()
            while (i < str.length) {
                if (str[i] == '|') {
                    sb.append('|')
                    i++
                    if (i < str.length) {
                        sb.append(str[i])
                    }
                } else if (str[i] == '\'') {
                    break
                } else {
                    sb.append(str[i])
                }
                i++
            }
            attrs[key] = sb.toString()
            i++ // skip closing quote
        }
        return attrs
    }

    fun unescape(value: String?): String? {
        if (value == null) return null
        var i = 0
        val sb = java.lang.StringBuilder()
        while (i < value.length) {
            if (value[i] == '|' && i + 1 < value.length) {
                val next = value[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    'x' -> {
                        // ignore TeamCity unicode unescaping logic for simplicity, or just:
                        sb.append("|x")
                    }
                    else -> sb.append(next) // handles |', ||, |[, |]
                }
                i += 2
            } else {
                sb.append(value[i])
                i++
            }
        }
        return sb.toString()
    }
}
