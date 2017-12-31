lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(
    name := "liquidity-ws-protocol"
  )
  .disablePlugins(ScalafmtCorePlugin)
  .settings(
    libraryDependencies := Seq.empty,
    coverageEnabled := false,
    crossPaths := false,
    unmanagedResourceDirectories in Compile ++=
      (PB.protoSources in Compile).value.map(_.asFile),
    homepage := Some(url("https://github.com/dhpcs/liquidity/")),
    startYear := Some(2015),
    description := "Liquidity is a smartphone based currency built for " +
      "Monopoly and all board and tabletop games.",
    licenses += "Apache-2.0" -> url(
      "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    organization := "com.dhpcs",
    organizationHomepage := Some(url("https://www.dhpcs.com/")),
    organizationName := "dhpcs",
    developers := List(
      Developer(
        id = "dhpiggott",
        name = "David Piggott",
        email = "david@piggott.me.uk",
        url = url("https://www.dhpiggott.net/")
      )),
    scmInfo := Some(
      ScmInfo(
        browseUrl = url("https://github.com/dhpcs/liquidity/"),
        connection = "scm:git:https://github.com/dhpcs/liquidity.git",
        devConnection = Some("scm:git:git@github.com:dhpcs/liquidity.git")
      )),
    releaseEarlyEnableInstantReleases := false,
    releaseEarlyNoGpg := true,
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    bintrayOrganization := Some("dhpcs")
  )

lazy val `ws-protocol-scala-binding` = project
  .in(file("ws-protocol-scala-binding"))
  .settings(
    name := "liquidity-ws-protocol-scala-binding"
  )
  .settings(protobufScalaSettings(`ws-protocol`))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "1.0.0",
      "com.squareup.okio" % "okio" % "1.13.0"
    ))

lazy val `actor-protocol` = project
  .in(file("actor-protocol"))
  .settings(
    name := "liquidity-actor-protocol"
  )
  .dependsOn(`ws-protocol`)
  .disablePlugins(ScalafmtCorePlugin)

lazy val `actor-protocol-scala-binding` = project
  .in(file("actor-protocol-scala-binding"))
  .settings(protobufScalaSettings(`actor-protocol`))
  .settings(
    name := "liquidity-actor-protocol-scala-binding"
  )
  .settings(
    libraryDependencies += "com.typesafe.akka" %% "akka-typed" % "2.5.8")
  .dependsOn(`ws-protocol-scala-binding`)
  .settings(
    PB.includePaths in Compile += file("ws-protocol/src/main/protobuf")
  )

lazy val server = project
  .in(file("server"))
  .settings(
    name := "server"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-protocol-scala-binding`)
  .dependsOn(`actor-protocol`)
  .dependsOn(`actor-protocol-scala-binding`)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.8",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.chuusai" %% "shapeless" % "2.3.3",
      "org.typelevel" %% "cats-core" % "1.0.0",
      "com.typesafe.akka" %% "akka-cluster" % "2.5.8",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "0.7.1",
      "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.8",
      "com.typesafe.akka" %% "akka-cluster-tools" % "2.5.8",
      "com.typesafe.akka" %% "akka-persistence" % "2.5.8",
      "com.typesafe.akka" %% "akka-persistence-query" % "2.5.8",
      "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.0.1",
      "org.tpolecat" %% "doobie-core" % "0.5.0-M11",
      "org.tpolecat" %% "doobie-hikari" % "0.5.0-M11",
      "mysql" % "mysql-connector-java" % "5.1.45",
      "com.typesafe.akka" %% "akka-http" % "10.0.11",
      "com.trueaccord.scalapb" %% "scalapb-json4s" % "0.3.3",
      "com.typesafe.play" %% "play-json" % "2.6.8",
      "de.heikoseeberger" %% "akka-http-play-json" % "1.18.1"
    )
  )
  .settings(libraryDependencies ++= Seq(
    "com.h2database" % "h2" % "1.4.196" % Test,
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest" %% "scalatest" % "3.0.4" % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.0.11" % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.8" % Test
  ))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(inConfig(IntegrationTest)(scalafmtSettings))
  .settings(libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.4" % IntegrationTest,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.0.11" % IntegrationTest,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.8" % IntegrationTest
  ))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.server",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    packageName in Docker := "liquidity",
    version in Docker := version.value.replace('+', '-'),
    dockerBaseImage := "openjdk:8-jre",
    dockerRepository := Some("837036139524.dkr.ecr.eu-west-2.amazonaws.com")
  )

def protobufScalaSettings(project: Project) = Seq(
  PB.protoSources in Compile ++= (PB.protoSources in project in Compile).value,
  PB.targets in Compile := Seq(
    scalapb
      .gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" %
    com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)
