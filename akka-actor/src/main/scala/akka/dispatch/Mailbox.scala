/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import java.util.{ Comparator, PriorityQueue, Queue, Deque }
import java.util.concurrent._
import akka.AkkaException
import akka.dispatch.sysmsg._
import akka.actor.{ ActorCell, ActorRef, Cell, ActorSystem, InternalActorRef, DeadLetter }
import akka.util.{ Unsafe, BoundedBlockingQueue }
import akka.event.Logging.Error
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.annotation.tailrec
import scala.util.control.NonFatal
import com.typesafe.config.Config
/**
 * INTERNAL API
 */
private[akka] object Mailbox {

  type Status = Int

  /*
   * The following assigned numbers CANNOT be changed without looking at the code which uses them!
   */

  // Primary status
  final val Open = 0 // _status is not initialized in AbstractMailbox, so default must be zero! Deliberately without type ascription to make it a compile-time constant
  final val Closed = 1 // Deliberately without type ascription to make it a compile-time constant
  // Secondary status: Scheduled bit may be added to Open/Suspended
  final val Scheduled = 2 // Deliberately without type ascription to make it a compile-time constant
  // Shifted by 2: the suspend count!
  final val shouldScheduleMask = 3
  final val shouldNotProcessMask = ~2
  final val suspendMask = ~3
  final val suspendUnit = 4

  // mailbox debugging helper using println (see below)
  // since this is a compile-time constant, scalac will elide code behind if (Mailbox.debug) (RK checked with 2.9.1)
  final val debug = false // Deliberately without type ascription to make it a compile-time constant
}

/**
 * Mailbox and InternalMailbox is separated in two classes because ActorCell is needed for implementation,
 * but can't be exposed to user defined mailbox subclasses.
 *
 * INTERNAL API
 */
