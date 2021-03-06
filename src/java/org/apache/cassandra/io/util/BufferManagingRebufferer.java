package org.apache.cassandra.io.util;

import java.nio.ByteBuffer;

import org.apache.cassandra.utils.memory.BufferPool;

/**
 * Buffer manager used for reading from a ChunkReader when cache is not in use. Instances of this class are
 * reader-specific and thus do not need to be thread-safe since the reader itself isn't.
 *
 * The instances reuse themselves as the BufferHolder to avoid having to return a new object for each rebuffer call.
 */
public abstract class BufferManagingRebufferer implements Rebufferer, Rebufferer.BufferHolder
{
    protected final ChunkReader source;
    protected final ByteBuffer buffer;
    protected long offset = 0;

    public static BufferManagingRebufferer on(ChunkReader wrapped)
    {
        return wrapped.alignmentRequired()
             ? new Aligned(wrapped)
             : new Unaligned(wrapped);
    }

    abstract long alignedPosition(long position);

    public BufferManagingRebufferer(ChunkReader wrapped)
    {
        this.source = wrapped;
        buffer = RandomAccessReader.allocateBuffer(wrapped.chunkSize(), wrapped.preferredBufferType());
        buffer.limit(0);
    }

    @Override
    public void closeReader()
    {
        BufferPool.put(buffer);
        offset = -1;
    }

    @Override
    public void close()
    {
        assert offset == -1;    // reader must be closed at this point.
        source.close();
    }

    @Override
    public ChannelProxy channel()
    {
        return source.channel();
    }

    @Override
    public long fileLength()
    {
        return source.fileLength();
    }

    @Override
    public BufferHolder rebuffer(long position)
    {
        offset = alignedPosition(position);
        source.readChunk(offset, buffer);
        return this;
    }

    @Override
    public double getCrcCheckChance()
    {
        return source.getCrcCheckChance();
    }

    @Override
    public String toString()
    {
        return "BufferManagingRebufferer." + getClass().getSimpleName() + ":" + source.toString();
    }

    // BufferHolder methods

    public ByteBuffer buffer()
    {
        return buffer;
    }

    public long offset()
    {
        return offset;
    }

    @Override
    public void release()
    {
        // nothing to do, we don't delete buffers before we're closed.
    }

    public static class Unaligned extends BufferManagingRebufferer
    {
        public Unaligned(ChunkReader wrapped)
        {
            super(wrapped);
        }

        @Override
        long alignedPosition(long position)
        {
            return position;
        }
    }

    public static class Aligned extends BufferManagingRebufferer
    {
        public Aligned(ChunkReader wrapped)
        {
            super(wrapped);
            assert Integer.bitCount(wrapped.chunkSize()) == 1;
        }

        @Override
        long alignedPosition(long position)
        {
            return position & -buffer.capacity();
        }
    }
}
