package eu.inn.servicebus.transport

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSingletonManager
import akka.util.Timeout
import com.typesafe.config.Config
import eu.inn.servicebus.serialization._
import eu.inn.servicebus.transport.distributedakka.{AutoDownControlActor, OnServerActor, Start, SubscribeServerActor}
import eu.inn.servicebus.util.ConfigUtils._
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.concurrent.duration.{FiniteDuration, Duration}
import akka.pattern.gracefulStop

class DistributedAkkaServerTransport(val actorSystem: ActorSystem,
                                     implicit val executionContext: ExecutionContext = ExecutionContext.global)
  extends ServerTransport {

  def this(config: Config) = this(ActorSystemRegistry.getOrCreate(config.getString("actor-system", "eu-inn")),
    scala.concurrent.ExecutionContext.global)

  val subscriptions = new TrieMap[String, ActorRef]
  protected val idCounter = new AtomicLong(0)
  protected val log = LoggerFactory.getLogger(this.getClass)

  if (Cluster(actorSystem).getSelfRoles.contains("auto-down-controller")) {
    actorSystem.actorOf(ClusterSingletonManager.props(
      Props(classOf[AutoDownControlActor]),
      "control-auto-down-singleton",
      PoisonPill,
      Some("auto-down-controller"))
    )
  }

  override def on[OUT, IN](topic: Topic,
                           inputDecoder: Decoder[IN],
                           partitionArgsExtractor: PartitionArgsExtractor[IN],
                           exceptionEncoder: Encoder[Throwable])
                          (handler: (IN) ⇒ SubscriptionHandlerResult[OUT]): String = {

    val id = idCounter.incrementAndGet().toHexString
    val actor = actorSystem.actorOf(Props[OnServerActor[OUT,IN]], "eu-inn-distr-on-server" + id)
    subscriptions.put(id, actor)
    actor ! Start(id, distributedakka.Subscription[OUT, IN](topic, None, inputDecoder, partitionArgsExtractor, exceptionEncoder, handler))
    id
  }

  override def subscribe[IN](topic: Topic,
                             groupName: String,
                             inputDecoder: Decoder[IN],
                             partitionArgsExtractor: PartitionArgsExtractor[IN])
                            (handler: (IN) ⇒ SubscriptionHandlerResult[Unit]): String = {
    val id = idCounter.incrementAndGet().toHexString
    val actor = actorSystem.actorOf(Props[SubscribeServerActor[IN]], "eu-inn-distr-subscribe-server" + id)
    subscriptions.put(id, actor)
    actor ! Start(id, distributedakka.Subscription[Unit, IN](topic, Some(groupName), inputDecoder, partitionArgsExtractor, null, handler))
    id
  }

  override def off(subscriptionId: String): Unit = {
    subscriptions.get(subscriptionId).foreach{ s⇒
      actorSystem.stop(s)
      subscriptions.remove(subscriptionId)
    }
  }

  def shutdown(duration: FiniteDuration): Future[Boolean] = {
    val actorStopFutures = subscriptions.map(s ⇒
      gracefulStop(s._2, duration) recover {
        case t: Throwable ⇒
          log.error("Shutting down ditributed akka", t)
          false
      }
    )
    val promise = Promise[Boolean]()
    Future.sequence(actorStopFutures) map { list ⇒
      val result = list.forall(_ == true)
      if (!actorSystem.isTerminated) {
        actorSystem.registerOnTermination(promise.success(result))
        actorSystem.shutdown()
      }
      else {
        promise.success(result)
      }
      subscriptions.clear()
    }
    promise.future
  }
}

