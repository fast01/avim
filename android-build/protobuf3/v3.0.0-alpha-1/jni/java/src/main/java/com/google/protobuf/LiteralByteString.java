// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
// https://developers.google.com/protocol-buffers/
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.google.protobuf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * This class implements a {@link com.google.protobuf.ByteString} backed by a
 * single array of bytes, contiguous in memory. It supports substring by
 * pointing to only a sub-range of the underlying byte array, meaning that a
 * substring will reference the full byte-array of the string it's made from,
 * exactly as with {@link String}.
 *
 * @author carlanton@google.com (Carl Haverl)
 */
class LiteralByteString extends ByteString {

  private static final long serialVersionUID = 1L;

  protected final byte[] bytes;

  /**
   * Creates a {@code LiteralByteString} backed by the given array, without
   * copying.
   *
   * @param bytes array to wrap
   */
  LiteralByteString(byte[] bytes) {
    this.bytes = bytes;
  }

  @Override
  public byte byteAt(int index) {
    // Unlike most methods in this class, this one is a direct implementation
    // ignoring the potential offset because we need to do range-checking in the
    // substring case anyway.
    return bytes[index];
  }

  @Override
  public int size() {
    return bytes.length;
  }

  // =================================================================
  // ByteString -> substring

  @Override
  public ByteString substring(int beginIndex, int endIndex) {
    if (beginIndex < 0) {
      throw new IndexOutOfBoundsException(
          "Beginning index: " + beginIndex + " < 0");
    }
    if (endIndex > size()) {
      throw new IndexOutOfBoundsException("End index: " + endIndex + " > " +
          size());
    }
    int substringLength = endIndex - beginIndex;
    if (substringLength < 0) {
      throw new IndexOutOfBoundsException(
          "Beginning index larger than ending index: " + beginIndex + ", "
              + endIndex);
    }

    ByteString result;
    if (substringLength == 0) {
      result = ByteString.EMPTY;
    } else {
      result = new BoundedByteString(bytes, getOffsetIntoBytes() + beginIndex,
          substringLength);
    }
    return result;
  }

  // =================================================================
  // ByteString -> byte[]

  @Override
  protected void copyToInternal(byte[] target, int sourceOffset, 
      int targetOffset, int numberToCopy) {
    // Optimized form, not for subclasses, since we don't call
    // getOffsetIntoBytes() or check the 'numberToCopy' parameter.
    System.arraycopy(bytes, sourceOffset, target, targetOffset, numberToCopy);
  }

  @Override
  public void copyTo(ByteBuffer target) {
    target.put(bytes, getOffsetIntoBytes(), size());  // Copies bytes
  }

  @Override
  public ByteBuffer asReadOnlyByteBuffer() {
    ByteBuffer byteBuffer =
        ByteBuffer.wrap(bytes, getOffsetIntoBytes(), size());
    return byteBuffer.asReadOnlyBuffer();
  }

  @Override
  public List<ByteBuffer> asReadOnlyByteBufferList() {
    // Return the ByteBuffer generated by asReadOnlyByteBuffer() as a singleton
    List<ByteBuffer> result = new ArrayList<ByteBuffer>(1);
    result.add(asReadOnlyByteBuffer());
    return result;
 }

 @Override
  public void writeTo(OutputStream outputStream) throws IOException {
    outputStream.write(toByteArray());
  }

  @Override
  void writeToInternal(OutputStream outputStream, int sourceOffset,
      int numberToWrite) throws IOException {
    outputStream.write(bytes, getOffsetIntoBytes() + sourceOffset,
        numberToWrite);
  }

  @Override
  public String toString(String charsetName)
      throws UnsupportedEncodingException {
    return new String(bytes, getOffsetIntoBytes(), size(), charsetName);
  }

  // =================================================================
  // UTF-8 decoding

  @Override
  public boolean isValidUtf8() {
    int offset = getOffsetIntoBytes();
    return Utf8.isValidUtf8(bytes, offset, offset + size());
  }

  @Override
  protected int partialIsValidUtf8(int state, int offset, int length) {
    int index = getOffsetIntoBytes() + offset;
    return Utf8.partialIsValidUtf8(state, bytes, index, index + length);
  }

