package com.modradar.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileTest {

    private val mgr = BackupManager

    @Test
    fun `能解析合法备份 未含密钥时字段为空`() {
        val f = mgr.parse("""{"format":"modradar-backup","version":1,"ai":{"baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat"},"tasks":[]}""")
        assertEquals("deepseek-chat", f.ai!!.model)
        assertNull(f.ai!!.apiKey)
    }

    @Test
    fun `拒绝非备份文件与未来版本`() {
        assertTrue(runCatching { mgr.parse("""{"format":"other"}""") }.exceptionOrNull()!!.message!!.contains("格式不符"))
        assertTrue(runCatching { mgr.parse("""{"format":"modradar-backup","version":9}""") }.exceptionOrNull()!!.message!!.contains("升级"))
        assertTrue(runCatching { mgr.parse("not json") }.exceptionOrNull()!!.message!!.contains("不是有效"))
    }
}
