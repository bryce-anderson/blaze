package org.http4s.blaze.util

import java.nio.ByteBuffer


private[blaze] final class LocalBuffer(bufferSize: Int) {

  private[this] val localBuffer = new ThreadLocal[ByteBuffer]

  def getBuffer(): ByteBuffer = {
    var buffer = localBuffer.get()
    if (buffer == null) {
      buffer = ByteBuffer.allocateDirect(bufferSize)
      localBuffer.set(buffer)
    }
    buffer
  }
}
