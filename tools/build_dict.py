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
