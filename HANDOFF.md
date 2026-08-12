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
| `DocClassifier.java` | Office/PDF/テキストの本文抽出と機密度分類・ラベル付与 |
| `DeskInspector.java` | 撮影画像のOCRによる机上放置書類の検出 |
| `AppAuditor.java` | インストール済みアプリの危険権限棚卸し |
| `AttackCatalog.java` | 攻撃名→関連チェックのマッピングと🔐✅️⚠️💣️の4段階判定 |
| `DailyWorker.java` | WorkManagerによる日次チェック・通知・スコア履歴 |
| `RiskEngine.java` | ルールベース推論・重み付けスコア・改善提案 |
| `Store.java` | JSON永続化 |

## タブと設計書の対応

| タブ | 設計書 |
|---|---|
| 資産 | 1. 資産管理 |
| 脆弱性 | 機能設計 2. 脆弱性管理 |
| 認証 | 3. 認証管理 |
| 感染予防 | 2. AI感染予防 |
| 機密情報 | 4. AI機密情報分類 |
| ネットワーク | 7. AIネットワーク管理 |
| 物理・外出 | 6. AI外出管理 |
| アプリ | 1. 資産管理（アプリ棚卸し） |
| 書類点検 | 5. AI物理セキュリティ |
| AI分析 | 8. AI統合分析 |

## スコアリング

各チェックに weight を持たせ、`100 - Σ(NGのweight)` を総合スコアとする（0〜100でクランプ）。
カテゴリ別は `(カテゴリ内weight合計 - NG合計) / weight合計 × 100`。

## 未実装（次バージョン候補）

- v1.6: Google Drive 自動送信（現状は SAF の書き出しで代替）
- v1.6: バックグラウンド位置ログ
- v2.0: 管理者アプリ側（統合ダッシュボード）、BonsaiApp 連携によるローカルLLM推論

## 機密情報分類の仕組み（v1.2）

- **docx/xlsx/pptx**: ZIPとして開き、`word/document.xml` `xl/sharedStrings.xml` `ppt/slides/*` 等のXMLからタグを除去して本文を得る（外部ライブラリ不使用）。
- **pdf**: `stream`〜`endstream` を Inflater で展開し、`(...)` 内の文字列を抽出。抽出できない場合はファイル名のみで判定し、その旨をUIに表示。
- **判定**: キーワード表（重み付き）＋正規表現（16桁/12桁の番号、メール、電話）でスコア化。10以上=極秘、6以上=社外秘、3以上=社内限、それ未満=公開相当。
- **ラベル付与**: `DocumentFile.renameTo` でファイル名の先頭に `【社外秘】` 等を付与する。既に `【` で始まる場合はスキップ。
- 解析は別スレッドで実行（最大300ファイル / 1ファイル6MBまで）。

## 書類点検の仕組み（v1.3）

- ML Kit `text-recognition-japanese`（バンドル版・完全オフライン）で撮影画像から日本語テキストを抽出。
- 抽出テキストを `DocClassifier.scoreText()` に通し、文書分類と同じ重み付けで機密度を判定する。
- 撮影は `ACTION_IMAGE_CAPTURE` 相当（`ActivityResultContracts.TakePicture`）＋ FileProvider（`${applicationId}.fileprovider` / `cache/captures`）。CAMERA権限は宣言していないため権限要求は発生しない。
- 画像は端末内で処理し保存も送信もしない。`inspections.json` には判定結果のみ最大100件を記録。
- リスクエンジンは「最終点検が7日以内」かつ「機密相当が未検出」を満たす場合のみOKとする（weight 12）。

## v1.4/v1.5 の変更

**バグ修正 (v1.4)**
- 感染予防スキャンを全て別スレッド化（`ensureScan()`）。UIスレッドでのSAF走査を撤廃
- 位置取得を「2分以内のlastKnownがあれば即適用、なければ単発測位のみ」に変更し、二重記録と拠点の上書きを解消
- `captureUri` を `onSaveInstanceState` で退避（撮影中のプロセス死対応）
- 文書解析の連打ガード（`analyzing` フラグ）
- PDF抽出に16進文字列 `<...>Tj` の復号を追加（UTF-16BE→可読判定→ASCIIフォールバック）。ToUnicode CMapは未対応のため CID埋め込みPDFは依然ファイル名判定

**機能追加 (v1.5)**
- アプリタブ: 危険権限（SMS/アクセシビリティ/重ね描き等）の重み付き棚卸し。スコア5以上を高リスクとしてリスクエンジンに反映（QUERY_ALL_PACKAGES 使用）
- Wi-Fi暗号化判定: API 31+ で `getCurrentSecurityType`。オープン/WEPは減点15
- 機密分類の除外リスト: 文書カードの「誤検知として除外」→ prefs の StringSet。解析時にスキップ
- スコア履歴: `history.json` に日次で最大90件。AI分析タブに14日分の横棒グラフ
- WorkManager 24時間周期の `DailyWorker`: バックグラウンドでスキャン→履歴追記→「暗号化拡張子検出 / スコア60未満 / 10以上の低下」で通知

## 署名（v1.6〜）

`app/sechq.keystore`（alias: sechq / pass: sechqpass）を debug/release 両方の signingConfig に固定。CIランナー任せのdebug署名だと毎回変わり上書きインストール不可になるための措置。**このキーストアを削除・再生成すると既存端末は再びアンインストールが必要になる**ので変更しないこと。

## ホーム画面（v1.7）

トップは攻撃者視点の一覧。9種の攻撃（ランサムウェア/フィッシング/マルウェア/乗っ取り/盗聴/盗難紛失/持ち出し/のぞき見/脆弱性悪用）ごとに関連チェックを束ね、
- 💣️危険 = weight15以上のNGあり
- ⚠️注意 = 軽微なNGあり
- ✅️要確認 = 未実施の点検あり（スキャン/棚卸し/書類点検/拠点登録など）
- 🔐安全 = 全てOK
で分類。タップでダイアログ（手口・端末の状態・今すぐできる対策）。起動時に感染予防スキャンとアプリ棚卸しを自動でバックグラウンド実行し、完了次第ホームを再描画する。タブは11（ホーム＋機能10）。

## 注意

- SSID取得には位置情報の許可と位置情報ONが必要。
- 感染予防タブは SAF でフォルダを選ぶまでスキャンできない（Download を選ぶ想定）。
- APKは debug ビルド。artifact から取得してインストールする。

## ビルド上の注意

androidx 側が引き込む kotlin-stdlib と kotlin-stdlib-jdk7/jdk8 のバージョン差で `checkDebugDuplicateClasses` が失敗するため、`app/build.gradle` で kotlin-bom 1.8.22 と resolutionStrategy.force により全て 1.8.22 に揃えている。androidx のバージョンを上げる際はこの数値も合わせて見直すこと。
