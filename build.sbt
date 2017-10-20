lazy val protobufSettings = Seq(
  PB.targets in Compile := Seq(
    scalapb.gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb"          %% "scalapb-runtime" %
    com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)

lazy val `proto-binding` = project
  .in(file("proto-binding"))
  .settings(
    name := "liquidity-proto-binding"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai"   %% "shapeless" % "2.3.2",
      "org.typelevel" %% "cats-core" % "0.9.0"
    ))

lazy val model = project
  .in(file("model"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-model"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.squareup.okio" % "okio"       % "1.13.0",
      "org.typelevel"     %% "cats-core" % "0.9.0"
    ))

lazy val `model-proto-binding` = project
  .in(file("model-proto-binding"))
  .settings(
    name := "liquidity-model-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model`)

lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-ws-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .dependsOn(model)
  .settings(libraryDependencies += "com.typesafe.akka" %% "akka-typed" % "2.5.6")
  .dependsOn(testkit % Test)
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val `ws-protocol-proto-binding` = project
  .in(file("ws-protocol-proto-binding"))
  .settings(
    name := "liquidity-ws-protocol-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model-proto-binding`)
  .dependsOn(`ws-protocol`)

lazy val `actor-protocol` = project
  .in(file("actor-protocol"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-actor-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf"),
    PB.includePaths in Compile += file("ws-protocol/src/main/protobuf")
  )
  .dependsOn(model)
  .dependsOn(`ws-protocol`)

lazy val `actor-protocol-proto-binding` = project
  .in(file("actor-protocol-proto-binding"))
  .settings(
    name := "liquidity-actor-protocol-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model-proto-binding`)
  .dependsOn(`ws-protocol-proto-binding`)
  .dependsOn(`actor-protocol`)

lazy val testkit = project
  .in(file("testkit"))
  .settings(
    name := "liquidity-testkit"
  )

lazy val server = project
  .in(file("server"))
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-protocol-proto-binding`)
  .dependsOn(`actor-protocol`)
  .dependsOn(`actor-protocol-proto-binding`)
  .settings(
    dependencyOverrides ++= Seq(
      "org.scala-lang.modules"     %% "scala-xml"                   % "1.0.6",
      "com.lihaoyi"                %% "fastparse"                   % "0.4.4",
      "com.fasterxml.jackson.core" % "jackson-annotations"          % "2.8.9",
      "com.fasterxml.jackson.core" % "jackson-databind"             % "2.8.9",
      "com.github.jnr"             % "jnr-constants"                % "0.9.9",
      "com.github.jnr"             % "jnr-ffi"                      % "2.1.6",
      "org.slf4j"                  % "slf4j-api"                    % "1.7.25",
      "com.typesafe.akka"          %% "akka-actor"                  % "2.5.6",
      "com.typesafe.akka"          %% "akka-stream"                 % "2.5.6",
      "com.typesafe.akka"          %% "akka-stream-testkit"         % "2.5.6",
      "com.typesafe.akka"          %% "akka-cluster"                % "2.5.6",
      "com.typesafe.akka"          %% "akka-cluster-sharding"       % "2.5.6",
      "com.typesafe.akka"          %% "akka-cluster-tools"          % "2.5.6",
      "com.typesafe.akka"          %% "akka-persistence"            % "2.5.6",
      "com.typesafe.akka"          %% "akka-persistence-query"      % "2.5.6",
      "com.typesafe.akka"          %% "akka-http"                   % "10.0.10",
      "io.netty"                   % "netty-transport-native-epoll" % "4.0.44.Final",
      "com.google.protobuf"        % "protobuf-java"                % "3.4.0",
      "com.trueaccord.scalapb"     %% "scalapb-runtime"             % "0.6.6",
      "com.typesafe.play"          %% "play-json"                   % "2.6.6"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-slf4j"                   % "2.5.6",
      "ch.qos.logback"     % "logback-classic"               % "1.2.3",
      "com.typesafe.akka"  %% "akka-cluster"                 % "2.5.6",
      "com.lightbend.akka" %% "akka-management-cluster-http" % "0.5",
      "com.typesafe.akka"  %% "akka-cluster-sharding"        % "2.5.6",
      "com.typesafe.akka"  %% "akka-cluster-tools"           % "2.5.6",
      "com.typesafe.akka"  %% "akka-persistence"             % "2.5.6",
      "com.typesafe.akka"  %% "akka-persistence-query"       % "2.5.6",
      // TODO: Switch to akka-persistence-jdbc
      "com.typesafe.akka" %% "akka-persistence-cassandra"  % "0.58",
      "io.netty"          % "netty-transport-native-epoll" % "4.1.12.Final" classifier "linux-x86_64",
      // TODO: Switch to doobie
      "com.datastax.cassandra" % "cassandra-driver-core" % "3.2.0",
      "com.typesafe.akka"      %% "akka-http"            % "10.0.10",
      "com.trueaccord.scalapb" %% "scalapb-json4s"       % "0.3.3",
      "com.typesafe.play"      %% "play-json"            % "2.6.6",
      "de.heikoseeberger"      %% "akka-http-play-json"  % "1.18.1"
    )
  )
  .dependsOn(`ws-protocol` % "test->test")
  .dependsOn(testkit % Test)
  .settings(libraryDependencies ++= Seq(
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest"       %% "scalatest"                 % "3.0.4"   % Test,
    "com.typesafe.akka"   %% "akka-http-testkit"         % "10.0.10" % Test,
    "com.typesafe.akka"   %% "akka-stream-testkit"       % "2.5.6"   % Test
  ))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .settings(inConfig(MultiJvm)(scalafmtSettings))
  .dependsOn(testkit % MultiJvm)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-multi-node-testkit"             % "2.5.6" % MultiJvm,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % "0.58"  % MultiJvm
  ))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.server",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    dockerBaseImage := "openjdk:8-jre",
    daemonUser in Docker := "root"
  )

lazy val client = project
  .in(file("client"))
  .settings(
    name := "liquidity-client"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-protocol-proto-binding`)
  .settings(
    dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix"   % "1.54.0.0",
      "com.squareup.okhttp3"    % "okhttp" % "3.9.0"
    )
  )
  .dependsOn(testkit % Test)
  .dependsOn(server % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val healthcheck = project
  .in(file("healthcheck"))
  .settings(
    name := "liquidity-healthcheck"
  )
  .dependsOn(client)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.6",
      "org.scalatest"     %% "scalatest"           % "3.0.4"
    )
  )
  .enablePlugins(JavaAppPackaging, UniversalPlugin)

lazy val boardgame = project
  .in(file("boardgame"))
  .settings(
    name := "liquidity-boardgame"
  )
  .dependsOn(client)

lazy val root = project
  .in(file("."))
  .settings(
    name := "liquidity"
  )
  .aggregate(
    `proto-binding`,
    model,
    `ws-protocol`,
    `ws-protocol-proto-binding`,
    `actor-protocol`,
    `actor-protocol-proto-binding`,
    testkit,
    server,
    client,
    healthcheck,
    boardgame
  )
