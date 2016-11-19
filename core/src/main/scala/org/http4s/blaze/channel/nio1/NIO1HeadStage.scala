package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.ChannelHead

import scala.util.{Failure, Success, Try}
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException, SelectableChannel, SelectionKey}

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.Command.{Disconnected, EOF}
import org.http4s.blaze.util.{BufferTools, LocalBuffer}


private[nio1] object NIO1HeadStage {

  private val cachedSuccess = Success(())
  private val cachedFutureSuccess = Future.fromTry(cachedSuccess)
  private val localBuffer = new LocalBuffer(32*1024)

  sealed trait WriteResult
  case object Complete extends WriteResult
  case object Incomplete extends WriteResult
  case class WriteError(t: Exception) extends WriteResult // EOF signals normal termination

}

private[nio1] abstract class NIO1HeadStage(ch: SelectableChannel,
                                         loop: SelectorLoop,
                                          key: SelectionKey) extends ChannelHead
{
  import NIO1HeadStage._

  override def name: String = "NIO1 ByteBuffer Head Stage"

  private[this] val setWriteTask = new SelectorTask {
    override def run(scratch: ByteBuffer): Unit = setOp(SelectionKey.OP_WRITE)
  }

  private[this] val setReadTask = new SelectorTask {
    override def run(scratch: ByteBuffer): Unit = {
      setOp(SelectionKey.OP_READ)
    }
  }

  // State of the HeadStage. These should only be accessed from the SelectorLoop thread
  private var closedReason: Throwable = null // will only be written to inside of 'closeWithError'

  private var readPromise: Promise[ByteBuffer] = null
  private var readBuffer: Success[ByteBuffer] = null

  private var writeData: Array[ByteBuffer] = null
  private var writePromise: Promise[Unit] = null

  private val readLock: AnyRef = setWriteTask
  private val writeLock: AnyRef = setWriteTask


  ///  channel reading bits //////////////////////////////////////////////


  // Called by the selector loop when this channel has data to read
  final def readReady(scratch: ByteBuffer): Unit = readLock.synchronized {

    if (closedReason != null) {
      return
    }

    val r = performRead(scratch)

    // if we successfully read some data, unset the interest and
    // complete the promise, otherwise fail appropriately
    r match {
      case r@Success(_) =>
        val p = readPromise
        readPromise = null
        if (p != null) {
          p.tryComplete(r)
        }
        else {
          assert(readBuffer == null)
          unsetOp(SelectionKey.OP_READ)
          readBuffer = r
        }

      case Failure(e) =>
        val ee = checkError(e)
        sendInboundCommand(Disconnected)
        closeWithError(ee)    // will complete the promise with the error
    }
  }

  final override def readRequest(size: Int): Future[ByteBuffer] = {
    logger.trace(s"NIOHeadStage received a read request of size $size")
    readLock.synchronized {
      if (readPromise != null) {
        Future.failed(new IllegalStateException("Cannot have more than one pending read request"))
      } else if (readBuffer != null) {
        val b = readBuffer
        readBuffer = null
        Future.fromTry(b)
      } else performRead(localBuffer.getBuffer()) match {
        case r@Success(buf) if buf.remaining() > 0 =>
          Future.fromTry(r)

        case Success(_) => // no data

          val p = Promise[ByteBuffer]
          readPromise = p

          loop.executeTask(setReadTask)

          p.future

        case Failure(e) =>
          val ee = checkError(e)
          sendInboundCommand(Disconnected)
          closeWithError(ee)    // will complete the promise with the error
          Future.failed(ee)
      }
    }
  }

  /// channel write bits /////////////////////////////////////////////////

    // Called by the selector loop when this channel can be written to
  final def writeReady(scratch: ByteBuffer): Unit = writeLock.synchronized {
    val buffers = writeData // get a local reference so we don't hit the volatile a lot
    performWrite(scratch, buffers) match {
      case Complete =>
        writeData = null
        unsetOp(SelectionKey.OP_WRITE)
        val p = writePromise
        writePromise = null
        if (p != null) {
          p.tryComplete(cachedSuccess)
        }
        else { /* NOOP: channel must have been closed in some manner */ }

      case Incomplete => /* Need to wait for another go around to try and send more data */
        BufferTools.dropEmpty(buffers)

      case WriteError(t) =>
        unsetOp(SelectionKey.OP_WRITE)
        sendInboundCommand(Disconnected)
        closeWithError(t)
    }
  }

  final override def writeRequest(data: ByteBuffer): Future[Unit] =
    doWriteRequest(Array(data))

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] =
    doWriteRequest(data.toArray)

  private[this] def doWriteRequest(writes: Array[ByteBuffer]): Future[Unit] = {
    logger.trace(s"NIO1HeadStage Write Request: ${writes.to[List]}")
    writeLock.synchronized {
      if (writePromise != null) {
        val t = new IllegalStateException("Cannot have more than one pending write request")
        logger.error(t)(s"Multiple pending write requests")
        Future.failed(t)
      } else if (BufferTools.checkEmpty(writes)) {
        cachedFutureSuccess
      } else performWrite(localBuffer.getBuffer(), writes) match {
        case Complete =>
          cachedFutureSuccess

        case Incomplete =>
          /* Need to wait for the socket to be ready to try and send more data */
          BufferTools.dropEmpty(writes)
          val p = Promise[Unit]
          writePromise = p
          writeData = writes
          loop.executeTask(setWriteTask)
          p.future

        case WriteError(t) =>
          sendInboundCommand(Disconnected)
          closeWithError(t)
          Future.failed(t)
      }
    }
  }

  ///////////////////////////////// Shutdown methods ///////////////////////////////////

  /** Shutdown the channel with an EOF for any pending OPs */
  override protected def stageShutdown(): Unit = {
    closeWithError(EOF)
    super.stageShutdown()
  }

  ///////////////////////////////// Channel Ops ////////////////////////////////////////

  /** Performs the read operation
    * @param scratch a ByteBuffer which is owned by the SelectorLoop to use as
    *                scratch space until this method returns
    * @return a Try with either a successful ByteBuffer, an error, or null if this operation is not complete
    */
  protected def performRead(scratch: ByteBuffer): Try[ByteBuffer]

  /** Perform the write operation for this channel
    * @param buffers buffers to be written to the channel
    * @return a WriteResult that is one of Complete, Incomplete or WriteError(e: Exception)
    */
  protected def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult

  // Cleanup any read or write requests with the Throwable
  final override def closeWithError(t: Throwable): Unit = {

    // intended to be called from within the SelectorLoop but if
    // it's closed it will be performed in the current thread
    def doClose(t: Throwable) {

      // this is the only place that writes to the variable
      if (closedReason == null) {
        closedReason = t
      }
      else if(closedReason != EOF && closedReason != t) {
        closedReason.addSuppressed(t)
      }

      if (readPromise != null) {
        readPromise.tryFailure(t)
        readPromise = null
      }

      if (writePromise != null) {
        writePromise.tryFailure(t)
        writePromise = null
      }

      writeData = null
    }

    try loop.executeTask(new Runnable {
      def run() = {
        logger.trace(s"closeWithError($t); readPromise: $readPromise, writePromise: $writePromise")
        if (t != EOF) logger.error(t)("Abnormal NIO1HeadStage termination")

        if (key.isValid) key.interestOps(0)
        key.attach(null)
        ch.close()

        doClose(t)
      }
    })
    catch { case NonFatal(t2) =>
      t2.addSuppressed(t)
      logger.error(t2)("Caught exception while closing channel: trying to close from " +
                      s"${Thread.currentThread.getName}")
      doClose(t2)
    }
  }

  /** Unsets a channel interest
   *  only to be called by the SelectorLoop thread
   **/
  private def unsetOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops & ~op)
      }
    } catch {
      case _: CancelledKeyException =>
        sendInboundCommand(Disconnected)
        closeWithError(EOF)
    }
  }

  // only to be called by the SelectorLoop thread
  private def setOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) == 0) {
        key.interestOps(ops | op)
      }
    } catch {
      case _: CancelledKeyException =>
        sendInboundCommand(Disconnected)
        closeWithError(EOF)
    }
  }
}
