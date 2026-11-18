package com.googlecode.mp4parser.authoring.builder;

import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.util.Mp4Arrays;

import static java.util.Arrays.binarySearch;

/**
 * Created by sannies on 26.03.2016.
 */
public class BetterFragmenter implements Fragmenter {
    private double targetDuration;

    public BetterFragmenter(double targetDuration) {
        this.targetDuration = targetDuration;
    }

    public long[] sampleNumbers(Track track) {
        long ts = track.getTrackMetaData().getTimescale();
        long targetTicks = (long) (targetDuration * ts);
        long[] fragments = new long[0];
        long[] syncSamples = track.getSyncSamples();
        long[] durations = track.getSampleDurations();
        // 同期サンプルが 0 個の場合は「同期サンプルなし」と同じ扱いにする
        // (以前は下の syncSampleTicks[syncSampleTicks.length - 1] で ArrayIndexOutOfBoundsException になっていた)
        if (syncSamples != null && syncSamples.length > 0) {
            long[] syncSampleTicks = new long[syncSamples.length];
            long ticks = 0;
            long duration = track.getDuration();

            for (int i = 0; i < durations.length; i++) {
                int pos = binarySearch(syncSamples, (long) i + 1);
                if (pos >= 0) {
                    syncSampleTicks[pos] = ticks;
                }
                ticks += durations[i];
            }
            long nextTargetTick = 0;

            for (int currentSyncSampleIndex = 0; currentSyncSampleIndex < syncSampleTicks.length - 1; currentSyncSampleIndex++) {
                long tickN1 = syncSampleTicks[currentSyncSampleIndex];
                long tickN2 = syncSampleTicks[currentSyncSampleIndex + 1];
                if (nextTargetTick <= tickN2) {
                    if (Math.abs(tickN1 - nextTargetTick) < Math.abs(tickN2 - nextTargetTick)) {
                        fragments = Mp4Arrays.copyOfAndAppend(fragments, syncSamples[currentSyncSampleIndex]);
                        nextTargetTick = syncSampleTicks[currentSyncSampleIndex] + targetTicks;
                    }
                }
            }
            if (duration - syncSampleTicks[syncSampleTicks.length - 1] > targetTicks / 2) {
                fragments = Mp4Arrays.copyOfAndAppend(fragments, syncSamples[syncSampleTicks.length - 1]);
            }

            // 最初のチャンクは必ずサンプル 1 から始まる必要がある。
            // 同期サンプルが 1 個だけで長さが targetDuration / 2 以下のトラック(1 秒以下の短いクリップ)では
            // 上のループも末尾の判定も成立せず結果が空になり、ビルダーがチャンク 0 個の(再生できない)
            // ファイルを書き出していた。また先頭が同期サンプルでないトラックでは、先頭の同期サンプルより
            // 前のサンプルがどのチャンクにも入らなくなる。どちらもサンプル 1 を先頭に補って救済する。
            // ※正常に出力できていたケースは結果が [1, ...] なので変わらない
            if (fragments.length == 0 || fragments[0] != 1) {
                long[] withFirst = new long[fragments.length + 1];
                withFirst[0] = 1;
                System.arraycopy(fragments, 0, withFirst, 1, fragments.length);
                fragments = withFirst;
            }
        } else {

            double time = 0.0D;
            fragments = new long[]{1L};
            for (int i = 1; i < durations.length; ++i) {
                time += (double) durations[i] / (double) ts;
                if (time >= targetDuration) {
                    if (i > 0) {
                        fragments = Mp4Arrays.copyOfAndAppend(fragments, (long) (i + 1));
                    }

                    time = 0.0D;
                }
            }

            if (time < targetDuration && fragments.length > 1) {
                long numberSamplesLastTwoSegments = durations.length + 1 - fragments[fragments.length - 2];
                fragments[fragments.length - 1] = fragments[fragments.length - 2] + numberSamplesLastTwoSegments / 2;
            }

        }
        return fragments;

    }
}
