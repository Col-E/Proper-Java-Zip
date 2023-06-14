package software.coley.llzip.format.compression;

import software.coley.llzip.format.model.LocalFileHeader;
import software.coley.llzip.util.ByteData;
import software.coley.llzip.util.FastWrapOutputStream;
import software.coley.llzip.util.UnsafeInflater;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.DataFormatException;
import java.util.zip.ZipException;

/**
 * Optimized implementation of {@link DeflateDecompressor} with unsafe resetting for more throughput.
 *
 * @author xDark
 */
public class UnsafeDeflateDecompressor implements Decompressor {
	private static final Deque<UnsafeInflater> INFLATERS = new ArrayDeque<>();

	@Override
	public ByteData decompress(LocalFileHeader header, ByteData data) throws IOException {
		if (header.getCompressionMethod() != ZipCompressions.DEFLATED)
			throw new IOException("LocalFileHeader contents not using 'Deflated'!");
		FastWrapOutputStream out = new FastWrapOutputStream();
		UnsafeInflater inflater;
		Deque<UnsafeInflater> inflaters = INFLATERS;
		synchronized (inflaters) {
			inflater = inflaters.poll();
			if (inflater == null) {
				inflater = new UnsafeInflater(true);
			}
		}
		try {
			byte[] output = new byte[1024];
			byte[] buffer = new byte[1024];
			long position = 0L;
			long length = data.length();
			do {
				if (inflater.needsInput()) {
					int remaining = (int) Math.min(buffer.length, length);
					if (remaining == 0) {
						break;
					}
					data.get(position, buffer, 0, remaining);
					length -= remaining;
					position += remaining;
					inflater.setInput(buffer, 0, remaining);
				}
				int count = inflater.inflate(output);
				if (count != 0) {
					out.write(output, 0, count);
				}
			} while (!inflater.finished() && !inflater.needsDictionary());
		} catch (DataFormatException e) {
			String s = e.getMessage();
			throw (ZipException) new ZipException(s != null ? null : "Invalid ZLIB data format").initCause(e);
		} finally {
			end:
			{
				if (inflaters.size() < 32) {
					synchronized (inflaters) {
						if (inflaters.size() < 32) {
							inflater.fastReset();
							inflaters.push(inflater);
							break end;
						}
					}
				}
				inflater.end();
			}
		}
		return out.wrap();
	}
}