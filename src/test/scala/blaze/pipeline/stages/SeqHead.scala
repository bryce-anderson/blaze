package blaze.pipeline.stages

import blaze.pipeline.HeadStage
import scala.concurrent.Future
import blaze.pipeline.Command.EOF

/**
 * @author Bryce Anderson
 *         Created on 1/19/14
 */
class SeqHead[O](private var data: Seq[O]) extends HeadStage[O] {

  def name: String = "SeqHead test HeadStage"

  def readRequest(size: Int): Future[O] = {
    if (!data.isEmpty) {
      val h = data.head
      data = data.tail
      Future.successful(h)
    }
    else Future.failed(EOF)
  }

  def writeRequest(data: O): Future[Any] = Future.failed(new NotImplementedError("SeqHead doesn't accept writes"))

}