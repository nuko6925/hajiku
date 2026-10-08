#!/usr/bin/env python3
"""Mozc OSS 辞書から変換用データを生成する。

出力 (app/src/main/assets/):
  dict.db  : dict(読み, コスト, 表記, 左ID, 右ID) / pos(品詞ID, 付属語フラグ) / en(英単語, 頻度)
  conn.bin : 連接コスト行列 int16 LE [右ID(前) * N + 左ID(後)]、先頭 4byte に N

usage:
  python3 tools/build_dict.py              # GitHub から取得
  python3 tools/build_dict.py --src DIR    # ローカルの dictionary_oss ディレクトリ
  --en FILE                                # 英単語頻度リスト (FrequencyWords 形式) をローカルから
"""
import argparse, array, os, re, sqlite3, struct, sys, urllib.request

BASE = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/"
DATA = "https://raw.githubusercontent.com/google/mozc/master/src/data/"
KANA = re.compile(r"^[ぁ-ゖー]+$")
# 濁点・半濁点・小書きを外した「ゆるい読み」(打ち間違い・付け忘れの吸収用)。Converter.kt の FUZZY と同じ表
FUZZY = str.maketrans("がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔぁぃぅぇぉっゃゅょゎ",
                      "かきくけこさしすせそたちつてとはひふへほはひふへほうあいうえおつやゆよわ")
FILES = [f"dictionary{i:02d}.txt" for i in range(10)]
EN_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"
EN_WORD = re.compile(r"^[a-z][a-z']*$")


def read_text(src, name):
    if src:
        with open(os.path.join(src, name), encoding="utf-8") as f:
            return f.read()
    print("fetch", name, file=sys.stderr)
    with urllib.request.urlopen(BASE + name) as r:
        return r.read().decode("utf-8")


def read_data(src, rel):
    """Mozc src/data/ 以下のファイル (emoji/…, emoticon/…)。src は dictionary_oss ディレクトリ"""
    if src:
        p = os.path.join(src, "..", rel)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                return f.read()
    print("fetch", rel, file=sys.stderr)
    with urllib.request.urlopen(DATA + rel) as r:
        return r.read().decode("utf-8")


def emo_entries(src):
    """(よみ, 表記, 種類 0=絵文字 1=顔文字, 並び順)"""
    out = {}

    def add(reading, surface, kind, order):
        if KANA.match(reading) and (reading, surface) not in out:
            out[(reading, surface)] = (kind, order)

    # 絵文字: 符号位置 / 絵文字 / よみ(空白区切り) / … / 説明 / 版
    for i, ln in enumerate(read_data(src, "emoji/emoji_data.tsv").splitlines()):
        if ln.startswith("#"):
            continue
        p = ln.split("\t")
        if len(p) < 3:
            continue
        # 古い絵文字ほど定番なので先に (よみ一覧は五十音順で重要度の手がかりにならない)。
        # ZWJ 合成 (家族・カップル等) は後ろへ。選んだものは学習で前に出る
        try:
            ver = float(p[-1].lstrip("E"))
        except ValueError:
            ver = 99.0
        rank = (1_000_000 if "\u200d" in p[1] else 0) + int(ver * 10) * 10_000 + i
        for r in p[2].split():
            add(r, p[1], 0, rank)
    # 顔文字: 顔文字 / よみ(空白区切り) / 分類
    for i, ln in enumerate(read_data(src, "emoticon/emoticon.tsv").splitlines()):
        p = ln.split("\t")
        if len(p) < 2 or not p[0] or ln.startswith("#"):
            continue
        for r in p[1].split():
            add(r, p[0], 1, i)
    # 顔文字 (分類別): 顔文字 / 分類 / よみ(空白区切り)
    for i, ln in enumerate(read_data(src, "emoticon/categorized.tsv").splitlines()):
        p = ln.split("\t")
        if len(p) < 3 or ln.startswith("#"):
            continue
        for r in p[2].split():
            add(r, p[0], 1, 10000 + i)
    return [(r, s, k, o) for (r, s), (k, o) in out.items()]


