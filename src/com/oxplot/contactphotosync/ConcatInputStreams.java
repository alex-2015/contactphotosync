/**
 * ConcatInputStreams.java - Concatenation of multiple InputStreams.
 * 
 * Copyright (C) 2012 Mansour <mansour@oxplot.com>
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

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
    } catch (NoSuchElementException e) {}
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
