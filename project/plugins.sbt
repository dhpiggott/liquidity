addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")
addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.5.1")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.8.2"
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.19")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.1.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.14")
addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1")
