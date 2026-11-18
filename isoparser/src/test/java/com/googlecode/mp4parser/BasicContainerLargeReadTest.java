package com.googlecode.mp4parser;

import org.junit.Assert;
import org.junit.Test;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * FileDataSourceImpl は heap バッファへの1回の read を最大 8MB に制限しているため、
 * BasicContainer.getByteBuffer が 8MB を超える範囲でも全量を読み込めることを確認する
 */
public class BasicContainerLargeReadTest {

    // 8MB の読み込み上限をまたぐサイズ(20MB + 端数)
    private static final int FILE_SIZE = 20 * 1024 * 1024 + 12345;

    private static byte valueAt(long pos) {
        // 位置ごとに異なる値になるようにして、読み飛ばしやずれを検出できるようにする
        return (byte) ((pos * 31 + (pos >>> 13)) & 0xff);
    }

    private static File createTempFile() throws IOException {
        File f = File.createTempFile("large_read_test", ".bin");
        f.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            long pos = 0;
            while (pos < FILE_SIZE) {
                int len = (int) Math.min(buf.length, FILE_SIZE - pos);
                for (int i = 0; i < len; i++) {
                    buf[i] = valueAt(pos + i);
                }
                fos.write(buf, 0, len);
                pos += len;
            }
        } finally {
            fos.close();
        }
        return f;
    }

    @Test
    public void getByteBuffer_8MB超の範囲を全量読み込める() throws IOException {
        File f = createTempFile();
        FileDataSourceImpl ds = new FileDataSourceImpl(f);
        try {
            BasicContainer bc = new BasicContainer();
            bc.dataSource = ds;

            long start = 1000;
            int size = FILE_SIZE - 2000;
            ByteBuffer bb = bc.getByteBuffer(start, size);

            Assert.assertEquals(0, bb.position());
            Assert.assertEquals(size, bb.limit());
            for (int i = 0; i < size; i++) {
                if (bb.get(i) != valueAt(start + i)) {
                    Assert.fail("offset " + i + " の内容が一致しません");
                }
            }
        } finally {
            ds.close();
            f.delete();
        }
    }

    @Test(expected = EOFException.class)
    public void getByteBuffer_ファイル末尾を超える範囲はEOFException() throws IOException {
        File f = createTempFile();
        FileDataSourceImpl ds = new FileDataSourceImpl(f);
        try {
            BasicContainer bc = new BasicContainer();
            bc.dataSource = ds;

            // ファイル実体より 1 バイト多く要求する(途中で切れたファイルを想定)
            bc.getByteBuffer(0, FILE_SIZE + 1);
        } finally {
            ds.close();
            f.delete();
        }
    }
}
