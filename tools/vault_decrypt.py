#!/usr/bin/env python3
"""Hajiku の暗号化バックアップ (.hajikuvault) を PC で復号する。

    pip install cryptography
    python3 tools/vault_decrypt.py hajiku-20261012.hajikuvault            # JSON を表示
    python3 tools/vault_decrypt.py hajiku-20261012.hajikuvault --csv out.csv  # Chrome 形式の CSV に

形式は app/src/main/java/io/github/nuko6925/flickkb/VaultBackup.kt の説明を参照。
"""
import argparse
import base64
import csv
import getpass
import hashlib
import json
import sys

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FORMAT = "hajiku-vault"


def decrypt(doc: dict, passphrase: str) -> list:
    if doc.get("format") != FORMAT:
        raise ValueError("Hajiku のバックアップではありません")
    if doc.get("version") != 1 or doc.get("kdf") != "PBKDF2-HMAC-SHA256":
        raise ValueError(f"対応していない版です (version {doc.get('version')})")
    key = hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"),
                              base64.b64decode(doc["salt"]), int(doc["iterations"]), 32)
    plain = AESGCM(key).decrypt(base64.b64decode(doc["iv"]), base64.b64decode(doc["data"]),
                                f"{FORMAT}/1".encode())
    return json.loads(plain)["entries"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--csv", metavar="OUT", help="Chrome 形式 (name,url,username,password,note) の CSV に書き出す")
    a = ap.parse_args()
    with open(a.file, encoding="utf-8") as f:
        doc = json.load(f)
    try:
        entries = decrypt(doc, getpass.getpass("パスフレーズ: "))
    except InvalidTag:
        sys.exit("パスフレーズが違います")
    if a.csv:
        with open(a.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator="\n")
            w.writerow(["name", "url", "username", "password", "note"])
            for e in entries:
                url = f"https://{e['domain']}/" if e["domain"] else f"android://{e['pkg']}/"
                w.writerow([e["domain"] or e["pkg"], url, e["username"], e["password"], ""])
        print(f"{len(entries)} 件を {a.csv} に書き出しました (暗号化されていません)", file=sys.stderr)
    else:
        json.dump(entries, sys.stdout, ensure_ascii=False, indent=2)
        print()


if __name__ == "__main__":
    main()
