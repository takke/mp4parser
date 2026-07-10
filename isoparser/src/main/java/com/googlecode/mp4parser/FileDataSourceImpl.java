package com.googlecode.mp4parser;

import com.googlecode.mp4parser.util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

public class FileDataSourceImpl implements DataSource {
    private static Logger LOG = Logger.getLogger(FileDataSourceImpl.class);

    // 1回の read で FileChannel に渡す最大サイズ。
    // 巨大な heap ByteBuffer をそのまま渡すと NIO 内部で同サイズの一時 DirectByteBuffer が
    // 確保され OutOfMemoryError の原因になるため分割して読み込む。
    private static final int MAX_READ_CHUNK_SIZE = 8 * 1024 * 1024;

    FileChannel fc;
    String filename;


    public FileDataSourceImpl(File f) throws FileNotFoundException {
        this.fc = new FileInputStream(f).getChannel();
        this.filename = f.getName();
    }

    public FileDataSourceImpl(String f) throws FileNotFoundException {
        File file = new File(f);
        this.fc = new FileInputStream(file).getChannel();
        this.filename =  file.getName();
    }


    public FileDataSourceImpl(FileChannel fc) {
        this.fc = fc;
        this.filename = "unknown";
    }
    public FileDataSourceImpl(FileChannel fc, String filename) {
        this.fc = fc;
        this.filename = filename;
    }

    public synchronized int read(ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.isDirect() || byteBuffer.remaining() <= MAX_READ_CHUNK_SIZE) {
            return fc.read(byteBuffer);
        }
        // limit を一時的に縮めてチャンク単位で読み込む（呼び出し側は remaining が 0 になるまでループする契約）
        int originalLimit = byteBuffer.limit();
        byteBuffer.limit(byteBuffer.position() + MAX_READ_CHUNK_SIZE);
        try {
            return fc.read(byteBuffer);
        } finally {
            byteBuffer.limit(originalLimit);
        }
    }

    public synchronized long size() throws IOException {
        return fc.size();
    }

    public synchronized long position() throws IOException {
        return fc.position();
    }

    public synchronized void position(long nuPos) throws IOException {
        fc.position(nuPos);
    }

    public synchronized long transferTo(long startPosition, long count, WritableByteChannel sink) throws IOException {
        return fc.transferTo(startPosition, count, sink);
    }

    public synchronized ByteBuffer map(long startPosition, long size) throws IOException {
        //LOG.logDebug(startPosition + " " + size);
        return fc.map(FileChannel.MapMode.READ_ONLY, startPosition, size);
    }

    public void close() throws IOException {
        fc.close();
    }

    @Override
    public String toString() {
        return filename;
    }
}
