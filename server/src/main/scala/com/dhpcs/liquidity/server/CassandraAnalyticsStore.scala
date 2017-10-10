package com.dhpcs.liquidity.server

import java.nio.ByteBuffer
import java.time.Instant
import java.util.Date

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.typed.ActorRef
import akka.typed.scaladsl.adapter._
import com.datastax.driver.core.{PreparedStatement, ResultSet, Row, Session}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.CassandraAnalyticsStore.ZoneStore._
import com.dhpcs.liquidity.server.CassandraAnalyticsStore._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.trueaccord.scalapb.json.JsonFormat
import okio.ByteString

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

object CassandraAnalyticsStore {

  def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[CassandraAnalyticsStore] = {
    for {
      _                          <- execute(s"""
                                               |CREATE KEYSPACE IF NOT EXISTS $keyspace
                                               |  WITH replication = {'class': 'SimpleStrategy' , 'replication_factor': '1'};
      """.stripMargin)
      journalSequenceNumberStore <- JournalSequenceNumberStore(keyspace)
      zoneStore                  <- ZoneStore(keyspace)
      balanceStore               <- BalanceStore(keyspace)
      clientStore                <- ClientStore(keyspace)
    } yield new CassandraAnalyticsStore(journalSequenceNumberStore, zoneStore, balanceStore, clientStore)
  }

  object JournalSequenceNumberStore {
    def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[JournalSequenceNumberStore] =
      for {
        _ <- execute(s"""
                        |CREATE TABLE IF NOT EXISTS $keyspace.journal_sequence_numbers_by_zone (
                        |  id text,
                        |  journal_sequence_number bigint,
                        |  PRIMARY KEY (id)
                        |);
      """.stripMargin)
      } yield new JournalSequenceNumberStore(keyspace)
  }

  class JournalSequenceNumberStore private (keyspace: String)(implicit session: Session) {

    private[this] val retrieveStatement = prepareStatement(s"""
                       |SELECT journal_sequence_number
                       |  FROM $keyspace.journal_sequence_numbers_by_zone
                       |  WHERE id = ?
        """.stripMargin)

    def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Long] =
      for (resultSet <- retrieveStatement.execute(zoneId.id))
        yield
          resultSet.one match {
            case null => 0L
            case row  => row.getLong("journal_sequence_number")
          }

    private[this] val updateStatement = prepareStatement(s"""
                       |UPDATE $keyspace.journal_sequence_numbers_by_zone
                       |  SET journal_sequence_number = ?
                       |  WHERE id = ?
        """.stripMargin)

