package com.modradar.app.task.stages

/**
 * 规则释义：把规则编号翻译成「这是什么 / 可能造成什么 / 什么情况下是正常的」。
 * 报告与证据页用它给普通用户解释，AI 追问时也会带上。
 */
object RuleGlossary {

    data class Entry(val what: String, val harm: String, val benign: String)

    private val MAP: Map<String, Entry> = mapOf(
        "C1" to Entry(
            "同一个方法里既从网络取数据，又调用了动态加载 / 执行命令的接口。",
            "最坏情况：应用可以在你不知情时从服务器下载一段新代码并立刻运行，安装时审核过的功能可以被随时替换。",
            "热更新、插件化框架会这样做；若下载地址是开发者自己的域名且有签名校验，风险较低。",
        ),
        "C2" to Entry(
            "从网络取数据的方法，经过几层调用后能到达动态加载 / 执行接口。",
            "具备「远端下发代码 → 本地执行」的完整通路，可绕过应用商店审核改变行为。",
            "大型应用的统一网络层与插件加载器可能在调用图上连通，但实际并不传递代码；需要看具体方法确认。",
        ),
        "C3" to Entry(
            "包里存在动态加载 DEX / 执行 shell 的能力，但没找到它们的外部数据来源。",
            "能力本身不危险，若之后配合下载或解密，可用于运行隐藏代码。",
            "Xposed 模块、加固壳、插件框架普遍具备，单独出现通常不说明问题。",
        ),
        "C4" to Entry(
            "应用同时具备联网能力和执行 / 动态加载能力，但没有找到两者直接相连的代码。",
            "弱信号：理论上可以组合成远端执行，但静态分析没有证据表明两者连通。",
            "绝大多数联网应用都会命中，作为参考即可。",
        ),
        "C5" to Entry(
            "在同一个方法里先解密 / 解压数据，紧接着就加载或执行它。",
            "典型的「藏代码」手法：把真正的逻辑加密存放，运行时才解出来执行，躲避静态审查。",
            "加固壳、资源保护也会这样做；需要结合解密的数据来源判断。",
        ),
        "C6" to Entry(
            "包内有已知加固 / 壳的特征文件。",
            "真实代码被加密隐藏，静态分析看不到实际行为，无法证明它是安全的。",
            "很多正规商业应用为防破解会加固，本身不代表恶意。",
        ),
        "C8" to Entry(
            "native 库同时导入了动态加载、修改内存权限、执行进程/ptrace 和网络相关的函数。",
            "具备在 native 层下载并执行代码、修改其它代码的能力，比 Java 层更难审查。",
            "Hook 框架（LSPlant、Dobby 等）和游戏引擎需要这些函数；Xposed 模块中属于正常。",
        ),
        "ANTIDEBUG" to Entry(
            "代码里出现了检测调试器、Frida、Xposed、Root、模拟器的特征。",
            "应用在主动探测运行环境，可能用于对抗安全分析，或在检测到分析环境时隐藏真实行为。",
            "银行、游戏等为防作弊普遍会做；Xposed / KSU 模块引用这些关键字是本职。",
        ),
        "SDK" to Entry("集成了统计、广告或推送 SDK。", "这些 SDK 会收集设备信息与使用行为并上传给第三方。", "国内应用普遍集成，属于常见的商业行为。"),
        "NET_CLEARTEXT" to Entry("应用允许走不加密的 http 流量。", "同一 Wi-Fi 下的人可以看到甚至篡改传输内容。", "只访问局域网设备（如 NAS、路由器）的工具类应用需要开启。"),
        "NET_HTTP" to Entry("代码里写死了 http:// 明文地址。", "若这些地址用于传输数据，内容可被窃听或篡改。", "很多只是文档链接或命名空间字符串，不会真的被请求。"),
        "NET_TRACKER" to Entry("直接连接已知的统计 / 广告域名。", "你的使用数据会被发送给第三方统计平台。", "与集成 SDK 的情况类似，较常见。"),
        "PERM_COMBO" to Entry("同时申请了悬浮窗、无障碍、通知监听等「高权限组合」。", "这是木马常用组合：可以读取屏幕内容、模拟点击、拦截通知（含验证码）。", "自动化工具、读屏辅助类应用确实需要，需确认用途与声明一致。"),
        "PERM_SENSITIVE" to Entry("申请了短信、通话记录、定位、录音、通讯录等敏感权限。", "可读取你的隐私数据，若与功能无关则属于过度索取。", "功能确实需要时属于正常，可在系统设置里按需授权。"),
        "COMP_SR" to Entry("有 service / receiver 对外开放且没有权限保护。", "其它应用可以直接调用它们，可能触发敏感操作或泄露数据。", "推送、小部件等组件需要导出；Xposed 模块的入口组件也常导出。"),
        "COMP_AP" to Entry("有 activity / provider 对外开放。", "其它应用可直接打开页面或读取 provider 数据。", "启动页、分享入口必须导出，多数情况下正常。"),
        "DBG" to Entry("安装包开启了 debuggable 调试标志。", "任何人都能用调试器附加、读取内存和注入代码。", "开发测试版本常见，正式发布版不应开启。"),
        "BACKUP" to Entry("允许通过 adb backup 导出应用数据。", "拿到你手机的人可以导出该应用的本地数据。", "影响有限，需物理接触设备。"),
        "SDK_OLD" to Entry("targetSdk 很低。", "可以绕开新版 Android 的隐私与安全限制。", "老应用或特殊工具常见。"),
        "SIG_NONE" to Entry("安装包没有可验证的签名。", "无法确认来源，可能被篡改过。", "KSU / Magisk 模块 zip 本身不支持签名；从源码自行构建的测试包也可能如此。"),
        "LSPATCH" to Entry("应用被 LSPatch 重打包，把 Xposed 模块直接塞进了安装包。", "内嵌模块以该应用身份运行，可读取它的全部数据与权限；签名也被替换，无法再收到官方更新。", "你自己用 LSPatch 给常用应用加模块时出现，属预期。"),
        "SRC_MODIFIED" to Entry("与 GitHub 官方 release 比对后不一致。", "可能是第三方修改版、重打包或植入了额外代码。", "作者换过签名证书，或你拿到的是 CI / 测试构建。"),
        "SIG_DEBUG" to Entry("使用 Android 调试证书签名。", "任何人都可以用同一证书签名伪造更新包。", "开源项目的 CI 构建、个人模块常用调试签名。"),
        "SIG_V1ONLY" to Entry("只有旧版 v1 签名。", "完整性保护较弱，存在已知的签名绕过问题。", "面向老系统的应用常见。"),
        "KSU_SCRIPT" to Entry("模块脚本里有下载执行、关闭 SELinux、写块设备等危险操作。", "以 Root 权限运行，可以完全控制系统、植入后门或损坏设备。", "极少数系统级工具确有需要，必须确认作者可信。"),
        "KSU_OPS" to Entry("模块脚本里的改属性、挂载、后台常驻等操作。", "这些是模块实现功能的常规手段。", "属于 KSU / Magisk 模块的本职工作。"),
        "KSU_PROP" to Entry("module.prop 缺少必备字段。", "管理器可能无法正确识别、更新或卸载该模块。", "不影响安全，多为作者疏忽。"),
    )

