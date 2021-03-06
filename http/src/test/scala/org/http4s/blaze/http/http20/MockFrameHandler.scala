package org.http4s.blaze.http
package http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.Http2Settings.Setting

private[http20] class MockFrameHandler(inHeaders: Boolean) extends FrameHandler {
  override def inHeaderSequence(): Boolean = inHeaders
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = ???
  override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): Http2Result = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result = ???
  override def onHeadersFrame(streamId: Int, priority: Option[Priority], end_headers: Boolean, end_stream: Boolean, buffer: ByteBuffer): Http2Result = ???
  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???
  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = ???
  override def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Http2Result = ???
  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = ???
}


private[http20] class MockDecodingFrameHandler extends DecodingFrameHandler(new HeaderDecoder(20*1024, 4096)) {
  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???
  override def onCompleteHeadersFrame(streamId: Int, priority: Option[Priority], end_stream: Boolean, headers: Headers): Http2Result = ???
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = ???
  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result = ???
  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = ???
  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = ???
}