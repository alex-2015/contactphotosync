package com.oxplot.contactphotosync;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ConcatInputStreams extends InputStream {

  private Iterator<InputStream> iter;
  private InputStream cur;

  public ConcatInputStreams(Collection<InputStream> streams) {
    iter = streams.iterator();
    try {
      cur = iter.next();
    } catch (NoSuchElementException e) {
    }
  }

  @Override
  public int read() throws IOException {
    byte[] buffer = new byte[1];
    int bytesRead = read(buffer);
    while (bytesRead == 0)
      bytesRead = read(buffer);
    return bytesRead > 0 ? (256 + buffer[0]) % 256 : -1;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (offset < 0 || length < 0 || offset + length > buffer.length)
      throw new IndexOutOfBoundsException();
    if (cur == null)
      return -1;
    int bytesRead = cur.read(buffer, offset, length);
    if (bytesRead < 0) {
      try {
        cur = iter.next();
      } catch (NoSuchElementException e) {
        cur = null;
        return -1;
      }
      return 0;
    } else {
      return bytesRead;
    }

  }

}
