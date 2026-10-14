#!/usr/bin/env python3
"""批量生成 ModRadar UI 原型图。

prompt 组装规则与 work-image-gen 的 assemble_prompt 保持一致：
  最终 prompt = 精炼英文 prompt + 中文文字兜底声明 + 标题声明
（宽高比已写在 prompt 内，脚本检测到后不会重复追加）

直接调用 sws-gemini 以获得固定的输出文件名与 2K 分辨率。
"""

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from prompts import PROMPTS  # noqa: E402

SWS = os.path.expanduser("~/.agents/skills/sws-gemini/scripts/generate_image.py")
TOKEN_FILE = os.path.expanduser("~/.config/sws-gemini/token")
RESOLUTION = "2K"


def build_prompt(p: dict) -> str:
    text = p["prompt"].rstrip(". ") + ". "
    text += (
        "IMPORTANT: All visible text in the image, including titles, "
        "node labels, layer names, annotations, and any other text, "
        "must be displayed in Chinese characters. "
    )
    text += f'The diagram title is: "{p["title"]}". '
    return text


def main() -> int:
    token = open(TOKEN_FILE).read().strip()
    env = dict(os.environ, ANTHROPIC_AUTH_TOKEN=token)

    only = sys.argv[1:] or None
    failures = []

    for p in PROMPTS:
        if only and p["id"] not in only:
            continue
        out = os.path.join(HERE, f'{p["id"]}.png')
        if os.path.exists(out) and os.path.getsize(out) > 10000:
            print(f"[skip] {p['id']} 已存在", flush=True)
            continue

        print(f"[gen ] {p['id']}  {p['title']}", flush=True)
        r = subprocess.run(
            ["python3", SWS, "--prompt", build_prompt(p), "--filename", out,
             "--resolution", RESOLUTION],
            capture_output=True, text=True, env=env, timeout=600,
        )
        if r.returncode == 0 and os.path.exists(out):
            size = os.path.getsize(out)
            print(f"[ ok ] {p['id']}  {size // 1024} KB", flush=True)
        else:
            msg = (r.stderr or r.stdout or "").strip().splitlines()
            failures.append((p["id"], msg[-1] if msg else "unknown"))
            print(f"[fail] {p['id']}  {msg[-1] if msg else 'unknown'}", flush=True)

    if failures:
        print("\n失败清单:", flush=True)
        for k, v in failures:
            print(f"  {k}: {v}", flush=True)
        return 1
    print("\n全部完成", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
