package com.googlecode.mp4parser.authoring.tracks;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.CompositionTimeToSample;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.SampleDependencyTypeBox;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SampleTableBox;
import com.coremedia.iso.boxes.SubSampleInformationBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.UnknownBox;
import com.coremedia.iso.boxes.sampleentry.SampleEntry;
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry;
import com.googlecode.mp4parser.authoring.AbstractTrack;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.SampleImpl;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.authoring.builder.DefaultStaticChunkMp4Builder;
import com.googlecode.mp4parser.MemoryDataSourceImpl;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SampleEntry として読めない sample entry(UnknownBox)を持つトラックの AppendTrack の回帰テスト
 *
 * 未対応コーデック(vp09 / av01 / Opus 等)や未知のメタデータトラック(mett 等)は stsd の中身が UnknownBox として読まれ、
 * 修正前は AppendTrack のコンストラクタの getBoxes(SampleEntry.class).get(0) が
 * IndexOutOfBoundsException("Index: 0") になっていた(アプリのマルチカットで発生)。
 */
public class AppendTrackUnknownSampleEntryTest {

    @Test
    public void 未知のsampleEntryで同じstsdを持つトラックを連結できる() throws Exception {
        // 同じ元トラックから切り出した 2 区間の連結(マルチカット)を模擬する
        DummyTrack orig = new DummyTrack(unknownStsd("vp09", new byte[]{1, 2, 3, 4}), 30);
        AppendTrack appended = new AppendTrack(new CroppedTrack(orig, 0, 10), new CroppedTrack(orig, 20, 30));

        Assert.assertEquals(20, appended.getSamples().size());
        Assert.assertEquals(20, appended.getSampleDurations().length);
        // 元の stsd をそのまま使う
        Assert.assertSame(orig.getSampleDescriptionBox(), appended.getSampleDescriptionBox());
        Assert.assertArrayEquals(new long[]{1, 11}, appended.getSyncSamples());
    }

    @Test
    public void 未知のsampleEntryでバイト単位で同一の別インスタンスのstsdでも連結できる() throws Exception {
        DummyTrack t1 = new DummyTrack(unknownStsd("Opus", new byte[]{9, 8, 7}), 10);
        DummyTrack t2 = new DummyTrack(unknownStsd("Opus", new byte[]{9, 8, 7}), 5);
        AppendTrack appended = new AppendTrack(t1, t2);

        Assert.assertEquals(15, appended.getSamples().size());
        Assert.assertArrayEquals(toBytes(t1.getSampleDescriptionBox()), toBytes(appended.getSampleDescriptionBox()));
    }

    @Test
    public void 未知のsampleEntryを連結したトラックからmp4を書き出して読み直せる() throws Exception {
        DummyTrack orig = new DummyTrack(unknownStsd("vp09", new byte[]{1, 2, 3, 4}), 30);
        Movie movie = new Movie();
        movie.addTrack(new AppendTrack(new CroppedTrack(orig, 0, 10), new CroppedTrack(orig, 20, 30)));
        Container container = new DefaultStaticChunkMp4Builder().build(movie);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        container.writeContainer(Channels.newChannel(baos));

        IsoFile isoFile = new IsoFile(new MemoryDataSourceImpl(baos.toByteArray()));
        TrackBox trackBox = isoFile.getMovieBox().getBoxes(TrackBox.class).get(0);
        SampleTableBox stbl = trackBox.getSampleTableBox();
        Assert.assertEquals(20, stbl.getSampleSizeBox().getSampleCount());
        Assert.assertEquals("vp09", stbl.getSampleDescriptionBox().getBoxes().get(0).getType());
        isoFile.close();
    }

