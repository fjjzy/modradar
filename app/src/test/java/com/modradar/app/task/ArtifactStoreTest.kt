package com.modradar.app.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writeAtomic 落盘后可读且 sha256 一致`() {
        val store = ArtifactStore(tmp.newFolder("tasks"))
        val payload = """{"stage":"S1","ok":true}"""

        val sha = store.writeAtomic("task-1", "S1.json", payload)

        assertTrue(store.exists("task-1", "S1.json"))
        assertEquals(payload, store.readText("task-1", "S1.json"))
        assertEquals(ArtifactStore.sha256(payload.toByteArray()), sha)
    }

    @Test
    fun `writeAtomic 不留下临时文件`() {
        val store = ArtifactStore(tmp.newFolder("tasks"))
        store.writeAtomic("task-2", "S2.json", "{}")

        val leftovers = store.taskDir("task-2").listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("不应残留 .tmp 文件", leftovers.isEmpty())
    }

    @Test
    fun `覆盖写入以最后一次为准`() {
        val store = ArtifactStore(tmp.newFolder("tasks"))
        store.writeAtomic("t", "S1.json", "first")
        store.writeAtomic("t", "S1.json", "second")

        assertEquals("second", store.readText("t", "S1.json"))
    }

    @Test
    fun `deleteTask 清空整个任务目录`() {
        val store = ArtifactStore(tmp.newFolder("tasks"))
        store.writeAtomic("t3", "S1.json", "x")
        store.writeAtomic("t3", "S2.json", "y")

        store.deleteTask("t3")

        assertFalse(store.exists("t3", "S1.json"))
        assertFalse(store.exists("t3", "S2.json"))
    }

    @Test
    fun `读取不存在的产物返回 null`() {
        val store = ArtifactStore(tmp.newFolder("tasks"))
        assertNull(store.readText("nope", "S9.json"))
        assertNotNull(store.root)
    }
}
