package com.modradar.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.modradar.app.ModRadarApp
import com.modradar.app.ai.AiSettings
import com.modradar.app.ai.ChatEngine
import com.modradar.app.ai.ChatEvent
import com.modradar.app.ai.tools.ToolRegistry
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.scan.ksu.KsuModuleInfo
import com.modradar.app.scan.ksu.RootShell
import com.modradar.app.scan.model.TargetKind
import com.modradar.app.scan.xposed.XposedModuleInfo
import com.modradar.app.scan.xposed.XposedScanner
import com.modradar.app.service.AnalysisService
import com.modradar.app.task.Stage
import com.modradar.app.task.stages.ReportArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val mod = app as ModRadarApp
    private val chat = ChatEngine(mod.repo, ToolRegistry(mod.repo, mod.store), mod.db)

    val tasks: StateFlow<List<AnalysisTaskEntity>> = MutableStateFlow(emptyList())
    private val _tasks = MutableStateFlow<List<AnalysisTaskEntity>>(emptyList())

    val allTasks: StateFlow<List<AnalysisTaskEntity>> get() = _tasks.asStateFlow()

    private val _checkpoints = MutableStateFlow<List<StageCheckpointEntity>>(emptyList())
    val checkpoints: StateFlow<List<StageCheckpointEntity>> get() = _checkpoints.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> get() = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy.asStateFlow()

    private val _chatEvents = MutableStateFlow<List<ChatEvent>>(emptyList())
    val chatEvents: StateFlow<List<ChatEvent>> get() = _chatEvents.asStateFlow()

    private val _aiSettings = MutableStateFlow(AiSettings())
    val aiSettings: StateFlow<AiSettings> get() = _aiSettings.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> get() = _testResult.asStateFlow()

    private val _report = MutableStateFlow<ReportArtifact?>(null)
    val report: StateFlow<ReportArtifact?> get() = _report.asStateFlow()

    /** 读取 S6 报告产物。 */
    fun loadReport(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = mod.store.readText(taskId, "${Stage.REPORT.id}.json")
            _report.value = text?.let {
                runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<ReportArtifact>(it)
                }.getOrNull()
            }
        }
    }

    // ---- 已安装模块 ----

    private val _xposedModules = MutableStateFlow<List<XposedModuleInfo>>(emptyList())
    val xposedModules: StateFlow<List<XposedModuleInfo>> get() = _xposedModules.asStateFlow()

    private val _ksuModules = MutableStateFlow<List<KsuModuleInfo>>(emptyList())
    val ksuModules: StateFlow<List<KsuModuleInfo>> get() = _ksuModules.asStateFlow()

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
            _moduleScanning.value = true
            _moduleError.value = null
            runCatching { XposedScanner.scan(getApplication()) }
                .onSuccess { _xposedModules.value = it }
                .onFailure { _moduleError.value = "扫描 Xposed 模块失败：${it.message}" }
            _moduleScanning.value = false
        }
    }

    /** 扫描 KSU / Magisk 模块（需要 root，只读）。 */
    fun scanKsuModules() {
        viewModelScope.launch(Dispatchers.IO) {
            _moduleScanning.value = true
            _moduleError.value = null
            val root = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            _rootAvailable.value = root
            _ksuModules.value = if (!root) {
                emptyList()
            } else {
                runCatching { RootShell.listModuleDirs().mapNotNull { RootShell.loadModule(it) } }
                    .onFailure { _moduleError.value = "读取模块目录失败：${it.message}" }
                    .getOrDefault(emptyList())
            }
            _moduleScanning.value = false
        }
    }

    /** 把已安装应用的 APK 复制进私有目录并创建分析任务。 */
    fun analyzeInstalledApk(apkPath: String, label: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(apkPath)
                    if (!src.isFile) error("无法读取 APK：$apkPath")
                    val inbox = File(mod.filesDir, "inbox").also { it.mkdirs() }
                    val dst = File(inbox, label.replace('/', '_').take(60) + ".apk")
                    src.copyTo(dst, overwrite = true)
                    mod.repo.create(dst.absolutePath, dst.name, TargetKind.APK)
                }.getOrNull()
            }
            _busy.value = false
            created?.let { onCreated(it.id) }
        }
    }

    init {
        _aiSettings.value = AiSettings.load(app)
        viewModelScope.launch {
            mod.repo.observeAll().collect { _tasks.value = it }
        }
    }

    /** 把 SAF 选中的文件复制到应用私有目录，作为分析目标。 */
    fun createTaskFromUri(ctx: Context, uri: Uri, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(ctx, uri) ?: "target.bin"
                    val inbox = File(mod.filesDir, "inbox").also { it.mkdirs() }
                    val dst = File(inbox, name)
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { out -> ins.copyTo(out) }
                    } ?: error("无法读取所选文件")
                    mod.repo.create(dst.absolutePath, name, guessKind(name))
                }.getOrNull()
            }
            _busy.value = false
            created?.let {
                refreshCheckpoints(it.id)
                onCreated(it.id)
            }
        }
    }

    private fun guessKind(name: String): TargetKind = when {
        name.endsWith(".zip", true) -> TargetKind.KSU_MODULE
        name.endsWith(".apk", true) -> TargetKind.APK
        else -> TargetKind.UNKNOWN
    }

    private fun queryName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun refreshCheckpoints(taskId: String) {
        viewModelScope.launch { _checkpoints.value = mod.repo.checkpoints(taskId) }
    }

    fun refreshMessages(taskId: String) {
        viewModelScope.launch {
            val s = chat.ensureSession(taskId)
            _messages.value = chat.history(s.id)
        }
    }

    fun start(taskId: String) = AnalysisService.start(getApplication(), taskId)

    fun pause(taskId: String) = AnalysisService.pause(getApplication(), taskId)

    fun resume(taskId: String) {
        AnalysisService.resume(getApplication(), taskId)
        refreshCheckpoints(taskId)
    }

    fun cancel(taskId: String) {
        AnalysisService.cancel(getApplication(), taskId)
        viewModelScope.launch { _tasks.value = mod.repo.unfinished() }
    }

    fun sendChat(taskId: String, text: String) {
        viewModelScope.launch {
            val s = chat.ensureSession(taskId)
            _chatEvents.value = emptyList()
            _busy.value = true
            chat.send(taskId, s.id, text, _aiSettings.value) { ev ->
                _chatEvents.value = _chatEvents.value + ev
                if (ev is ChatEvent.AssistantText || ev is ChatEvent.Failed) {
                    viewModelScope.launch { _messages.value = chat.history(s.id) }
                }
            }
            _messages.value = chat.history(s.id)
            _busy.value = false
        }
    }

    fun saveAiSettings(s: AiSettings) {
        AiSettings.save(getApplication(), s)
        _aiSettings.value = s
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = "测试中…"
            _testResult.value = runCatching {
                com.modradar.app.ai.LmClient(_aiSettings.value).testConnection()
            }.getOrElse { "失败：${it.message}" }
        }
    }

    fun stageOf(task: AnalysisTaskEntity): Stage? = Stage.byId(task.currentStage)
}
