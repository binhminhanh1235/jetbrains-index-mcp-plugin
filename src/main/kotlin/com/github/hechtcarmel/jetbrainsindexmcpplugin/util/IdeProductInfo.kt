package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.ApplicationInfo

/**
 * Provides IDE-specific information for server configuration.
 *
 * Each JetBrains IDE gets a unique server name and default port to avoid conflicts
 * when multiple IDEs are running simultaneously.
 *
 * Uses the public ApplicationInfo API to detect the current IDE product via
 * the build's product code (e.g., "IC", "IU", "PY", "WS").
 */
object IdeProductInfo {

    /**
     * IDE product types with their server names and default ports.
     *
     * Product codes reference:
     * - IC = IntelliJ IDEA Community
     * - IU = IntelliJ IDEA Ultimate
     * - IE = IntelliJ IDEA Educational
     * - PC = PyCharm Community
     * - PY = PyCharm Professional
     * - PE = PyCharm Educational
     * - WS = WebStorm
     * - GO = GoLand
     * - PS = PhpStorm
     * - RM = RubyMine
     * - CL = CLion
     * - RR = RustRover
     * - DB = DataGrip
     * - AI = Android Studio
     * - QA = Aqua
     * - DS = DataSpell
     * - RD = Rider
     */
    enum class IdeProduct(
        val productCodes: Set<String>,
        val serverName: String,
        val defaultPort: Int,
        val displayName: String
    ) {
        INTELLIJ_IDEA(setOf("IC", "IU", "IE"), "intellij-index", 29170, "IntelliJ IDEA"),
        ANDROID_STUDIO(setOf("AI"), "android-studio-index", 29171, "Android Studio"),
        PYCHARM(setOf("PC", "PY", "PE"), "pycharm-index", 29172, "PyCharm"),
        WEBSTORM(setOf("WS"), "webstorm-index", 29173, "WebStorm"),
        GOLAND(setOf("GO"), "goland-index", 29174, "GoLand"),
        PHPSTORM(setOf("PS"), "phpstorm-index", 29175, "PhpStorm"),
        RUBYMINE(setOf("RM"), "rubymine-index", 29176, "RubyMine"),
        CLION(setOf("CL"), "clion-index", 29177, "CLion"),
        RUSTROVER(setOf("RR"), "rustrover-index", 29178, "RustRover"),
        DATAGRIP(setOf("DB"), "datagrip-index", 29179, "DataGrip"),
        AQUA(setOf("QA"), "aqua-index", 29180, "Aqua"),
        DATASPELL(setOf("DS"), "dataspell-index", 29181, "DataSpell"),
        RIDER(setOf("RD"), "rider-index", 29182, "Rider"),
        UNKNOWN(emptySet(), "jetbrains-index", 29199, "JetBrains IDE");

        companion object {
            /**
             * Find the IdeProduct matching the given product code.
             */
            fun fromProductCode(code: String): IdeProduct {
                return entries.find { code in it.productCodes } ?: UNKNOWN
            }
        }
    }

    // Cached product detection (IDE doesn't change during runtime)
    private val cachedProduct: IdeProduct by lazy {
        detectIdeProductInternal()
    }

    /**
     * Detects the current IDE product using ApplicationInfo.
     * Uses the build's product code which is part of the public API.
     */
    private fun detectIdeProductInternal(): IdeProduct {
        return try {
            val productCode = ApplicationInfo.getInstance().build.productCode
            IdeProduct.fromProductCode(productCode)
        } catch (e: Exception) {
            IdeProduct.UNKNOWN
        }
    }

    /**
     * Gets the detected IDE product.
     */
    fun detectIdeProduct(): IdeProduct = cachedProduct

    /**
     * Gets the IDE-specific server name (e.g., "intellij-index", "pycharm-index").
     */
    fun getServerName(): String = cachedProduct.serverName

    /**
     * Gets the IDE-specific default port.
     */
    fun getDefaultPort(): Int = cachedProduct.defaultPort

    /**
     * Gets the IDE display name.
     */
    fun getIdeDisplayName(): String = cachedProduct.displayName

    /**
     * Gets the raw product code from ApplicationInfo.
     */
    fun getProductCode(): String {
        return try {
            ApplicationInfo.getInstance().build.productCode
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
