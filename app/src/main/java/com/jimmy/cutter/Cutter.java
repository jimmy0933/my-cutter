package com.jimmy.cutter;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 無損剪輯：只做 stream copy（MediaExtractor -> MediaMuxer），不解碼、不重新編碼。
 * 起點必須是關鍵幀，片段會依序接在一起，輸出為同編碼、同容器（MP4 / WebM）。
 * 只保留第一條影片軌與使用者勾選的音軌，其餘軌道與 metadata 一律丟棄以瘦身。
 */
public final class Cutter {
    public interface Progress { void onProgress(float fraction); }

    public static final class Result {
        public long outBytes;
        public int keptTracks;
    }

    private static final long FRAME_GAP_US = 33_333L;

    private Cutter() {}

    /** @param segments 每個元素 {startUs, endUs}，startUs 應為關鍵幀時間 */
    public static Result cut(Context ctx, Uri src, VideoInfo info, List<long[]> segments,
                             java.util.Set<Integer> audioKeep, File out, Progress cb) throws Exception {
        int fmt = info.containerMime.contains("webm")
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

        long total = 0;
        for (long[] s : segments) total += Math.max(1, s[1] - s[0]);

        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        Result res = new Result();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(src, "r")) {
            if (pfd == null) throw new IllegalStateException("無法開啟檔案");
            ex.setDataSource(pfd.getFileDescriptor());
            mux = new MediaMuxer(out.getPath(), fmt);

            int n = ex.getTrackCount();
            int[] map = new int[n];
            java.util.Arrays.fill(map, -1);
            int videoTrack = -1, bufSize = 1 << 20;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                boolean take = false;
                if (mime.startsWith("video/") && videoTrack < 0) { videoTrack = i; take = true; }
                else if (mime.startsWith("audio/") && audioKeep.contains(i)) { take = true; }
                if (take) {
                    map[i] = mux.addTrack(f);
                    ex.selectTrack(i);
                    res.keptTracks++;
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufSize = Math.max(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                }
            }
            if (videoTrack < 0) throw new IllegalStateException("找不到影片軌");
            bufSize = Math.max(bufSize, 8 << 20);
            if (info.rotation != 0) mux.setOrientationHint(info.rotation);
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(bufSize);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long offset = 0, doneUs = 0;

            for (long[] seg : segments) {
                long s = seg[0], e = seg[1];
                ex.seekTo(s, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                boolean[] done = new boolean[n];
                int remaining = res.keptTracks;
                long lastVideo = s;
                while (remaining > 0) {
                    int ti = ex.getSampleTrackIndex();
                    if (ti < 0) break;
                    long t = ex.getSampleTime();
                    if (map[ti] < 0 || done[ti]) { ex.advance(); continue; }
                    boolean isVideo = ti == videoTrack;
                    if (t > e) {
                        done[ti] = true;
                        remaining--;
                        ex.advance();
                        continue;
                    }
                    if (t < s) { ex.advance(); continue; }
                    bi.offset = 0;
                    bi.size = ex.readSampleData(buf, 0);
                    if (bi.size < 0) break;
                    bi.presentationTimeUs = t - s + offset;
                    bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(map[ti], buf, bi);
                    res.outBytes += bi.size;
                    if (isVideo) {
                        lastVideo = Math.max(lastVideo, t);
                        if (cb != null) cb.onProgress(Math.min(1f, (doneUs + (t - s)) / (float) total));
                    }
                    ex.advance();
                }
                long len = Math.max(0, lastVideo - s) + FRAME_GAP_US;
                offset += len;
                doneUs += Math.max(1, e - s);
            }
        } finally {
            try { if (mux != null && started) mux.stop(); } catch (Exception ignored) { }
            try { if (mux != null) mux.release(); } catch (Exception ignored) { }
            ex.release();
        }
        res.outBytes = out.length();
        return res;
    }
}
