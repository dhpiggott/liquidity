package com.dhpcs.liquidity.server.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.server.actors.ClientsMonitorActor.{
  ActiveClientsSummary,
  GetActiveClientsSummary,
  PublishStatus
}

import scala.concurrent.duration._

object ClientsMonitorActor {

  def props: Props = Props(new ClientsMonitorActor)

  case class ActiveClientsSummary(activeClientSummaries: Seq[ClientConnectionActor.ActiveClientSummary])

  case object GetActiveClientsSummary

  private case object PublishStatus

}

class ClientsMonitorActor extends Actor with ActorLogging {

  import context.dispatcher

  private[this] val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(ClientConnectionActor.Topic, self)

  private[this] val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private[this] var activeClientSummaries = Map.empty[ActorRef, ClientConnectionActor.ActiveClientSummary]

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    case PublishStatus =>
      log.info(s"${activeClientSummaries.size} clients are active")
    case activeClientSummary: ClientConnectionActor.ActiveClientSummary =>
      if (!activeClientSummaries.contains(sender())) {
        context.watch(sender())
      }
      activeClientSummaries = activeClientSummaries + (sender() -> activeClientSummary)
    case GetActiveClientsSummary =>
      sender() ! ActiveClientsSummary(activeClientSummaries.values.toSeq)
    case Terminated(clientConnection) =>
      activeClientSummaries = activeClientSummaries - clientConnection
  }
}
