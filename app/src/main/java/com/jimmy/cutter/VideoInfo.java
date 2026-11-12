package com.jimmy.cutter;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 影片分析結果：軌道資訊、關鍵幀時間表、檔案大小。 */
public class VideoInfo {
    public long sizeBytes;
    public long durationUs;
    public int rotation;
    public String containerMime = "";
    public String description = "";
    /** 影片軌的關鍵幀（sync sample）時間，單位微秒，遞增。 */
    public long[] keyframes = new long[0];

    public static VideoInfo analyze(Context ctx, Uri uri) throws Exception {
        VideoInfo info = new VideoInfo();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IllegalStateException("無法開啟檔案");
            info.sizeBytes = pfd.getStatSize();

            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(pfd.getFileDescriptor());
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                String m = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                if (d != null) info.durationUs = Long.parseLong(d) * 1000L;
                if (r != null) info.rotation = Integer.parseInt(r);
                if (m != null) info.containerMime = m;
            } finally {
                mmr.release();
            }

            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(pfd.getFileDescriptor());
                StringBuilder sb = new StringBuilder();
                int video = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    sb.append("軌道").append(i).append(": ").append(mime);
                    if (mime.startsWith("video/")) {
                        if (video < 0) video = i;
                        sb.append(String.format(Locale.US, " %dx%d",
                                f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT)));
                    } else if (mime.startsWith("audio/") && f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sb.append(" ").append(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)).append("Hz");
                    }
                    sb.append('\n');
                }
                info.description = sb.toString().trim();
                if (video < 0) throw new IllegalStateException("找不到影片軌");

                ex.selectTrack(video);
                List<Long> kf = new ArrayList<>();
                do {
                    if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        kf.add(ex.getSampleTime());
                    }
                } while (ex.advance());
                info.keyframes = new long[kf.size()];
                for (int i = 0; i < info.keyframes.length; i++) info.keyframes[i] = kf.get(i);
                java.util.Arrays.sort(info.keyframes);
            } finally {
                ex.release();
            }
        }
        return info;
    }

    /** 回傳 <= timeUs 的最近關鍵幀；若沒有則回傳第一個關鍵幀。 */
    public long snapBack(long timeUs) {
        if (keyframes.length == 0) return 0;
        long best = keyframes[0];
        for (long k : keyframes) {
            if (k <= timeUs) best = k; else break;
        }
        return best;
    }

    /** 回傳 > timeUs 的下一個關鍵幀；沒有則 -1。 */
    public long nextKeyframe(long timeUs) {
        for (long k : keyframes) if (k > timeUs) return k;
        return -1;
    }

    /** 回傳 < timeUs 的上一個關鍵幀；沒有則 -1。 */
    public long prevKeyframe(long timeUs) {
        long best = -1;
        for (long k : keyframes) {
            if (k < timeUs) best = k; else break;
        }
        return best;
    }
}
