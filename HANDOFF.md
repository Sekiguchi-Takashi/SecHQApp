# SecHQApp / セキュリティ対策2.0 HANDOFF

## 位置づけ

`セキュリティ対策2.0_オントロジー設計と全体像.md` の **クライアント側（社員スマホ）** 実装。
オントロジー（資産・脆弱性・脅威・対策・インシデント・AI分析）をそのままデータ構造と画面に対応させている。

## 構成

- 言語: Java / プログラマティックUI（XMLレイアウトなし）
- minSdk 24 / targetSdk 34 / AGP 8.5.2 / Gradle 8.7
- 依存: appcompat, core, documentfile のみ
- CI: `.github/workflows/build.yml`（gradle wrapper jar を同梱せず、CI側の gradle 8.7 を使用）。**`actions/upload-artifact` は使わない**（Artifactsストレージ無料枠0.5GBが枯渇し "Artifact storage quota has been hit" でビルドが落ちるため）。build.yml はコンパイル確認用と割り切り、APKの配布は `release.yml` が作る Release から行う
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
| `AdminHub.java` | 受信集約・指示発行・集計・整理・管理者ロック |
| `ClientService.java` | 出社中の常駐サービス（状態送信・指示ポーリング） |
| `NightlyWorker.java` | 23時の自動集計 |
| `Exporter.java` | 統合JSON生成と共有フォルダへの書き込み |
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

（クライアント側は完了。以降は改善のみ）
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

## v2.5 追加

- **未対応警告パネル**: 統制タブ上部に未ackの警告を最大10件表示。「すべて対応済みにする」で prefs の `acked`（ファイル名のSet、最大500）へ記録し一覧から消える。
- **集計レポート共有**: `AdminHub.reportText` がテキスト版レポート（サマリ＋端末別＋未対応警告）を生成し、`ACTION_SEND` で LINE/メール等へ共有。
- **受信データの整理**: `AdminHub.cleanup(keepDays)` が保持日数（既定30日、変更可）より古い `loc_/alert_/desk_/sechq_` を削除。`status_` と `summary_` は残す。共有フォルダの肥大化と読み込み遅延の対策。
- **クライアントのWi-Fi違反バナー**: 出社中に許可外SSIDへ接続していると出社画面に赤バナー＋「Wi-Fiを切り替える」ボタン（通知を見逃しても画面で気づける）。

## v2.4 追加

- **クライアント メッセージ受信箱**: 管理者からの `message` / `photo` 指示を `messages.json` に保存（最大50件）。出社画面に未読件数付きで直近10件を表示、「既読にする」で一括既読。通知を見逃しても本文が残る。
- **退社リマインド**: 設定した時刻（既定20時）を過ぎても出社中なら通知。1日1回のみ（`clockout_notified`）。設定はタイトル長押し→「退社リマインド時刻」。
- **管理者ロックのハートビート**: 4分間隔で `admin_lock.json` を更新。アプリを開いたまま10分放置しても他端末に奪われない。競合状態の変化を検知したら即UIへ反映。
- **端末履歴の詳細**: 統制タブの端末カードを長押しで、その端末の全受信履歴（時刻降順）をダイアログ表示。そこから直接指示送信も可能。

## deploy.sh（恒久仕様）

`git pull --rebase origin main` とタグ発行を含む定型スクリプト。カタログ管理システムが API 経由で `.github/workflows/release.yml` と `ci/appathy.keystore` を直接コミットするため、rebase が無いと push が rejected になる。**`ci/` と `release.yml` は配布ビルドに必要なので削除・追跡解除しないこと**。タグを打つと Actions がビルドして Release を作り、自作アプリストアに更新として現れる。

## 署名（v1.6〜）

`app/sechq.keystore`（alias: sechq / pass: sechqpass）を debug/release 両方の signingConfig に固定。CIランナー任せのdebug署名だと毎回変わり上書きインストール不可になるための措置。**このキーストアを削除・再生成すると既存端末は再びアンインストールが必要になる**ので変更しないこと。

## ホーム画面（v1.7）

トップは攻撃者視点の一覧。9種の攻撃（ランサムウェア/フィッシング/マルウェア/乗っ取り/盗聴/盗難紛失/持ち出し/のぞき見/脆弱性悪用）ごとに関連チェックを束ね、
- 💣️危険 = weight15以上のNGあり
- ⚠️注意 = 軽微なNGあり
- ✅️要確認 = 未実施の点検あり（スキャン/棚卸し/書類点検/拠点登録など）
- 🔐安全 = 全てOK
で分類。タップでダイアログ（手口・端末の状態・今すぐできる対策）。起動時に感染予防スキャンとアプリ棚卸しを自動でバックグラウンド実行し、完了次第ホームを再描画する。タブは11（ホーム＋機能10）。

## 自動エクスポートと位置自動記録（v1.8）

