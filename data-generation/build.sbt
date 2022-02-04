name := "data-generation"

version := "0.1"

// https://mvnrepository.com/artifact/org.apache.spark/spark-sql
libraryDependencies += "org.apache.spark" %% "spark-sql" % "3.2.0"

// https://mvnrepository.com/artifact/org.apache.spark/spark-sql
libraryDependencies += "org.apache.spark" %% "spark-graphx" % "3.2.0"
libraryDependencies += "com.github.javafaker" % "javafaker" % "1.0.2"


scalaVersion := "2.13.8"
