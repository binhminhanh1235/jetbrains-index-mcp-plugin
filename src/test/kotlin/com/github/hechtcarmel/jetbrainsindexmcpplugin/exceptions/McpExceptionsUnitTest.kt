package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import junit.framework.TestCase

class McpExceptionsUnitTest : TestCase() {

    // ParseErrorException tests

    fun testParseErrorException() {
        val exception = ParseErrorException("Invalid JSON syntax")

        assertEquals("Invalid JSON syntax", exception.message)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, exception.errorCode)
        assertEquals(-32700, exception.errorCode)
    }

    // InvalidRequestException tests

    fun testInvalidRequestException() {
        val exception = InvalidRequestException("Missing required field 'id'")

        assertEquals("Missing required field 'id'", exception.message)
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, exception.errorCode)
        assertEquals(-32600, exception.errorCode)
    }

    // MethodNotFoundException tests

    fun testMethodNotFoundException() {
        val exception = MethodNotFoundException("unknown/method")

        assertEquals("Method not found: unknown/method", exception.message)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, exception.errorCode)
        assertEquals(-32601, exception.errorCode)
    }

    fun testMethodNotFoundExceptionFormatsMessage() {
        val exception = MethodNotFoundException("tools/execute")

        assertTrue(exception.message!!.contains("tools/execute"))
        assertTrue(exception.message!!.startsWith("Method not found:"))
    }

    // InvalidParamsException tests

    fun testInvalidParamsException() {
        val exception = InvalidParamsException("Parameter 'file' is required")

        assertEquals("Parameter 'file' is required", exception.message)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, exception.errorCode)
        assertEquals(-32602, exception.errorCode)
    }

    // InternalErrorException tests

    fun testInternalErrorException() {
        val exception = InternalErrorException("Unexpected null pointer")

        assertEquals("Unexpected null pointer", exception.message)
        assertEquals(JsonRpcErrorCodes.INTERNAL_ERROR, exception.errorCode)
        assertEquals(-32603, exception.errorCode)
    }

    // IndexNotReadyException tests

    fun testIndexNotReadyException() {
        val exception = IndexNotReadyException("IDE is currently indexing")

        assertEquals("IDE is currently indexing", exception.message)
        assertEquals(JsonRpcErrorCodes.INDEX_NOT_READY, exception.errorCode)
        assertEquals(-32001, exception.errorCode)
    }

    // FileNotFoundException tests

    fun testFileNotFoundException() {
        val exception = FileNotFoundException("src/Missing.kt")

        assertEquals("File not found: src/Missing.kt", exception.message)
        assertEquals(JsonRpcErrorCodes.FILE_NOT_FOUND, exception.errorCode)
        assertEquals(-32002, exception.errorCode)
    }

    fun testFileNotFoundExceptionFormatsPath() {
        val exception = FileNotFoundException("/absolute/path/to/file.java")

        assertTrue(exception.message!!.contains("/absolute/path/to/file.java"))
        assertTrue(exception.message!!.startsWith("File not found:"))
    }

    // SymbolNotFoundException tests

    fun testSymbolNotFoundException() {
        val exception = SymbolNotFoundException("Could not resolve symbol 'MyClass'")

        assertEquals("Could not resolve symbol 'MyClass'", exception.message)
        assertEquals(JsonRpcErrorCodes.SYMBOL_NOT_FOUND, exception.errorCode)
        assertEquals(-32003, exception.errorCode)
    }

    // RefactoringConflictException tests

    fun testRefactoringConflictException() {
        val exception = RefactoringConflictException("Cannot rename: symbol has usages in read-only files")

        assertEquals("Cannot rename: symbol has usages in read-only files", exception.message)
        assertEquals(JsonRpcErrorCodes.REFACTORING_CONFLICT, exception.errorCode)
        assertEquals(-32004, exception.errorCode)
    }

    // McpException hierarchy tests

    fun testAllExceptionsExtendMcpException() {
        val exceptions: List<McpException> = listOf(
            ParseErrorException("test"),
            InvalidRequestException("test"),
            MethodNotFoundException("test"),
            InvalidParamsException("test"),
            InternalErrorException("test"),
            IndexNotReadyException("test"),
            FileNotFoundException("test"),
            SymbolNotFoundException("test"),
            RefactoringConflictException("test")
        )

        exceptions.forEach { exception ->
            assertTrue("${exception::class.simpleName} should be McpException", exception is McpException)
            assertTrue("${exception::class.simpleName} should be Exception", exception is Exception)
        }
    }

    fun testExceptionErrorCodesAreUnique() {
        val errorCodes = listOf(
            ParseErrorException("").errorCode,
            InvalidRequestException("").errorCode,
            MethodNotFoundException("").errorCode,
            InvalidParamsException("").errorCode,
            InternalErrorException("").errorCode,
            IndexNotReadyException("").errorCode,
            FileNotFoundException("").errorCode,
            SymbolNotFoundException("").errorCode,
            RefactoringConflictException("").errorCode
        )

        val uniqueCodes = errorCodes.toSet()
        assertEquals("All error codes should be unique", errorCodes.size, uniqueCodes.size)
    }

    fun testStandardJsonRpcErrorCodesAreNegative() {
        val standardCodes = listOf(
            JsonRpcErrorCodes.PARSE_ERROR,
            JsonRpcErrorCodes.INVALID_REQUEST,
            JsonRpcErrorCodes.METHOD_NOT_FOUND,
            JsonRpcErrorCodes.INVALID_PARAMS,
            JsonRpcErrorCodes.INTERNAL_ERROR
        )

        standardCodes.forEach { code ->
            assertTrue("Standard JSON-RPC error code $code should be negative", code < 0)
            assertTrue("Standard JSON-RPC error code $code should be in range -32768 to -32000",
                code in -32768..-32000)
        }
    }

    fun testCustomErrorCodesAreInCustomRange() {
        val customCodes = listOf(
            JsonRpcErrorCodes.INDEX_NOT_READY,
            JsonRpcErrorCodes.FILE_NOT_FOUND,
            JsonRpcErrorCodes.SYMBOL_NOT_FOUND,
            JsonRpcErrorCodes.REFACTORING_CONFLICT
        )

        customCodes.forEach { code ->
            assertTrue("Custom error code $code should be negative", code < 0)
            // Custom codes are in the -32001 to -32099 range (implementation-defined)
            assertTrue("Custom error code $code should be in custom range -32099 to -32001",
                code in -32099..-32001)
        }
    }
}
