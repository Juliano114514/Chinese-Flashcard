"""Build/check revision-bound stroke names and bundled narration (never at runtime).

The cnchar sequence supplies candidate families. Split families use nearest
normalised median exemplars; ambiguous matches remain unnamed, never guessed.
--audio explicitly renders fixed PCM with the existing pinned Melo model.
--check is read-only and uses only the Python standard library.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.gradle/stroke-names-source'
REVISION = 'b8397db3e08e88ebbf8acf92b5cbab1a7e4c1550'
DATA = ROOT / 'core/data/src/main/assets/writing-strokes'
AUDIO = ROOT / 'core/media/src/main/assets/writing-strokes'
DOMAIN = ROOT / 'core/domain/src/main/kotlin/com/example/chinese_flashcard/core/domain/StrokeName.kt'
CATALOG = [
    ('HENG', '横', 'horizontal', 'héng', 'j', '一', 0),
    ('SHU', '竖', 'vertical', 'shù', 'f', '十', 1),
    ('PIE', '撇', 'left-falling', 'piě', 's', '人', 0),
    ('NA', '捺', 'right-falling', 'nà', 'l', '人', 1),
    ('DIAN', '点', 'dot', 'diǎn', 'kd', '六', 0),
    ('TI', '提', 'rising', 'tí', 'i', '打', 2),
    ('SHU_GOU', '竖钩', 'vertical hook', 'shù gōu', 'g', '丁', 1),
    ('WAN_GOU', '弯钩', 'curved hook', 'wān gōu', 't', '狗', 1),
    ('XIE_GOU', '斜钩', 'slanting hook', 'xié gōu', 'y', '戈', 1),
    ('WO_GOU', '卧钩', 'lying hook', 'wò gōu', 'y', '心', 1),
    ('HENG_GOU', '横钩', 'horizontal hook', 'héng gōu', 'e', '你', 3),
    ('HENG_ZHE', '横折', 'horizontal turn', 'héng zhé', 'c', '口', 1),
    ('HENG_PIE', '横撇', 'horizontal left-falling', 'héng piě', 'e', '又', 0),
    ('SHU_ZHE', '竖折', 'vertical turn', 'shù zhé', 'b', '山', 1),
    ('SHU_WAN', '竖弯', 'vertical curve', 'shù wān', 'b', '四', 3),
    ('PIE_ZHE', '撇折', 'left-falling turn', 'piě zhé', 'n', '云', 2),
    ('PIE_DIAN', '撇点', 'left-falling dot', 'piě diǎn', 'm', '女', 0),
    ('SHU_TI', '竖提', 'vertical rising', 'shù tí', 'h', '长', 2),
    ('SHU_WAN_GOU', '竖弯钩', 'vertical curved hook', 'shù wān gōu', 'u', '儿', 1),
    ('HENG_ZHE_TI', '横折提', 'horizontal turn rising', 'héng zhé tí', 'p', '说', 1),
    ('HENG_ZHE_GOU', '横折钩', 'horizontal turn hook', 'héng zhé gōu', 'r', '刀', 0),
    ('HENG_ZHE_WAN', '横折弯', 'horizontal turn curve', 'héng zhé wān', 'v', '投', 4),
    ('HENG_ZHE_ZHE', '横折折', 'horizontal double turn', 'héng zhé zhé', 'v', '凹', 1),
    ('HENG_XIE_GOU', '横斜钩', 'horizontal slanting hook', 'héng xié gōu', 'o', '风', 1),
    ('HENG_ZHE_WAN_GOU', '横折弯钩', 'horizontal turn curved hook', 'héng zhé wān gōu', 'o', '九', 1),
    ('HENG_PIE_WAN_GOU', '横撇弯钩', 'horizontal left-falling curved hook', 'héng piě wān gōu', 'w', '队', 0),
    ('HENG_ZHE_ZHE_PIE', '横折折撇', 'horizontal double turn left-falling', 'héng zhé zhé piě', 'a', '及', 1),
    ('HENG_ZHE_ZHE_ZHE', '横折折折', 'horizontal triple turn', 'héng zhé zhé zhé', 'q', '凸', 3),
    ('HENG_ZHE_ZHE_ZHE_GOU', '横折折折钩', 'horizontal triple turn hook', 'héng zhé zhé zhé gōu', 'w', '乃', 0),
    ('SHU_ZHE_PIE', '竖折撇', 'vertical turn left-falling', 'shù zhé piě', 'x', '专', 2),
    ('SHU_ZHE_ZHE', '竖折折', 'vertical double turn', 'shù zhé zhé', 'x', '鼎', 5),
    ('SHU_ZHE_ZHE_GOU', '竖折折钩', 'vertical double turn hook', 'shù zhé zhé gōu', 'z', '马', 1),
]


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def normalise(points):
    """Resample by arc length; independent axes tolerate tall/narrow glyphs."""
    xy = [(p['x'], p['y']) for p in points]
    lengths = [0.0]
    for a, b in zip(xy, xy[1:]):
        lengths.append(lengths[-1] + math.dist(a, b))
    output, segment = [], 1
    for i in range(40):
        target = lengths[-1] * i / 39
        while segment < len(xy) - 1 and lengths[segment] < target:
            segment += 1
        ratio = (target - lengths[segment - 1]) / max(1e-9, lengths[segment] - lengths[segment - 1])
        output.append(tuple(a + (b - a) * ratio for a, b in zip(xy[segment - 1], xy[segment])))
    minimum = tuple(min(p[axis] for p in output) for axis in range(2))
    spans = tuple(max(p[axis] for p in output) - minimum[axis] for axis in range(2))
    return [tuple((p[axis] - minimum[axis]) / max(1, spans[axis]) for axis in range(2)) for p in output]


def distance(a, b):
    previous = [0.0] + [float('inf')] * len(b)
    for p in a:
        current = [float('inf')]
        for j, q in enumerate(b):
            current.append(math.dist(p, q) + min(previous[j], previous[j + 1], current[-1]))
        previous = current
    return previous[-1] / (len(a) + len(b))


def metadata():
    import subprocess
    assert subprocess.check_output(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD'], text=True).strip() == REVISION
    order_file = SOURCE / 'src/cnchar/plugin/order/dict/stroke-order-jian.json'
    orders = json.loads(order_file.read_text('utf-8'))
    glyphs = {item['glyph']: item for shard in (ROOT / 'core/data/src/main/assets/wordlist-strokes').glob('shard_*.json')
              for item in json.loads(shard.read_text('utf-8'))['items']}
    templates = {}
    for row in CATALOG:
        name, _, _, _, codes, glyph, index = row
        assert orders[glyph][index] in codes, (name, glyph, index)
        for code in codes:
            templates.setdefault(code, []).append((name, normalise(glyphs[glyph]['medians'][index])))
    # 乙 is the curved form, despite cnchar grouping it with 横斜钩.
    templates['o'].append(('HENG_ZHE_WAN_GOU', normalise(glyphs['乙']['medians'][0])))
    entries, unavailable = {}, 0
    for glyph, item in sorted(glyphs.items()):
        order = orders.get(glyph, '')
        if len(order) != len(item['paths']):
            unavailable += len(item['paths'])
            continue
        names = []
        for code, median in zip(order, item['medians']):
            candidates = templates[code]
            if len(candidates) == 1:
                names.append(candidates[0][0])
                continue
            path = normalise(median)
            ranked = sorted((min(distance(path, template) for label, template in candidates if label == name), name)
                            for name in {label for label, _ in candidates})
            # Close split-family matches remain silent. Do not label an uncertain trajectory.
            best, name = ranked[0]
            names.append(name if best <= .16 and ranked[1][0] - best >= .012 else None)
        unavailable += names.count(None)
        # Compact ordinal IDs are bound to the generated enum's catalogue order.
        names = [next(i for i, row in enumerate(CATALOG) if row[0] == name) if name else None for name in names]
        entries[glyph] = [digest('\n'.join(item['paths']).encode()), names]
    return {'version': 1, 'catalog': [row[0] for row in CATALOG], 'source': f'https://github.com/theajack/cnchar/tree/{REVISION}',
            'sourceSha256': digest(order_file.read_bytes()), 'classification': 'cnchar family + median exemplars; uncertain splits omitted',
            'glyphCount': len(glyphs), 'unavailableStrokes': unavailable, 'items': entries}


def audio():
    import evaluate_melo_tts as melo
    melo.verify_sources()
    melo.prepare_runtime()
    import numpy as np
    import onnxruntime as ort
    from melo_tts_frontend import compile_reading
    assert digest((melo.CACHE / 'model-duration.onnx').read_bytes()) == 'c6029f601d660bd8ab720d1b6a159638c7613d9e4cbc4bd06f347c372205de55'
    options = ort.SessionOptions()
    options.intra_op_num_threads, options.inter_op_num_threads = 2, 1
    model = ort.InferenceSession(str(melo.CACHE / 'model-duration.onnx'), sess_options=options, providers=['CPUExecutionProvider'])
    tokens = dict(line.split() for line in (melo.CACHE / 'tokens.txt').read_text('utf-8').splitlines())
    entries = {}
    AUDIO.mkdir(parents=True, exist_ok=True)
    for name, chinese, _, pinyin, *_ in CATALOG:
        reading = compile_reading(chinese, pinyin)
        phones, tones = reading['phoneIds'], reading['tones']
        count = len(phones)
        ids = [int(tokens['_'])]
        tone_ids = [0]
        for copy in range(3):
            ids += phones + [int(tokens['.'] if copy == 2 else tokens[','])]
            tone_ids += tones + [0]
        ids += [int(tokens['_'])]
        tone_ids += [0]
        expanded, expanded_tones = np.zeros(2 * len(ids) + 1, np.int64), np.zeros(2 * len(ids) + 1, np.int64)
        expanded[1::2], expanded_tones[1::2] = ids, tone_ids
        inputs = {'x': expanded[None], 'tones': expanded_tones[None], 'x_lengths': np.array([len(expanded)], np.int64),
                  'sid': np.array([1], np.int64), 'noise_scale': np.array([.6], np.float32),
                  'length_scale': np.array([1 / .85], np.float32), 'noise_scale_w': np.array([.8], np.float32)}
        samples, durations = model.run(['y', '/Ceil_output_0'], inputs)
        samples, durations = samples.reshape(-1), durations.reshape(-1)
        bounds = np.concatenate(([0], np.cumsum(durations.astype(np.int64) * 512)))
        assert bounds[-1] == len(samples)
        first = 2 * (count + 2) + 1
        last = first + 2 * (count - 1)
        clip = samples[bounds[first - 1]:bounds[last + 2]].copy()
        assert 4410 <= len(clip) <= 44100 * 6 and np.isfinite(clip).all()
        rms, peak = float(np.sqrt(np.mean(clip.astype(np.float64) ** 2))), float(np.max(np.abs(clip)))
        assert rms > .001 and peak > .01
        clip *= min(.12 / rms, .79 / peak, 4)
        fade = min(132, len(clip) // 4)
        clip[:fade] *= np.linspace(0, 1, fade)
        clip[-fade:] *= np.linspace(1, 0, fade)
        payload = (clip * 32767).astype('<i2').tobytes()
        (AUDIO / f'{name}.pcm').write_bytes(payload)
        entries[name] = {'bytes': len(payload), 'sha256': digest(payload)}
        print('rendered', chinese, len(payload), flush=True)
    (AUDIO / 'index.json').write_bytes(encoded({'version': 1, 'sampleRate': 44100, 'encoding': 'pcm-s16le',
        'sourceModelSha256': melo.MODEL_SHA256, 'method': 'Melo repeated context, middle phrase duration crop, gain and 3ms fade',
        'humanListeningReviewed': False, 'entries': entries}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--audio', action='store_true')
    args = parser.parse_args()
    content = metadata()
    if args.audio:
        assert not args.check
        audio()
    kotlin = 'package com.example.chinese_flashcard.core.domain\n\n// Generated by tools/build_writing_strokes.py. Fixed narration IDs are an asset allowlist.\n'
    kotlin += 'enum class StrokeName(val chinese: String, val english: String) {\n'
    kotlin += ''.join(f'  {name}("{cn}", "{en}"),\n' for name, cn, en, *_ in CATALOG) + '}\n'
    targets = {DATA / 'names.json': encoded(content), DOMAIN: kotlin.encode(),
               DATA / 'CNCHAR-MIT.txt': (SOURCE / 'LICENSE').read_text('utf-8').encode('utf-8'),
               DATA / 'NOTICE.txt': (f'Writing stroke names\ncnchar, theajack and contributors, MIT\n'
                   f'https://github.com/theajack/cnchar/tree/{REVISION}\n'
                   'Selected sequence families mapped to the existing glyph paths. Split families use median exemplars; '
                   'uncertain or mismatched sequences are omitted. English labels are editorial teaching translations.\n'
                   'Narration: 32 fixed clips generated with the existing pinned MeloTTS FP32 model; '
                   'repeated context, duration crop, amplitude adjustment and edge fade. '
                   'No runtime synthesis or download. No human listening certification. '
                   'Model/code licenses: chinese-speech/LICENSES.txt.\n').encode()}
    for path, payload in targets.items():
        if args.check:
            assert path.read_bytes() == payload, f'Outdated output: {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
    index = json.loads((AUDIO / 'index.json').read_text('utf-8'))
    assert set(index['entries']) == {row[0] for row in CATALOG}
    for name, record in index['entries'].items():
        payload = (AUDIO / f'{name}.pcm').read_bytes()
        assert len(payload) == record['bytes'] and digest(payload) == record['sha256'] and len(payload) % 2 == 0
        assert 8820 <= len(payload) <= 44100 * 6 * 2
    print(json.dumps({'glyphs': len(content['items']), 'unavailableStrokes': content['unavailableStrokes'],
        'names': len(CATALOG), 'metadataBytes': len(targets[DATA / 'names.json']),
        'pcmBytes': sum(r['bytes'] for r in index['entries'].values())}))


if __name__ == '__main__':
    main()
