package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ParameterInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SignatureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class GetSignatureTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = true
    override val participatesInLifecycle: Boolean = true

    override val name = ToolNames.GET_SIGNATURE

    override val description = """
        Get the signature (parameters, return type) of a method, function, or class at a position.
        Useful when you need to know how to call a function without reading the entire file.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .lineAndColumn()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        return suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            var target: PsiNamedElement? = null
            var current: PsiElement? = element
            while (current != null) {
                if (current is PsiNamedElement) {
                    val className = current.javaClass.simpleName.lowercase()
                    if (className.contains("method") || className.contains("function") ||
                        className.contains("class") || className.contains("interface") ||
                        className.contains("property")) {
                        target = current
                        break
                    }
                }
                if (current.parent == current) break
                current = current.parent
            }

            if (target == null) {
                // Fallback to the resolved element if it's named
                if (element is PsiNamedElement) {
                    target = element
                } else {
                    return@suspendingReadAction createErrorResult("Could not find a method, function, or class at the specified position.")
                }
            }

            val targetFile = target.containingFile?.virtualFile
                ?: return@suspendingReadAction createErrorResult("Could not resolve containing file.")
            
            val language = target.language.id
            val name = target.name ?: "unknown"
            val kind = determineKind(target.javaClass.simpleName.lowercase())

            // Try reflection for PsiMethod (Java)
            var returnType: String? = null
            var modifiers: List<String>? = null
            val parameters = mutableListOf<ParameterInfo>()
            var containingClass: String? = null
            
            try {
                // Very basic reflection-based extraction for Java/Kotlin Methods
                val methods = target.javaClass.methods
                
                // Return type
                val retTypeMethod = methods.find { it.name == "getReturnType" }
                if (retTypeMethod != null) {
                    val typeObj = retTypeMethod.invoke(target)
                    if (typeObj != null) {
                        returnType = typeObj.javaClass.getMethod("getPresentableText").invoke(typeObj) as? String
                    }
                }

                // Parameters
                val paramListMethod = methods.find { it.name == "getParameterList" }
                if (paramListMethod != null) {
                    val paramListObj = paramListMethod.invoke(target)
                    if (paramListObj != null) {
                        val getParamsMethod = paramListObj.javaClass.getMethod("getParameters")
                        val paramsArray = getParamsMethod.invoke(paramListObj) as Array<*>
                        for (p in paramsArray) {
                            if (p != null) {
                                val pName = p.javaClass.getMethod("getName").invoke(p) as? String ?: "unknown"
                                val pTypeObj = p.javaClass.getMethod("getType").invoke(p)
                                val pType = if (pTypeObj != null) {
                                    pTypeObj.javaClass.getMethod("getPresentableText").invoke(pTypeObj) as? String
                                } else null
                                parameters.add(ParameterInfo(pName, pType, null))
                            }
                        }
                    }
                }

                // Containing class
                val containingClassMethod = methods.find { it.name == "getContainingClass" }
                if (containingClassMethod != null) {
                    val clsObj = containingClassMethod.invoke(target)
                    if (clsObj != null) {
                        containingClass = clsObj.javaClass.getMethod("getName").invoke(clsObj) as? String
                    }
                }

            } catch (e: Exception) {
                // Ignore reflection errors and fallback to raw text parsing
            }

            // Construct signature string from text as fallback
            val rawText = target.text
            val signature = rawText.split("{")[0].trim()

            createJsonResult(
                SignatureResult(
                    name = name,
                    signature = signature,
                    kind = kind,
                    returnType = returnType,
                    parameters = if (parameters.isEmpty()) null else parameters,
                    modifiers = modifiers,
                    containingClass = containingClass,
                    file = getRelativePath(project, targetFile),
                    line = arguments["line"]?.jsonPrimitive?.intOrNull ?: 1, // approximate
                    language = language
                )
            )
        }
    }

    private fun determineKind(className: String): String {
        return when {
            className.contains("method") -> "method"
            className.contains("function") -> "function"
            className.contains("class") -> "class"
            className.contains("interface") -> "interface"
            className.contains("property") -> "property"
            else -> "unknown"
        }
    }
}
