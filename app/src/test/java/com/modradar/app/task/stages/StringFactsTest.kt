package com.modradar.app.task.stages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringFactsTest {

    @Test
    fun `命名空间 空 URL 与占位符不算明文链接`() {
        listOf(
            "http://", "http://schemas.android.com/apk/res/android", "http://www.android.com/",
            "http://www.w3.org/2001/XMLSchema", "http://%s/api", "http://{host}/x", "http://localhost",
            "http://127.0.0.1:8080/", "http://a", "http://xmlpull.org/v1/doc/features.html",
        ).forEach { assertFalse(it, StringFacts.isMeaningfulUrl(it)) }
    }

    @Test
    fun `真实主机保留`() {
        listOf("http://api.example.cn/v1/stat", "https://raw.githubusercontent.com/a/b/manifest.json", "http://10.1.2.3/x")
            .forEach { assertTrue(it, StringFacts.isMeaningfulUrl(it)) }
    }
}
