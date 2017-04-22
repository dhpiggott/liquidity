package com.dhpcs.liquidity.server

import java.security.KeyStore
import java.security.cert.{CertificateException, X509Certificate}
import javax.net.ssl._

import akka.NotUsed
import akka.actor.{ActorPath, ActorRef, ActorSystem, Deploy, PoisonPill}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.pattern.{ask, gracefulStop}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer, TLSClientAuth}
import akka.util.Timeout
import com.dhpcs.liquidity.actor.protocol.{ActiveClientSummary, ActiveZoneSummary}
import com.dhpcs.liquidity.model.{AccountId, PublicKey, Zone, ZoneId}
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor.{ActiveClientsSummary, GetActiveClientsSummary}
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor.{
  ActiveZonesSummary,
  GetActiveZonesSummary,
  GetZoneCount,
  ZoneCount
}
import com.dhpcs.liquidity.server.actor._
import com.typesafe.config.{Config, ConfigFactory}
import okio.ByteString
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object LiquidityServer {

  final val EnabledCipherSuites = Seq(
    // Recommended by https://typesafehub.github.io/ssl-config/CipherSuites.html#id4
    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"
  )

  final val EnabledProtocols = Seq(
    "TLSv1.2",
    "TLSv1.1",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLSv1"
  )

  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore.p12"

  private final val ZoneHostRole    = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole   = "analytics"

  def main(args: Array[String]): Unit = {
    val config               = ConfigFactory.load
    implicit val system      = ActorSystem("liquidity")
    implicit val mat         = ActorMaterializer()
    val readJournal          = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val ec          = ExecutionContext.global
    val futureAnalyticsStore = readJournal.session.underlying().flatMap(CassandraAnalyticsStore(config)(_, ec))
    val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
      Console.err.println("Exiting due to stream failure")
      t.printStackTrace(Console.err)
      sys.exit(1)
    }

    val zoneValidatorShardRegion =
      if (Cluster(system).selfRoles.contains(ZoneHostRole))
        ClusterSharding(system).start(
          typeName = ZoneValidatorActor.ShardTypeName,
          entityProps = ZoneValidatorActor.props,
          settings = ClusterShardingSettings(system).withRole(ZoneHostRole),
          extractEntityId = ZoneValidatorActor.extractEntityId,
          extractShardId = ZoneValidatorActor.extractShardId
        )
      else
        ClusterSharding(system).startProxy(
          typeName = ZoneValidatorActor.ShardTypeName,
          role = Some(ZoneHostRole),
          extractEntityId = ZoneValidatorActor.extractEntityId,
          extractShardId = ZoneValidatorActor.extractShardId
        )

    if (Cluster(system).selfRoles.contains(AnalyticsRole)) {
      val zoneAnalyticsShardRegion = ClusterSharding(system).start(
        typeName = ZoneAnalyticsActor.ShardTypeName,
        entityProps = ZoneAnalyticsActor.props(readJournal, futureAnalyticsStore, streamFailureHandler),
        settings = ClusterShardingSettings(system).withRole(AnalyticsRole),
        extractEntityId = ZoneAnalyticsActor.extractEntityId,
        extractShardId = ZoneAnalyticsActor.extractShardId
      )
      system.actorOf(
        ClusterSingletonManager.props(
          singletonProps = ZoneAnalyticsStarterActor.props(readJournal, zoneAnalyticsShardRegion, streamFailureHandler),
          terminationMessage = PoisonPill,
          settings =
            ClusterSingletonManagerSettings(system).withSingletonName("zone-analytics-starter").withRole(AnalyticsRole)
        ),
        name = "zone-analytics-starter-singleton"
      )
    }

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(
      getClass.getClassLoader.getResourceAsStream(KeyStoreFilename),
      Array.emptyCharArray
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    val server = new LiquidityServer(
      config,
      readJournal,
      futureAnalyticsStore,
      zoneValidatorShardRegion,
      keyManagerFactory.getKeyManagers
    )
    sys.addShutdownHook {
      Await.result(server.shutdown(), Duration.Inf)
      Await.result(system.terminate(), Duration.Inf)
    }
  }
}