private[akka] abstract class Mailbox(val messageQueue: MessageQueue)
  extends SystemMessageQueue with Runnable {

  import Mailbox._

  /*
   * This is needed for actually executing the mailbox, i.e. invoking the
   * ActorCell. There are situations (e.g. RepointableActorRef) where a Mailbox
   * is constructed but we know that we will not execute it, in which case this
   * will be null. It must be a var to support switching into an “active”
   * mailbox, should the owning ActorRef turn local.
   *
   * ANOTHER THING, IMPORTANT:
   *
   * actorCell.start() publishes actorCell & self to the dispatcher, which
   * means that messages may be processed theoretically before self’s constructor
   * ends. The JMM guarantees visibility for final fields only after the end
   * of the constructor, so safe publication requires that THIS WRITE BELOW
   * stay as it is.
   */
  @volatile
  var actor: ActorCell = _
  def setActor(cell: ActorCell): Unit = actor = cell

  def dispatcher: MessageDispatcher = actor.dispatcher

  /**
   * Try to enqueue the message to this queue, or throw an exception.
   */
  def enqueue(receiver: ActorRef, msg: Envelope): Unit = messageQueue.enqueue(receiver, msg)

  /**
   * Try to dequeue the next message from this queue, return null failing that.
   */
  def dequeue(): Envelope = messageQueue.dequeue()

  /**
   * Indicates whether this queue is non-empty.
   */
  def hasMessages: Boolean = messageQueue.hasMessages

  /**
   * Should return the current number of messages held in this queue; may
   * always return 0 if no other value is available efficiently. Do not use
   * this for testing for presence of messages, use `hasMessages` instead.
   */
  def numberOfMessages: Int = messageQueue.numberOfMessages

  @volatile
  protected var _statusDoNotCallMeDirectly: Status = _ //0 by default

  @volatile
  protected var _systemQueueDoNotCallMeDirectly: SystemMessage = _ //null by default

  @inline
  final def status: Mailbox.Status = Unsafe.instance.getIntVolatile(this, AbstractMailbox.mailboxStatusOffset)

  @inline
  final def shouldProcessMessage: Boolean = (status & shouldNotProcessMask) == 0

  @inline
  final def suspendCount: Int = status / suspendUnit

  @inline
  final def isSuspended: Boolean = (status & suspendMask) != 0

  @inline
  final def isClosed: Boolean = status == Closed

  @inline
  final def isScheduled: Boolean = (status & Scheduled) != 0

  @inline
  protected final def updateStatus(oldStatus: Status, newStatus: Status): Boolean =
    Unsafe.instance.compareAndSwapInt(this, AbstractMailbox.mailboxStatusOffset, oldStatus, newStatus)

  @inline
  protected final def setStatus(newStatus: Status): Unit =
    Unsafe.instance.putIntVolatile(this, AbstractMailbox.mailboxStatusOffset, newStatus)

  /**
   * Reduce the suspend count by one. Caller does not need to worry about whether
   * status was Scheduled or not.
   *
   * @return true if the suspend count reached zero
   */
  @tailrec
  final def resume(): Boolean = status match {
    case Closed ⇒
      setStatus(Closed); false
    case s ⇒
      val next = if (s < suspendUnit) s else s - suspendUnit
      if (updateStatus(s, next)) next < suspendUnit
      else resume()
  }

  /**
   * Increment the suspend count by one. Caller does not need to worry about whether
   * status was Scheduled or not.
   *
   * @return true if the previous suspend count was zero
   */
  @tailrec
  final def suspend(): Boolean = status match {
    case Closed ⇒
      setStatus(Closed); false
    case s ⇒
      if (updateStatus(s, s + suspendUnit)) s < suspendUnit
      else suspend()
  }

  /**
   * set new primary status Closed. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeClosed(): Boolean = status match {
    case Closed ⇒
      setStatus(Closed); false
    case s ⇒ updateStatus(s, Closed) || becomeClosed()
  }

  /**
   * Set Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsScheduled(): Boolean = {
    val s = status
    /*
     * Only try to add Scheduled bit if pure Open/Suspended, not Closed or with
     * Scheduled bit already set.
     */
    if ((s & shouldScheduleMask) != Open) false
    else updateStatus(s, s | Scheduled) || setAsScheduled()
  }

  /**
   * Reset Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsIdle(): Boolean = {
    val s = status
    updateStatus(s, s & ~Scheduled) || setAsIdle()
  }
  /*
   * AtomicReferenceFieldUpdater for system queue.
   */
  protected final def systemQueueGet: LatestFirstSystemMessageList =
    // Note: contrary how it looks, there is no allocation here, as SystemMessageList is a value class and as such
    // it just exists as a typed view during compile-time. The actual return type is still SystemMessage.
    new LatestFirstSystemMessageList(Unsafe.instance.getObjectVolatile(this, AbstractMailbox.systemMessageOffset).asInstanceOf[SystemMessage])

  protected final def systemQueuePut(_old: LatestFirstSystemMessageList, _new: LatestFirstSystemMessageList): Boolean =
    // Note: calling .head is not actually existing on the bytecode level as the parameters _old and _new
    // are SystemMessage instances hidden during compile time behind the SystemMessageList value class.
    // Without calling .head the parameters would be boxed in SystemMessageList wrapper.
    Unsafe.instance.compareAndSwapObject(this, AbstractMailbox.systemMessageOffset, _old.head, _new.head)

  final def canBeScheduledForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case Open | Scheduled ⇒ hasMessageHint || hasSystemMessageHint || hasSystemMessages || hasMessages
    case Closed           ⇒ false
    case _                ⇒ hasSystemMessageHint || hasSystemMessages
  }

  final def run = {
    try {
      if (!isClosed) { //Volatile read, needed here
        processAllSystemMessages() //First, deal with any system messages
        processMailbox() //Then deal with messages
      }
    } finally {
      setAsIdle() //Volatile write, needed here
      dispatcher.registerForExecution(this, false, false)
    }
  }

  /**
   * Process the messages in the mailbox
   */
  @tailrec private final def processMailbox(
    left: Int = java.lang.Math.max(dispatcher.throughput, 1),
    deadlineNs: Long = if (dispatcher.isThroughputDeadlineTimeDefined == true) System.nanoTime + dispatcher.throughputDeadlineTime.toNanos else 0L): Unit =
    if (shouldProcessMessage) {
      val next = dequeue()
      if (next ne null) {
        if (Mailbox.debug) println(actor.self + " processing message " + next)
        actor invoke next
        if (Thread.interrupted())
          throw new InterruptedException("Interrupted while processing actor messages")
        processAllSystemMessages()
        if ((left > 1) && ((dispatcher.isThroughputDeadlineTimeDefined == false) || (System.nanoTime - deadlineNs) < 0))
          processMailbox(left - 1, deadlineNs)
      }
    }

  /**
   * Will at least try to process all queued system messages: in case of
   * failure simply drop and go on to the next, because there is nothing to
   * restart here (failure is in ActorCell somewhere …). In case the mailbox
   * becomes closed (because of processing a Terminate message), dump all
   * already dequeued message to deadLetters.
   */
  final def processAllSystemMessages() {
    var interruption: Throwable = null
    var messageList = systemDrain(SystemMessageList.LNil)
    while ((messageList.nonEmpty) && !isClosed) {
      val msg = messageList.head
      messageList = messageList.tail
      msg.unlink()
      if (debug) println(actor.self + " processing system message " + msg + " with " + actor.childrenRefs)
      // we know here that systemInvoke ensures that only "fatal" exceptions get rethrown
      actor systemInvoke msg
      if (Thread.interrupted())
        interruption = new InterruptedException("Interrupted while processing system messages")
      // don’t ever execute normal message when system message present!
      if ((messageList.isEmpty) && !isClosed) messageList = systemDrain(SystemMessageList.LNil)
    }
    /*
     * if we closed the mailbox, we must dump the remaining system messages
     * to deadLetters (this is essential for DeathWatch)
     */
    val dlm = actor.systemImpl.deadLetterMailbox
    while (messageList.nonEmpty) {
      val msg = messageList.head
      messageList = messageList.tail
      msg.unlink()
      try dlm.systemEnqueue(actor.self, msg)
      catch {
        case e: InterruptedException ⇒ interruption = e
        case NonFatal(e) ⇒ actor.system.eventStream.publish(
          Error(e, actor.self.path.toString, this.getClass, "error while enqueuing " + msg + " to deadLetters: " + e.getMessage))
      }
    }
    // if we got an interrupted exception while handling system messages, then rethrow it
    if (interruption ne null) {
      Thread.interrupted() // clear interrupted flag before throwing according to java convention
      throw interruption
    }
  }

  /**
   * Overridable callback to clean up the mailbox,
   * called when an actor is unregistered.
   * By default it dequeues all system messages + messages and ships them to the owning actors' systems' DeadLetterMailbox
   */
  protected[dispatch] def cleanUp(): Unit =
    if (actor ne null) { // actor is null for the deadLetterMailbox
      val dlm = actor.systemImpl.deadLetterMailbox
      var messageList = systemDrain(new LatestFirstSystemMessageList(NoMessage))
      while (messageList.nonEmpty) {
        // message must be “virgin” before being able to systemEnqueue again
        val msg = messageList.head
        messageList = messageList.tail
        msg.unlink()
        dlm.systemEnqueue(actor.self, msg)
      }

      if (messageQueue ne null) // needed for CallingThreadDispatcher, which never calls Mailbox.run()
        messageQueue.cleanUp(actor.self, actor.systemImpl.deadLetterMailbox.messageQueue)
    }
}

