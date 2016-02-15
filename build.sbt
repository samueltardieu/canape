name := "canape"

organization := "net.rfc1149"

version := "0.0.8-SNAPSHOT"

scalaVersion := "2.11.7"

resolvers ++= Seq("Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
                  Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.2-RC3",
  "com.typesafe.akka" %% "akka-stream" % "2.4.2-RC3",
  "com.typesafe.akka" %% "akka-http-core" % "2.4.2-RC3",
  "com.typesafe.akka" %% "akka-http-experimental" % "2.4.2-RC3",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.2-RC3" % "test",
  "com.typesafe.play" %% "play-json" % "2.5.0-M2",
  "com.iheart" %% "ficus" % "1.2.1",
  "org.specs2" %% "specs2-core" % "3.7" % "test"
)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

fork in Test := true

publishTo := {
  val path = "/home/sam/rfc1149.net/data/ivy2/" + (if (version.value.trim.endsWith("SNAPSHOT")) "snapshots/" else "releases")
  Some(Resolver.ssh("rfc1149 ivy releases", "rfc1149.net", path) as "sam" withPermissions("0644"))
}