def en_words(path, limit):
    if path:
        text = open(path, encoding="utf-8").read()
    else:
        print("fetch en_50k.txt", file=sys.stderr)
        text = urllib.request.urlopen(EN_URL).read().decode("utf-8")
    out = {}
    for ln in text.splitlines():
        p = ln.split()
        if len(p) != 2 or not EN_WORD.match(p[0]) or p[0].endswith("'"):
            continue
        if len(p[0]) == 1 and p[0] not in ("a", "i"):
            continue
        out.setdefault(p[0], int(p[1]))
        if len(out) >= limit:
            break
    # 元データは "don't" が "don" + "t" に分割されているので短縮形を復元
    for frag in ("don", "didn", "doesn", "isn", "wasn", "weren", "wouldn", "couldn", "shouldn",
                 "haven", "hasn", "hadn", "aren", "ain", "mustn", "needn"):
        if frag in out:
            out[frag + "'t"] = out.pop(frag)
    for base, form, k in (("can", "can't", .5), ("won", "won't", 1.5), ("i", "i'm", .3), ("i", "i'll", .1),
                          ("i", "i've", .08), ("i", "i'd", .06), ("it", "it's", .4), ("that", "that's", .3),
                          ("what", "what's", .3), ("there", "there's", .3), ("let", "let's", .6),
                          ("you", "you're", .1), ("you", "you'll", .04), ("we", "we're", .2),
                          ("they", "they're", .2), ("he", "he's", .3), ("she", "she's", .3)):
        if base in out:
            out[form] = max(out.get(form, 0), int(out[base] * k))
    return out


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--src")
    ap.add_argument("--en")
    ap.add_argument("--en-limit", type=int, default=40000)
    ap.add_argument("--fuzzy-max-cost", type=int, default=7000,
                    help="ゆるい読みの索引に入れる語のコスト上限 (小さいほど辞書が小さい)")
    ap.add_argument("--out-dir", default=os.path.join(here, "..", "app/src/main/assets"))
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)

    # --- 単語 ---
    best = {}  # (reading, surface, lid, rid) -> min cost
    for name in FILES:
        for ln in read_text(a.src, name).splitlines():
            p = ln.split("\t")
            if len(p) < 5:
                continue
            k = (p[0], p[4], int(p[1]), int(p[2]))
            c = int(p[3])
            if k not in best or c < best[k]:
                best[k] = c

    # --- 品詞: 付属語 (前の文節にくっつくもの) ---
    pos = []
    for ln in read_text(a.src, "id.def").splitlines():
        i, name = ln.split(" ", 1)
        func = name.startswith(("助詞", "助動詞")) or "非自立" in name or "接尾" in name
        pos.append((int(i), 1 if func else 0))

    # --- 連接行列 ---
    lines = read_text(a.src, "connection_single_column.txt").split()
    n = int(lines[0])
    mat = array.array("h", (int(x) for x in lines[1:]))
    assert len(mat) == n * n
    if sys.byteorder != "little":
        mat.byteswap()
    with open(os.path.join(a.out_dir, "conn.bin"), "wb") as f:
        f.write(struct.pack("<i", n))
        mat.tofile(f)

    out = os.path.join(a.out_dir, "dict.db")
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.execute("PRAGMA page_size=4096")
    db.execute("""CREATE TABLE dict(
        reading TEXT NOT NULL, cost INTEGER NOT NULL, surface TEXT NOT NULL,
        lid INTEGER NOT NULL, rid INTEGER NOT NULL,
        PRIMARY KEY(reading, cost, surface, lid, rid)) WITHOUT ROWID""")
    db.executemany("INSERT INTO dict VALUES(?,?,?,?,?)",
                   sorted((r, c, s, l, rr) for (r, s, l, rr), c in best.items()))
    db.execute("CREATE TABLE pos(id INTEGER PRIMARY KEY, func INTEGER NOT NULL)")
    db.executemany("INSERT INTO pos VALUES(?,?)", pos)
    en = en_words(a.en, a.en_limit)
    # ゆるい読み → 本来の読み (よく使う語だけ。2文字以上)
    minc = {}
    for (r, _s, _l, _rr), c in best.items():
        if c < minc.get(r, 1 << 30):
            minc[r] = c
    fuzzy = sorted((r.translate(FUZZY), r) for r, c in minc.items()
                   if c <= a.fuzzy_max_cost and len(r) >= 2 and r.translate(FUZZY) != r)
    db.execute("CREATE TABLE fuzzy(nreading TEXT NOT NULL, reading TEXT NOT NULL, PRIMARY KEY(nreading, reading)) WITHOUT ROWID")
    db.executemany("INSERT INTO fuzzy VALUES(?,?)", fuzzy)
    print(f"{len(fuzzy)} fuzzy readings", file=sys.stderr)
    emo = emo_entries(a.src)
    db.execute("""CREATE TABLE emo(
        reading TEXT NOT NULL, surface TEXT NOT NULL, kind INTEGER NOT NULL, ord INTEGER NOT NULL,
        PRIMARY KEY(reading, surface)) WITHOUT ROWID""")
    db.executemany("INSERT INTO emo VALUES(?,?,?,?)", sorted(emo))
    print(f"{len(emo)} emoji/emoticon readings", file=sys.stderr)
    db.execute("CREATE TABLE en(word TEXT PRIMARY KEY, freq INTEGER NOT NULL) WITHOUT ROWID")
    db.executemany("INSERT INTO en VALUES(?,?)", sorted(en.items()))
    db.execute("CREATE TABLE android_metadata(locale TEXT)")
    db.execute("INSERT INTO android_metadata VALUES('ja_JP')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{len(best)} entries, {len(en)} english words, conn {n}x{n}", file=sys.stderr)
    for f in ("dict.db", "conn.bin"):
        print(f, f"{os.path.getsize(os.path.join(a.out_dir, f)) / 1e6:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
