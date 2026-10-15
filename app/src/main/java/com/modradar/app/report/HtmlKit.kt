package com.modradar.app.report

/**
 * 手机端 HTML 的公共外壳：固定样式 + 片段清洗。
 *
 * - 视口锁定为屏幕宽度，所有长串（路径、类名、证书主体）强制断行，页面不会出现横向滚动。
 * - AI 对话里的 HTML 片段只允许使用这里预置的 class（见 [COMPONENTS]），
 *   清洗掉脚本、样式、事件属性和外部资源，版式由本地 CSS 决定。
 */
object HtmlKit {

    fun page(title: String, body: String): String = buildString {
        append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">")
        append("<title>").append(esc(title)).append("</title>")
        append("<style>").append(CSS).append("</style></head><body><main>")
        append(body)
        append("</main></body></html>")
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** 转义后只放行 `行内代码` 与 **加粗** 两种标记。 */
    fun rich(s: String): String {
        var out = Regex("`([^`]+)`").replace(esc(s)) { "<span class=\"ic\">${it.groupValues[1]}</span>" }
        out = Regex("\\*\\*([^*]+)\\*\\*").replace(out) { "<b>${it.groupValues[1]}</b>" }
        return out
    }

    private val DROP_BLOCKS = Regex(
        "<(script|style|iframe|object|embed|noscript|template|svg|math|form|head|title)\\b[\\s\\S]*?</\\1\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val DROP_TAGS = Regex(
        "</?(script|style|iframe|object|embed|link|meta|base|form|input|button|textarea|select|img|video|audio|source|html|body|frame|frameset)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val EVENT_ATTR = Regex("\\s(on[a-z]+|style|src|srcset|href|action|formaction|xlink:href)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)

    /** 清洗模型生成的 HTML 片段：去脚本/样式/外链/事件，保留结构与 class。 */
    fun sanitize(fragment: String): String {
        var s = fragment.replace(Regex("<!--[\\s\\S]*?-->"), "")
        s = DROP_BLOCKS.replace(s, "")
        s = DROP_TAGS.replace(s, "")
        s = EVENT_ATTR.replace(s, "")
        return s.replace(Regex("javascript:", RegexOption.IGNORE_CASE), "")
    }

