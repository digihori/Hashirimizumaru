# 走水丸

横須賀・大津港〜走水周辺の小型ボート釣り向けAndroidアプリです。

## 開く・実行する

1. Android Studioでこのフォルダーを開く
2. Gradle Syncを実行
3. 位置情報を利用できる実機またはエミュレーターで `app` を実行

パッケージ名は `tk.horiuchi.hashirimizumaru`、最小AndroidバージョンはAndroid 8.0
（API 26）です。

## 実装済み

- MapLibreによる対象地域限定地図、OpenSeaMapシーマーク切替
- GPS現在地、省電力（15秒・30m）/高精度モード
- Roomによるウェイポイントの登録・編集・削除
- 目的地の距離・方位表示、50m以内で振動と通知
- 15秒間隔でバッファするGPS航跡記録
- Roomによる釣果記録
- 初回免責同意、プライバシーポリシー、バージョン情報

## 配信前に設定が必要な項目

- `app/build.gradle.kts` の `PRIVACY_POLICY_URL` を実際のGitHub上の
  `PRIVACY.ja.md` URLに合わせる
- 海しる等深線の利用条件と配信URLを確認し、レイヤへ接続する
- MapLibre Offline APIで対象範囲の事前ダウンロード画面を追加する
- 釣果写真の選択と永続URI許可を追加する

通常の地図タイルとシーマークはMapLibreのキャッシュに保存されますが、現段階では対象範囲を
一括事前ダウンロードしません。タイル提供元の利用規約に沿ったキャッシュ上限・User-Agent・
配信基盤をリリース前に確定してください。
