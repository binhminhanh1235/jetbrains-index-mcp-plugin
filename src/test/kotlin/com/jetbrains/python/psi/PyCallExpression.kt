package com.jetbrains.python.psi

import com.jetbrains.python.psi.resolve.PyResolveContext

interface PyCallExpression : PyExpression {
    fun getCallee(): PyExpression?
    fun multiResolveCalleeFunction(resolveContext: PyResolveContext): List<PyElement>
}
