package com.modradar.app.task.stages

import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import java.io.File

/**
 * S3 能力链分析。
 *
 * 目标：回答「远端取文件 → 本地执行」这类静态特征扫不到的风险，
 * 做法是建立 SOURCE / TRANSFORM / SINK 三要素的方法级索引 + 调用图可达性。
 *
 * 规则（扣分见计划.md §4）：
 *  C1 SOURCE 与 SINK 同一方法        −35
 *  C2 跨方法调用链可达（≤4 层）      −25
 *  C3 仅有 SINK（有能力无证据）      −10
 *  C4 网络能力与 exec/动态加载并存   −15
 *  C5 解密后立即加载/执行            −30
 *  C6 壳 / VMP → 不可审计            −25（依据 S2 的加固特征）
 */
class ChainStage : StageRunner {

    override val stage: Stage = Stage.CHAIN

    private val json = Json { ignoreUnknownKeys = true }

    private class MethodNode(val key: String) {
        val sources = linkedSetOf<String>()
        val transforms = linkedSetOf<String>()
        val sinks = linkedSetOf<String>()
        val callees = linkedSetOf<String>()
    }

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val file = File(ctx.targetPath)
        val opcodes = Opcodes.forApi(24)

        val nodes = HashMap<String, MethodNode>(1 shl 16)
        val httpUrls = linkedSetOf<String>()
        val allStrings = linkedSetOf<String>()
        var methodCount = 0
        var dexCount = 0
        var truncated = false

        val dexNames = com.modradar.app.scan.apk.ApkReader(file).use { it.dexEntries }
        onProgress(5, "发现 ${dexNames.size} 个 DEX")

        for ((idx, dexName) in dexNames.withIndex()) {
            ctx.checkPause()
            dexCount++

            val dex = runCatching {
                DexFileFactory.loadDexEntry(file, dexName, true, opcodes).dexFile
            }.getOrNull() ?: continue

            runCatching {
                for (cls in dex.classes) {
                    for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        methodCount++
                        if (methodCount > MAX_METHODS) { truncated = true; break }

                        val key = m.definingClass + "->" + m.name
                        val node = nodes.getOrPut(key) { MethodNode(key) }

                        for (insn in impl.instructions) {
                            val ref = (insn as? ReferenceInstruction)?.reference ?: continue
                            when (ref) {
                                is MethodReference -> {
                                    val callee = ref.definingClass + "->" + ref.name
                                    node.callees.add(callee)
                                    val (s, t, k) = ApiSignatures.classifyMethod(ref.definingClass, ref.name)
                                    node.sources.addAll(s)
                                    node.transforms.addAll(t)
                                    node.sinks.addAll(k)
                                }
                                is StringReference -> {
                                    val v = ref.string
                                    if (v.startsWith("http://") || v.startsWith("https://")) {
                                        if (httpUrls.size < MAX_URLS) httpUrls.add(v) else truncated = true
                                    }
                                    if (allStrings.size < MAX_STRINGS) allStrings.add(v)
                                }
                            }
                        }
                    }
                    if (methodCount > MAX_METHODS) break
                }
            }