    def update(zoneId: ZoneId, sequenceNumber: Long)(implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- updateStatement.execute(
             sequenceNumber: java.lang.Long,
             zoneId.id
           )) yield ()

  }

  object ZoneStore {

    object MemberStore {
      def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[MemberStore] =
        for {
          _ <- execute(s"""
                          |CREATE TABLE IF NOT EXISTS $keyspace.member_updates_by_id (
                          |  zone_id text,
                          |  id text,
                          |  updated timestamp,
                          |  owner_fingerprint set<text>,
                          |  created timestamp,
                          |  name text,
                          |  metadata text,
                          |  hidden boolean,
                          |  PRIMARY KEY ((zone_id), id, updated)
                          |);
      """.stripMargin)
          _ <- execute(s"""
                          |CREATE TABLE IF NOT EXISTS $keyspace.members_by_zone (
                          |  zone_id text,
                          |  id text,
                          |  owner_public_keys set<blob>,
                          |  owner_fingerprint set<text>,
                          |  created timestamp,
                          |  modified timestamp,
                          |  name text,
                          |  metadata text,
                          |  hidden boolean,
                          |  PRIMARY KEY ((zone_id), id)
                          |);
      """.stripMargin)
        } yield new MemberStore(keyspace)
    }

    class MemberStore private (keyspace: String)(implicit session: Session) {

      private[this] val retrieveStatement = prepareStatement(s"""
                         |SELECT id, owner_public_keys, name, metadata
                         |  FROM $keyspace.members_by_zone
                         |  WHERE zone_id = ?
        """.stripMargin)

      def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[MemberId, Member]] =
        for (resultSet <- retrieveStatement.execute(zoneId.id))
          yield
            (for {
              row <- resultSet.iterator.asScala
              memberId = MemberId(row.getString("id"))
              ownerPublicKeys = row
                .getSet("owner_public_keys", classOf[ByteBuffer])
                .asScala
                .map(ownerPublicKey => PublicKey(ByteString.of(ownerPublicKey)))
                .toSet
              name = Option(row.getString("name"))
              metadata = Option(row.getString("metadata"))
                .map(JsonFormat.fromJsonString[com.google.protobuf.struct.Struct])
            } yield memberId -> Member(memberId, ownerPublicKeys, name, metadata)).toMap

      private[this] val createStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.members_by_zone (zone_id, id, owner_public_keys, owner_fingerprint, created, name, metadata, hidden)
                         |  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """.stripMargin)

      def create(zoneId: ZoneId, created: Instant)(member: Member)(implicit ec: ExecutionContext): Future[Unit] =
        for {
          _ <- addUpdate(zoneId, updated = created, member)
          _ <- createStatement.execute(
            zoneId.id,
            member.id.id,
            member.ownerPublicKeys.map(_.value.asByteBuffer).asJava,
            member.ownerPublicKeys.map(_.fingerprint).asJava,
            Date.from(created),
            member.name.orNull,
            member.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
            member.metadata
              .flatMap(_.fields.get("hidden").flatMap(_.kind.boolValue).map(hidden => hidden: java.lang.Boolean))
              .orNull
          )
        } yield ()

      private[this] val updateStatement = prepareStatement(s"""
                         |UPDATE $keyspace.members_by_zone
                         |  SET owner_public_keys = ?, owner_fingerprint = ?, modified = ?, name = ?, metadata = ?, hidden = ?
                         |  WHERE zone_id = ? AND id = ?
          """.stripMargin)

      def update(zoneId: ZoneId, modified: Instant, member: Member)(implicit ec: ExecutionContext): Future[Unit] =
        for {
          _ <- addUpdate(zoneId, updated = modified, member)
          _ <- updateStatement.execute(
            member.ownerPublicKeys.map(_.value.asByteBuffer).asJava,
            member.ownerPublicKeys.map(_.fingerprint).asJava,
            Date.from(modified),
            member.name.orNull,
            member.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
            member.metadata
              .flatMap(_.fields.get("hidden").flatMap(_.kind.boolValue).map(hidden => hidden: java.lang.Boolean))
              .orNull,
            zoneId.id,
            member.id.id
          )
        } yield ()

      private[this] val addUpdateStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.member_updates_by_id (zone_id, id, updated, owner_fingerprint, name, metadata, hidden)
                         |  VALUES (?, ?, ?, ?, ?, ?, ?)
        """.stripMargin)

      private[this] def addUpdate(zoneId: ZoneId, updated: Instant, member: Member)(
          implicit ec: ExecutionContext): Future[Unit] =
        for (_ <- addUpdateStatement.execute(
               zoneId.id,
               member.id.id,
               Date.from(updated),
               member.ownerPublicKeys.map(_.fingerprint).asJava,
               member.name.orNull,
               member.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
               member.metadata
                 .flatMap(_.fields.get("hidden").flatMap(_.kind.boolValue).map(hidden => hidden: java.lang.Boolean))
                 .orNull
             )) yield ()

    }

    object AccountStore {
      def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[AccountStore] =
        for {
          _ <- execute(s"""
                          |CREATE TABLE IF NOT EXISTS $keyspace.account_updates_by_id (
                          |  zone_id text,
                          |  id text,
                          |  updated timestamp,
                          |  owner_member_ids set<text>,
                          |  owner_names list<text>,
                          |  created timestamp,
                          |  modified timestamp,
                          |  name text,
                          |  metadata text,
                          |  PRIMARY KEY ((zone_id), id, updated)
                          |);
      """.stripMargin)
          _ <- execute(s"""
                          |CREATE TABLE IF NOT EXISTS $keyspace.accounts_by_zone (
                          |  zone_id text,
                          |  id text,
                          |  owner_member_ids set<text>,
                          |  owner_names list<text>,
                          |  created timestamp,
                          |  modified timestamp,
                          |  name text,
                          |  metadata text,
                          |  PRIMARY KEY ((zone_id), id)
                          |);
      """.stripMargin)
        } yield new AccountStore(keyspace)
    }

    class AccountStore private (keyspace: String)(implicit session: Session) {

      private[this] val retrieveStatement = prepareStatement(s"""
                         |SELECT id, owner_member_ids, name, metadata
                         |  FROM $keyspace.accounts_by_zone
                         |  WHERE zone_id = ?
        """.stripMargin)

      def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[AccountId, Account]] =
        for (resultSet <- retrieveStatement.execute(zoneId.id))
          yield
            (for {
              row <- resultSet.iterator.asScala
              accountId = AccountId(row.getString("id"))
              ownerMemberIds = row
                .getSet("owner_member_ids", classOf[String])
                .asScala
                .map(MemberId)
                .toSet
              name = Option(row.getString("name"))
              metadata = Option(row.getString("metadata"))
                .map(JsonFormat.fromJsonString[com.google.protobuf.struct.Struct])
            } yield accountId -> Account(accountId, ownerMemberIds, name, metadata)).toMap

      private[this] val createStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.accounts_by_zone (zone_id, id, owner_member_ids, owner_names, created, name, metadata)
                         |  VALUES (?, ?, ?, ?, ?, ?, ?)
          """.stripMargin)

      def create(zone: Zone, created: Instant)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
        for {
          _ <- addUpdate(zone, updated = created, account)
          _ <- createStatement.execute(
            zone.id.id,
            account.id.id,
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
            Date.from(created),
            account.name.orNull,
            account.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull
          )
        } yield ()

      private[this] val updatesStatement = prepareStatement(s"""
                         |UPDATE $keyspace.accounts_by_zone
                         |  SET owner_names = ?
                         |  WHERE zone_id = ? AND id = ?
          """.stripMargin)

      def update(zone: Zone, accounts: Iterable[Account])(implicit ec: ExecutionContext): Future[Unit] =
        for (_ <- Future.traverse(accounts)(
               account =>
                 updatesStatement.execute(
                   ownerNames(zone.members, account),
                   zone.id.id,
                   account.id.id
               )
             )) yield ()

      private[this] val updateStatement = prepareStatement(s"""
                         |UPDATE $keyspace.accounts_by_zone
                         |  SET owner_member_ids = ?, owner_names = ?, modified = ?, name = ?, metadata = ?
                         |  WHERE zone_id = ? AND id = ?
          """.stripMargin)

      def update(zone: Zone, modified: Instant, account: Account)(implicit ec: ExecutionContext): Future[Unit] =
        for {
          _ <- addUpdate(zone, updated = modified, account)
          _ <- updateStatement.execute(
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
            Date.from(modified),
            account.name.orNull,
            account.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
            zone.id.id,
            account.id.id
          )
        } yield ()

      private[this] val addUpdateStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.account_updates_by_id (zone_id, id, updated, owner_member_ids, owner_names, name, metadata)
                         |  VALUES (?, ?, ?, ?, ?, ?, ?)
        """.stripMargin)

      private[this] def addUpdate(zone: Zone, updated: Instant, account: Account)(
          implicit ec: ExecutionContext): Future[Unit] =
        for (_ <- addUpdateStatement.execute(
               zone.id.id,
               account.id.id,
               Date.from(updated),
               account.ownerMemberIds.map(_.id).asJava,
               ownerNames(zone.members, account),
               account.name.orNull,
               account.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull
             )) yield ()

    }

    object TransactionStore {
      def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[TransactionStore] =
        for {
          _ <- execute(s"""
                          |CREATE TABLE IF NOT EXISTS $keyspace.transactions_by_zone (
                          |  zone_id text,
                          |  id text,
                          |  "from" text,
                          |  from_owner_names list<text>,
                          |  "to" text,
                          |  to_owner_names list<text>,
                          |  value decimal,
                          |  creator text,
                          |  created timestamp,
                          |  description text,
                          |  metadata text,
                          |  PRIMARY KEY ((zone_id), id)
                          |);
      """.stripMargin)
        } yield new TransactionStore(keyspace)
    }

    class TransactionStore private (keyspace: String)(implicit session: Session) {

      private[this] val retrieveStatement = prepareStatement(s"""
                         |SELECT id, "from", "to", value, creator, created, description, metadata
                         |  FROM $keyspace.transactions_by_zone
                         |  WHERE zone_id = ?
        """.stripMargin)

      def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[TransactionId, Transaction]] =
        for (resultSet <- retrieveStatement.execute(zoneId.id))
          yield
            (for {
              row <- resultSet.iterator.asScala
              transactionId = TransactionId(row.getString("id"))
              from          = AccountId(row.getString("from"))
              to            = AccountId(row.getString("to"))
              value         = BigDecimal(row.getDecimal("value"))
              creator       = MemberId(row.getString("creator"))
              created       = row.getTimestamp("created").getTime
              description   = Option(row.getString("description"))
              metadata = Option(row.getString("metadata"))
                .map(JsonFormat.fromJsonString[com.google.protobuf.struct.Struct])
            } yield
              transactionId -> Transaction(transactionId, from, to, value, creator, created, description, metadata)).toMap

      private[this] val updatesStatement = prepareStatement(s"""
                         |UPDATE $keyspace.transactions_by_zone
                         |  SET "from" = ?, from_owner_names = ?, "to" = ?, to_owner_names = ?, value = ?, creator = ?, created = ?, description = ?, metadata = ?
                         |  WHERE zone_id = ? AND id = ?
          """.stripMargin)

      def update(zone: Zone, transactions: Iterable[Transaction])(implicit ec: ExecutionContext): Future[Unit] =
        for (_ <- Future.traverse(transactions)(transaction =>
               updatesStatement.execute(
                 transaction.from.id,
                 ownerNames(zone.members, zone.accounts(transaction.from)),
                 transaction.to.id,
                 ownerNames(zone.members, zone.accounts(transaction.to)),
                 transaction.value.underlying,
                 transaction.creator.id,
                 new Date(transaction.created),
                 transaction.description.orNull,
                 transaction.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
                 zone.id.id,
                 transaction.id.id
             ))) yield ()

      private[this] val addStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.transactions_by_zone (zone_id, id, "from", from_owner_names, "to", to_owner_names, value, creator, created, description, metadata)
                         |  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """.stripMargin)

      def add(zone: Zone)(transaction: Transaction)(implicit ec: ExecutionContext): Future[Unit] =
        for (_ <- addStatement.execute(
               zone.id.id,
               transaction.id.id,
               transaction.from.id,
               ownerNames(zone.members, zone.accounts(transaction.from)),
               transaction.to.id,
               ownerNames(zone.members, zone.accounts(transaction.to)),
               transaction.value.underlying,
               transaction.creator.id,
               new Date(transaction.created),
               transaction.description.orNull,
               transaction.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull
             )) yield ()

    }

    def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[ZoneStore] =
      for {
        _                 <- execute(s"""
                                       |CREATE TABLE IF NOT EXISTS $keyspace.zone_name_changes_by_id (
                                       |  id text,
                                       |  changed timestamp,
                                       |  name text,
                                       |  PRIMARY KEY ((id), changed)
                                       |);
      """.stripMargin)
        _                 <- execute(s"""
                                       |CREATE TABLE IF NOT EXISTS $keyspace.zones_by_id(
                                       |  id text,
                                       |  bucket bigint,
                                       |  equity_account_id text,
                                       |  created timestamp,
                                       |  modified timestamp,
                                       |  expires timestamp,
                                       |  name text,
                                       |  metadata text,
                                       |  currency text,
                                       |  PRIMARY KEY ((id), bucket)
                                       |);
      """.stripMargin)
        _                 <- execute(s"""
                                       |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.zones_by_modified AS
                                       |  SELECT * FROM $keyspace.zones_by_id
                                       |  WHERE bucket IS NOT NULL AND modified IS NOT NULL
                                       |  PRIMARY KEY ((bucket), modified, id);
      """.stripMargin)
        membersStore      <- MemberStore(keyspace)
        accountsStore     <- AccountStore(keyspace)
        transactionsStore <- TransactionStore(keyspace)
      } yield new ZoneStore(keyspace, membersStore, accountsStore, transactionsStore)

  }

  class ZoneStore private (keyspace: String,
                           val memberStore: MemberStore,
                           val accountStore: AccountStore,
                           val transactionStore: TransactionStore)(implicit session: Session) {

    private[this] val retrieveStatement = prepareStatement(s"""
                       |SELECT equity_account_id, created, expires, name, metadata
                       |  FROM $keyspace.zones_by_id
                       |  WHERE bucket = ? AND id = ?
        """.stripMargin)

    def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Zone] =
      for {
        resultSet <- retrieveStatement.execute(1L: java.lang.Long, zoneId.id)
        zone      <- toZone(zoneId)(resultSet.one)
      } yield zone

    def retrieveOpt(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Option[Zone]] = {
      (for {
        resultSet <- retrieveStatement.execute(1L: java.lang.Long, zoneId.id)
        zoneOpt = Option(resultSet.one).map(toZone(zoneId))
      } yield zoneOpt).flatMap {
        case None             => Future.successful(None)
        case Some(futureZone) => futureZone.map(Some(_))
      }
    }

    private[this] def toZone(zoneId: ZoneId)(row: Row)(implicit ec: ExecutionContext): Future[Zone] = {
      for {
        members      <- memberStore.retrieve(zoneId)
        accounts     <- accountStore.retrieve(zoneId)
        transactions <- transactionStore.retrieve(zoneId)
        equityAccountId = AccountId(row.getString("equity_account_id"))
        created         = row.getTimestamp("created").getTime
        expires         = row.getTimestamp("expires").getTime
        name            = Option(row.getString("name"))
        metadata = Option(row.getString("metadata"))
          .map(JsonFormat.fromJsonString[com.google.protobuf.struct.Struct])
      } yield Zone(zoneId, equityAccountId, members, accounts, transactions, created, expires, name, metadata)
    }

    private[this] val createStatement = prepareStatement(s"""
                       |INSERT INTO $keyspace.zones_by_id(id, bucket, equity_account_id, created, expires, name, metadata, currency)
                       |  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.stripMargin)

    def create(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
      for {
        _ <- addNameChange(zone.id, Instant.ofEpochMilli(zone.created), zone.name)
        _ <- createStatement.execute(
          zone.id.id,
          1L: java.lang.Long,
          zone.equityAccountId.id,
          new Date(zone.created),
          new Date(zone.expires),
          zone.name.orNull,
          zone.metadata.map(JsonFormat.toJsonString[com.google.protobuf.struct.Struct]).orNull,
          zone.metadata.flatMap(_.fields.get("currency").flatMap(_.kind.stringValue)).orNull
        )
        _ <- Future.traverse(zone.members.values)(memberStore.create(zone.id, Instant.ofEpochMilli(zone.created)))
        _ <- Future.traverse(zone.accounts.values)(accountStore.create(zone, Instant.ofEpochMilli(zone.created)))
        _ <- Future.traverse(zone.transactions.values)(transactionStore.add(zone))
      } yield ()

    private[this] val changeNameStatement = prepareStatement(s"""
                       |UPDATE $keyspace.zones_by_id
                       |  SET modified = ?, name = ?
                       |  WHERE id = ? AND bucket = ?
        """.stripMargin)

    def changeName(zoneId: ZoneId, modified: Instant, name: Option[String])(
        implicit ec: ExecutionContext): Future[Unit] =
      for {
        _ <- addNameChange(zoneId, changed = modified, name)
        _ <- changeNameStatement.execute(
          Date.from(modified),
          name.orNull,
          zoneId.id,
          1L: java.lang.Long
        )
      } yield ()

    private[this] val addNameChangeStatement = prepareStatement(s"""
                       |INSERT INTO $keyspace.zone_name_changes_by_id (id, changed, name)
                       |  VALUES (?, ?, ?)
        """.stripMargin)

    private[this] def addNameChange(zoneId: ZoneId, changed: Instant, name: Option[String])(
        implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- addNameChangeStatement.execute(
             zoneId.id,
             Date.from(changed),
             name.orNull
           )) yield ()

    private[this] val updateModifiedStatement = prepareStatement(s"""
                       |UPDATE $keyspace.zones_by_id
                       |  SET modified = ?
                       |  WHERE id = ? AND bucket = ?
        """.stripMargin)

    def updateModified(zoneId: ZoneId, modified: Instant)(implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- updateModifiedStatement.execute(
             Date.from(modified),
             zoneId.id,
             1L: java.lang.Long
           )) yield ()

  }

  object BalanceStore {
    def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[BalanceStore] =
      for {
        _ <- execute(s"""
                        |CREATE TABLE IF NOT EXISTS $keyspace.balances_by_zone (
                        |  zone_id text,
                        |  account_id text,
                        |  owner_names list<text>,
                        |  balance decimal,
                        |  PRIMARY KEY ((zone_id), account_id)
                        |);
      """.stripMargin)
      } yield new BalanceStore(keyspace)
  }

  class BalanceStore private (keyspace: String)(implicit session: Session) {

    private[this] val retrieveStatement = prepareStatement(s"""
                       |SELECT account_id, balance
                       |  FROM $keyspace.balances_by_zone
                       |  WHERE zone_id = ?
        """.stripMargin)

    def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[AccountId, BigDecimal]] =
      for (resultSet <- retrieveStatement.execute(zoneId.id))
        yield
          (for {
            row <- resultSet.iterator.asScala
            accountId = AccountId(row.getString("account_id"))
            value     = BigDecimal(row.getDecimal("balance"))
          } yield accountId -> value).toMap

    private[this] val createStatement = prepareStatement(s"""
                       |INSERT INTO $keyspace.balances_by_zone (zone_id, account_id, owner_names, balance)
                       |  VALUES (?, ?, ?, ?)
          """.stripMargin)

    def create(zone: Zone, balance: BigDecimal, accounts: Iterable[Account])(
        implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- Future.traverse(accounts)(create(zone, balance))) yield ()

    def create(zone: Zone, balance: BigDecimal)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- createStatement.execute(
             zone.id.id,
             account.id.id,
             ownerNames(zone.members, account),
             balance.underlying
           )) yield ()

    def update(zone: Zone, accounts: Iterable[Account], balances: Map[AccountId, BigDecimal])(
        implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- Future.traverse(accounts.map(account => account -> balances(account.id)))(update(zone)))
        yield ()

    private[this] val updateStatement = prepareStatement(s"""
                       |UPDATE $keyspace.balances_by_zone
                       |  SET owner_names = ?, balance = ?
                       |  WHERE zone_id = ? AND account_id = ?
          """.stripMargin)

    def update(zone: Zone)(accountAndBalance: (Account, BigDecimal))(implicit ec: ExecutionContext): Future[Unit] = {
      val (account, balance) = accountAndBalance
      for (_ <- updateStatement.execute(
             ownerNames(zone.members, account),
             balance.underlying,
             zone.id.id,
             account.id.id
           )) yield ()
    }
  }

  object ClientStore {
    def apply(keyspace: String)(implicit session: Session, ec: ExecutionContext): Future[ClientStore] =
      for {
        _ <- execute(s"""
                        |CREATE TABLE IF NOT EXISTS $keyspace.client_sessions_by_zone_fingerprint (
                        |  zone_id text,
                        |  fingerprint text,
                        |  joined timestamp,
                        |  actor_ref text,
                        |  quit timestamp,
                        |  PRIMARY KEY ((zone_id, fingerprint), joined, actor_ref)
                        |);
      """.stripMargin)
        _ <- execute(s"""
                        |CREATE TABLE IF NOT EXISTS $keyspace.clients_by_zone (
                        |  zone_id text,
                        |  public_key blob,
                        |  fingerprint text,
                        |  last_joined timestamp,
                        |  current_actor_ref text,
                        |  last_quit timestamp,
                        |  PRIMARY KEY ((zone_id), public_key)
                        |);
      """.stripMargin)
        _ <- execute(s"""
                        |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.client_zones_by_fingerprint AS
                        |  SELECT * FROM $keyspace.clients_by_zone
                        |  WHERE public_key IS NOT NULL AND last_joined IS NOT NULL
                        |  PRIMARY KEY ((public_key), zone_id, last_joined);
      """.stripMargin)
      } yield new ClientStore(keyspace)
  }

  class ClientStore private (keyspace: String)(implicit session: Session) {

    private[this] val retrieveStatement = prepareStatement(s"""
                       |SELECT public_key, last_joined, current_actor_ref
                       |  FROM $keyspace.clients_by_zone
                       |  WHERE zone_id = ?
        """.stripMargin)

    def retrieve(zoneId: ZoneId)(implicit ec: ExecutionContext,
                                 system: ExtendedActorSystem): Future[Map[ActorRef[Nothing], (Instant, PublicKey)]] =
      for (resultSet <- retrieveStatement.execute(zoneId.id))
        yield
          (for {
            row <- resultSet.iterator.asScala if !row.isNull("current_actor_ref")
            publicKey                          = PublicKey(ByteString.of(row.getBytes("public_key")))
            lastJoined                         = row.getTimestamp("last_joined").toInstant
            currentActorRef: ActorRef[Nothing] = system.provider.resolveActorRef(row.getString("current_actor_ref"))
          } yield currentActorRef -> (lastJoined -> publicKey)).toMap

    private[this] val createOrUpdateStatement = prepareStatement(s"""
                       |INSERT INTO $keyspace.clients_by_zone (zone_id, public_key, fingerprint, last_joined, current_actor_ref, last_quit)
                       |  VALUES (?, ?, ?, ?, ?, null)
        """.stripMargin)

    def createOrUpdate(zoneId: ZoneId, publicKey: PublicKey, joined: Instant, actorRef: ActorRef[Nothing])(
        implicit ec: ExecutionContext): Future[Unit] =
      for {
        _ <- openSession(zoneId, publicKey, joined, actorRef)
        _ <- createOrUpdateStatement.execute(
          zoneId.id,
          publicKey.value.asByteBuffer,
          publicKey.fingerprint,
          Date.from(joined),
          Serialization.serializedActorPath(actorRef.toUntyped)
        )
      } yield ()

    private[this] val updateStatement = prepareStatement(s"""
                       |UPDATE $keyspace.clients_by_zone
                       |  SET current_actor_ref = null, last_quit = ?
                       |  WHERE zone_id = ? AND public_key = ?
        """.stripMargin)

    def update(zoneId: ZoneId, publicKey: PublicKey, joined: Instant, actorRef: ActorRef[Nothing], quit: Instant)(
        implicit ec: ExecutionContext): Future[Unit] =
      for {
        _ <- closeSession(zoneId, publicKey, joined, actorRef, quit)
        _ <- updateStatement.execute(
          Date.from(quit),
          zoneId.id,
          publicKey.value.asByteBuffer
        )
      } yield ()

    private[this] val openSessionStatement = prepareStatement(s"""
                         |INSERT INTO $keyspace.client_sessions_by_zone_fingerprint (zone_id, fingerprint, joined, actor_ref)
                         |  VALUES (?, ?, ?, ?)
          """.stripMargin)

    private[this] def openSession(zoneId: ZoneId, publicKey: PublicKey, joined: Instant, actorRef: ActorRef[Nothing])(
        implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- openSessionStatement.execute(
             zoneId.id,
             publicKey.fingerprint,
             Date.from(joined),
             Serialization.serializedActorPath(actorRef.toUntyped)
           )) yield ()

    private[this] val closeSessionStatement = prepareStatement(s"""
                         |UPDATE $keyspace.client_sessions_by_zone_fingerprint
                         |  SET quit = ?
                         |  WHERE zone_id = ? AND fingerprint = ? AND joined = ? AND actor_ref = ?
          """.stripMargin)

    private[this] def closeSession(zoneId: ZoneId,
                                   publicKey: PublicKey,
                                   joined: Instant,
                                   actorRef: ActorRef[Nothing],
                                   quit: Instant)(implicit ec: ExecutionContext): Future[Unit] =
      for (_ <- closeSessionStatement.execute(
             Date.from(quit),
             zoneId.id,
             publicKey.fingerprint,
             Date.from(joined),
             Serialization.serializedActorPath(actorRef.toUntyped)
           )) yield ()

  }

  private[this] def execute(statement: String)(implicit session: Session) =
    session.executeAsync(statement).asScala

  private[this] def prepareStatement(statement: String)(implicit session: Session): Future[PreparedStatement] =
    session.prepareAsync(statement).asScala

  implicit class RichPreparedStatement(private val preparedStatement: Future[PreparedStatement]) extends AnyVal {
    def execute(args: AnyRef*)(implicit session: Session, ec: ExecutionContext): Future[ResultSet] =
      preparedStatement.flatMap(preparedStatement => session.executeAsync(preparedStatement.bind(args: _*)).asScala)
  }

  implicit class RichListenableFuture[A](private val listenableFuture: ListenableFuture[A]) extends AnyVal {
    def asScala: Future[A] = {
      val promise = Promise[A]()
      Futures.addCallback(listenableFuture, new FutureCallback[A] {
        def onFailure(t: Throwable): Unit = promise.failure(t)
        def onSuccess(result: A): Unit    = promise.success(result)
      })
      promise.future
    }
  }

  private[this] def ownerNames(members: Map[MemberId, Member], account: Account): java.util.List[String] =
    account.ownerMemberIds
      .map(members)
      .toSeq
      .map(_.name.getOrElse("<unnamed>"))
      .asJava

}

class CassandraAnalyticsStore private (val journalSequenceNumberStore: JournalSequenceNumberStore,
                                       val zoneStore: ZoneStore,
                                       val balanceStore: BalanceStore,
                                       val clientStore: ClientStore)