  // =================================================================
  // equals() and hashCode()

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof ByteString)) {
      return false;
    }

    if (size() != ((ByteString) other).size()) {
      return false;
    }
    if (size() == 0) {
      return true;
    }

    if (other instanceof LiteralByteString) {
      LiteralByteString otherAsLiteral = (LiteralByteString) other;
      // If we know the hash codes and they are not equal, we know the byte
      // strings are not equal.
      if (hash != 0 
          && otherAsLiteral.hash != 0 
          && hash != otherAsLiteral.hash) {
        return false;
      }
      
      return equalsRange((LiteralByteString) other, 0, size());
    } else if (other instanceof RopeByteString) {
      return other.equals(this);
    } else {
      throw new IllegalArgumentException(
          "Has a new type of ByteString been created? Found "
              + other.getClass());
    }
  }

  /**
   * Check equality of the substring of given length of this object starting at
   * zero with another {@code LiteralByteString} substring starting at offset.
   *
   * @param other  what to compare a substring in
   * @param offset offset into other
   * @param length number of bytes to compare
   * @return true for equality of substrings, else false.
   */
  boolean equalsRange(LiteralByteString other, int offset, int length) {
    if (length > other.size()) {
      throw new IllegalArgumentException(
          "Length too large: " + length + size());
    }
    if (offset + length > other.size()) {
      throw new IllegalArgumentException(
          "Ran off end of other: " + offset + ", " + length + ", " +
              other.size());
    }

    byte[] thisBytes = bytes;
    byte[] otherBytes = other.bytes;
    int thisLimit = getOffsetIntoBytes() + length;
    for (int thisIndex = getOffsetIntoBytes(), otherIndex =
        other.getOffsetIntoBytes() + offset;
        (thisIndex < thisLimit); ++thisIndex, ++otherIndex) {
      if (thisBytes[thisIndex] != otherBytes[otherIndex]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Cached hash value.  Intentionally accessed via a data race, which
   * is safe because of the Java Memory Model's "no out-of-thin-air values"
   * guarantees for ints.
   */
  private int hash = 0;

  /**
   * Compute the hashCode using the traditional algorithm from {@link
   * ByteString}.
   *
   * @return hashCode value
   */
  @Override
  public int hashCode() {
    int h = hash;

    if (h == 0) {
      int size = size();
      h = partialHash(size, 0, size);
      if (h == 0) {
        h = 1;
      }
      hash = h;
    }
    return h;
  }

  @Override
  protected int peekCachedHashCode() {
    return hash;
  }

  @Override
  protected int partialHash(int h, int offset, int length) {
    return hashCode(h, bytes, getOffsetIntoBytes() + offset, length);
  }
  
  static int hashCode(int h, byte[] bytes, int offset, int length) {
    for (int i = offset; i < offset + length; i++) {
      h = h * 31 + bytes[i];
    }
    return h;
  }
  
  static int hashCode(byte[] bytes) {
    int h = hashCode(bytes.length, bytes, 0, bytes.length);
    return h == 0 ? 1 : h;
  }

  // =================================================================
  // Input stream

  @Override
  public InputStream newInput() {
    return new ByteArrayInputStream(bytes, getOffsetIntoBytes(),
        size());  // No copy
  }

  @Override
  public CodedInputStream newCodedInput() {
    // We trust CodedInputStream not to modify the bytes, or to give anyone
    // else access to them.
    return CodedInputStream.newInstance(this);
  }

  // =================================================================
  // ByteIterator

  @Override
  public ByteIterator iterator() {
    return new LiteralByteIterator();
  }

  private class LiteralByteIterator implements ByteIterator {
    private int position;
    private final int limit;

    private LiteralByteIterator() {
      position = 0;
      limit = size();
    }

    public boolean hasNext() {
      return (position < limit);
    }

    public Byte next() {
      // Boxing calls Byte.valueOf(byte), which does not instantiate.
      return nextByte();
    }

    public byte nextByte() {
      try {
        return bytes[position++];
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new NoSuchElementException(e.getMessage());
      }
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  // =================================================================
  // Internal methods

  @Override
  protected int getTreeDepth() {
    return 0;
  }

  @Override
  protected boolean isBalanced() {
    return true;
  }

  /**
   * Offset into {@code bytes[]} to use, non-zero for substrings.
   *
   * @return always 0 for this class
   */
  protected int getOffsetIntoBytes() {
    return 0;
  }
}
