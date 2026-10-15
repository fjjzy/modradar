package com.modradar.app.scan.ksu

import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellTreeTest {

    @Test
    fun `解析 ls -1RF 输出 跳过目录 链接与越界路径`() {
        val d = "/data/adb/modules/demo"
        val listing = """
            $d:
            module.prop
            service.sh*
            system/
            link@

            $d/system:
            bin/

            $d/system/bin:
            tool*
            bad name
        """.trimIndent()
        assertEquals(
            listOf("$d/module.prop", "$d/service.sh", "$d/system/bin/tool"),
            RootShell.parseTree(d, listing),
        )
    }
}
