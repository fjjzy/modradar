package com.modradar.app.scan.apk

import com.modradar.app.scan.axml.AxmlNode
import com.modradar.app.scan.axml.AxmlParser

data class ComponentInfo(
    val type: String,
    val name: String,
    val exported: Boolean?,
    val permission: String?,
    val actions: List<String>,
)

data class ManifestInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val declaredPermissions: List<Pair<String, String?>>,
    val debuggable: Boolean,
    val allowBackup: Boolean?,
    val usesCleartextTraffic: Boolean?,
    val networkSecurityConfig: String?,
    val sharedUserId: String?,
    val applicationName: String?,
    val components: List<ComponentInfo>,
    val metaData: Map<String, String>,
) {
    val isXposedModule: Boolean
        get() = metaData["xposedmodule"]?.equals("true", ignoreCase = true) == true

    val xposedMinVersion: String? get() = metaData["xposedminversion"]
    val xposedDescription: String? get() = metaData["xposeddescription"]
    val xposedScope: String? get() = metaData["xposedscope"]

    val exportedComponentsWithoutPermission: List<ComponentInfo>
        get() = components.filter { it.exported == true && it.permission.isNullOrEmpty() }
}

/** 解析编译后的 AndroidManifest.xml（AXML）。 */
object ManifestAnalyzer {

    fun analyze(bytes: ByteArray): ManifestInfo? {
        val root = AxmlParser.parse(bytes) ?: return null
        if (root.name != "manifest") return null

        val app = root.child("application").firstOrNull()
        val sdk = root.child("uses-sdk").firstOrNull()

        val permissions = mutableListOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            for (n in root.child(tag)) n.attr("name")?.value?.let { permissions.add(it) }
        }

        val declared = root.child("permission").mapNotNull { n ->
            val name = n.attr("name")?.value ?: return@mapNotNull null
            name to n.attr("protectionLevel")?.value
        }

        val components = mutableListOf<ComponentInfo>()
        if (app != null) {
            for (type in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
                for (n in app.child(type)) {
                    val name = n.attr("name")?.value ?: continue
                    val actions = n.child("intent-filter")
                        .flatMap { it.child("action") }
                        .mapNotNull { it.attr("name")?.value }
                    components.add(
                        ComponentInfo(
                            type = type,
                            name = name,
                            exported = n.attr("exported")?.value?.toBooleanStrictOrNull(),
                            permission = n.attr("permission")?.value,
                            actions = actions,
                        )
                    )
                }
            }
        }

        val metaData = mutableMapOf<String, String>()
        if (app != null) {
            for (m in app.child("meta-data")) {
                val k = m.attr("name")?.value ?: continue
                val v = m.attr("value")?.value ?: m.attr("resource")?.value ?: ""
                metaData[k] = v
            }
        }

        return ManifestInfo(
            packageName = root.attr("package")?.value,
            versionName = root.attr("versionName")?.value,
            versionCode = root.attr("versionCode")?.value?.toLongOrNull(),
            minSdk = sdk?.attr("minSdkVersion")?.value?.toIntOrNull(),
            targetSdk = sdk?.attr("targetSdkVersion")?.value?.toIntOrNull(),
            permissions = permissions.distinct().sorted(),
            declaredPermissions = declared,
            debuggable = app?.attr("debuggable")?.value == "true",
            allowBackup = app?.attr("allowBackup")?.value?.toBooleanStrictOrNull(),
            usesCleartextTraffic = app?.attr("usesCleartextTraffic")?.value?.toBooleanStrictOrNull(),
            networkSecurityConfig = app?.attr("networkSecurityConfig")?.value,
            sharedUserId = root.attr("sharedUserId")?.value,
            applicationName = app?.attr("name")?.value,
            components = components,
            metaData = metaData,
        )
    }

    fun nodeNames(root: AxmlNode): List<String> = root.children.map { it.name }
}
