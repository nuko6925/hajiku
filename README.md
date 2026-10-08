# Hajiku

iOS 標準の日本語かなキーボード(フリック入力)の操作感と見た目を参考にした、Android 用の日本語入力アプリ(IME)です。

> iPhone から Android に移っても、指が覚えているフリック入力のまま打てることを目指しています。

本アプリは Apple Inc. とは関係のない非公式の個人プロジェクトです。iOS は Cisco の米国およびその他の国における商標または登録商標であり、ライセンスに基づき使用されています。

## 特徴

- **iOS と同じ配列・操作**: かな / ABC / ☆123 の3モード、5方向フリックとトグル入力、→(トグル確定)・↺↻、小゛゜、`^_^` キー
- **かな漢字変換**: Mozc OSS 辞書と連接コストによる文節変換、予測変換、フリックの打ち間違い・濁点/小書きの付け忘れ補正、学習
- **英単語予測**: ABC モードで入力中の単語を補完
- **絵文字・顔文字候補**: 「ねこ」→ 🐱、「すやぁ」→ ( ˘ω˘)ｽﾔｧ など(Mozc のデータ)
- **日付入力**: 「きょう」「きのう」「あした」で日付・曜日・和暦
- **ユーザ辞書**: よみ → 単語の登録(アプリ内で管理)
- **その他**: 空白長押しのトラックパッド、絵文字パネル、候補一覧の展開、ダークモード、角丸パネル

日本語入力専用です(UI・変換とも日本語が前提)。

## 動作環境

Android 10 (API 29) 以上

## インストール

1. [Releases](../../releases) から `Hajiku-vX.Y.Z.apk` をダウンロードしてインストール
2. アプリを開き「キーボードを有効化」から Hajiku をオン
3. 「入力方法を切り替え」で Hajiku を選択

[Obtainium](https://github.com/ImranR98/Obtainium) にこのリポジトリを登録すると、更新を自動で受け取れます。

## ビルド

```sh
python3 tools/build_dict.py   # 辞書を生成 (app/src/main/assets/dict.db, conn.bin)
./gradlew assembleDebug
```

辞書(約70MB)はリポジトリに含めず、ビルド時に Mozc / FrequencyWords のデータから生成します。

リリース版の署名は `keystore.properties`(`keystore.properties.example` 参照)か環境変数で指定します。`v*` タグを push すると GitHub Actions が署名済み APK を Releases に添付します(`.github/workflows/release.yml`)。

## 構成

| ファイル | 役割 |
|---|---|
| `Keys.kt` | 3モードのキー定義。flick = [中央,左,上,右,下], cycle = トグル順 |
| `Composer.kt` | 未確定文字列・トグル状態・↺↻・小゛゜ |
| `KeyboardView.kt` | Canvas 描画とタッチ処理(フリック判定、ガイド、BS リピート、トラックパッド) |
| `FlickImeService.kt` | IME 本体。候補選択・確定・エディタ連携 |
| `Converter.kt` | 候補の組み立て(文変換・文節・予測・Typo 補正・日付・学習) |
| `Lattice.kt` | 連接コストによる最小コスト経路 (Viterbi) と文節まとめ |
| `UserDict.kt` / `UserDictActivity.kt` | ユーザ辞書と管理画面 |
| `CandidateBarView.kt` / `CandidatePanelView.kt` | 候補バーと展開表示 |
| `EmojiPanelView.kt` | 絵文字パネル |
| `tools/build_dict.py` | 辞書データの生成 |

## 未実装

- 文節区切りの手動変更
- 絵文字の検索・肌の色の選択
- 英語の次単語予測
- ユーザ辞書のエクスポート / インポート

## ライセンス

ソースコードは [MIT License](LICENSE) です。

APK に同梱する辞書・単語リスト・絵文字データはそれぞれ元のライセンスに従います。一覧は [NOTICE.md](NOTICE.md) を参照してください。英単語データ(CC BY-SA 4.0)の継承条件はそのデータ部分にのみ適用されます。
