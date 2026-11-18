package com.googlecode.mp4parser.authoring.samples;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.TrackBox;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.SampleImpl;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.WrappingTrack;
import com.googlecode.mp4parser.authoring.builder.DefaultStaticChunkMp4Builder;
import com.googlecode.mp4parser.authoring.builder.Fragmenter;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.AbstractList;
import java.util.List;

/**
 * 巨大なチャンクを持つファイルの読み込みの回帰テスト
 * <p>
 * 以前はサンプルを読むたびにチャンク全体を heap に読み込んで(SoftReference で)キャッシュしていたため、
 * チャンクが数百 MB あると OutOfMemoryError になっていた。
 * 閾値(DEFAULT_LARGE_CHUNK_THRESHOLD)を超えるチャンクはサンプル単位で読み、キャッシュしないこと、
 * 読み込んだ内容が正しいことを確認する。
 * <p>
 * ※大きな素材はリポジトリに置かず、テスト中に一時ファイルとして生成する
 */
public class DefaultMp4SampleListLargeChunkTest {

    // チャンク 1: 小さいサンプル x SMALL_COUNT (閾値以下 => 従来どおりチャンク全体を読んでキャッシュ)
    private static final int SMALL_COUNT = 10;
    private static final int SMALL_SIZE = 1000;
    // チャンク 2: 大きいサンプル x 残り全部 (閾値超え => サンプル単位で読む)
    private static final int LARGE_SIZE = 150 * 1000;

    private File file;
    private int sampleCount;

    private static byte valueAt(int sampleIndex, int pos) {
        // サンプルごと・位置ごとに異なる値にして、読み込み位置のずれを検出できるようにする
        return (byte) ((sampleIndex * 131 + pos * 7 + (pos >>> 9)) & 0xff);
    }

    private static int sizeOf(int sampleIndex) {
        // 大きいサンプルは端数を付けてサイズをばらつかせる
        return sampleIndex < SMALL_COUNT ? SMALL_SIZE + sampleIndex : LARGE_SIZE + sampleIndex * 3;
    }

    private static ByteBuffer expected(int sampleIndex) {
        byte[] b = new byte[sizeOf(sampleIndex)];
        for (int i = 0; i < b.length; i++) {
            b[i] = valueAt(sampleIndex, i);
        }
        return ByteBuffer.wrap(b);
    }

    @Before
    public void createFile() throws Exception {
        // 既存の素材の映像トラック(サンプル数 130 程度)をもとに、サンプルの中身だけを差し替えたトラックを作る
        File src = new File(getClass().getResource("/com/googlecode/mp4parser/authoring/samples/1365070268951.mp4").toURI());
        Movie srcMovie = MovieCreator.build(new FileDataSourceImpl(src));
        final Track srcTrack = srcMovie.getTracks().get(0);
        sampleCount = srcTrack.getSamples().size();
        Assert.assertTrue("素材のサンプル数が少なすぎます: " + sampleCount, sampleCount > SMALL_COUNT + 60);

        Track track = new WrappingTrack(srcTrack) {
            @Override
            public List<Sample> getSamples() {
                return new AbstractList<Sample>() {
                    @Override
                    public Sample get(int index) {
                        return new SampleImpl(expected(index));
                    }

                    @Override
                    public int size() {
                        return sampleCount;
                    }
                };
            }
        };
        Movie movie = new Movie();
        movie.addTrack(track);

        DefaultStaticChunkMp4Builder builder = new DefaultStaticChunkMp4Builder();
        builder.setFragmenter(new Fragmenter() {
            public long[] sampleNumbers(Track track) {
                return new long[]{1, SMALL_COUNT + 1};
            }
        });
        Container c = builder.build(movie);

        file = File.createTempFile("large_chunk_test", ".mp4");
        FileOutputStream fos = new FileOutputStream(file);
        try {
            c.writeContainer(fos.getChannel());
        } finally {
            fos.close();
        }
        srcMovie.getTracks().get(0).close();
    }

    @After
    public void deleteFile() {
        if (file != null) {
            file.delete();
        }
    }

    private DefaultMp4SampleList openSampleList(IsoFile isoFile) {
        TrackBox tb = isoFile.getMovieBox().getBoxes(TrackBox.class).get(0);
        return new DefaultMp4SampleList(tb.getTrackHeaderBox().getTrackId(), isoFile);
    }

    @Test
    public void 素材のチャンク構成が想定どおり() throws Exception {
        IsoFile isoFile = new IsoFile(new FileDataSourceImpl(file));
        try {
            DefaultMp4SampleList list = openSampleList(isoFile);
            Assert.assertEquals(sampleCount, list.size());
            Assert.assertEquals(2, list.chunkOffsets.length);
            // チャンク 1 は閾値以下、チャンク 2 は閾値超え
            Assert.assertTrue(list.chunkSizes[0] <= DefaultMp4SampleList.DEFAULT_LARGE_CHUNK_THRESHOLD);
            Assert.assertTrue(list.chunkSizes[1] > DefaultMp4SampleList.DEFAULT_LARGE_CHUNK_THRESHOLD);
        } finally {
            isoFile.close();
        }
    }

    @Test
    public void 閾値を超えるチャンクもサンプル単位で正しく読めてキャッシュしない() throws Exception {
        IsoFile isoFile = new IsoFile(new FileDataSourceImpl(file));
        try {
            DefaultMp4SampleList list = openSampleList(isoFile);
            for (int i = 0; i < list.size(); i++) {
                Sample s = list.get(i);
                Assert.assertEquals(sizeOf(i), s.getSize());
                Assert.assertEquals("サンプル " + i + " の内容が一致しません", expected(i), s.asByteBuffer());
            }
            // 閾値以下のチャンクは従来どおりキャッシュする
            Assert.assertNotNull(list.cache[0].get());
            // 閾値を超えるチャンクは全体を読み込まない(キャッシュしない)
            Assert.assertNull("巨大なチャンクがキャッシュされています", list.cache[1].get());
        } finally {
            isoFile.close();
        }
    }

    @Test
    public void writeToでも正しく書き出せる() throws Exception {
        IsoFile isoFile = new IsoFile(new FileDataSourceImpl(file));
        try {
            DefaultMp4SampleList list = openSampleList(isoFile);
            // 逆順(チャンクをまたいで行き来する)でも正しく読めること
            for (int i = list.size() - 1; i >= 0; i--) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                list.get(i).writeTo(Channels.newChannel(baos));
                Assert.assertEquals("サンプル " + i + " の内容が一致しません", expected(i), ByteBuffer.wrap(baos.toByteArray()));
            }
        } finally {
            isoFile.close();
        }
    }

    @Test
    public void 閾値を下げると全チャンクがサンプル単位でも同じ内容になる() throws IOException {
        IsoFile isoFile = new IsoFile(new FileDataSourceImpl(file));
        try {
            DefaultMp4SampleList list = openSampleList(isoFile);
            list.largeChunkThreshold = 0;
            for (int i = 0; i < list.size(); i++) {
                Assert.assertEquals("サンプル " + i + " の内容が一致しません", expected(i), list.get(i).asByteBuffer());
            }
            Assert.assertNull(list.cache[0].get());
            Assert.assertNull(list.cache[1].get());
        } finally {
            isoFile.close();
        }
    }
}