    /** 片段里的第一个标题，作为聊天卡片的标题。 */
    fun firstHeading(fragment: String): String? =
        Regex("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE).find(fragment)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotEmpty() }

    /** 提示词里给模型的组件说明，与 [CSS] 一一对应。 */
    const val COMPONENTS = """
- <h3>标题</h3>、<p>、<b>、<code>
- 卡片：<div class="card">…</div>，按风险着色加 hi / mid / low / ok：<div class="card hi">
- 指标宫格：<div class="grid"><div class="tile ok"><b>未发现</b><span>远程代码</span></div>…</div>（2 列，值在上、标签在下）
- 标签：<span class="tag mid">需关注</span>
- 流程（数据流/调用链）：<ol class="flow"><li>读取剪贴板</li><li>Base64 编码</li><li>POST 到 x.com</li></ol>
- 导图（树）：<ul class="tree"><li>模块<ul><li>Hook 输入法</li><li>远端皮肤</li></ul></li></ul>
- 折叠细节：<details><summary>证据</summary>…</details>
- 要点清单：<ul class="check"><li>…</li></ul>"""

    private val CSS = """
*{box-sizing:border-box;min-width:0}
html,body{margin:0;max-width:100%;overflow-x:hidden;-webkit-text-size-adjust:100%}
body{background:#000;color:#E6E6F0;font:15px/1.7 -apple-system,"Noto Sans SC","PingFang SC",sans-serif;
padding:12px 12px 28px;overflow-wrap:anywhere;word-break:break-word}
main{max-width:640px;margin:0 auto}
section{background:#16161C;border-radius:18px;padding:14px;margin:0 0 10px}
h1{font-size:19px;line-height:1.4;margin:8px 0 6px}
h2{font-size:13px;letter-spacing:.05em;color:#8C8CFF;margin:0 0 10px;font-weight:600;display:flex;align-items:center;gap:6px}
h2::before{content:"";width:4px;height:14px;border-radius:2px;background:#8C8CFF}
h3{font-size:15px;margin:12px 0 6px}
p{margin:0 0 8px}b{color:#fff}.dim{color:#9A9AB0;font-size:12px}
code{display:block;background:#0C0C10;border-radius:8px;padding:6px 8px;margin:6px 0 0;
font:12px/1.5 ui-monospace,Menlo,monospace;color:#B9B9FF;white-space:pre-wrap;word-break:break-all}
.ic{font:13px ui-monospace,Menlo,monospace;background:#0C0C10;color:#B9B9FF;border-radius:4px;padding:0 4px;word-break:break-all}
.card{--c:#3A3A48;background:#1E1E26;border-left:4px solid var(--c);border-radius:12px;padding:10px 12px;margin:0 0 8px}
.card>:last-child{margin-bottom:0}
.tag{--c:#9A9AB0;display:inline-block;border-radius:999px;padding:0 9px;font-size:12px;line-height:20px;font-weight:700;
color:#000;background:var(--c);white-space:nowrap;vertical-align:middle}
.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:0 0 8px}
.tile{--c:#8C8CFF;background:#1E1E26;border-radius:12px;padding:10px;border-top:3px solid var(--c)}
.tile b{display:block;font-size:16px;line-height:1.35;color:var(--c)}
.tile span{display:block;font-size:12px;color:#9A9AB0;margin-top:2px}
.flow{list-style:none;margin:0 0 8px;padding:0;counter-reset:f}
.flow li{counter-increment:f;position:relative;background:#1E1E26;border-radius:10px;padding:8px 10px 8px 38px;margin:0 0 18px}
.flow li::before{content:counter(f);position:absolute;left:10px;top:9px;width:20px;height:20px;border-radius:50%;
background:#8C8CFF;color:#000;font-size:12px;font-weight:700;text-align:center;line-height:20px}
.flow li:not(:last-child)::after{content:"↓";position:absolute;left:16px;bottom:-18px;color:#6E6E80;font-size:13px;line-height:18px}
.flow li:last-child{margin-bottom:0}
.tree,.tree ul{list-style:none;margin:0;padding:0}
.tree ul{margin-left:10px;padding-left:12px;border-left:2px solid #2A2A33}
.tree li{position:relative;padding:3px 0}
.tree ul>li::before{content:"";position:absolute;left:-12px;top:15px;width:10px;border-top:2px solid #2A2A33}
.tree>li{font-weight:700}.tree li li{font-weight:400}
details{background:#1E1E26;border-radius:10px;padding:8px 10px;margin:0 0 8px}
summary{cursor:pointer;color:#B9B9FF;font-size:13px}
ul,ol{margin:0 0 8px;padding-left:20px}li{margin:0 0 4px}
.check{list-style:none;padding:0}.check li{position:relative;padding-left:22px}
.check li::before{content:"✓";position:absolute;left:2px;color:#4CC38A;font-weight:700}
.unk li::before{content:"?";color:#F2B33D}
table{width:100%;border-collapse:collapse;font-size:13px;table-layout:fixed}
th,td{padding:6px 4px;border-bottom:1px solid #22222A;text-align:left;vertical-align:top}
.card.hi,.tile.hi,.tag.hi{--c:#FF5A5F}.card.mid,.tile.mid,.tag.mid{--c:#F2B33D}
.card.low,.tile.low,.tag.low{--c:#8C8CFF}.card.ok,.tile.ok,.tag.ok{--c:#4CC38A}
footer{color:#6E6E80;font-size:11px;text-align:center;margin-top:14px}
"""
}