/**
 * A MessageQueue is one of the core components in forming an Akka Mailbox.
 * The MessageQueue is where the normal messages that are sent to Actors will be enqueued (and subsequently dequeued)
 * It needs to at least support N producers and 1 consumer thread-safely.
 */
trait MessageQueue {
  /**
   * Try to enqueue the message to this queue, or throw an exception.
   */
  def enqueue(receiver: ActorRef, handle: Envelope): Unit // NOTE: receiver is used only in two places, but cannot be removed

  /**
   * Try to dequeue the next message from this queue, return null failing that.
   */
  def dequeue(): Envelope

  /**
   * Should return the current number of messages held in this queue; may
   * always return 0 if no other value is available efficiently. Do not use
   * this for testing for presence of messages, use `hasMessages` instead.
   */
  def numberOfMessages: Int

  /**
   * Indicates whether this queue is non-empty.
   */
  def hasMessages: Boolean

  /**
   * Called when the mailbox this queue belongs to is disposed of. Normally it
   * is expected to transfer all remaining messages into the dead letter queue
   * which is passed in. The owner of this MessageQueue is passed in if
   * available (e.g. for creating DeadLetters()), “/deadletters” otherwise.
   */
  def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit
}

class NodeMessageQueue extends AbstractNodeQueue[Envelope] with MessageQueue {

  final def enqueue(receiver: ActorRef, handle: Envelope): Unit = add(handle)

  final def dequeue(): Envelope = poll()

  final def numberOfMessages: Int = count()

  final def hasMessages: Boolean = !isEmpty()

