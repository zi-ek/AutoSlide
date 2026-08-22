#!/usr/bin/env python3
"""把 docs/使用教程.md 转成 App 内置的 assets/tutorial.html。

教程正文只维护 Markdown 一份，改完执行：

    python3 tools/gen_tutorial_html.py

生成的 HTML 是自包含的（样式内联、不联网），由 TutorialActivity 用 WebView 加载。
标题锚点沿用 GitHub 的生成规则，正文里的目录链接不用改就能跳转。
"""

import re
import sys
from pathlib import Path

try:
    import markdown
except ImportError:
    sys.exit("需要先安装 markdown：pip3 install markdown")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "使用教程.md"
DST = ROOT / "app" / "src" / "main" / "assets" / "tutorial.html"

# 与 App 主题一致的配色（浅色取 values/colors.xml，深色取 values-night/colors.xml）
CSS = """
:root{
  --bg:#ffffff; --surface:#eff0f6; --line:rgba(0,0,0,.12);
  --text:#1a1a1a; --text-dim:#4a4f55; --primary:#545995; --code-bg:#f2f3f8;
}
:root[data-theme="dark"]{
  --bg:#121212; --surface:#1e1e24; --line:#2a2a33;
  --text:#e1e1e6; --text-dim:#a1a1a8; --primary:#a5c3ff; --code-bg:#1b1b22;
}
*{box-sizing:border-box}
body{
  margin:0; padding:16px 16px 56px;
  background:var(--bg); color:var(--text);
  font:15px/1.75 -apple-system,"PingFang SC","Noto Sans CJK SC","Source Han Sans SC",sans-serif;
  -webkit-text-size-adjust:100%; word-wrap:break-word;
}
h1{font-size:22px; margin:8px 0 16px; line-height:1.4}
h2{font-size:19px; margin:32px 0 12px; padding-top:12px; border-top:1px solid var(--line)}
h3{font-size:16px; margin:22px 0 8px}
h4{font-size:15px; margin:18px 0 6px}
h1,h2,h3,h4{scroll-margin-top:12px}
p{margin:10px 0}
a{color:var(--primary); text-decoration:none}
ul,ol{margin:10px 0; padding-left:22px}
li{margin:4px 0}
hr{height:1px; border:0; background:var(--line); margin:28px 0}
blockquote{
  margin:14px 0; padding:10px 14px;
  background:var(--surface); border-left:3px solid var(--primary); border-radius:0 10px 10px 0;
  color:var(--text-dim);
}
blockquote p{margin:4px 0}
code{
  background:var(--code-bg); padding:1px 5px; border-radius:5px;
  font-family:ui-monospace,Menlo,Consolas,monospace; font-size:13px;
}
pre{
  background:var(--code-bg); padding:12px 14px; border-radius:10px;
  overflow-x:auto; margin:14px 0;
}
pre code{background:none; padding:0; font-size:13px; line-height:1.6}
.table-wrap{overflow-x:auto; margin:14px 0; -webkit-overflow-scrolling:touch}
table{border-collapse:collapse; width:100%; font-size:14px}
th,td{border:1px solid var(--line); padding:8px 10px; text-align:left; vertical-align:top}
th{background:var(--surface); font-weight:600; white-space:nowrap}
/* 目录：去掉列表符号，排得紧凑一些 */
#toc-list{list-style:none; padding-left:0}
#toc-list li{margin:6px 0}
"""

JS = """
// 主题：TutorialActivity 用 #dark / #light 传入 App 当前的深浅色；没传就跟随系统
(function(){
  var hash=(location.hash||'').replace('#','');
  if(hash==='dark'||hash==='light'){
    document.documentElement.setAttribute('data-theme',hash);
  }else if(window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches){
    document.documentElement.setAttribute('data-theme','dark');
  }
})();
// 表格套一层可横向滚动的容器，窄屏上不撑破页面
document.addEventListener('DOMContentLoaded',function(){
  document.querySelectorAll('table').forEach(function(t){
    var wrap=document.createElement('div');
    wrap.className='table-wrap';
    t.parentNode.insertBefore(wrap,t);
    wrap.appendChild(t);
  });
});
"""


def github_slug(text):
    """复刻 GitHub 的标题锚点规则：去标点、转小写、空格换连字符。"""
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.strip().lower().replace(" ", "-")


def main():
    text = SRC.read_text(encoding="utf-8")
    # 去掉正文开头那段只对仓库读者有意义的引用说明
    body = markdown.markdown(
        text,
        extensions=["tables", "fenced_code", "toc", "sane_lists"],
        extension_configs={"toc": {"slugify": lambda v, s: github_slug(v)}},
    )
    # 给目录列表加个 id，样式里单独收紧
    body = body.replace("<ul>\n<li><a href=\"#一5-分钟快速上手\">", "<ul id=\"toc-list\">\n<li><a href=\"#一5-分钟快速上手\">", 1)
    html = (
        "<!DOCTYPE html>\n"
        '<html lang="zh-CN">\n<head>\n'
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
        "<title>使用教程</title>\n"
        f"<style>{CSS}</style>\n"
        f"<script>{JS}</script>\n"
        "</head>\n<body>\n"
        "<!-- 本文件由 tools/gen_tutorial_html.py 从 docs/使用教程.md 生成，不要直接改这里 -->\n"
        f"{body}\n</body>\n</html>\n"
    )
    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_text(html, encoding="utf-8")
    print(f"已生成 {DST.relative_to(ROOT)}（{len(html)} 字节）")


if __name__ == "__main__":
    main()
