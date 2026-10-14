package com.modradar.app.task

import java.io.File
import java.security.MessageDigest

/**
 * 任务产物存储。
 *
 * 约定：
 * - checkpoint 与结构化结果写 `filesDir/tasks/<taskId>/`（**不可用 cacheDir**，会被系统清理）
 * - 大体积中间产物（解包目录、反编译结果）写 `cacheDir/tasks/<taskId>/`，可重算
 * - 提交协议：临时文件 → fsync → 原子 rename
 */
class ArtifactStore(private val rootDir: File) {

    val root: File = rootDir.also { it.mkdirs() }

    fun taskDir(taskId: String): File = File(rootDir, taskId).also { it.mkdirs() }

    fun cacheDir(cacheRoot: File, taskId: String): File =
        File(cacheRoot, taskId).also { it.mkdirs() }

    fun artifactFile(taskId: String, name: String): File = File(taskDir(taskId), name)

    fun exists(taskId: String, name: String): Boolean = artifactFile(taskId, name).isFile

    fun readBytes(taskId: String, name: String): ByteArray? =
        artifactFile(taskId, name).takeIf { it.isFile }?.readBytes()

    fun readText(taskId: String, name: String): String? =
        readBytes(taskId, name)?.toString(Charsets.UTF_8)

    /** 原子写入，返回内容 sha256。 */
    fun writeAtomic(taskId: String, name: String, bytes: ByteArray): String {
        val dir = taskDir(taskId)
        val tmp = File(dir, "$name.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val dst = File(dir, name)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) throw java.io.IOException("原子 rename 失败: $name")
        }
        return sha256(bytes)
    }

    fun writeAtomic(taskId: String, name: String, text: String): String =
        writeAtomic(taskId, name, text.toByteArray(Charsets.UTF_8))

    fun deleteTask(taskId: String) {
        taskDir(taskId).deleteRecursively()
    }

    fun availableBytes(): Long = rootDir.usableSpace

    /** 提交前检查空间：不足则拒绝写入，避免半截状态。 */
    fun requireSpace(bytes: Long) {
        if (availableBytes() < bytes) throw java.io.IOException("存储空间不足，已保留已完成阶段")
    }

    companion object {
        fun sha256(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
