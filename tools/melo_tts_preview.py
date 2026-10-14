#!/usr/bin/env python3
"""Render a local, dependency-free listening page for the MeloTTS evaluation."""
from __future__ import annotations

import argparse
from collections.abc import Mapping
import html
import json
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlsplit


ROOT = Path(__file__).resolve().parents[1]


def _escape(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _display(value: Any) -> str:
    if isinstance(value, (dict, list, tuple)):
        return json.dumps(value, ensure_ascii=False, indent=2)
    return str(value)


def _number(value: Any, suffix: str = "", digits: int = 2) -> str:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return f"{value:.{digits}f}{suffix}"
    return "未记录"


def _audio_url(value: Any, output_dir: Path) -> str | None:
    """Allow only local relative media paths within this repository."""
    if not isinstance(value, str) or not value:
        return None
    path = value.replace("\\", "/")
    try:
        parsed = urlsplit(path)
    except ValueError:
        return None
    if (path.startswith("/") or parsed.scheme or parsed.netloc
            or parsed.query or parsed.fragment
            or any(ord(character) < 32 for character in path)):
        return None
    try:
        (output_dir / path).resolve().relative_to(ROOT)
    except (OSError, ValueError):
        return None
    return quote(path, safe="/.-_")


def _audio_panel(label: str, filename: Any, output_dir: Path,
                 *, missing: str, enabled: bool = True) -> str:
    url = _audio_url(filename, output_dir) if enabled else None
    if url is None:
        return (f'<div class="audio-panel unavailable"><div class="audio-label">'
                f'{_escape(label)}</div><p>{_escape(missing)}</p></div>')
    return (f'<div class="audio-panel"><div class="audio-label">{_escape(label)}</div>'
            f'<audio controls preload="none" aria-label="{_escape(label)}">'
            f'<source src="{_escape(url)}" type="audio/wav">'
            '你的浏览器不支持音频播放。</audio></div>')


def _sample_card(sample: Mapping[str, Any], index: int, output_dir: Path) -> str:
    status = str(sample.get("status", "inference_error"))
    ready = status == "ready"
    labels = {"ready": "已生成", "unsupported": "发音未支持", "near_silent": "近乎静音",
              "inference_error": "合成失败"}
    label = labels.get(status, status)
    status_class = "ready" if ready else "failed"
    sample_id = sample.get("id", f"sample-{index:02d}")
    group = sample.get("group")
    group_html = f'<span class="group">{_escape(group)}</span>' if group else ""
    notes = sample.get("notes", [])
    notes_html = ""
    if notes:
        if isinstance(notes, str):
            notes = [notes]
        notes_html = '<ul class="notes">' + "".join(
            f'<li>{_escape(note)}</li>' for note in notes) + '</ul>'
    error_html = (f'<p class="error">{_escape(sample["error"])}</p>'
                  if sample.get("error") else "")
    spoken = sample.get("spokenPinyin")
    spoken_html = (f'<p class="spoken"><span>合成读音</span> {_escape(spoken)}</p>'
                   if spoken else "")
    metrics = (f'<span>推理 {_escape(_number(sample.get("inferenceSeconds"), " s"))}</span>'
               f'<span>音频 {_escape(_number(sample.get("audioSeconds"), " s"))}</span>'
               f'<span>RTF {_escape(_number(sample.get("rtf")))}</span>')
    melo_label = "MeloTTS FP32" + (" · " + str(sample["variantLabel"]) if sample.get("variantLabel") else "")
    melo = _audio_panel(melo_label, sample.get("file"), output_dir,
                        missing="本条未生成音频，原因见下方。" if not ready
                        else "音频路径无效。", enabled=ready)
    kokoro = _audio_panel("旧 Kokoro 1.1 INT8 对照", sample.get("kokoroFile"),
                          output_dir, missing="本条没有保留的旧音频。")
    return f'''<article class="sample" data-status="{status_class}">
  <div class="sample-top"><span class="sample-id">{_escape(sample_id)}</span>{group_html}
    <span class="status {status_class}">{_escape(label)}</span></div>
  <h2>{_escape(sample.get("text", ""))}</h2>
  <p class="pinyin">{_escape(sample.get("pinyin", ""))}</p>
  {spoken_html}
  <div class="audio-pair">{melo}{kokoro}</div>
  <div class="metrics">{metrics}</div>
  {notes_html}{error_html}
</article>'''


def _details(title: str, values: Any) -> str:
    if not values:
        return ""
    if isinstance(values, Mapping):
        content = '<dl>' + "".join(
            f'<div><dt>{_escape(key)}</dt><dd><pre>{_escape(_display(value))}</pre></dd></div>'
            for key, value in values.items()) + '</dl>'
    else:
        content = f'<pre>{_escape(_display(values))}</pre>'
    return f'<details><summary>{_escape(title)}</summary>{content}</details>'


STYLE = """
:root { color-scheme: light; --ink:#28312c; --muted:#66716a; --paper:#fffdf8;
  --line:#e5e4d9; --green:#365f4c; --warm:#efece0; --failure:#8e4636; }
* { box-sizing:border-box; }
body { margin:0; background:#f5f3ea; color:var(--ink); font:16px/1.6 system-ui,
  -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
main { max-width:1160px; margin:0 auto; padding:44px 28px 56px; }
.eyebrow { margin:0 0 10px; color:var(--green); font-size:12px; letter-spacing:.12em;
  font-weight:750; text-transform:uppercase; }
h1 { margin:0; font-size:clamp(28px,4vw,42px); letter-spacing:-.04em; line-height:1.25; }
.lead { color:var(--muted); max-width:850px; margin:16px 0 0; }
.parameters { display:flex; flex-wrap:wrap; gap:8px; margin-top:20px; }
.parameters span { padding:5px 11px; background:var(--warm); border-radius:7px;
  font-size:13px; }
.notice { border-left:3px solid #a38246; background:#f0eadb; padding:14px 18px;
  margin:24px 0; border-radius:0 8px 8px 0; }
.notice p { margin:0; } .notice p + p { margin-top:5px; }
.summary-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin:25px 0; }
.stat { padding:16px 20px; background:var(--paper); border:1px solid var(--line); border-radius:10px; }
.stat strong { display:block; font-size:28px; line-height:1.2; font-weight:650; }
.stat span { font-size:13px; color:var(--muted); }
.toolbar { position:sticky; top:0; z-index:1; display:flex; flex-wrap:wrap; align-items:center;
  gap:12px; padding:14px 0; margin:8px 0 16px; background:#f5f3eaf5; }
.search-label { flex:1 1 260px; }
input,select { font:inherit; color:var(--ink); background:var(--paper); border:1px solid #c7cdc1;
  border-radius:8px; padding:10px 12px; }
input { width:100%; } select { cursor:pointer; }
input:focus-visible,select:focus-visible,summary:focus-visible { outline:3px solid #b2cbbc; outline-offset:2px; }
.visible-count { color:var(--muted); font-size:13px; min-width:88px; }
.samples { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }
.sample { padding:22px; background:var(--paper); border:1px solid var(--line); border-radius:12px;
  box-shadow:0 2px 5px #24312903; min-width:0; }
.sample[hidden] { display:none; }
.sample-top { display:flex; align-items:center; gap:8px; flex-wrap:wrap; min-height:25px; }
.sample-id { font-size:12px; color:var(--muted); font-family:ui-monospace,Consolas,monospace; }
.group { font-size:11px; color:var(--muted); background:#f1f1e9; padding:2px 7px; border-radius:4px; }
.status { margin-left:auto; font-size:11px; font-weight:600; padding:3px 8px; border-radius:20px; }
.status.ready { color:#345e45; background:#e6f0e6; }
.status.failed { color:var(--failure); background:#f9e9df; }
h2 { font-size:25px; font-weight:650; line-height:1.4; margin:12px 0 4px; overflow-wrap:anywhere; }
.pinyin { color:var(--muted); margin:0 0 14px; min-height:25px; overflow-wrap:anywhere; }
.spoken { margin:0 0 14px; font-size:12px; color:var(--muted); overflow-wrap:anywhere; }
.spoken span { color:var(--green); font-weight:650; }
.audio-pair { display:grid; grid-template-columns:1fr; gap:10px; }
.audio-panel { background:#f5f6ef; padding:10px 12px; border-radius:8px; }
.audio-label { font-size:12px; font-weight:650; color:var(--green); margin-bottom:5px; }
audio { display:block; width:100%; height:38px; }
.unavailable { background:#f5f1e9; }
.unavailable p { margin:0; color:var(--muted); font-size:12px; }
.metrics { display:flex; flex-wrap:wrap; gap:6px 15px; margin-top:12px; color:var(--muted); font-size:11px; }
.notes { margin:12px 0 0; padding-left:18px; color:var(--muted); font-size:12px; }
.notes li + li { margin-top:3px; }
.error { color:var(--failure); background:#fbefea; border-radius:6px; margin:12px 0 0;
  padding:8px 10px; font-size:12px; overflow-wrap:anywhere; }
.empty { padding:32px; text-align:center; color:var(--muted); }
.evidence { margin-top:34px; padding:22px; border:1px solid var(--line); border-radius:12px; background:#f0f0e6; }
.evidence h2 { font-size:17px; margin:0 0 10px; } .evidence ul { padding-left:20px; margin:0; font-size:13px; }
details { border-top:1px solid var(--line); padding:14px 0; }
.technical { margin-top:24px; }
summary { cursor:pointer; font-size:13px; font-weight:650; color:var(--green); }
dl { margin:12px 0 0; } dl > div { display:grid; grid-template-columns:minmax(100px,22%) 1fr; gap:16px;
  padding:8px 0; border-bottom:1px solid var(--line); }
dt { font-size:12px; color:var(--muted); overflow-wrap:anywhere; } dd { margin:0; min-width:0; }
pre { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; font:12px/1.6 ui-monospace,Consolas,monospace; }
footer { margin-top:28px; font-size:12px; color:var(--muted); }
.sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden;
  clip:rect(0,0,0,0); white-space:nowrap; border:0; }
@media(max-width:760px) { main { padding:28px 16px 40px; } .samples { grid-template-columns:1fr; }
  .summary-grid { grid-template-columns:repeat(2,1fr); } .sample { padding:18px; }
  .toolbar { gap:8px; } .visible-count { font-size:12px; } dl > div { grid-template-columns:1fr; gap:3px; } }
"""


SCRIPT = """
const search = document.getElementById('search');
const statusFilter = document.getElementById('status-filter');
const cards = Array.from(document.querySelectorAll('.sample'));
const count = document.getElementById('visible-count');
const empty = document.getElementById('empty');
function filterSamples() {
  const query = search.value.trim().toLocaleLowerCase();
  const status = statusFilter.value;
  let visible = 0;
  for (const card of cards) {
    const matches = (status === 'all' || card.dataset.status === status)
      && (!query || card.textContent.toLocaleLowerCase().includes(query));
    card.hidden = !matches;
    if (matches) visible += 1;
    else for (const audio of card.querySelectorAll('audio')) audio.pause();
  }
  count.textContent = `显示 ${visible} / ${cards.length}`;
  empty.hidden = visible !== 0;
}
search.addEventListener('input', filterSamples);
statusFilter.addEventListener('change', filterSamples);
const audios = Array.from(document.querySelectorAll('audio'));
for (const current of audios) {
  current.addEventListener('play', () => {
    for (const other of audios) if (other !== current) other.pause();
  });
}
filterSamples();
"""


def render_preview(output_dir: Path | str, report: Mapping[str, Any]) -> Path:
    """Write index.html next to generated WAVs and return its absolute path.

    Report fields: samples, summary, metadata, model, pins, runtime,
    evidenceBoundaries. Sample statuses are ready, unsupported, inference_error.
    All report text is escaped; JavaScript contains no interpolated report data.
    """
    output_dir = Path(output_dir).resolve()
    samples = report.get("samples", [])
    summary = report.get("summary", {})
    metadata = report.get("metadata", {})
    sample_cards = "\n".join(_sample_card(sample, index, output_dir)
                             for index, sample in enumerate(samples, start=1))
    total = summary.get("total", len(samples))
    supported = summary.get("supported", sum(sample.get("status") == "ready" for sample in samples))
    failed = summary.get("failed", len(samples) - supported)
    parameters = [
        "FP32 · 固定单声线",
        f'语速 {metadata.get("speedLabel") or _number(metadata.get("speed"), digits=1)}',
        f'采样率 {_number(metadata.get("sampleRate"), " Hz", digits=0)}',
        f'noise_scale {_number(metadata.get("noiseScale"), digits=1)}',
        f'noise_scale_w {_number(metadata.get("noiseScaleW"), digits=1)}',
    ]
    parameter_html = "".join(f'<span>{_escape(value)}</span>' for value in parameters)
    boundaries = report.get("evidenceBoundaries", [])
    boundary_html = "".join(f'<li>{_escape(value)}</li>' for value in boundaries)
    details = "\n".join([
        _details("合成参数与生成时间", metadata),
        _details("模型资料", report.get("model")),
        _details("固定资源与 SHA-256", report.get("pins")),
        _details("桌面推理环境", report.get("runtime")),
    ])
    title = str(report.get("title", "MeloTTS 中文试听"))
    page = f'''<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{_escape(title)}</title><style>{STYLE}</style></head>
<body><main>
<header><p class="eyebrow">Chinese Flashcard · Voice evaluation</p>
<h1>{_escape(title)}</h1>
<p class="lead">逐条试听 MeloTTS FP32，并与保留的 Kokoro 音频比较。关注声调、轻声、自然度和句尾完整性；
本页是接入前的音质评估。</p><div class="parameters">{parameter_html}</div></header>
<aside class="notice"><p><strong>请确认这条 Melo 声线的音质满意后，再接入 App。</strong></p>
<p>音质仍待你判断。下方耗时来自桌面 CPU 推理，不能代表手机上的延迟、内存或最终体验。</p></aside>
<section class="summary-grid" aria-label="本次生成摘要">
<div class="stat"><strong>{_escape(total)}</strong><span>试听样本</span></div>
<div class="stat"><strong>{_escape(supported)}</strong><span>已生成音频</span></div>
<div class="stat"><strong>{_escape(failed)}</strong><span>未支持 / 合成失败</span></div>
<div class="stat"><strong>{_escape(_number(summary.get("inferenceSeconds"), " s", digits=1))}</strong><span>桌面累计推理</span></div>
</section>
<div class="toolbar"><label class="search-label"><span class="sr-only">搜索文本、拼音或说明</span>
<input id="search" type="search" placeholder="搜索文本、拼音或说明" autocomplete="off"></label>
<label><span class="sr-only">筛选生成状态</span><select id="status-filter">
<option value="all">全部样本</option><option value="ready">已生成</option><option value="failed">未支持 / 失败</option>
</select></label><span id="visible-count" class="visible-count" aria-live="polite"></span></div>
<section class="samples" aria-label="逐条试听">{sample_cards}</section>
<p id="empty" class="empty" hidden>没有匹配的样本。</p>
<section class="evidence"><h2>如何读这份试听结果</h2><ul>
<li>「已生成」只说明合成成功，不能证明普通话读音或听感正确。</li>
<li>页面每次只播放一条音频，不自动跳过失败项或续播。</li>
<li>RTF = 推理耗时 ÷ 音频时长；数值小于 1 表示该次桌面推理快于实时。</li>
{boundary_html}</ul></section>
<section class="technical" aria-label="生成记录">{details}</section>
<footer>全部音频从本地文件读取；播放与搜索无需联网。没有旧音频的条目会明确标记。</footer>
</main><script>{SCRIPT}</script></body></html>
'''
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "index.html"
    output_path.write_text(page, encoding="utf-8", newline="\n")
    return output_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="Generated MeloTTS report JSON")
    parser.add_argument("--output-dir", type=Path, help="Defaults to the report directory")
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    print(render_preview(args.output_dir or args.report.parent, report))


if __name__ == "__main__":
    main()
