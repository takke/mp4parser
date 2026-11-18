package com.googlecode.mp4parser.authoring.builder;

import com.coremedia.iso.boxes.CompositionTimeToSample;
import com.coremedia.iso.boxes.SampleDependencyTypeBox;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SubSampleInformationBox;
import com.googlecode.mp4parser.authoring.Edit;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.SampleImpl;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack;
import com.googlecode.mp4parser.boxes.mp4.samplegrouping.GroupEntry;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 短いトラック(同期サンプルが 1 個だけ等)のチャンク分割の回帰テスト
 *
 * 修正前は BetterFragmenter.sampleNumbers が空配列を返し、ビルダーがチャンク 0 個の
 * (再生できない)ファイルを例外なしで書き出していた。
 * ※DummyTrack は 1 サンプル 40ms(timescale 1000)、BetterFragmenter の目標は 2 秒(半分は 1 秒)
 */
public class ShortTrackChunkTest {

    private static long[] fragment(int total, long[] syncSamples) {
        return new BetterFragmenter(2.0).sampleNumbers(new DummyTrack(total, syncSamples));
    }

    //--------------------------------------------------
    // BetterFragmenter
    //--------------------------------------------------

    @Test
    public void 同期サンプル1個で1秒以下でもサンプル1から始まるチャンクが1つできる() {
        // 20 サンプル = 0.8 秒
        Assert.assertArrayEquals(new long[]{1}, fragment(20, new long[]{1}));
    }

    @Test
    public void 同期サンプル1個でちょうど1秒でもチャンクが1つできる() {
        // 25 サンプル = 1.0 秒(duration - tick > targetTicks / 2 が偽になる境界)
        Assert.assertArrayEquals(new long[]{1}, fragment(25, new long[]{1}));
    }

    @Test
    public void 同期サンプル1個で1秒より1フレーム長い場合は従来どおり() {
        // 26 サンプル = 1.04 秒(修正前から [1] になるケース)
        Assert.assertArrayEquals(new long[]{1}, fragment(26, new long[]{1}));
    }

    @Test
    public void 同期サンプル0個は同期サンプルなしと同じ扱いになる() {
        // 修正前は syncSampleTicks[-1] で ArrayIndexOutOfBoundsException になっていた
        Assert.assertArrayEquals(fragment(20, null), fragment(20, new long[0]));
        Assert.assertArrayEquals(new long[]{1}, fragment(20, new long[0]));
        Assert.assertArrayEquals(fragment(151, null), fragment(151, new long[0]));
    }

    @Test
    public void 先頭が同期サンプルでないトラックでもサンプル1から始まる() {
        // 修正前は [11, 61] となりサンプル 1〜10 がどのチャンクにも入らなかった
        Assert.assertArrayEquals(new long[]{1, 11, 61}, fragment(151, new long[]{11, 61}));
    }

    @Test
    public void 既存の正常ケースの結果は変わらない() {
        Assert.assertArrayEquals(new long[]{1, 51, 101, 126}, fragment(151, null));
        Assert.assertArrayEquals(new long[]{1, 51, 86}, fragment(121, null));
        Assert.assertArrayEquals(new long[]{1, 51}, fragment(106, new long[]{1, 51, 62, 101}));
        Assert.assertArrayEquals(new long[]{1, 52, 101}, fragment(151, new long[]{1, 6, 52, 101}));
        Assert.assertArrayEquals(new long[]{1, 51, 101}, fragment(151, new long[]{1, 51, 62, 101}));
        Assert.assertArrayEquals(new long[]{1, 51}, fragment(100, new long[]{1, 26, 51, 76}));
        // 0 サンプルのトラック(同期サンプルなし)は従来どおり [1]
        Assert.assertArrayEquals(new long[]{1}, fragment(0, null));
    }

    //--------------------------------------------------
    // CroppedTrack.getSyncSamples
    //--------------------------------------------------

    @Test
    public void CroppedTrackの同期サンプル番号は1始まりで0を含まない() {
        // 0 始まりで 30, 31 番目(1 始まりで 31, 32)のサンプルが連続するキーフレーム
        DummyTrack orig = new DummyTrack(120, new long[]{1, 31, 32, 61});

        // 0 始まり 31 番目(=キーフレーム)から切り出す。修正前は [0, 1] になっていた
        Assert.assertArrayEquals(new long[]{1, 30}, new CroppedTrack(orig, 31, 120).getSyncSamples());
        // 0 始まり 30 番目から切り出す
        Assert.assertArrayEquals(new long[]{1, 2, 31}, new CroppedTrack(orig, 30, 120).getSyncSamples());
        // 終端(exclusive)ちょうどのキーフレームは含まない / 終端の直前のキーフレームは含む
        Assert.assertArrayEquals(new long[]{1, 2}, new CroppedTrack(orig, 30, 60).getSyncSamples());
        Assert.assertArrayEquals(new long[]{1, 2, 31}, new CroppedTrack(orig, 30, 61).getSyncSamples());
        // 先頭から
        Assert.assertArrayEquals(new long[]{1, 31, 32}, new CroppedTrack(orig, 0, 60).getSyncSamples());
    }

