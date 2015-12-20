package lila.hub

import scala.concurrent.duration._
import scala.concurrent.Promise
import scala.util.Try

import akka.actor._

final class Sequencer(
    receiveTimeout: Option[FiniteDuration],
    executionTimeout: Option[FiniteDuration] = None) extends Actor {

  receiveTimeout.foreach(context.setReceiveTimeout)

  private def idle: Receive = {

    case msg =>
      context become busy
      processThenDone(msg)
  }

  private def busy: Receive = {

    case Done => dequeue match {
      case None       => context become idle
      case Some(work) => processThenDone(work)
    }

    case msg => queue enqueue msg
  }

  def receive = idle

  private val queue = collection.mutable.Queue[Any]()
  private def dequeue: Option[Any] = Try(queue.dequeue).toOption

  private case object Done

  private def processThenDone(work: Any) {
    work match {
      case ReceiveTimeout => self ! PoisonPill
      case Sequencer.Work(run, promiseOption, timeoutOption) =>
        timeoutOption.orElse(executionTimeout).fold(run()) { timeout =>
          run().withTimeout(
            duration = timeout,
            error = lila.common.LilaException(s"Sequencer timed out after $timeout")
          )(context.system)
        } andThenAnyway {
          promiseOption.foreach(_.success(()))
          self ! Done
        }
      case x => logwarn(s"[Sequencer] Unsupported message $x")
    }
  }
}

object Sequencer {

  case class Work(
    run: () => Funit,
    promise: Option[Promise[Unit]] = None,
    timeout: Option[FiniteDuration] = None)

  def work(
    run: => Funit,
    promise: Option[Promise[Unit]] = None,
    timeout: Option[FiniteDuration] = None): Work = Work(() => run, promise, timeout)
}
