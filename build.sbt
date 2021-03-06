import org.scalafmt.sbt.ScalafmtPlugin

lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(
    name := "liquidity-ws-protocol"
  )
  .settings(
    libraryDependencies := Seq.empty,
    coverageEnabled := false,
    crossPaths := false,
    Compile / unmanagedResourceDirectories ++=
      (Compile / PB.protoSources).value.map(_.asFile),
    homepage := Some(url("https://github.com/dhpcs/liquidity/")),
    startYear := Some(2015),
    description := "Virtual currencies for Monopoly and other board and " +
      "tabletop games.",
    licenses += "Apache-2.0" -> url(
      "https://www.apache.org/licenses/LICENSE-2.0.txt"
    ),
    organization := "com.dhpcs",
    organizationHomepage := Some(url("https://www.dhpcs.com/")),
    organizationName := "dhpcs",
    developers := List(
      Developer(
        id = "dhpiggott",
        name = "David Piggott",
        email = "david@piggott.me.uk",
        url = url("https://www.dhpiggott.net/")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        browseUrl = url("https://github.com/dhpcs/liquidity/"),
        connection = "scm:git:https://github.com/dhpcs/liquidity.git",
        devConnection = Some("scm:git:git@github.com:dhpcs/liquidity.git")
      )
    ),
    releaseEarlyEnableInstantReleases := false,
    releaseEarlyNoGpg := true,
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    bintrayOrganization := Some("dhpcs")
  )

lazy val `proto-gen` = project
  .in(file("proto-gen"))
  .enablePlugins(
    AkkaGrpcPlugin
  )
  .settings(
    Compile / PB.protoSources ++= (`ws-protocol` / Compile / PB.protoSources).value,
    PB.generate / excludeFilter := "descriptor.proto",
    akkaGrpcCodeGeneratorSettings += "server_power_apis"
  )
  .settings(
    // Because the sources akka-grpc generates aren't perfect in this regard
    scalacOptions -= "-Xfatal-warnings"
  )
  .settings(
    libraryDependencies +=
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion %
        ProtocPlugin.ProtobufConfig
  )

lazy val model = project
  .in(file("model"))
  .dependsOn(`proto-gen`)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "1.6.1",
      "com.squareup.okio" % "okio" % "2.2.2",
      "com.typesafe.akka" %% "akka-actor-typed" % "2.5.23"
    )
  )

lazy val service = project
  .in(file("service"))
  .dependsOn(model)
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai" %% "shapeless" % "2.3.3",
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.23",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "net.logstash.logback" % "logstash-logback-encoder" % "6.1",
      "com.typesafe.akka" %% "akka-discovery" % "2.5.23",
      "com.typesafe.akka" %% "akka-cluster-typed" % "2.5.23",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.5.23",
      "com.typesafe.akka" %% "akka-persistence-typed" % "2.5.23",
      "com.typesafe.akka" %% "akka-persistence-query" % "2.5.23",
      "com.lightbend.akka.discovery" %% "akka-discovery-aws-api-async" % "1.0.1",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.1",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.1",
      "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.5.2",
      "org.tpolecat" %% "doobie-core" % "0.7.0",
      "org.tpolecat" %% "doobie-hikari" % "0.7.0",
      "mysql" % "mysql-connector-java" % "8.0.16",
      "org.typelevel" %% "cats-effect" % "1.3.1",
      "dev.zio" %% "zio" % "1.0.0-RC9-4",
      "dev.zio" %% "zio-interop-cats" % "1.3.1.0-RC3",
      "com.typesafe.akka" %% "akka-http" % "10.1.8",
      "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "1.1.0",
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.62",
      "com.typesafe.akka" %% "akka-http2-support" % "10.1.8",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.8",
      "com.typesafe.akka" %% "akka-http-xml" % "10.1.8",
      "com.typesafe.akka" %% "akka-stream-typed" % "2.5.23",
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.9.2",
      "org.json4s" %% "json4s-native" % "3.6.7",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.27.0",
      "com.nimbusds" % "nimbus-jose-jwt" % "7.4"
    ),
    dependencyOverrides += "com.zaxxer" % "HikariCP" % "2.7.8"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.h2database" % "h2" % "1.4.199" % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.5.23" % Test,
      "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.15.2" % Test,
      "org.scalatest" %% "scalatest" % "3.0.8" % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8" % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.23" % Test
    )
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(inConfig(IntegrationTest)(ScalafmtPlugin.scalafmtConfigSettings))
  .settings(IntegrationTest / logBuffered := false)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.8" % IntegrationTest,
      "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8" % IntegrationTest,
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.23" % IntegrationTest
    )
  )
  .enablePlugins(
    BuildInfoPlugin,
    JavaAppPackaging,
    DockerPlugin
  )
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.service",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    bashScriptExtraDefines += "addJava -Djdk.tls.ephemeralDHKeySize=2048",
    Docker / packageName := "liquidity",
    dockerBaseImage := "openjdk:12-jdk-oracle",
    dockerExposedPorts := Seq(8443)
  )