            onProgress(5 + (idx + 1) * 60 / dexNames.size.coerceAtLeast(1), "已扫描 $dexName")
        }

        ctx.checkPause()
        onProgress(70, "建立调用图并判定能力链")

        val sourceMethods = nodes.values.filter { it.sources.isNotEmpty() }
        val sinkMethods = nodes.values.filter { it.sinks.isNotEmpty() }
        val transformSinkMethods = nodes.values.filter { it.transforms.isNotEmpty() && it.sinks.isNotEmpty() }

        val hits = mutableListOf<ChainHit>()

        // C1：同一方法内 SOURCE + SINK
        val c1 = nodes.values.filter { it.sources.isNotEmpty() && it.sinks.isNotEmpty() }
        c1.take(MAX_HITS).forEach { n ->
            hits += ChainHit(
                ruleId = "C1",
                title = "同方法内 取数据 → 执行",
                deduction = -35,
                sources = n.sources.toList(),
                sinks = n.sinks.toList(),
                sameMethod = true,
                evidence = listOf(
                    ChainEvidence("method", "同一方法同时引用 SOURCE 与 SINK", n.key),
                    ChainEvidence("api", "SOURCE: ${n.sources.joinToString(", ")}", n.key),
                    ChainEvidence("api", "SINK: ${n.sinks.joinToString(", ")}", n.key),
                ),
            )
        }

        // C5：解密后立即加载/执行
        transformSinkMethods.take(MAX_HITS).forEach { n ->
            hits += ChainHit(
                ruleId = "C5",
                title = "解密后加载/执行",
                deduction = -30,
                transforms = n.transforms.toList(),
                sinks = n.sinks.toList(),
                sameMethod = true,
                evidence = listOf(
                    ChainEvidence("method", "同方法内解密后调用危险汇点", n.key),
                    ChainEvidence("api", "TRANSFORM: ${n.transforms.joinToString(", ")}", n.key),
                    ChainEvidence("api", "SINK: ${n.sinks.joinToString(", ")}", n.key),
                ),
            )
        }

        // C2：跨方法调用链可达（≤4 层）
        val sinkKeys = sinkMethods.map { it.key }.toHashSet()
        var c2Count = 0
        for (src in sourceMethods) {
            if (c2Count >= MAX_HITS) break
            ctx.checkPause()
            val hit = bfsToSink(nodes, src.key, sinkKeys)
            if (hit != null && !(src.sources.isNotEmpty() && src.sinks.isNotEmpty())) {
                val sinkNode = nodes[hit]
                hits += ChainHit(
                    ruleId = "C2",
                    title = "调用链可达：取数据 → 执行",
                    deduction = -25,
                    sources = src.sources.toList(),
                    sinks = sinkNode?.sinks?.toList().orEmpty(),
                    reachable = true,
                    evidence = listOf(
                        ChainEvidence("chain", "从 SOURCE 方法经调用链到达 SINK 方法", "${src.key} ⇒ $hit"),
                        ChainEvidence("api", "SOURCE: ${src.sources.joinToString(", ")}", src.key),
                        ChainEvidence("api", "SINK: ${sinkNode?.sinks?.joinToString(", ").orEmpty()}", hit),
                    ),
                )
                c2Count++
            }
        }

        // C4：网络能力与 exec/动态加载并存（弱证据）
        val hasNetwork = sourceMethods.any { n ->
            n.sources.any { it in NETWORK_LABELS }
        }
        val execSinks = sinkMethods.flatMap { it.sinks }.toSet()
        if (hasNetwork && execSinks.isNotEmpty() && c1.isEmpty() && c2Count == 0) {
            hits += ChainHit(
                ruleId = "C4",
                title = "网络能力与执行/加载能力并存",
                deduction = -15,
                sinks = execSinks.toList().take(8),
                evidence = listOf(
                    ChainEvidence("capability", "包内同时存在网络来源与执行/动态加载汇点（未发现直接链路）", "全局"),
                ),
            )
        }

        // C3：仅有 SINK
        if (sinkMethods.isNotEmpty() && sourceMethods.isEmpty()) {
            hits += ChainHit(
                ruleId = "C3",
                title = "存在执行/加载能力（未发现数据来源）",
                deduction = -10,
                sinks = execSinks.toList().take(8),
                evidence = listOf(
                    ChainEvidence("capability", "存在危险汇点但未发现外部数据来源", "全局"),
                ),
            )
        }

        // C6：壳 / VMP
        val extract = ctx.read("${Stage.EXTRACT.id}.json")
            ?.let { runCatching { json.decodeFromString<ExtractArtifact>(it) }.getOrNull() }
        if (extract != null && extract.shellHints.isNotEmpty()) {
            hits += ChainHit(
                ruleId = "C6",
                title = "存在加固/壳特征，代码不可审计",
                deduction = -25,
                evidence = extract.shellHints.take(8).map {
                    ChainEvidence("file", "命中加固特征文件", it)
                },
            )
        }

        ctx.checkPause()
        onProgress(85, "统计追踪 SDK 与反调试特征")

        val trackerHits = linkedSetOf<String>()
        val antiDebugHits = linkedSetOf<String>()
        val trackerDomains = linkedSetOf<String>()
        for (s in allStrings) {
            trackerHits.addAll(StringFacts.matchTracker(s))
            antiDebugHits.addAll(StringFacts.matchAntiDebug(s))
            for (u in httpUrls) StringFacts.matchTrackerDomain(u)?.let { trackerDomains.add(it) }
        }
        val cleartext = httpUrls.count { it.startsWith("http://") }

        ctx.checkPause()

        val artifact = ChainArtifact(
            hits = hits,
            dexCount = dexCount,
            methodCount = methodCount,
            sourceMethodCount = sourceMethods.size,
            sinkMethodCount = sinkMethods.size,
            httpUrls = httpUrls.take(50).toList(),
            truncated = truncated,
            note = if (truncated) "分析量超出上限，已截断（结果偏保守）" else null,
            trackerHits = trackerHits.toList(),
            antiDebugHits = antiDebugHits.toList(),
            cleartextHttpCount = cleartext,
            trackerDomainHits = trackerDomains.toList(),
        )
        ctx.write(ctx.artifactName(stage), json.encodeToString(artifact))

        onProgress(100, "能力链分析完成 · 命中 ${hits.size} 条")
    }

    /** 从 start 出发，最多 [MAX_DEPTH] 层内是否可达任一 sink 方法。 */
    private fun bfsToSink(
        nodes: Map<String, MethodNode>,
        start: String,
        sinkKeys: Set<String>,
    ): String? {
        if (sinkKeys.isEmpty()) return null
        var frontier = listOf(start)
        val visited = hashSetOf(start)
        repeat(MAX_DEPTH) {
            val next = mutableListOf<String>()
            for (k in frontier) {
                val node = nodes[k] ?: continue
                for (callee in node.callees) {
                    if (callee in sinkKeys) return callee
                    if (visited.add(callee)) next.add(callee)
                }
            }
            if (next.isEmpty()) return null
            frontier = next
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 4
        const val MAX_HITS = 20
        const val MAX_METHODS = 300_000
        const val MAX_URLS = 2_000
        const val MAX_STRINGS = 80_000

        val NETWORK_LABELS = setOf(
            "OkHttp", "Okio", "HttpURLConnection", "java.net.URL", "Socket",
            "ServerSocket", "DatagramSocket", "DownloadManager", "URLConnection",
        )
    }
}
