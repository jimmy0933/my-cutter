# my-cutter 無損剪輯（Android）

不重新編碼的影片剪輯工具：只做 stream copy（`MediaExtractor` → `MediaMuxer`），畫質零損失、速度極快。

## 功能
- 選擇影片，顯示軌道資訊與關鍵幀數量
- 預覽、拖曳、跳到上/下一個關鍵幀
- 設定起點（自動對齊關鍵幀）與終點，可加入多個片段並依序合併
- 輸出同編碼、同容器（MP4 → MP4、WebM → WebM），存到 `Movies/MyCutter`
- 瘦身：剪掉不要的片段，並移除多餘音軌、字幕軌與 metadata（含 GPS）
- 匯出後顯示原檔 / 輸出大小與節省比例

## 限制
- 起點必須是關鍵幀，所以不是逐幀精準；終點可在任意幀。
- 輸出容器只支援 MP4 / WebM（Android `MediaMuxer` 的限制）；MKV、AVI、TS 等無法無損輸出。
- 編碼需為 MediaMuxer 支援者（H.264、HEVC、VP8/9、AV1 視系統版本而定）。
- 真正的「同編碼再壓縮」會有損，目前未提供。
- minSdk 26（Android 8.0）。

## 建置
```
./build.sh        # 產出 dist/my-cutter.apk（簽章用本機 debug keystore）
```
需要 JDK 8+ 與 aapt、dx、apksigner、zipalign、API 23 android.jar
（Debian/Ubuntu：`apt install aapt apksigner zipalign libandroid-23-java android-sdk-build-tools dalvik-exchange`）。