    @Test
    public void 未知のsampleEntryでstsdが異なる場合は原因が分かるIOExceptionになる() {
        DummyTrack t1 = new DummyTrack(unknownStsd("vp09", new byte[]{1, 2, 3, 4}), 10);
        DummyTrack t2 = new DummyTrack(unknownStsd("vp09", new byte[]{1, 2, 3, 5}), 10);
        try {
            new AppendTrack(t1, t2);
            Assert.fail("IOException になるはず");
        } catch (IOException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("vp09"));
        }
    }

    @Test
    public void 既知のsampleEntryと未知のsampleEntryの連結は原因が分かるIOExceptionになる() {
        DummyTrack t1 = new DummyTrack(visualStsd(320), 10);
        DummyTrack t2 = new DummyTrack(unknownStsd("vp09", new byte[]{1, 2, 3, 4}), 10);
        try {
            new AppendTrack(t1, t2);
            Assert.fail("IOException になるはず");
        } catch (IOException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("avc1") && e.getMessage().contains("vp09"));
        }
    }

    @Test
    public void 既知のsampleEntryは従来どおり新しいstsdにマージされる() throws Exception {
        DummyTrack t1 = new DummyTrack(visualStsd(320), 10);
        DummyTrack t2 = new DummyTrack(visualStsd(320), 10);
        AppendTrack appended = new AppendTrack(t1, t2);

        SampleDescriptionBox stsd = appended.getSampleDescriptionBox();
        Assert.assertNotSame(t1.getSampleDescriptionBox(), stsd);
        List<SampleEntry> entries = stsd.getBoxes(SampleEntry.class);
        Assert.assertEquals(1, entries.size());
        Assert.assertEquals("avc1", entries.get(0).getType());
        Assert.assertEquals(20, appended.getSamples().size());
    }

    //--------------------------------------------------
    // ヘルパー
    //--------------------------------------------------

    private static SampleDescriptionBox unknownStsd(String type, byte[] data) {
        SampleDescriptionBox stsd = new SampleDescriptionBox();
        UnknownBox entry = new UnknownBox(type);
        entry.setData(ByteBuffer.wrap(data));
        stsd.addBox(entry);
        return stsd;
    }

    private static SampleDescriptionBox visualStsd(int width) {
        SampleDescriptionBox stsd = new SampleDescriptionBox();
        VisualSampleEntry entry = new VisualSampleEntry(VisualSampleEntry.TYPE3);
        entry.setWidth(width);
        entry.setHeight(240);
        stsd.addBox(entry);
        return stsd;
    }

    private static byte[] toBytes(SampleDescriptionBox box) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        box.getBox(Channels.newChannel(baos));
        return baos.toByteArray();
    }

    /**
     * テスト用の最小限の Track(1 サンプル 40ms、timescale 1000、10 サンプルごとにキーフレーム)
     */
    static class DummyTrack extends AbstractTrack {
        final SampleDescriptionBox stsd;
        final long[] sampleDurations;
        final long[] syncSamples;
        final List<Sample> samples = new ArrayList<Sample>();
        final TrackMetaData trackMetaData = new TrackMetaData();

        DummyTrack(SampleDescriptionBox stsd, int total) {
            super("dummy");
            this.stsd = stsd;
            sampleDurations = new long[total];
            Arrays.fill(sampleDurations, 40);
            syncSamples = new long[(total + 9) / 10];
            for (int i = 0; i < syncSamples.length; i++) {
                syncSamples[i] = i * 10 + 1;
            }
            for (int i = 0; i < total; i++) {
                samples.add(new SampleImpl(ByteBuffer.wrap(new byte[]{(byte) i})));
            }
            trackMetaData.setTimescale(1000);
        }

        public List<Sample> getSamples() {
            return samples;
        }

        public SampleDescriptionBox getSampleDescriptionBox() {
            return stsd;
        }

        public long[] getSampleDurations() {
            return sampleDurations;
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
            return trackMetaData;
        }

        public String getHandler() {
            return "vide";
        }

        public SubSampleInformationBox getSubsampleInformationBox() {
            return null;
        }

        public void close() throws IOException {
        }
    }
}
