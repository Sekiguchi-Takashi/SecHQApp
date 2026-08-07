# SecHQApp / セキュリティ対策2.0 HANDOFF

## 位置づけ

`セキュリティ対策2.0_オントロジー設計と全体像.md` の **クライアント側（社員スマホ）** 実装。
オントロジー（資産・脆弱性・脅威・対策・インシデント・AI分析）をそのままデータ構造と画面に対応させている。

## 構成

- 言語: Java / プログラマティックUI（XMLレイアウトなし）
- minSdk 24 / targetSdk 34 / AGP 8.5.2 / Gradle 8.7
- 依存: appcompat, core, documentfile のみ
- CI: `.github/workflows/android.yml`（gradle wrapper jar を同梱せず、CI側の gradle 8.7 を使用）
- 保存: `filesDir` の JSON（accounts / snapshot / location）＋ SharedPreferences（tree, home_lat/lng）

## ファイル

| ファイル | 役割 |
|---|---|
| `MainActivity.java` | 7タブのシェルと全画面描画 |
| `Collector.java` | 資産・ネットワークの事実収集 |
| `FileScanner.java` | SAF経由のダウンロード監視（危険/二重/暗号化拡張子） |
| `RiskEngine.java` | ルールベース推論・重み付けスコア・改善提案 |
| `Store.java` | JSON永続化 |

## タブと設計書の対応

| タブ | 設計書 |
|---|---|
| 資産 | 1. 資産管理 |
| 脆弱性 | 機能設計 2. 脆弱性管理 |
| 認証 | 3. 認証管理 |
| 感染予防 | 2. AI感染予防 |
| ネットワーク | 7. AIネットワーク管理 |
| 物理・外出 | 5. AI物理セキュリティ / 6. AI外出管理 |
| AI分析 | 8. AI統合分析 |

## スコアリング

各チェックに weight を持たせ、`100 - Σ(NGのweight)` を総合スコアとする（0〜100でクランプ）。
カテゴリ別は `(カテゴリ内weight合計 - NG合計) / weight合計 × 100`。

## 未実装（次バージョン候補）

- v1.1: 4. AI機密情報分類（Office/PDF解析＋ラベル付与）、カメラによる書類放置検知
- v1.2: 定期実行（WorkManager）とバックグラウンド位置ログ
- v1.3: Google Drive 自動送信（現状は SAF の書き出しで代替）
- v2.0: 管理者アプリ側（統合ダッシュボード）、BonsaiApp 連携によるローカルLLM推論

## 注意

- SSID取得には位置情報の許可と位置情報ONが必要。
- 感染予防タブは SAF でフォルダを選ぶまでスキャンできない（Download を選ぶ想定）。
- APKは debug ビルド。artifact から取得してインストールする。

## ビルド上の注意

androidx 側が引き込む kotlin-stdlib と kotlin-stdlib-jdk7/jdk8 のバージョン差で `checkDebugDuplicateClasses` が失敗するため、`app/build.gradle` で kotlin-bom 1.8.22 と resolutionStrategy.force により全て 1.8.22 に揃えている。androidx のバージョンを上げる際はこの数値も合わせて見直すこと。
