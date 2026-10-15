package com.modradar.app.report

import com.modradar.app.task.stages.AiRuleReview
import com.modradar.app.task.stages.AiVerdict
import com.modradar.app.task.stages.ScoreFinding
import com.modradar.app.task.stages.ReportArtifact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 研判的可视化 HTML 报告。
 *
 * 版式与样式由本地固定模板决定，AI 只提供 [AiVerdict] 里的数据字段——
 * 多次研判的外观完全一致，也避免执行模型生成的 HTML / 脚本。所有文本一律转义。
 */
object AiReportHtml {

    fun render(rep: ReportArtifact, v: AiVerdict): String {
        val level = rep.level
        val score = rep.score
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(rep.generatedAt))
        val reviewById = v.ruleReview.associateBy { it.ruleId }
        val body = buildString {
            // 1. 总览：分数环 + 结论，窄屏纵向排列（单一分数：100 − Σ扣分，AI 修正已计入）
            append("<section class=\"hero\" style=\"--c:").append(levelColor(level)).append("\">")
            append("<div class=\"hero-top\"><div class=\"ring\"><svg viewBox=\"0 0 120 120\"><circle cx=\"60\" cy=\"60\" r=\"52\" class=\"bg\"/>")
            val pct = score.coerceIn(0, 100)
            append("<circle cx=\"60\" cy=\"60\" r=\"52\" class=\"fg\" stroke-dasharray=\"")
                .append("%.1f".format(Locale.US, 326.7 * pct / 100)).append(" 326.7\"/></svg>")
            append("<div class=\"num\">").append(score).append("<small>安全分</small></div></div>")
            append("<div class=\"hero-side\"><span class=\"pill\">").append(esc(v.verdict.ifBlank { "未给出" })).append("</span>")
            append("<div class=\"dim\">").append(esc(kindLabel(rep.targetKind))).append("</div>")
            append("<div class=\"dim\">累计扣分 ").append(rep.totalDeduction)
            if (rep.aiAdjustment != 0) {
                append(" · AI 调整 ").append(if (rep.aiAdjustment > 0) "+" else "").append(rep.aiAdjustment)
            }
            append("</div></div></div>")
            append("<h1>").append(esc(v.headline.ifBlank { "AI 研判" })).append("</h1></section>")

            // 2. 关键判断宫格
            if (v.highlights.isNotEmpty()) {
                append("<section><h2>关键判断</h2><div class=\"grid\">")
                v.highlights.take(6).forEach { h ->
                    append("<div class=\"tile ").append(tone(h.tone)).append("\"><b>").append(esc(h.value))
                        .append("</b><span>").append(esc(h.label)).append("</span></div>")
                }
                append("</div></section>")
            }

            // 3. 总体分析：按句拆成要点，避免一大段
            val points = sentences(v.overview)
            if (points.isNotEmpty()) {
                append("<section><h2>总体分析</h2>")
                if (points.size == 1) append("<p>").append(HtmlKit.rich(points[0])).append("</p>")
                else {
                    append("<ul class=\"check dot\">")
                    points.forEach { append("<li>").append(HtmlKit.rich(it)).append("</li>") }
                    append("</ul>")
                }
                append("</section>")
            }

            // 4. 关键发现：卡片 + 折叠证据
            if (v.findings.isNotEmpty()) {
                append("<section><h2>关键发现 · ").append(v.findings.size).append("</h2>")
                v.findings.forEach { f ->
                    append("<div class=\"card ").append(sevTone(f.severity)).append("\">")
                    append("<div class=\"card-h\"><span class=\"tag ").append(sevTone(f.severity)).append("\">")
                        .append(sevLabel(f.severity)).append("</span>")
                    if (f.status.isNotBlank()) append("<span class=\"st\">").append(esc(f.status)).append("</span>")
                    append("</div><b class=\"ft\">").append(esc(f.title)).append("</b>")
                    if (f.impact.isNotBlank()) append("<p>").append(HtmlKit.rich(f.impact)).append("</p>")
                    if (f.evidence.isNotBlank()) {
                        append("<details><summary>证据定位</summary><code>").append(esc(f.evidence)).append("</code></details>")
                    }
                    append("</div>")
                }
                append("</section>")
            }

            // 5. 规则复核：每条一行卡片，误报单独收起（加重项显示追加值）
            val deducted = rep.findings.filter { it.deduction != 0 || it.aiDismissed || it.aiAggravated }
            // 只复核本地真实存在的规则，模型臆造的规则编号不展示
            val known = rep.findings.map { it.ruleId }.toSet()
            val ids = (deducted.map { it.ruleId } + v.ruleReview.map { it.ruleId }.filter { it in known }).distinct()
            if (ids.isNotEmpty()) {
                val (fp, rest) = ids.partition { reviewById[it]?.judgement == "误报" }
                append("<section><h2>本地规则复核</h2>")
                rest.forEach { id -> ruleRow(this, id, deducted.firstOrNull { it.ruleId == id }, reviewById[id]) }
                if (fp.isNotEmpty()) {
                    append("<details><summary>AI 判定误报 ").append(fp.size).append(" 条 · 已退回扣分</summary>")
                    fp.forEach { id -> ruleRow(this, id, deducted.firstOrNull { it.ruleId == id }, reviewById[id]) }
                    append("</details>")
                }
                append("</section>")
            }

            if (v.privacy.isNotBlank()) {
                append("<section><h2>隐私与权限</h2><p>").append(HtmlKit.rich(v.privacy)).append("</p></section>")
            }
            list(this, "建议", v.recommendations, "check")
            list(this, "无法判断的部分", v.unknowns, "check unk")

            append("<footer>ModRadar 本地模板 · ").append(esc(rep.aiModel ?: "AI"))
            append(" · ").append(time).append("<br>基于静态证据，仅供参考，不构成安全担保</footer>")
        }
        return HtmlKit.page("${rep.targetName} · ModRadar AI 研判", "<style>$CSS</style>$body")
    }

    private fun ruleRow(sb: StringBuilder, id: String, f: ScoreFinding?, r: AiRuleReview?) {
        val ded = f?.originalDeduction?.takeIf { it != 0 } ?: f?.deduction
        sb.append("<div class=\"rule\"><div class=\"rule-h\"><b>").append(esc(id)).append("</b>")
        sb.append("<span class=\"tag ").append(judgeTone(r?.judgement)).append("\">")
            .append(esc(r?.judgement?.ifBlank { null } ?: "未复核")).append("</span>")
        if (ded != null && ded != 0) {
            sb.append("<span class=\"ded").append(if (f?.aiDismissed == true) " struck" else "").append("\">").append(ded).append("</span>")
        }
        if (f?.aiAggravated == true && f.aiAdjustment != 0) {
            sb.append("<span class=\"ded\">").append(f.deduction).append("</span>")
        }
        sb.append("</div>")
        f?.title?.takeIf { it.isNotBlank() }?.let { sb.append("<div class=\"dim\">").append(esc(it)).append("</div>") }
        r?.reason?.takeIf { it.isNotBlank() }?.let { sb.append("<p>").append(HtmlKit.rich(it)).append("</p>") }
        sb.append("</div>")
    }

    private fun list(sb: StringBuilder, title: String, items: List<String>, cls: String) {
        if (items.isEmpty()) return
        sb.append("<section><h2>").append(title).append("</h2><ul class=\"").append(cls).append("\">")
        items.forEach { sb.append("<li>").append(HtmlKit.rich(it)).append("</li>") }
        sb.append("</ul></section>")
    }

    /** 按中文句号/分号拆句；太短的并入上一句。 */
    internal fun sentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[。；！？])|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in parts) if (out.isNotEmpty() && p.length < 8) out[out.lastIndex] = out.last() + p else out += p
        return out
    }

    fun esc(s: String): String = HtmlKit.esc(s)

    private fun tone(t: String) = when (t.lowercase()) { "ok", "hi", "mid", "low" -> t.lowercase(); else -> "low" }
    private fun sevTone(sev: String) = when (sev.uppercase()) { "CRITICAL", "HIGH" -> "hi"; "MEDIUM" -> "mid"; "LOW" -> "low"; else -> "ok" }
    private fun judgeTone(j: String?) = when (j) { "误报" -> "ok"; "加重" -> "hi"; "成立" -> "mid"; else -> "" }

    private fun levelColor(level: String) = when (level) {
        "LOW" -> "#4CC38A"; "MEDIUM" -> "#F2B33D"; "HIGH" -> "#FF8A3D"; "CRITICAL" -> "#FF5A5F"; else -> "#9A9AB0"
    }

    private fun sevLabel(sev: String) = when (sev.uppercase()) {
        "CRITICAL" -> "严重"; "HIGH" -> "高"; "MEDIUM" -> "中"; "LOW" -> "低"; else -> "提示"
    }

    private fun kindLabel(k: String) = when (k) {
        "XPOSED_MODULE" -> "Xposed 模块"; "KSU_MODULE" -> "KSU 模块"; "APK" -> "APK"; else -> k
    }

    /** 报告专用样式，叠加在 [HtmlKit] 公共样式之上。 */
    private val CSS = """
.hero{border:1px solid var(--c)}
.hero-top{display:flex;align-items:center;gap:14px}
.ring{position:relative;width:96px;height:96px;flex:none}.ring svg{width:100%;height:100%;transform:rotate(-90deg)}
.ring circle{fill:none;stroke-width:10;stroke-linecap:round}.ring .bg{stroke:#2A2A33}.ring .fg{stroke:var(--c)}
.num{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:28px;font-weight:700;color:var(--c);line-height:1.1}
.num small{font-size:11px;color:#9A9AB0;font-weight:400;margin-top:2px}
.hero-side{display:flex;flex-direction:column;gap:4px;align-items:flex-start}
.pill{display:inline-block;padding:2px 12px;border-radius:999px;background:var(--c);color:#000;font-size:13px;font-weight:700}
.card-h{display:flex;gap:8px;align-items:center;margin-bottom:4px}
.st{margin-left:auto;color:#9A9AB0;font-size:12px}
.ft{display:block;font-size:15px;line-height:1.45;margin-bottom:4px}
.card details{background:#16161C;margin:6px 0 0}
.rule{background:#1E1E26;border-radius:12px;padding:8px 12px;margin:0 0 8px}
.rule p{margin:4px 0 0;font-size:14px}
.rule-h{display:flex;gap:8px;align-items:center}
.ded{margin-left:auto;color:#FF8A3D;font-weight:700}.struck{color:#6E6E80;text-decoration:line-through}
.dot li::before{content:"•";color:#8C8CFF;font-size:18px;line-height:1.4}
section>details{margin-top:4px}
""".trimIndent()
}