    //--------------------------------------------------
    // ビルダーの getChunkSizes の整合性チェック
    //--------------------------------------------------

    private static final Fragmenter EMPTY_FRAGMENTER = new Fragmenter() {
        public long[] sampleNumbers(Track track) {
            return new long[0];
        }
    };

    private static final Fragmenter BROKEN_FRAGMENTER = new Fragmenter() {
        public long[] sampleNumbers(Track track) {
            // サンプル 1〜10 がどのチャンクにも入らない
            return new long[]{11};
        }
    };

    @Test
    public void チャンク0個になる場合はDefaultStaticChunkMp4Builderが例外を投げる() {
        DefaultStaticChunkMp4Builder builder = new DefaultStaticChunkMp4Builder();
        builder.setFragmenter(EMPTY_FRAGMENTER);
        try {
            builder.getChunkSizes(new DummyTrack(20, new long[]{1}));
            Assert.fail("例外が発生しませんでした");
        } catch (IllegalStateException expected) {
            // OK
        }
        builder.setFragmenter(BROKEN_FRAGMENTER);
        try {
            builder.getChunkSizes(new DummyTrack(20, new long[]{1}));
            Assert.fail("例外が発生しませんでした");
        } catch (IllegalStateException expected) {
            // OK
        }
    }

    @Test
    public void チャンク0個になる場合はDefaultMp4Builderが例外を投げる() {
        DefaultMp4Builder builder = new DefaultMp4Builder();
        builder.setFragmenter(EMPTY_FRAGMENTER);
        try {
            builder.getChunkSizes(new DummyTrack(20, new long[]{1}));
            Assert.fail("例外が発生しませんでした");
        } catch (IllegalStateException expected) {
            // OK
        }
        builder.setFragmenter(BROKEN_FRAGMENTER);
        try {
            builder.getChunkSizes(new DummyTrack(20, new long[]{1}));
            Assert.fail("例外が発生しませんでした");
        } catch (IllegalStateException expected) {
            // OK
        }
    }

    @Test
    public void 短いトラックでもチャンクのサンプル数の合計がサンプル数と一致する() {
        DefaultStaticChunkMp4Builder staticBuilder = new DefaultStaticChunkMp4Builder();
        staticBuilder.setFragmenter(new BetterFragmenter(2));
        DefaultMp4Builder builder = new DefaultMp4Builder();
        builder.setFragmenter(new BetterFragmenter(2));

        Assert.assertArrayEquals(new int[]{20}, staticBuilder.getChunkSizes(new DummyTrack(20, new long[]{1})));
        Assert.assertArrayEquals(new int[]{25}, builder.getChunkSizes(new DummyTrack(25, new long[]{1})));
        Assert.assertArrayEquals(new int[]{10, 50, 91}, staticBuilder.getChunkSizes(new DummyTrack(151, new long[]{11, 61})));
        // サンプル 0 個のトラックは従来どおり通す
        Assert.assertArrayEquals(new int[]{0}, staticBuilder.getChunkSizes(new DummyTrack(0, null)));
    }

    /**
     * テスト用の最小限の Track(1 サンプル 40ms、timescale 1000)
     */
    static class DummyTrack implements Track {

        final long[] syncSamples;
        final long[] sampleDurations;
        final List<Sample> samples;

        DummyTrack(int total, long[] syncSamples) {
            this.syncSamples = syncSamples;
            sampleDurations = new long[total];
            Arrays.fill(sampleDurations, 40);
            samples = new ArrayList<Sample>();
            for (int i = 0; i < total; i++) {
                samples.add(new SampleImpl(ByteBuffer.wrap(new byte[]{(byte) i})));
            }
        }

        public SampleDescriptionBox getSampleDescriptionBox() {
            return null;
        }

        public long[] getSampleDurations() {
            return sampleDurations;
        }

        public long getDuration() {
            long duration = 0;
            for (long delta : sampleDurations) {
                duration += delta;
            }
            return duration;
        }

        public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
            return null;
        }

        public long[] getSyncSamples() {
            return syncSamples;
        }

        public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
            return null;
        }

        public TrackMetaData getTrackMetaData() {
            TrackMetaData tmd = new TrackMetaData();
            tmd.setTimescale(1000);
            return tmd;
        }

        public String getHandler() {
            return "vide";
        }

        public List<Sample> getSamples() {
            return samples;
        }

        public SubSampleInformationBox getSubsampleInformationBox() {
            return null;
        }

        public String getName() {
            return "dummy";
        }

        public List<Edit> getEdits() {
            return null;
        }

        public Map<GroupEntry, long[]> getSampleGroups() {
            return null;
        }

        public void close() throws IOException {
        }
    }
}
