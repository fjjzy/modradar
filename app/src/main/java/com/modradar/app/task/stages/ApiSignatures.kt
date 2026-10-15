package com.modradar.app.task.stages

/** API 能力分类表：命中即视为该能力存在。 */
object ApiSignatures {

    /** 外部数据来源：网络 / 外部输入 / 文件。 */
    val SOURCES: List<Pair<String, String>> = listOf(
        "Lokhttp3/" to "OkHttp",
        "Lokio/" to "Okio",
        "Ljava/net/HttpURLConnection;" to "HttpURLConnection",
        "Ljava/net/URL;" to "java.net.URL",
        "Ljava/net/Socket;" to "Socket",
        "Ljava/net/ServerSocket;" to "ServerSocket",
        "Ljava/net/DatagramSocket;" to "DatagramSocket",
        "Landroid/app/DownloadManager;" to "DownloadManager",
        "Landroid/webkit/WebView;" to "WebView",
        "Landroid/content/ContentResolver;" to "ContentResolver",
        "Landroid/content/ClipboardManager;" to "剪贴板",
        "Landroid/content/SharedPreferences;" to "SharedPreferences",
        "Landroid/content/Context;->openFileInput" to "读取内部文件",
        "Ljava/io/FileInputStream;" to "FileInputStream",
        "Ljava/net/URLConnection;" to "URLConnection",
    )

    /** 变换：解码 / 解密 / 解压。 */
    val TRANSFORMS: List<Pair<String, String>> = listOf(
        "Landroid/util/Base64;" to "Base64",
        "Ljava/util/Base64;" to "java.util.Base64",
        "Ljavax/crypto/Cipher;" to "Cipher",
        "Ljavax/crypto/spec/SecretKeySpec;" to "SecretKeySpec",
        "Ljava/util/zip/GZIPInputStream;" to "GZIP",
        "Ljava/util/zip/Inflater;" to "Inflater",
        "Ljava/security/MessageDigest;" to "MessageDigest",
        "Ljavax/crypto/Mac;" to "Mac",
    )

    /** 危险汇点：动态加载 / 执行 / 反射 / 脚本。 */
    val SINKS: List<Pair<String, String>> = listOf(
        "Ldalvik/system/DexClassLoader;" to "DexClassLoader",
        "Ldalvik/system/InMemoryDexClassLoader;" to "InMemoryDexClassLoader",
        "Ldalvik/system/PathClassLoader;" to "PathClassLoader",
        "Ldalvik/system/BaseDexClassLoader;" to "BaseDexClassLoader",
        "Ljava/lang/ClassLoader;->defineClass" to "defineClass",
        "Ljava/lang/System;->load" to "System.load",
        "Ljava/lang/System;->loadLibrary" to "System.loadLibrary",
        "Ljava/lang/Runtime;->exec" to "Runtime.exec",
        "Ljava/lang/ProcessBuilder;" to "ProcessBuilder",
        "Ljava/lang/reflect/Method;->invoke" to "反射 invoke",
        "Ljava/lang/Class;->forName" to "Class.forName",
        "Landroid/webkit/WebView;->evaluateJavascript" to "evaluateJavascript",
        "Landroid/webkit/WebView;->loadUrl" to "WebView.loadUrl",
        "Lorg/mozilla/javascript/" to "Rhino",
        "Lorg/luaj/vm2/" to "LuaJava",
        "Ljavax/script/ScriptEngine" to "ScriptEngine",
        "Landroid/system/Os;->chmod" to "chmod",
        "Landroid/system/Os;->exec" to "Os.exec",
        "Lcom/termux/" to "Termux 运行时",
        "Landroid/database/sqlite/SQLiteDatabase;->rawQuery" to "rawQuery 拼接",
    )

    /**
     * 几乎所有应用都会用到的「弱汇点 / 弱变换」：反射、loadLibrary、WebView 加载、哈希校验等。
     * 它们单独出现不说明问题，计入能力链只会制造大量误报，因此不参与 C1–C5 判定。
     */
    val WEAK: Set<String> = setOf(
        "反射 invoke", "Class.forName", "System.loadLibrary", "PathClassLoader", "BaseDexClassLoader",
        "WebView.loadUrl", "evaluateJavascript", "rawQuery 拼接", "chmod",
        "MessageDigest", "Mac",
    )

    /** 远端数据来源：只有这些来源 + 强汇点才构成「远端取代码 → 本地执行」。 */
    val REMOTE_SOURCES: Set<String> = setOf(
        "OkHttp", "Okio", "HttpURLConnection", "java.net.URL", "Socket",
        "ServerSocket", "DatagramSocket", "DownloadManager", "URLConnection", "WebView",
    )

    fun classifyMethod(definingClass: String, name: String): Triple<Set<String>, Set<String>, Set<String>> {
        val sig = definingClass + "->" + name
        val s = mutableSetOf<String>()
        val t = mutableSetOf<String>()
        val k = mutableSetOf<String>()

        fun match(list: List<Pair<String, String>>, out: MutableSet<String>) {
            for ((prefix, label) in list) {
                val hit = when {
                    prefix.endsWith(";") -> definingClass.startsWith(prefix) || definingClass == prefix
                    prefix.contains("->") -> sig.startsWith(prefix)
                    else -> definingClass.startsWith(prefix)
                }
                if (hit) out.add(label)
            }
        }
        match(SOURCES, s)
        match(TRANSFORMS, t)
        match(SINKS, k)
        return Triple(s, t, k)
    }
}
