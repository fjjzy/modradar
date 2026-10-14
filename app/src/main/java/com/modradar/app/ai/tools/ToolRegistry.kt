package com.modradar.app.ai.tools

import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.Stage
import com.modradar.app.task.TaskRepository
import com.modradar.app.task.stages.ChainArtifact
import com.modradar.app.task.stages.ExtractArtifact
import com.modradar.app.task.stages.ReportArtifact
import com.modradar.app.task.stages.ScoreArtifact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具注册表：一次定义，供内置 function calling / MCP Server 共用。
 *
 * 门控规则：调用前比对 [ToolSpec.requiredStages] 与任务已完成阶段；
 * 缺阶段则返回 [ToolResult.NotReady]，**不会**返回空结果。
 */
class ToolRegistry(
    private val repo: TaskRepository,
    private val store: ArtifactStore,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    val specs: List<ToolSpec> = buildList {
        add(listStagesTool())
        add(readArtifactTool())
        add(readManifestTool())
        add(listPermissionsTool())
        add(listComponentsTool())
        add(scanShellHintsTool())
        add(capabilityChainTool())
        add(scoreBreakdownTool())
        add(getReportTool())
        add(decompileClassTool())
    }

    val byName: Map<String, ToolSpec> = specs.associateBy { it.name }

    /** 供 function calling 使用的 OpenAI 兼容 tools 数组。 */
    fun toolsJson(): String = buildString {
        append("[")
        specs.forEachIndexed { i, s ->
            if (i > 0) append(",")
            append("""{"type":"function","function":{"name":"${s.name}","description":""")
            append(s.description.replace("\"", "'"))
            append("""","parameters":${s.parameters}}}""")
        }
        append("]")
    }

    suspend fun invoke(taskId: String, name: String, args: JsonObject): ToolResult {
        val spec = byName[name] ?: return ToolResult.Error("未知工具：$name")
        val done = repo.completedStages(taskId)
        val missing = spec.requiredStages - done
        if (missing.isNotEmpty()) {
            return ToolResult.NotReady(
                completed = Stage.order.filter { it in done }.map { it.id },
                required = spec.requiredStages.map { it.id },
                hint = "该工具需要 ${missing.joinToString("、") { "${it.id} ${it.label}" }} 完成；当前分析尚未跑到该阶段。",
            )
        }
        return runCatching { spec.handler(ToolInvocation(taskId, args)) }
            .getOrElse { ToolResult.Error(it.message ?: it::class.java.simpleName) }
    }

    // ---- 工具实现 ----

    private fun listStagesTool() = ToolSpec(
        name = "list_stages",
        description = "列出该任务 S1–S6 各阶段的完成状态，用于判断当前分析进度与数据边界。",
        parameters = obj(),
    ) {
        val cps = repo.checkpoints(it.taskId)
        val text = Stage.order.joinToString("\n") { s ->
            val cp = cps.firstOrNull { c -> c.stage == s.id }
            "${s.id} ${s.label}: ${cp?.state ?: "PENDING"}${cp?.detail?.let { d -> " ($d)" } ?: ""}"
        }
        ToolResult.Ok(text)
    }

    private fun readArtifactTool() = ToolSpec(
        name = "read_artifact",
        description = "读取某个阶段已提交的结构化产物 JSON。参数 stage 取 S1..S6。",
        parameters = obj("stage" to strProp("阶段编号，如 S2")),
    ) { inv ->
        val stage = Stage.byId(inv.args["stage"]?.jsonPrimitive?.content ?: "")
            ?: return@ToolSpec ToolResult.Error("非法阶段编号")
        val text = store.readText(inv.taskId, "${stage.id}.json")
            ?: return@ToolSpec ToolResult.NotReady(
                completed = emptyList(),
                required = listOf(stage.id),
                hint = "${stage.id} ${stage.label} 尚未产出产物。",
            )
        ToolResult.Ok(text.take(6000))
    }

    private fun readManifestTool() = ToolSpec(
        name = "read_manifest",
        description = "读取已解析的 AndroidManifest 摘要：包名、版本、SDK、权限、组件暴露、meta-data。",
        parameters = obj(),
        requiredStages = setOf(Stage.EXTRACT),
    ) { inv ->
        val art = extractArtifact(inv.taskId)
        val m = art?.manifest
            ?: return@ToolSpec ToolResult.Ok("清单解析失败：${art?.parseError ?: "无数据"}")
        ToolResult.Ok(
            """
            包名: ${m.packageName}
            版本: ${m.versionName} (${m.versionCode})
            minSdk/targetSdk: ${m.minSdk} / ${m.targetSdk}
            debuggable: ${m.debuggable}
            allowBackup: ${m.allowBackup}
            usesCleartextTraffic: ${m.usesCleartextTraffic}
            sharedUserId: ${m.sharedUserId}
            组件数: ${m.componentCount}
            无权限保护的 exported 组件: ${m.exportedWithoutPermission.joinToString(", ").ifEmpty { "无" }}
            meta-data: ${m.metaData.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(1000)}
            """.trimIndent()
        )
    }

    private fun listPermissionsTool() = ToolSpec(
        name = "list_permissions",
        description = "列出应用声明的权限。",
        parameters = obj(),
        requiredStages = setOf(Stage.EXTRACT),
    ) { inv ->
        val perms = extractArtifact(inv.taskId)?.manifest?.permissions.orEmpty()
        ToolResult.Ok(if (perms.isEmpty()) "未声明权限" else perms.joinToString("\n"))
    }

    private fun listComponentsTool() = ToolSpec(
        name = "list_components",
        description = "列出无权限保护的 exported 组件（activity/service/receiver/provider）。",
        parameters = obj(),
        requiredStages = setOf(Stage.EXTRACT),
    ) { inv ->
        val list = extractArtifact(inv.taskId)?.manifest?.exportedWithoutPermission.orEmpty()
        ToolResult.Ok(if (list.isEmpty()) "无暴露组件" else list.joinToString("\n"))
    }

    private fun scanShellHintsTool() = ToolSpec(
        name = "scan_shell_hints",
        description = "列出包内命中的加固/壳特征文件，以及 ABI、DEX 列表。",
        parameters = obj(),
        requiredStages = setOf(Stage.EXTRACT),
    ) { inv ->
        val art = extractArtifact(inv.taskId)
            ?: return@ToolSpec ToolResult.Error("无 S2 产物")
        ToolResult.Ok(
            """
            ABI: ${art.abis.joinToString(", ").ifEmpty { "无 native 库" }}
            DEX: ${art.dexEntries.joinToString(", ")}
            签名文件: ${art.signatureFiles.joinToString(", ").ifEmpty { "无（可能仅 v2/v3 签名）" }}
            加固/壳特征: ${art.shellHints.joinToString(", ").ifEmpty { "未命中已知特征" }}
            Xposed 模块: ${art.isXposedModule} ${art.xposedDescription ?: ""}
            """.trimIndent()
        )
    }

    private fun capabilityChainTool() = ToolSpec(
        name = "capability_chain",
        description = "能力链分析结果：SOURCE → TRANSFORM → SINK 命中与证据。",
        parameters = obj(),
        requiredStages = setOf(Stage.CHAIN),
    ) { inv ->
        val art = store.readText(inv.taskId, "${Stage.CHAIN.id}.json")
            ?.let { runCatching { json.decodeFromString<ChainArtifact>(it) }.getOrNull() }
            ?: return@ToolSpec ToolResult.Error("无 S3 产物")
        val scope = "扫描 DEX ${art.dexCount} 个 / 方法 ${art.methodCount} 个；" +
            "SOURCE 方法 ${art.sourceMethodCount}、SINK 方法 ${art.sinkMethodCount}。" +
            (art.note?.let { " 注意：$it" } ?: "")
        if (art.hits.isEmpty()) {
            return@ToolSpec ToolResult.Ok("未命中能力链规则。$scope")
        }
        val text = art.hits.joinToString("\n\n") { h ->
            buildString {
                append("${h.ruleId} ${h.title}（${h.deduction}）\n")
                if (h.sources.isNotEmpty()) append("  SOURCE: ${h.sources.joinToString(", ")}\n")
                if (h.transforms.isNotEmpty()) append("  TRANSFORM: ${h.transforms.joinToString(", ")}\n")
                if (h.sinks.isNotEmpty()) append("  SINK: ${h.sinks.joinToString(", ")}\n")
                h.evidence.forEach { append("  证据[${it.kind}] ${it.detail} @ ${it.location}\n") }
            }
        }
        ToolResult.Ok((text + "\n" + scope).take(6000))
    }

    private fun scoreBreakdownTool() = ToolSpec(
        name = "score_breakdown",
        description = "规则评分明细与扣分依据（AI 结论不计入该分数）。",
        parameters = obj(),
        requiredStages = setOf(Stage.SCORE),
    ) { inv ->
        val art = store.readText(inv.taskId, "${Stage.SCORE.id}.json")
            ?.let { runCatching { json.decodeFromString<ScoreArtifact>(it) }.getOrNull() }
            ?: return@ToolSpec ToolResult.Error("无 S4 产物")
        val head = "总分 ${art.score}（${art.level}），累计扣分 ${art.totalDeduction}"
        val body = art.findings.joinToString("\n") { f ->
            "[${f.severity}] ${f.ruleId} ${f.title} ${f.deduction} — ${f.detail}"
        }
        val tail = if (art.notes.isEmpty()) "" else "\n注意：" + art.notes.joinToString("；")
        ToolResult.Ok("$head\n$body$tail".take(6000))
    }

    private fun getReportTool() = ToolSpec(
        name = "get_report",
        description = "汇总报告：总分、等级、分级统计、能力链与 AI 研判状态。",
        parameters = obj(),
        requiredStages = setOf(Stage.REPORT),
    ) { inv ->
        val rep = store.readText(inv.taskId, "${Stage.REPORT.id}.json")
            ?.let { runCatching { json.decodeFromString<ReportArtifact>(it) }.getOrNull() }
            ?: return@ToolSpec ToolResult.Error("无 S6 产物")
        ToolResult.Ok(
            buildString {
                append("目标：${rep.targetName}（${rep.targetKind}）\n")
                append("总分：${rep.score}（${rep.level}），累计扣分 ${rep.totalDeduction}\n")
                append("分级：高危 ${rep.highCount} / 中危 ${rep.mediumCount} / 低危 ${rep.lowCount}\n")
                append("能力链命中：${rep.chains.size} 条\n")
                append("AI 研判状态：${rep.aiStatus}（AI 结论不计入总分）\n")
                rep.aiSummary?.let { append("AI 结论摘要：${it.take(1200)}\n") }
                if (rep.notes.isNotEmpty()) append("注意：" + rep.notes.joinToString("；"))
            }.take(6000)
        )
    }

    private fun decompileClassTool() = ToolSpec(
        name = "decompile_class",
        description = "反编译指定类为 Java 源码。",
        parameters = obj("class_name" to strProp("完整类名")),
        requiredStages = setOf(Stage.EXTRACT),
    ) { ToolResult.Error("反编译能力尚未接入（S3 阶段实现）") }

    // ---- 辅助 ----

    private fun extractArtifact(taskId: String): ExtractArtifact? =
        store.readText(taskId, "${Stage.EXTRACT.id}.json")
            ?.let { runCatching { json.decodeFromString<ExtractArtifact>(it) }.getOrNull() }

    private fun obj(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", kotlinx.serialization.json.JsonArray(props.map { JsonPrimitive(it.first) }))
    }

    private fun strProp(desc: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(desc))
    }
}