    fun of(ruleId: String): Entry? = MAP[ruleId]

    /** API 标签的通俗说明（证据流程图里使用）。 */
    private val API: Map<String, String> = mapOf(
        "OkHttp" to "网络请求", "Okio" to "网络/文件读写", "HttpURLConnection" to "网络请求", "java.net.URL" to "访问网址",
        "Socket" to "直连服务器", "ServerSocket" to "监听端口", "DatagramSocket" to "UDP 通信", "DownloadManager" to "系统下载",
        "URLConnection" to "网络请求", "WebView" to "网页内容",
        "Base64" to "编码转换", "java.util.Base64" to "编码转换", "Cipher" to "加解密", "SecretKeySpec" to "密钥",
        "GZIP" to "解压", "Inflater" to "解压",
        "DexClassLoader" to "加载外部代码", "InMemoryDexClassLoader" to "从内存加载代码", "defineClass" to "动态定义类",
        "System.load" to "加载 native 库", "Runtime.exec" to "执行命令", "ProcessBuilder" to "启动进程",
        "Rhino" to "运行 JS 脚本", "LuaJava" to "运行 Lua 脚本", "ScriptEngine" to "运行脚本", "Os.exec" to "执行命令",
        "Termux 运行时" to "终端环境",
    )

    fun apiLabel(api: String): String = API[api]?.let { "$it（$api）" } ?: api
}