- **自動エクスポート**: AI分析タブで SAF の `ACTION_OPEN_DOCUMENT_TREE` により書込可フォルダ（Google Driveのフォルダ可）を `export_tree` として永続化。`Exporter.writeToExportTree` が `sechq_YYYY-MM-DD.json` を同名上書きで保存。手動ボタンに加え `DailyWorker` の日次実行でも自動保存する。OAuth不要（Driveアプリのdocument provider経由で同期される）。
- **位置自動記録**: 起動時に拠点登録済み＋位置権限ありの場合のみ、10分以内の lastKnownLocation を無音で履歴に記録（`auto: true`）。ACCESS_BACKGROUND_LOCATION は使わない方針（審査・許諾が重いため）。
- `Exporter.java` に統合JSON生成を集約（MainActivity/DailyWorker共用）。JSONにスコア履歴も含む。

## 2アプリ構成（v2.7）

同一リポジトリで2つのAPKを出す。**Javaソースは `app/src/main/java` の1本のみ**で、`client` モジュールが `sourceSets.java.srcDirs` でそれを共有する（コピーではない。片方だけ直す事故が起きない）。

| モジュール | applicationId | 表示名 | 用途 |
|---|---|---|---|
| `app` | jp.appathy.sechq | セキュリティ対策2.0 | 切替可能アプリ（管理者⇄クライアント） |
| `client` | jp.appathy.sechq.client | 出社チェック | クライアント専用アプリ |

- クライアント専用の判定はマニフェストの `<meta-data android:name="client_only" android:value="true"/>`。BuildConfig/Rはnamespaceに紐づき共有ソースから参照できないため、meta-dataで切り替える（Javaコードは生成Rを一切参照しない前提を維持すること）。
- client_only ビルドではモード選択ダイアログを出さず常にクライアント、設定メニューから「モードを切り替え」を隠す。
- リソースは `app/src/main/res` を共有し、client 側は重複しない `client_app_name` のみ追加。
- 署名は両モジュールとも `app/sechq.keystore`。両方インストールしても applicationId が違うので共存する。
- v2.0で作った単独の管理者アプリ（:admin / Fleet.java）は、統制タブに機能を統合したため削除。

## モード切替（v2.1）

`prefs.mode` = client / admin。初回起動でダイアログ選択、タイトル長押しの設定メニューで切替と共有フォルダ選択。今後「切替可能アプリ」と「クライアント専用アプリ」の2アプリに分割予定（まず切替可能アプリを完成させる方針）。

**クライアントモード**: トップは出社/退社の2ボタンのみ（タブ非表示）。
- 出社: ①API28以前はWi-Fi自動ON、29以降は未接続時にWi-Fiパネルを自動表示 ②status_<ID>.json（状態/SSID/VPN）と統合レポート sechq_<ID>_日付.json を共有フォルダへ送信 ③`ClientService`（location型フォアグラウンドサービス）開始
- ClientService: 5分間隔で (a)status更新 (b)Wi-Fiポリシー照合 (c)cmd_<ID>.json / cmd_all.json をポーリング
- コマンド書式: `{"ts": <epoch>, "actions": [{"type":"photo","msg":"…"} | {"type":"location"} | {"type":"wifi_policy","allowed":["SSID1"]} | {"type":"message","msg":"…"}]}`。tsが前回処理値以下なら無視（cmd_done_ts）
- photo: 通知→タップで撮影→OCR判定を desk_<ID>_<ts>.json で返送 / location: loc_<ID>_<ts>.json / 許可外SSID検知: alert_<ID>_<ts>.json＋本人へ通知（Android仕様で強制切断は不可のため検知・警告方式）
- 退社: status退社を送信しサービス停止

**管理者モード（v2.2）**: タブ12（ホーム/統制/…）。`統制`タブが管理者ダッシュボード。
- **受信一覧**: 共有フォルダの status_/sechq_/desk_/loc_/alert_ を読み、時刻降順の履歴と端末別サマリを表示（`AdminHub.load`）。
- **指示**: 端末カードをタップ or「全端末へ指示」→ メッセージ/撮影指示/位置取得/許可Wi-Fi配布。`cmd_<ID>.json`（全体は`cmd_all.json`）を生成。出社前の端末には出社時に届く（clockInで `cmd_done_ts` を0にリセットし、未処理コマンドを必ず拾う）。
- **集計**: ①対象台数（`expected_devices`）に達したら自動集計 ②「今すぐ集計」で手動 ③`NightlyWorker` が毎日23時に自動集計（当日集計済みならスキップ、データがある場合のみ）。結果は `summary_YYYY-MM-DD.json`。
- **競合制御**: `admin_lock.json` に owner+ts を書く。他端末が保持中（TTL 10分）なら管理者機能を全面停止し、統制タブでロックアウト表示＋ログアウトのみ可能。`render()` はロック中に必ずindex=1へ強制。onResumeで再取得、onDestroy/ログアウトで解放。

## 注意

- SSID取得には位置情報の許可と位置情報ONが必要。
- 感染予防タブは SAF でフォルダを選ぶまでスキャンできない（Download を選ぶ想定）。
- APKは debug ビルド。artifact から取得してインストールする。

## ビルド上の注意

androidx 側が引き込む kotlin-stdlib と kotlin-stdlib-jdk7/jdk8 のバージョン差で `checkDebugDuplicateClasses` が失敗するため、`app/build.gradle` で kotlin-bom 1.8.22 と resolutionStrategy.force により全て 1.8.22 に揃えている。androidx のバージョンを上げる際はこの数値も合わせて見直すこと。