  @tailrec final def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    val envelope = dequeue()
    if (envelope ne null) {
      deadLetters.enqueue(owner, envelope)
      cleanUp(owner, deadLetters)
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] trait SystemMessageQueue {
  /**
   * Enqueue a new system message, e.g. by prepending atomically as new head of a single-linked list.
   */
  def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit

  /**
   * Dequeue all messages from system queue and return them as single-linked list.
   */
  def systemDrain(newContents: LatestFirstSystemMessageList): EarliestFirstSystemMessageList

  def hasSystemMessages: Boolean
}

/**
 * INTERNAL API
 */
private[akka] trait DefaultSystemMessageQueue { self: Mailbox ⇒

  @tailrec
  final def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit = {
    assert(message.unlinked)
    if (Mailbox.debug) println(receiver + " having enqueued " + message)
    val currentList = systemQueueGet
    if (currentList.head == NoMessage) {
      if (actor ne null) actor.systemImpl.deadLetterMailbox.systemEnqueue(receiver, message)
    } else {
      if (!systemQueuePut(currentList, message :: currentList)) {
        message.unlink()
        systemEnqueue(receiver, message)
      }
    }
  }

  @tailrec
  final def systemDrain(newContents: LatestFirstSystemMessageList): EarliestFirstSystemMessageList = {
    val currentList = systemQueueGet
    if (currentList.head == NoMessage) new EarliestFirstSystemMessageList(null)
    else if (systemQueuePut(currentList, newContents)) currentList.reverse
    else systemDrain(newContents)
  }

  def hasSystemMessages: Boolean = systemQueueGet.head match {
    case null | NoMessage ⇒ false
    case _                ⇒ true
  }

}

/**
 * A QueueBasedMessageQueue is a MessageQueue backed by a java.util.Queue.
 */
trait QueueBasedMessageQueue extends MessageQueue {
  def queue: Queue[Envelope]
  def numberOfMessages = queue.size
  def hasMessages = !queue.isEmpty
  def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    if (hasMessages) {
      var envelope = dequeue
      while (envelope ne null) {
        deadLetters.enqueue(owner, envelope)
        envelope = dequeue
      }
    }
  }
}

/**
 * UnboundedMessageQueueSemantics adds unbounded semantics to a QueueBasedMessageQueue,
 * i.e. a non-blocking enqueue and dequeue.
 */
trait UnboundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def enqueue(receiver: ActorRef, handle: Envelope): Unit = queue add handle
  def dequeue(): Envelope = queue.poll()
}

/**
 * BoundedMessageQueueSemantics adds bounded semantics to a QueueBasedMessageQueue,
 * i.e. blocking enqueue with timeout.
 */
trait BoundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def pushTimeOut: Duration
  override def queue: BlockingQueue[Envelope]

  def enqueue(receiver: ActorRef, handle: Envelope): Unit =
    if (pushTimeOut.length > 0) {
      if (!queue.offer(handle, pushTimeOut.length, pushTimeOut.unit))
        receiver.asInstanceOf[InternalActorRef].provider.deadLetters.tell(
          DeadLetter(handle.message, handle.sender, receiver), handle.sender)
    } else queue put handle

  def dequeue(): Envelope = queue.poll()
}

/**
 * DequeBasedMessageQueue refines QueueBasedMessageQueue to be backed by a java.util.Deque.
 */
trait DequeBasedMessageQueue extends QueueBasedMessageQueue {
  def queue: Deque[Envelope]
  def enqueueFirst(receiver: ActorRef, handle: Envelope): Unit
}

/**
 * UnboundedDequeBasedMessageQueueSemantics adds unbounded semantics to a DequeBasedMessageQueue,
 * i.e. a non-blocking enqueue and dequeue.
 */
trait UnboundedDequeBasedMessageQueueSemantics extends DequeBasedMessageQueue {
  def enqueue(receiver: ActorRef, handle: Envelope): Unit = queue add handle
  def enqueueFirst(receiver: ActorRef, handle: Envelope): Unit = queue addFirst handle
  def dequeue(): Envelope = queue.poll()
}

/**
 * BoundedMessageQueueSemantics adds bounded semantics to a DequeBasedMessageQueue,
 * i.e. blocking enqueue with timeout.
 */
trait BoundedDequeBasedMessageQueueSemantics extends DequeBasedMessageQueue {
  def pushTimeOut: Duration
  override def queue: BlockingDeque[Envelope]

  def enqueue(receiver: ActorRef, handle: Envelope): Unit =
    if (pushTimeOut.length > 0) {
      if (!queue.offer(handle, pushTimeOut.length, pushTimeOut.unit))
        receiver.asInstanceOf[InternalActorRef].provider.deadLetters.tell(
          DeadLetter(handle.message, handle.sender, receiver), handle.sender)
    } else queue put handle

  def enqueueFirst(receiver: ActorRef, handle: Envelope): Unit =
    if (pushTimeOut.length > 0) {
      if (!queue.offerFirst(handle, pushTimeOut.length, pushTimeOut.unit))
        receiver.asInstanceOf[InternalActorRef].provider.deadLetters.tell(
          DeadLetter(handle.message, handle.sender, receiver), handle.sender)
    } else queue putFirst handle

  def dequeue(): Envelope = queue.poll()
}