class LiquidityServer(config: Config,
                      readJournal: ReadJournal with CurrentPersistenceIdsQuery,
                      futureAnalyticsStore: Future[CassandraAnalyticsStore],
                      zoneValidatorShardRegion: ActorRef,
                      keyManagers: Array[KeyManager])(implicit system: ActorSystem, mat: Materializer)
    extends HttpController {

  import system.dispatcher

  private[this] val clientsMonitorActor = system.actorOf(
    ClientsMonitorActor.props.withDeploy(Deploy.local),
    "clients-monitor"
  )
  private[this] val zonesMonitorActor = system.actorOf(
    ZonesMonitorActor.props(ZonesMonitorActor.zoneCount(readJournal)).withDeploy(Deploy.local),
    "zones-monitor"
  )

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
          throw new CertificateException

        override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(EnabledCipherSuites),
      enabledProtocols = Some(EnabledProtocols),
      clientAuth = Some(TLSClientAuth.Want)
    )
  }

  private[this] val binding = Http().bindAndHandle(
    route(enableClientRelay = Cluster(system).selfRoles.contains(ClientRelayRole)),
    config.getString("liquidity.server.http.interface"),
    config.getInt("liquidity.server.http.port"),
    httpsConnectionContext
  )

  private[this] val keepAliveInterval = FiniteDuration(
    config.getDuration("liquidity.server.http.keep-alive-interval", SECONDS),
    SECONDS
  )

  def shutdown(): Future[Unit] = {
    def stop(target: ActorRef): Future[Unit] =
      gracefulStop(target, 5.seconds).flatMap {
        case true  => Future.successful(())
        case false => stop(target)
      }
    for {
      binding <- binding
      _       <- binding.unbind()
      _       <- stop(clientsMonitorActor)
      _       <- stop(zonesMonitorActor)
    } yield ()
  }

  override protected[this] def getStatus: Future[JsValue] = {
    def fingerprint(id: String): JsValueWrapper = ByteString.encodeUtf8(id).sha256.hex
    def clientsStatus(activeClientsSummary: ActiveClientsSummary): JsObject =
      Json.obj(
        "count" -> activeClientsSummary.activeClientSummaries.size,
        "publicKeyFingerprints" -> activeClientsSummary.activeClientSummaries.map {
          case ActiveClientSummary(publicKey) => publicKey.fingerprint
        }.sorted
      )
    def activeZonesStatus(activeZonesSummary: ActiveZonesSummary): JsObject =
      Json.obj(
        "count" -> activeZonesSummary.activeZoneSummaries.size,
        "zones" -> activeZonesSummary.activeZoneSummaries.toSeq.sortBy(_.zoneId.id).map {
          case ActiveZoneSummary(
              zoneId,
              metadata,
              members,
              accounts,
              transactions,
              clientConnections
              ) =>
            Json.obj(
              "zoneIdFingerprint" -> fingerprint(zoneId.id.toString),
              "metadata"          -> metadata,
              "members"           -> Json.obj("count" -> members.size),
              "accounts"          -> Json.obj("count" -> accounts.size),
              "transactions"      -> Json.obj("count" -> transactions.size),
              "clientConnections" -> Json.obj(
                "count"                 -> clientConnections.size,
                "publicKeyFingerprints" -> clientConnections.map(_.fingerprint).toSeq.sorted
              )
            )
        }
      )
    def shardRegionStatus(shardRegionState: ShardRegion.CurrentShardRegionState): JsObject =
      Json.obj(
        "count" -> shardRegionState.shards.size,
        "shards" -> Json.obj(shardRegionState.shards.toSeq.sortBy(_.shardId).map {
          case ShardRegion.ShardState(shardId, entityIds) =>
            shardId ->
              (Json.arr(entityIds.toSeq.sorted.map(fingerprint): _*): JsValueWrapper)
        }: _*)
      )
    def clusterShardingStatus(clusterShardingStats: ShardRegion.ClusterShardingStats): JsObject =
      Json.obj(
        "count" -> clusterShardingStats.regions.size,
        "regions" -> Json.obj(clusterShardingStats.regions.toSeq.sortBy { case (address, _) => address }.map {
          case (address, shardRegionStats) =>
            address.toString ->
              (Json.obj(shardRegionStats.stats.toSeq
                .sortBy { case (shardId, _) => shardId }
                .map {
                  case (shardId, entityCount) =>
                    shardId -> (entityCount: JsValueWrapper)
                }: _*): JsValueWrapper)
        }: _*)
      )
    implicit val askTimeout = Timeout(5.seconds)
    for {
      activeClientsSummary <- (clientsMonitorActor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]
      activeZonesSummary   <- (zonesMonitorActor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]
      totalZonesCount      <- (zonesMonitorActor ? GetZoneCount).mapTo[ZoneCount]
      shardRegionState <- (zoneValidatorShardRegion ? ShardRegion.GetShardRegionState)
        .mapTo[ShardRegion.CurrentShardRegionState]
      clusterShardingStats <- (zoneValidatorShardRegion ? ShardRegion.GetClusterShardingStats(askTimeout.duration))
        .mapTo[ShardRegion.ClusterShardingStats]
    } yield
      Json.obj(
        "clients"         -> clientsStatus(activeClientsSummary),
        "totalZonesCount" -> totalZonesCount.count,
        "activeZones"     -> activeZonesStatus(activeZonesSummary),
        "shardRegions"    -> shardRegionStatus(shardRegionState),
        "clusterSharding" -> clusterShardingStatus(clusterShardingStats)
      )
  }

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      props = ClientConnectionActor.props(ip, publicKey, zoneValidatorShardRegion, keepAliveInterval),
      name = publicKey.fingerprint
    )

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    futureAnalyticsStore.flatMap(_.zoneStore.retrieveOpt(zoneId))

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    futureAnalyticsStore.flatMap(_.balanceStore.retrieve(zoneId))

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    futureAnalyticsStore.flatMap(_.clientStore.retrieve(zoneId))

}
