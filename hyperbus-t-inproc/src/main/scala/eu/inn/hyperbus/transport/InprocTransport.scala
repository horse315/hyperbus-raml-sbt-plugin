package eu.inn.hyperbus.transport

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import com.typesafe.config.Config
import eu.inn.hyperbus.transport.api._
import eu.inn.hyperbus.transport.inproc.{SubKey, Subscription}
import eu.inn.hyperbus.util.ConfigUtils._
import eu.inn.hyperbus.util.Subscriptions
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class InprocTransport(serialize: Boolean = false)
                     (implicit val executionContext: ExecutionContext) extends ClientTransport with ServerTransport {

  def this(config: Config) = this(config.getOptionBoolean("serialize").getOrElse(false))(
    scala.concurrent.ExecutionContext.global // todo: configurable ExecutionContext like in akka?
  )

  protected val subscriptions = new Subscriptions[SubKey, Subscription]
  protected val log = LoggerFactory.getLogger(this.getClass)

  protected def reserializeMessage[OUT <: TransportMessage](message: TransportMessage, deserializer: Deserializer[OUT]): OUT = {
    if (serialize) {
      val ba = new ByteArrayOutputStream()
      message.serialize(ba)
      val bi = new ByteArrayInputStream(ba.toByteArray)
      deserializer(bi)
    }
    else {
      message.asInstanceOf[OUT]
    }
  }

  protected def reserializeException[IN <: TransportRequest, OUT <: TransportResponse](e: Throwable,
                                                                                       exceptionSerializer: Serializer[Throwable],
                                                                                       deserializer: Deserializer[OUT]): OUT = {
    val ba = new ByteArrayOutputStream()
    exceptionSerializer(e, ba)
    val bi = new ByteArrayInputStream(ba.toByteArray)
    deserializer(bi)
  }

  // todo: refactor this method, it's awful
  protected def _ask[OUT <: TransportResponse](message: TransportRequest, outputDeserializer: Deserializer[OUT], isPublish: Boolean): Future[OUT] = {
    var result: Future[OUT] = null

    def tryX[T](failMsg: String, exceptionSerializer: Serializer[Throwable], code: ⇒ T): Option[T] = {
      try {
        Some(code)
      }
      catch {
        case NonFatal(e) ⇒
          result =
            if (serialize)
              Future.successful {
                reserializeException(e, exceptionSerializer, outputDeserializer)
              }
            else
              Future.failed {
                e
              }
          log.error(failMsg, e)
          None
      }
    }

    // todo: filter is redundant for inproc?
    subscriptions.get(message.topic.url.specific).subRoutes filter (_._1.filters.matchFilters(message.topic.extra)) foreach {
      case (subKey, subscriptionList) =>

        if (subKey.groupName.isEmpty) {
          // default subscription (groupName="") returns reply
          val subscriber = subscriptionList.getRandomSubscription

          tryX("Decode failed", subscriber.exceptionSerializer,
            reserializeMessage(message, subscriber.inputDeserializer)
          ) foreach { messageForSubscriber ⇒

            tryX("Decode failed", subscriber.exceptionSerializer,
              messageForSubscriber.topic.extra
              //subscriber. partitionArgsExtractor(messageForSubscriber)
            ) foreach { args ⇒

              if (subKey.filters.matchFilters(args)) {
                // todo: log if not matched?
                val handlerResult = subscriber.handler(messageForSubscriber)
                result = if (serialize) {
                  handlerResult map { out ⇒
                    reserializeMessage(out, outputDeserializer)
                  } recoverWith {
                    case NonFatal(e) ⇒
                      log.error("`process` handler failed with", e)
                      Future.successful {
                        reserializeException(e, subscriber.exceptionSerializer, outputDeserializer)
                      }
                  }
                }
                else {
                  handlerResult.asInstanceOf[Future[OUT]]
                }
                if (isPublish) {
                  // convert to Future[Unit]
                  result = result.map { _ ⇒
                    new PublishResult {
                      def sent = Some(true)

                      def offset = None
                    }
                  }.asInstanceOf[Future[OUT]]
                }
                if (log.isTraceEnabled) {
                  log.trace(s"Message ($messageForSubscriber) is delivered to `process` @$subKey}")
                }
              }
            }
          }
        } else {
          val subscriber = subscriptionList.getRandomSubscription

          val ma: Option[TransportRequest] =
            try {
              Some(reserializeMessage(message, subscriber.inputDeserializer))
            }
            catch {
              case NonFatal(e) ⇒
                log.error("`subscription` deserializer failed with", e)
                None
            }

          ma.foreach { messageForSubscriber ⇒
            if (subKey.filters.matchFilters(messageForSubscriber.topic.extra)) {
              // todo: log if not matched?
              subscriber.handler(messageForSubscriber).onFailure {
                case NonFatal(e) ⇒
                  log.error("`subscription` handler failed with", e)
              }

              if (result == null) {
                result = Future.successful(
                  new PublishResult {
                    def sent = Some(true)

                    def offset = None
                  }
                ).asInstanceOf[Future[OUT]]
              }
              if (log.isTraceEnabled) {
                log.trace(s"Message ($messageForSubscriber) is delivered to `subscriber` @$subKey}")
              }
            }
          }
        }
    }

    if (result == null) {
      Future.failed[OUT](new NoTransportRouteException(s"Subscription on '${message.topic}' isn't found"))
    }
    else {
      result
    }
  }

  override def ask[OUT <: TransportResponse](message: TransportRequest, outputDeserializer: Deserializer[OUT]): Future[OUT] = {
    _ask(message, outputDeserializer, isPublish = false)
  }

  override def publish(message: TransportRequest): Future[PublishResult] = {
    _ask[TransportResponse](message, null, isPublish = true).asInstanceOf[Future[PublishResult]]
  }

  override def process[IN <: TransportRequest](topicFilter: Topic, inputDeserializer: Deserializer[IN], exceptionSerializer: Serializer[Throwable])
                                              (handler: (IN) => Future[TransportResponse]): String = {

    subscriptions.add(
      topicFilter.url.specific, // currently only Specific url's are supported, todo: add Regex, Any, etc...
      SubKey(None, topicFilter.extra),
      Subscription(inputDeserializer, exceptionSerializer, handler.asInstanceOf[(TransportRequest) => Future[TransportResponse]])
    )
  }

  override def subscribe[IN <: TransportRequest](topicFilter: Topic, groupName: String, inputDeserializer: Deserializer[IN])
                                                (handler: (IN) => Future[Unit]): String = {
    subscriptions.add(
      topicFilter.url.specific, // currently only Specific url's are supported, todo: add Regex, Any, etc...
      SubKey(Some(groupName), topicFilter.extra),
      Subscription(inputDeserializer, null, handler.asInstanceOf[(TransportRequest) => Future[TransportResponse]])
    )
  }

  override def off(subscriptionId: String) = {
    subscriptions.remove(subscriptionId)
  }

  override def shutdown(duration: FiniteDuration): Future[Boolean] = {
    subscriptions.clear()
    Future.successful(true)
  }
}