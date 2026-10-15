package com.modradar.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.modradar.app.ModRadarApp
import com.modradar.app.ai.AiError
import com.modradar.app.backup.BackupManager
import com.modradar.app.ai.AiSettings
import com.modradar.app.ai.ChatEngine
import com.modradar.app.ai.ChatEvent
import com.modradar.app.ai.ConnectionReport
import com.modradar.app.ai.LmClient
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.mcp.McpSettings
import com.modradar.app.scan.ksu.KsuModuleInfo
import com.modradar.app.scan.ksu.RootShell
import com.modradar.app.scan.model.TargetKind
import com.modradar.app.scan.xposed.XposedModuleInfo
import com.modradar.app.scan.xposed.XposedScanner
import com.modradar.app.service.AnalysisService
import com.modradar.app.task.Stage
import com.modradar.app.task.stages.ChainArtifact
import com.modradar.app.task.stages.ExtractArtifact
import com.modradar.app.task.stages.ReportArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

/** 已安装应用条目。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val isSystem: Boolean,
    val isXposed: Boolean,
    val baseApk: String,
    val splitApks: List<String>,
)

/** 文件夹扫描条目。 */
data class FolderItem(val uri: Uri, val name: String, val size: Long, val kind: TargetKind)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val report: ConnectionReport) : TestState
    data class Fail(val message: String) : TestState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val mod = app as ModRadarApp
    private val chat = ChatEngine(mod.repo, mod.tools, mod.db)
    private val json = Json { ignoreUnknownKeys = true }

    private val _tasks = MutableStateFlow<List<AnalysisTaskEntity>>(emptyList())
    val allTasks: StateFlow<List<AnalysisTaskEntity>> get() = _tasks.asStateFlow()

    val queued: StateFlow<List<String>> get() = AnalysisService.queue
    val logs: StateFlow<Map<String, List<String>>> get() = AnalysisService.logs

    private val _checkpoints = MutableStateFlow<List<StageCheckpointEntity>>(emptyList())
    val checkpoints: StateFlow<List<StageCheckpointEntity>> get() = _checkpoints.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> get() = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy.asStateFlow()

    /** 当前轮流式输出（正文, 思考）；null 表示没有在输出。 */
    private val _streaming = MutableStateFlow<ChatEvent.Streaming?>(null)
    val streaming: StateFlow<ChatEvent.Streaming?> get() = _streaming.asStateFlow()

    private val _chatEvents = MutableStateFlow<List<ChatEvent>>(emptyList())
    val chatEvents: StateFlow<List<ChatEvent>> get() = _chatEvents.asStateFlow()

    private val _aiSettings = MutableStateFlow(AiSettings())
    val aiSettings: StateFlow<AiSettings> get() = _aiSettings.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> get() = _testState.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> get() = _models.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> get() = _modelsError.asStateFlow()

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> get() = _loadingModels.asStateFlow()

    /** 一次性提示（Snackbar）。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> get() = _toast.asStateFlow()
    fun consumeToast() { _toast.value = null }

    private val _report = MutableStateFlow<ReportArtifact?>(null)
    val report: StateFlow<ReportArtifact?> get() = _report.asStateFlow()

    private val _extract = MutableStateFlow<ExtractArtifact?>(null)
    val extract: StateFlow<ExtractArtifact?> get() = _extract.asStateFlow()

    private val _load = MutableStateFlow<com.modradar.app.task.stages.LoadArtifact?>(null)
    val load: StateFlow<com.modradar.app.task.stages.LoadArtifact?> get() = _load.asStateFlow()

    private val _chain = MutableStateFlow<ChainArtifact?>(null)
    val chain: StateFlow<ChainArtifact?> get() = _chain.asStateFlow()

    init {
        _aiSettings.value = AiSettings.load(app)
        viewModelScope.launch {
            mod.repo.observeAll().collect { _tasks.value = it }
        }
    }

    private var loadedReportFor: String? = null

    /** 读取 S6 报告与 S2/S3 产物（证据链页需要）。 */
    fun loadReport(taskId: String) {
        if (loadedReportFor != taskId) {
            _report.value = null; _extract.value = null; _chain.value = null; _load.value = null
            loadedReportFor = taskId
        }
        viewModelScope.launch(Dispatchers.IO) {
            _report.value = read<ReportArtifact>(taskId, Stage.REPORT)
            _extract.value = read<ExtractArtifact>(taskId, Stage.EXTRACT)
            _load.value = read<com.modradar.app.task.stages.LoadArtifact>(taskId, Stage.LOAD)
            _chain.value = read<ChainArtifact>(taskId, Stage.CHAIN)
        }
    }

    private inline fun <reified T> read(taskId: String, stage: Stage): T? =
        mod.store.readText(taskId, "${stage.id}.json")?.let {
            runCatching { json.decodeFromString<T>(it) }.getOrNull()
        }

    // ---- 已安装应用 ----

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> get() = _apps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> get() = _appsLoading.asStateFlow()

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager
            _apps.value = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != getApplication<Application>().packageName }
                    .map { ai ->
                        val xp = XposedScanner.isModule(ai)
                        InstalledApp(
                            packageName = ai.packageName,
                            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
                            versionName = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull(),
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            isXposed = xp,
                            baseApk = ai.sourceDir,
                            splitApks = ai.splitSourceDirs?.toList().orEmpty(),
                        )
                    }
                    .sortedWith(compareByDescending<InstalledApp> { it.isXposed }.thenBy { it.label.lowercase() })
            }.getOrDefault(emptyList())
            _appsLoading.value = false
        }
    }

    /** 批量：复制 base + split APK 进私有目录，逐个建任务并排队分析。 */
    fun analyzeApps(list: List<InstalledApp>, onCreated: (List<String>) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ids = withContext(Dispatchers.IO) {
                list.mapNotNull { a ->
                    runCatching {
                        val src = File(a.baseApk)
                        if (!src.canRead()) error("无法读取 ${a.label}")
                        val inbox = File(mod.filesDir, "inbox").also { it.mkdirs() }
                        val dst = File(inbox, safeName("${a.label}_${a.versionName ?: ""}") + ".apk")
                        src.copyTo(dst, overwrite = true)
                        val splitDir = File(dst.path + ".splits")
                        splitDir.deleteRecursively()
                        if (a.splitApks.isNotEmpty()) {
                            splitDir.mkdirs()
                            a.splitApks.forEach { p -> File(p).takeIf { it.canRead() }?.copyTo(File(splitDir, File(p).name), true) }
                        }
                        val kind = if (a.isXposed) TargetKind.XPOSED_MODULE else TargetKind.APK
                        mod.repo.create(dst.absolutePath, a.label, kind).id
                    }.onFailure { _toast.value = it.message }.getOrNull()
                }
            }
            ids.forEach { start(it) }
            _busy.value = false
            if (ids.isNotEmpty()) {
                _toast.value = if (ids.size > 1) "已加入 ${ids.size} 个分析任务，按顺序执行" else null
                onCreated(ids)
            }
        }
    }

    // ---- 文件夹（SAF）----

    private val _folderItems = MutableStateFlow<List<FolderItem>>(emptyList())
    val folderItems: StateFlow<List<FolderItem>> get() = _folderItems.asStateFlow()

    private val _folderUri = MutableStateFlow<Uri?>(null)
    val folderUri: StateFlow<Uri?> get() = _folderUri.asStateFlow()

    private val _folderScanning = MutableStateFlow(false)
    val folderScanning: StateFlow<Boolean> get() = _folderScanning.asStateFlow()

    fun restoreFolder() {
        val ctx = getApplication<Application>()
        val saved = ctx.getSharedPreferences("folder", Context.MODE_PRIVATE).getString("uri", null) ?: return
        val uri = Uri.parse(saved)
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (granted && _folderUri.value == null) openFolder(uri, persist = false)
    }

    fun openFolder(uri: Uri, persist: Boolean = true) {
        val ctx = getApplication<Application>()
        if (persist) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.getSharedPreferences("folder", Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()
        }
        _folderUri.value = uri
        viewModelScope.launch(Dispatchers.IO) {
            _folderScanning.value = true
            val root = DocumentFile.fromTreeUri(ctx, uri)
            val out = mutableListOf<FolderItem>()
            fun walk(dir: DocumentFile?, depth: Int) {
                dir?.listFiles()?.forEach { f ->
                    if (f.isDirectory && depth < 2) walk(f, depth + 1)
                    else if (f.isFile) {
                        val n = f.name.orEmpty()
                        if (n.endsWith(".apk", true) || n.endsWith(".zip", true)) {
                            out += FolderItem(f.uri, n, f.length(), classify(ctx, f.uri, n))
                        }
                    }
                }
            }
            runCatching { walk(root, 0) }.onFailure { _toast.value = "读取文件夹失败：${it.message}" }
            _folderItems.value = out.sortedWith(compareBy<FolderItem> { it.kind == TargetKind.UNKNOWN }.thenBy { it.name.lowercase() })
            _folderScanning.value = false
        }
    }

    /** 只看 zip 条目名判断类型，不解压内容。 */
    private fun classify(ctx: Context, uri: Uri, name: String): TargetKind = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                var manifest = false
                var count = 0
                while (count < 4000) {
                    val e = z.nextEntry ?: break
                    count++
                    when (e.name) {
                        "module.prop" -> return@runCatching TargetKind.KSU_MODULE
                        "assets/xposed_init", "META-INF/xposed/java_init.list" -> return@runCatching TargetKind.XPOSED_MODULE
                        "AndroidManifest.xml" -> manifest = true
                    }
                }
                if (manifest) TargetKind.APK else TargetKind.UNKNOWN
            }
        } ?: TargetKind.UNKNOWN
    }.getOrDefault(if (name.endsWith(".apk", true)) TargetKind.APK else TargetKind.UNKNOWN)

    /** 把 SAF 选中的文件复制到应用私有目录并建任务（支持批量）。 */
    fun createTasksFromUris(uris: List<Pair<Uri, TargetKind?>>, onCreated: (List<String>) -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _busy.value = true
            val ids = withContext(Dispatchers.IO) {
                uris.mapNotNull { (uri, kind) ->
                    runCatching {
                        val name = queryName(ctx, uri) ?: "target.bin"
                        val inbox = File(mod.filesDir, "inbox").also { it.mkdirs() }
                        val dst = File(inbox, safeName(name.substringBeforeLast('.')) + "." + name.substringAfterLast('.', "bin"))
                        ctx.contentResolver.openInputStream(uri)?.use { ins ->
                            dst.outputStream().use { out -> ins.copyTo(out) }
                        } ?: error("无法读取所选文件")
                        mod.repo.create(dst.absolutePath, name, kind ?: guessKind(name)).id
                    }.onFailure { _toast.value = "导入失败：${it.message}" }.getOrNull()
                }
            }
            ids.forEach { start(it) }
            _busy.value = false
            if (ids.size > 1) _toast.value = "已加入 ${ids.size} 个分析任务，按顺序执行"
            if (ids.isNotEmpty()) onCreated(ids)
        }
    }

    private fun guessKind(name: String): TargetKind = when {
        name.endsWith(".zip", true) -> TargetKind.KSU_MODULE
        name.endsWith(".apk", true) -> TargetKind.APK
        else -> TargetKind.UNKNOWN
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(60).ifBlank { "target" } +
            "_" + System.currentTimeMillis().toString(36)

    private fun queryName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---- 已安装模块 ----

    private val _xposedModules = MutableStateFlow<List<XposedModuleInfo>>(emptyList())
    val xposedModules: StateFlow<List<XposedModuleInfo>> get() = _xposedModules.asStateFlow()

    private val _ksuModules = MutableStateFlow<List<KsuModuleInfo>>(emptyList())
    val ksuModules: StateFlow<List<KsuModuleInfo>> get() = _ksuModules.asStateFlow()

    /** KSU 模块脚本危险命中：模块目录 → 命中描述。 */
    private val _ksuRisks = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val ksuRisks: StateFlow<Map<String, List<String>>> get() = _ksuRisks.asStateFlow()

    /** null 表示尚未检测。 */
    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> get() = _rootAvailable.asStateFlow()

    private val _moduleScanning = MutableStateFlow(false)
    val moduleScanning: StateFlow<Boolean> get() = _moduleScanning.asStateFlow()

    private val _moduleError = MutableStateFlow<String?>(null)
    val moduleError: StateFlow<String?> get() = _moduleError.asStateFlow()

    /** 扫描已安装的 Xposed 模块（免 root）。 */
    fun scanXposedModules() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { XposedScanner.scan(getApplication()) }
                .onSuccess { _xposedModules.value = it }
        }
    }

    /** 已安装 KSU 模块：root 只读打包成 zip 后建任务。 */
    fun analyzeKsuModules(list: List<KsuModuleInfo>, onCreated: (List<String>) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ids = withContext(Dispatchers.IO) {
                list.mapNotNull { m ->
                    runCatching {
                        val dir = m.dir ?: error("${m.name}：缺少模块目录")
                        val inbox = File(mod.filesDir, "inbox").also { it.mkdirs() }
                        val dst = File(inbox, safeName("${m.id}_${m.version ?: ""}") + ".zip")
                        if (RootShell.packModule(dir, dst) == 0) error("${m.name}：读取模块文件失败")
                        mod.repo.create(dst.absolutePath, m.name, TargetKind.KSU_MODULE).id
                    }.onFailure { _toast.value = it.message }.getOrNull()
                }
            }
            ids.forEach { start(it) }
            _busy.value = false
            if (ids.isNotEmpty()) {
                if (ids.size > 1) _toast.value = "已加入 ${ids.size} 个分析任务，按顺序执行"
                onCreated(ids)
            }
        }
    }

    /** 扫描 KSU / Magisk 模块（需要 root，只读）。 */
    fun scanKsuModules() {
        viewModelScope.launch(Dispatchers.IO) {
            _moduleScanning.value = true
            _moduleError.value = null
            val root = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            _rootAvailable.value = root
            if (!root) {
                _ksuModules.value = emptyList()
            } else {
                val mods = runCatching { RootShell.listModuleDirs().mapNotNull { RootShell.loadModule(it) } }
                    .onFailure { _moduleError.value = "读取模块目录失败：${it.message}" }
                    .getOrDefault(emptyList())
                _ksuModules.value = mods
                _ksuRisks.value = mods.mapNotNull { m ->
                    val dir = m.dir ?: return@mapNotNull null
                    val hits = com.modradar.app.scan.ksu.ScriptRisk.scanAll(RootShell.readScripts(dir, m.scripts))
                        .flatMap { (n, hs) -> hs.map { "$n: ${it.label}" } }.distinct()
                    dir to hits
                }.toMap()
            }
            _moduleScanning.value = false
        }
    }

    // ---- 任务控制 ----

    fun refreshCheckpoints(taskId: String) {
        viewModelScope.launch { _checkpoints.value = mod.repo.checkpoints(taskId) }
    }

    fun start(taskId: String) = AnalysisService.start(getApplication(), taskId)

    fun pause(taskId: String) = AnalysisService.pause(getApplication(), taskId)

    fun resume(taskId: String) {
        AnalysisService.resume(getApplication(), taskId)
        refreshCheckpoints(taskId)
    }

    /** 只重跑 S5 AI 研判与 S6 报告（网络出错后在报告页直接重试）。 */
    fun retryAi(taskId: String) {
        viewModelScope.launch {
            mod.repo.setStage(taskId, Stage.AI, com.modradar.app.task.StageState.PENDING, detail = "用户重试")
            mod.repo.setStage(taskId, Stage.REPORT, com.modradar.app.task.StageState.PENDING, detail = "用户重试")
            resume(taskId)
        }
    }

    /** 退回单条 AI 修正（AI 判错时）：恢复本地规则扣分并重算分数，写回 S6。 */
    fun revertAiAdjust(taskId: String, ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mod.pipeline.revertAiAdjustment(taskId, ruleId)
            _toast.value = if (ok) "已退回该条 AI 修正" else "退回失败：报告数据缺失"
            if (ok && loadedReportFor == taskId) {
                read<ReportArtifact>(taskId, Stage.REPORT)?.let { _report.value = it }
            }
        }
    }

    /** 正在重新核验来源的任务 id。 */
    private val _provenanceBusy = MutableStateFlow<String?>(null)
    val provenanceBusy: StateFlow<String?> get() = _provenanceBusy.asStateFlow()

    /** 报告页单独重跑联网来源核验，并用新结果重建 S6 报告（不重跑 AI）。 */
    fun recheckProvenance(t: AnalysisTaskEntity) {
        if (_provenanceBusy.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _provenanceBusy.value = t.id
            runCatching {
                val read: (String) -> String? = { mod.store.readText(t.id, it) }
                mod.provenance.run(t.targetPath, t.targetName, t.targetKind, read) { n, text -> mod.store.writeAtomic(t.id, n, text) }
                if (mod.store.exists(t.id, "${Stage.REPORT.id}.json")) {
                    val rep = com.modradar.app.task.stages.ReportStage.assemble(read, t.targetName, t.targetKind)
                    mod.store.writeAtomic(t.id, "${Stage.REPORT.id}.json", json.encodeToString(rep))
                    mod.repo.setResult(t.id, rep.score, rep.level)
                    if (loadedReportFor == t.id) _report.value = rep
                }
            }.onFailure { _toast.value = "来源核验失败：${it.message}" }
            _provenanceBusy.value = null
        }
    }

    /** 取消并删除任务（含产物与对话）。 */
    fun cancel(taskId: String) {
        val running = AnalysisService.running.value == taskId || taskId in AnalysisService.queue.value
        if (running) {
            AnalysisService.cancel(getApplication(), taskId)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                mod.repo.delete(taskId)
                mod.store.deleteTask(taskId)
            }
        }
    }

    fun deleteTasks(ids: Collection<String>) = ids.forEach { cancel(it) }

    // ---- 对话 ----

    fun refreshMessages(taskId: String) {
        viewModelScope.launch {
            val s = chat.ensureSession(taskId)
            _messages.value = chat.history(s.id)
        }
    }

    fun sendChat(taskId: String, text: String) {
        viewModelScope.launch {
            val s = chat.ensureSession(taskId)
            _chatEvents.value = emptyList()
            _busy.value = true
            chat.send(taskId, s.id, text, _aiSettings.value) { ev ->
                if (ev is ChatEvent.Streaming) {
                    _streaming.value = ev
                    return@send
                }
                _chatEvents.value = _chatEvents.value + ev
                viewModelScope.launch {
                    val h = chat.history(s.id)
                    // 先换成落库的消息再清掉流式气泡，避免中间闪一下空白
                    _messages.value = h
                    if (ev !is ChatEvent.ToolFinished) _streaming.value = null
                }
            }
            _messages.value = chat.history(s.id)
            _streaming.value = null
            _chatEvents.value = emptyList()
            _busy.value = false
        }
    }

    fun clearChat(taskId: String) {
        viewModelScope.launch {
            val s = chat.ensureSession(taskId)
            chat.clear(s.id)
            _messages.value = emptyList()
        }
    }

    // ---- AI 设置 ----

    fun saveAiSettings(s: AiSettings) {
        val c = s.cleaned()
        AiSettings.save(getApplication(), c)
        _aiSettings.value = c
        _toast.value = "已保存"
    }

    fun clearAiSettings() {
        AiSettings.clear(getApplication())
        _aiSettings.value = AiSettings()
        _testState.value = TestState.Idle
        _toast.value = "已清除 AI 配置"
    }

    /** 用表单当前值测试（无需先保存）。 */
    fun testConnection(draft: AiSettings) {
        viewModelScope.launch {
            _testState.value = TestState.Running
            _testState.value = runCatching { LmClient(draft).testConnection() }
                .fold({ TestState.Ok(it) }, { TestState.Fail(AiError.fromThrowable(it).userMessage) })
        }
    }

    fun fetchModels(draft: AiSettings) {
        viewModelScope.launch {
            _loadingModels.value = true
            _modelsError.value = null
            runCatching { LmClient(draft).listModels() }
                .onSuccess {
                    _models.value = it
                    if (it.isEmpty()) _modelsError.value = "接口未返回模型列表，请手动填写"
                }
                .onFailure { _modelsError.value = AiError.fromThrowable(it).userMessage }
            _loadingModels.value = false
        }
    }

    // ---- MCP ----

    private val _mcp = MutableStateFlow(McpSettings.load(app))
    val mcp: StateFlow<McpSettings> get() = _mcp.asStateFlow()
    val mcpStatus: StateFlow<String> get() = mod.mcpStatus

    private val _mcpClientStatus = MutableStateFlow<String?>(null)
    val mcpClientStatus: StateFlow<String?> get() = _mcpClientStatus.asStateFlow()

    fun setMcpServer(enabled: Boolean) {
        val cur = _mcp.value
        val s = cur.copy(serverEnabled = enabled, token = cur.token.ifBlank { McpSettings.newToken() })
        McpSettings.save(getApplication(), s)
        _mcp.value = s
        mod.applyMcpServer(s)
    }

    fun regenerateMcpToken() {
        val s = _mcp.value.copy(token = McpSettings.newToken())
        McpSettings.save(getApplication(), s)
        _mcp.value = s
        if (s.serverEnabled) mod.applyMcpServer(s)
    }

    fun connectMcpClient(url: String, token: String) {
        val s = _mcp.value.copy(clientUrl = url.trim(), clientToken = token.trim())
        McpSettings.save(getApplication(), s)
        _mcp.value = s
        viewModelScope.launch {
            _mcpClientStatus.value = "连接中…"
            _mcpClientStatus.value = mod.connectExternalMcp(s.clientUrl, s.clientToken).fold(
                { if (s.clientUrl.isBlank()) "已断开" else "已接入 $it 个只读工具" },
                { "连接失败：${it.message}" },
            )
        }
    }

    fun stageOf(task: AnalysisTaskEntity): Stage? = Stage.byId(task.currentStage)

    // ---- 导入 / 导出 ----

    private val backup by lazy { BackupManager(mod.db, mod.store) }

    /** 把文本写到用户通过 SAF 选定的位置。 */
    fun writeText(uri: Uri, text: String, done: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _toast.value = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                done
            }.getOrElse { "写入失败：${it.message}" }
        }
    }

    fun exportConfig(uri: Uri, includeSecrets: Boolean, includeTasks: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = backup.export(
                _aiSettings.value, _mcp.value, includeSecrets, includeTasks,
                com.modradar.app.BuildConfig.VERSION_NAME, System.currentTimeMillis(),
            )
            writeText(uri, text, "已导出${if (includeTasks) "配置、历史与对话" else "配置"}")
        }
    }

    fun importConfig(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _toast.value = runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val f = BackupManager.parse(text)
                val r = backup.import(
                    f, _aiSettings.value, _mcp.value,
                    saveAi = { AiSettings.save(getApplication(), it); _aiSettings.value = it },
                    saveMcp = { McpSettings.save(getApplication(), it); _mcp.value = it; mod.applyMcpServer(it) },
                )
                buildList {
                    if (r.ai) add("AI 设置")
                    if (r.mcp) add("MCP 设置")
                    if (r.tasks > 0) add("${r.tasks} 个任务")
                    if (r.messages > 0) add("${r.messages} 条对话")
                }.joinToString("、", prefix = "已导入：").ifEmpty { "备份为空" }
            }.getOrElse { "导入失败：${it.message}" }
        }
    }
}
