"""ModRadar UI 原型图 prompt 集（依据 modradar/docs/计划.md 的界面清单）。

约定：
- prompt 全英文（sws-gemini 硬性要求）
- 自带 "Aspect ratio: 9:16" 以拿到竖屏手机比例（脚本检测到后不再追加场景默认比例）
- 末尾给中文标签映射，保证图中文字为中文
"""

STYLE = (
    "High-fidelity Android app UI mockup, single phone screen, flat vector app screenshot, "
    "pure black background (#000000) with Material 3 dark theme, rounded corner cards with subtle "
    "1px borders, blue-violet accent color, soft elevation, generous padding, clean Chinese sans-serif "
    "typography, thin status bar at top, bottom navigation bar, crisp and legible text, "
    "real product screenshot look, NOT a 3D render, no phone frame, no hands. "
    "Aspect ratio: 9:16."
)

PROMPTS = [
    {
        "id": "01-dashboard",
        "title": "模块雷达 · 首页",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of the home dashboard. " + STYLE + " "
            "Layout from top to bottom: a top app bar with the app name on the left and a gear settings icon on the right. "
            "Below it a hero summary card containing a circular score ring showing 82 with a green ring, the words "
            "\"低风险\" beside it and a small label \"上次扫描\". "
            "Then three large tappable entry cards stacked vertically, each with a leading icon, a bold title and a grey subtitle: "
            "first card icon is a document/file, title \"扫描 APK 文件\", subtitle \"选择本地 APK 或模块包\"; "
            "second card icon is a grid of apps, title \"已安装应用\", subtitle \"识别 Xposed 模块\"; "
            "third card icon is a shield, title \"KSU 模块\", subtitle \"需 root · 只读\". "
            "At the bottom a section header \"最近扫描\" followed by three compact list rows, each row has a small app icon, "
            "an app name, a timestamp, and a colored score badge on the right (82 green, 38 red, 64 amber). "
            "Chinese labels for display: \"模块雷达\" -> \"模块雷达\", \"低风险\" -> \"低风险\", \"上次扫描\" -> \"上次扫描\", "
            "\"扫描 APK 文件\" -> \"扫描 APK 文件\", \"选择本地 APK 或模块包\" -> \"选择本地 APK 或模块包\", "
            "\"已安装应用\" -> \"已安装应用\", \"识别 Xposed 模块\" -> \"识别 Xposed 模块\", \"KSU 模块\" -> \"KSU 模块\", "
            "\"需 root · 只读\" -> \"需 root · 只读\", \"最近扫描\" -> \"最近扫描\"."
        ),
    },
    {
        "id": "02-installed-apps",
        "title": "选择目标 · 已安装应用",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a target picker screen listing installed apps. " + STYLE + " "
            "Top app bar titled \"选择目标\" with a back arrow. Below it a rounded search field with a magnifier icon and hint \"搜索应用\". "
            "Then a row of three filter chips: \"全部\" (selected, filled), \"XP 模块\", \"系统应用\". "
            "Then a scrollable list of app rows; each row has a rounded app icon, the app name in bold, the package name and version "
            "in small grey monospace text underneath, and on the right a circular checkbox. Two of the rows show a small blue pill "
            "badge labeled \"XP 模块\" next to the app name. Two rows have checked checkboxes. "
            "At the very bottom a sticky action bar with the text \"已选 2 个\" on the left and a filled primary button \"开始分析\" on the right. "
            "Chinese labels for display: \"选择目标\" -> \"选择目标\", \"搜索应用\" -> \"搜索应用\", \"全部\" -> \"全部\", "
            "\"XP 模块\" -> \"XP 模块\", \"系统应用\" -> \"系统应用\", \"已选 2 个\" -> \"已选 2 个\", \"开始分析\" -> \"开始分析\"."
        ),
    },
    {
        "id": "03-folder",
        "title": "文件夹读取",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a folder browsing screen for picking module packages. " + STYLE + " "
            "Top app bar titled \"文件夹读取\" with a back arrow. Below it a breadcrumb path bar showing \"/sdcard/Download\". "
            "Then a file list where each row has a file icon, the file name in monospace, the file size in grey, and a colored "
            "type tag pill on the right: a row named \"module.zip\" with an orange tag \"KSU 模块\"; a row named \"LSPosed-v1.9.2.apk\" "
            "with a blue tag \"XP 模块\"; a row named \"base.apk\" with a green tag \"APK\"; a greyed row named \"readme.txt\" with no tag. "
            "At the bottom a hint line with an info icon reading \"自动识别类型，无需手动选择\". "
            "Chinese labels for display: \"文件夹读取\" -> \"文件夹读取\", \"KSU 模块\" -> \"KSU 模块\", \"XP 模块\" -> \"XP 模块\", "
            "\"APK\" -> \"APK\", \"自动识别类型，无需手动选择\" -> \"自动识别类型，无需手动选择\"."
        ),
    },
    {
        "id": "04-ksu-modules",
        "title": "KSU 模块（root 只读）",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a KernelSU module list screen. " + STYLE + " "
            "Top app bar titled \"KSU 模块\" with a refresh icon. Below it a green status banner card with a check icon reading "
            "\"已获取 root\" and a small grey line \"只读模式 · 不修改任何文件\". "
            "Then a list of module cards; each card shows a module name in bold, a version line, an author line, a short description, "
            "and on the right a small state pill: the first two cards show a green pill \"已启用\", the third card is dimmed with a grey "
            "pill \"已停用\". One card has a small amber warning triangle with the text \"脚本含下载执行\". "
            "At the bottom a footnote in small grey text: \"仅读取 module.prop，不做任何启停操作\". "
            "Chinese labels for display: \"KSU 模块\" -> \"KSU 模块\", \"已获取 root\" -> \"已获取 root\", "
            "\"只读模式 · 不修改任何文件\" -> \"只读模式 · 不修改任何文件\", \"已启用\" -> \"已启用\", \"已停用\" -> \"已停用\", "
            "\"脚本含下载执行\" -> \"脚本含下载执行\", \"仅读取 module.prop，不做任何启停操作\" -> \"仅读取 module.prop，不做任何启停操作\"."
        ),
    },
    {
        "id": "05-scanning",
        "title": "分析进行中（运行时）",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of an in-progress analysis screen with live runtime feedback. " + STYLE + " "
            "At the top a centered animated circular progress ring showing 62 percent with the label \"分析中\" underneath. "
            "Below it a vertical stepper with six stages, each with a small status icon: \"装载\" with a green check, "
            "\"结构提取\" with a green check, \"能力链分析\" with a glowing blue pulsing dot and bold text, "
            "\"规则评分\" with an empty grey circle, \"AI 研判\" with an empty grey circle, \"生成报告\" with an empty grey circle. "
            "Then a dark monospace log card with a subtle border containing four short log lines with timestamps: "
            "\"classes.dex · 扫描 12480 个方法\", \"命中 SOURCE: OkHttp\", \"命中 SINK: DexClassLoader\", \"正在追踪调用链 (3/4 层)\". "
            "At the bottom a secondary outlined button labeled \"取消\". "
            "Chinese labels for display: \"分析中\" -> \"分析中\", \"装载\" -> \"装载\", \"结构提取\" -> \"结构提取\", "
            "\"能力链分析\" -> \"能力链分析\", \"规则评分\" -> \"规则评分\", \"AI 研判\" -> \"AI 研判\", \"生成报告\" -> \"生成报告\", "
            "\"取消\" -> \"取消\"."
        ),
    },
    {
        "id": "06-report",
        "title": "风险报告",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a risk report screen. " + STYLE + " "
            "Top app bar titled \"风险报告\" with a share icon. Below it a centered large circular score ring in red showing 38 "
            "with the words \"高风险\" underneath, and a row of three small summary chips: \"3 高危\" red, \"5 中危\" amber, \"4 低危\" grey. "
            "Then grouped finding cards. First card is a red-tinted card with a warning icon, title \"远端下载并执行代码\", "
            "a small tag \"C1\", a grey explanation line, and a right-aligned red text \"−35\" plus a small link \"查看证据\". "
            "Second card is an amber-tinted card titled \"危险权限组合\" with \"−20\" and \"查看证据\". "
            "Third card is a neutral grey card titled \"权限与 API 不匹配\" with \"−10\". "
            "Fourth card is an info card with a fingerprint icon titled \"签名证书 SHA256\" and a monospace hash line. "
            "At the bottom a sticky filled primary button \"AI 深度分析\". "
            "Chinese labels for display: \"风险报告\" -> \"风险报告\", \"高风险\" -> \"高风险\", \"3 高危\" -> \"3 高危\", "
            "\"5 中危\" -> \"5 中危\", \"4 低危\" -> \"4 低危\", \"远端下载并执行代码\" -> \"远端下载并执行代码\", "
            "\"查看证据\" -> \"查看证据\", \"危险权限组合\" -> \"危险权限组合\", \"权限与 API 不匹配\" -> \"权限与 API 不匹配\", "
            "\"签名证书 SHA256\" -> \"签名证书 SHA256\", \"AI 深度分析\" -> \"AI 深度分析\"."
        ),
    },
    {
        "id": "07-evidence-chain",
        "title": "能力链证据详情",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of an evidence chain detail screen. " + STYLE + " "
            "Top app bar titled \"证据链\" with a back arrow. "
            "Below it a horizontal three-node chain diagram connected by arrows: first node labeled \"网络请求\" with a small globe icon, "
            "second node labeled \"Base64 解密\" with a lock icon, third node labeled \"动态加载\" with a warning icon highlighted in red. "
            "Under the chain a card titled \"代码证据\" containing a small dark code block with four lines of Java code, "
            "two lines highlighted with a red left border, and a grey monospace location line \"com.a.b#c · classes.dex · 0x1a2b\". "
            "Then an amber warning banner with a shield icon reading \"该 APK 被加固，代码不可审计\" and a smaller grey line "
            "\"已标记为不可审计并计入风险\". At the bottom a secondary button \"复制证据\". "
            "Chinese labels for display: \"证据链\" -> \"证据链\", \"网络请求\" -> \"网络请求\", \"Base64 解密\" -> \"Base64 解密\", "
            "\"动态加载\" -> \"动态加载\", \"代码证据\" -> \"代码证据\", \"该 APK 被加固，代码不可审计\" -> \"该 APK 被加固，代码不可审计\", "
            "\"已标记为不可审计并计入风险\" -> \"已标记为不可审计并计入风险\", \"复制证据\" -> \"复制证据\"."
        ),
    },
    {
        "id": "08-ai-workspace",
        "title": "AI 逆向工作台",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of an AI reverse-engineering chat workspace with tool calls. " + STYLE + " "
            "Top app bar titled \"AI 逆向工作台\" with a more icon on the right. "
            "Below it a chat stream containing exactly four elements, with as little text as possible: "
            "a right-aligned blue user bubble with the single short line \"会从远端下载代码执行吗？\"; "
            "a left-aligned assistant card with a small wrench icon, a monospace tool name \"search_strings(http)\" "
            "and one short grey status line \"完成 · 37 条\"; "
            "a second left-aligned assistant card with a monospace tool name \"decompile_class(com.a.b)\" and a small dark code block "
            "holding only two very short lines of code; "
            "a third left-aligned conclusion card with a small tag \"AI 判定\" and exactly two short bullet lines. "
            "At the bottom a rounded input bar with the hint \"追问…\", a wrench icon on the left and a filled circular send button. "
            "Render every Chinese character cleanly; do not add extra paragraphs or placeholder sentences. "
            "Chinese labels for display: \"AI 逆向工作台\" -> \"AI 逆向工作台\", \"完成 · 37 条\" -> \"完成 · 37 条\", "
            "\"AI 判定\" -> \"AI 判定\", \"追问…\" -> \"追问…\"."
        ),
    },
    {
        "id": "09-ai-settings",
        "title": "AI 设置",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of an AI configuration settings screen. " + STYLE + " "
            "Top app bar titled \"AI 设置\" with a back arrow. "
            "Below it a grouped settings card titled \"模型配置\" containing four rows separated by thin dividers: "
            "\"协议\" with value \"OpenAI 兼容\" and a chevron; \"API 地址\" with a monospace value \"https://api.example.com/v1\"; "
            "\"API Key\" with a masked value of dots; \"模型\" with value \"deepseek-v4-flash\". "
            "Then a second row group with \"思考等级\" showing \"自动\" and a chevron. "
            "Then a full width outlined button \"测试连接\" followed by a green success chip with a check icon reading \"连接正常\". "
            "Then a third card titled \"数据边界\" with two grey bullet lines: \"仅上传特征摘要与反编译片段\" and \"不上传 APK 本体\". "
            "At the bottom a small grey footnote \"API Key 仅保存在本机\". "
            "Chinese labels for display: \"AI 设置\" -> \"AI 设置\", \"模型配置\" -> \"模型配置\", \"协议\" -> \"协议\", "
            "\"OpenAI 兼容\" -> \"OpenAI 兼容\", \"API 地址\" -> \"API 地址\", \"模型\" -> \"模型\", \"思考等级\" -> \"思考等级\", "
            "\"自动\" -> \"自动\", \"测试连接\" -> \"测试连接\", \"连接正常\" -> \"连接正常\", \"数据边界\" -> \"数据边界\", "
            "\"仅上传特征摘要与反编译片段\" -> \"仅上传特征摘要与反编译片段\", \"不上传 APK 本体\" -> \"不上传 APK 本体\", "
            "\"API Key 仅保存在本机\" -> \"API Key 仅保存在本机\"."
        ),
    },
    {
        "id": "10-history",
        "title": "历史记录",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a scan history screen. " + STYLE + " "
            "Top app bar titled \"历史记录\" with a search icon and a filter icon. "
            "Below it a horizontal row of filter chips \"全部\", \"高风险\", \"已配 AI\". "
            "Then a list of six history rows; each row has a rounded app icon on the left, the app or module name in bold, "
            "a grey timestamp line such as \"今天 14:32\", a small grey tag showing the target type (\"APK\" or \"XP 模块\" or \"KSU 模块\"), "
            "and on the right a colored score badge (82 green \"低\", 38 red \"高\", 64 amber \"中\") with a chevron. "
            "One row has a small grey pill \"未完成 AI 研判\". "
            "Chinese labels for display: \"历史记录\" -> \"历史记录\", \"全部\" -> \"全部\", \"高风险\" -> \"高风险\", "
            "\"已配 AI\" -> \"已配 AI\", \"APK\" -> \"APK\", \"XP 模块\" -> \"XP 模块\", \"KSU 模块\" -> \"KSU 模块\", "
            "\"未完成 AI 研判\" -> \"未完成 AI 研判\"."
        ),
    },
    {
        "id": "11-consent",
        "title": "首启协议同意页",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of a first-launch consent screen. " + STYLE + " "
            "Centered at the top a rounded app icon with a radar and shield motif, then the app name in large bold text "
            "\"模块雷达\" and a grey subtitle \"APK · Xposed · KernelSU 模块分析\". "
            "Below, two scrollable rounded text cards with titles \"用户协议\" and \"隐私政策\", each containing several short lines "
            "of small grey placeholder text. Under the cards a row with a checkbox and the text \"我已阅读并同意《用户协议》与《隐私政策》\". "
            "At the bottom a full width filled primary button \"同意并继续\" and beneath it a plain text button \"不同意\" in grey. "
            "Chinese labels for display: \"模块雷达\" -> \"模块雷达\", \"APK · Xposed · KernelSU 模块分析\" -> \"APK · Xposed · KernelSU 模块分析\", "
            "\"用户协议\" -> \"用户协议\", \"隐私政策\" -> \"隐私政策\", "
            "\"我已阅读并同意《用户协议》与《隐私政策》\" -> \"我已阅读并同意《用户协议》与《隐私政策》\", "
            "\"同意并继续\" -> \"同意并继续\", \"不同意\" -> \"不同意\"."
        ),
    },
    {
        "id": "12-about",
        "title": "关于",
        "scene": "product_prototype",
        "prompt": (
            "A high-fidelity Android app UI mockup of an about screen. " + STYLE + " "
            "Top app bar titled \"关于\" with a back arrow. "
            "At the top a centered app icon, the app name \"模块雷达\" in bold, and a grey version line \"版本 0.1.0\". "
            "Then a grouped list card with rows separated by thin dividers, each row has a leading icon, a label and a chevron: "
            "\"开源许可\" with value \"GPL-3.0\", \"隐私政策\", \"用户协议\", \"导出日志\", \"GitHub 仓库\". "
            "Below the list a small grey disclaimer block with two lines: \"本工具仅用于分析你有权处理的应用与模块\" and "
            "\"AI 结论仅供参考，不构成安全结论\". "
            "Chinese labels for display: \"关于\" -> \"关于\", \"模块雷达\" -> \"模块雷达\", \"版本 0.1.0\" -> \"版本 0.1.0\", "
            "\"开源许可\" -> \"开源许可\", \"GPL-3.0\" -> \"GPL-3.0\", \"隐私政策\" -> \"隐私政策\", \"用户协议\" -> \"用户协议\", "
            "\"导出日志\" -> \"导出日志\", \"GitHub 仓库\" -> \"GitHub 仓库\", "
            "\"本工具仅用于分析你有权处理的应用与模块\" -> \"本工具仅用于分析你有权处理的应用与模块\", "
            "\"AI 结论仅供参考，不构成安全结论\" -> \"AI 结论仅供参考，不构成安全结论\"."
        ),
    },
]