/**
 * MailboxType is a factory to create MessageQueues for an optionally
 * provided ActorContext.
 *
 * <b>Possibly Important Notice</b>
 *
 * When implementing a custom mailbox type, be aware that there is special
 * semantics attached to `system.actorOf()` in that sending to the returned
 * ActorRef may—for a short period of time—enqueue the messages first in a
 * dummy queue. Top-level actors are created in two steps, and only after the
 * guardian actor has performed that second step will all previously sent
 * messages be transferred from the dummy queue into the real mailbox.
 */
trait MailboxType {
  def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue
}

/**
 * UnboundedMailbox is the default unbounded MailboxType used by Akka Actors.
 */
case class UnboundedMailbox() extends MailboxType {

  def this(settings: ActorSystem.Settings, config: Config) = this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new ConcurrentLinkedQueue[Envelope]() with QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
      final def queue: Queue[Envelope] = this
    }
}

/**
 * SingleConsumerOnlyUnboundedMailbox is a high-performance, multiple producer—single consumer, unbounded MailboxType,
 * the only drawback is that you can't have multiple consumers,
 * which rules out using it with BalancingDispatcher for instance.
 */
case class SingleConsumerOnlyUnboundedMailbox() extends MailboxType {

  def this(settings: ActorSystem.Settings, config: Config) = this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = new NodeMessageQueue()
}

/**
 * BoundedMailbox is the default bounded MailboxType used by Akka Actors.
 */
case class BoundedMailbox( final val capacity: Int, final val pushTimeOut: FiniteDuration) extends MailboxType {

  def this(settings: ActorSystem.Settings, config: Config) = this(config.getInt("mailbox-capacity"),
    Duration(config.getNanoseconds("mailbox-push-timeout-time"), TimeUnit.NANOSECONDS))

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new LinkedBlockingQueue[Envelope](capacity) with QueueBasedMessageQueue with BoundedMessageQueueSemantics {
      final def queue: BlockingQueue[Envelope] = this
      final val pushTimeOut = BoundedMailbox.this.pushTimeOut
    }
}

/**
 * UnboundedPriorityMailbox is an unbounded mailbox that allows for prioritization of its contents.
 * Extend this class and provide the Comparator in the constructor.
 */
class UnboundedPriorityMailbox( final val cmp: Comparator[Envelope], final val initialCapacity: Int) extends MailboxType {
  def this(cmp: Comparator[Envelope]) = this(cmp, 11)
  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new PriorityBlockingQueue[Envelope](initialCapacity, cmp) with QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
      final def queue: Queue[Envelope] = this
    }
}

/**
 * BoundedPriorityMailbox is a bounded mailbox that allows for prioritization of its contents.
 * Extend this class and provide the Comparator in the constructor.
 */
class BoundedPriorityMailbox( final val cmp: Comparator[Envelope], final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new BoundedBlockingQueue[Envelope](capacity, new PriorityQueue[Envelope](11, cmp)) with QueueBasedMessageQueue with BoundedMessageQueueSemantics {
      final def queue: BlockingQueue[Envelope] = this
      final val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
    }
}

/**
 * UnboundedDequeBasedMailbox is an unbounded MailboxType, backed by a Deque.
 */
case class UnboundedDequeBasedMailbox() extends MailboxType {

  def this(settings: ActorSystem.Settings, config: Config) = this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new LinkedBlockingDeque[Envelope]() with DequeBasedMessageQueue with UnboundedDequeBasedMessageQueueSemantics {
      final val queue = this
    }
}

/**
 * BoundedDequeBasedMailbox is an bounded MailboxType, backed by a Deque.
 */
case class BoundedDequeBasedMailbox( final val capacity: Int, final val pushTimeOut: FiniteDuration) extends MailboxType {

  def this(settings: ActorSystem.Settings, config: Config) = this(config.getInt("mailbox-capacity"),
    Duration(config.getNanoseconds("mailbox-push-timeout-time"), TimeUnit.NANOSECONDS))

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedDequeBasedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedDequeBasedMailbox can not be null")

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new LinkedBlockingDeque[Envelope](capacity) with DequeBasedMessageQueue with BoundedDequeBasedMessageQueueSemantics {
      final val queue = this
      final val pushTimeOut = BoundedDequeBasedMailbox.this.pushTimeOut
    }
}

/**
 * Trait to signal that an Actor requires a certain type of message queue semantics.
 *
 * The mailbox type will be looked up by mapping the type T via akka.actor.mailbox.requirements in the config,
 * to a mailbox configuration. If no mailbox is assigned on Props or in deployment config then this one will be used.
 *
 * The queue type of the created mailbox will be checked against the type T and actor creation will fail if it doesn't
 * fulfill the requirements.
 */
trait RequiresMessageQueue[T]
