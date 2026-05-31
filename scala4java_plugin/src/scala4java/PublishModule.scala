/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java

import coursier.version.VersionConstraint
import mill.api.{CrossVersion, PathRef, Task}
import mill.api.Task.Simple as T
import mill.javalib.Dep
import mill.javalib.publish.Dependency
import os.Path

import scala.xml.{Elem, PrettyPrinter, XML}

/**
 * If we want to publish our newly created jar to a repository, we need to adjust the ivy and pom definitions:
 *  - dependency to scala classes have to be removed
 *  - dependency to java classes that are required by removed scala dependencies have to be added:
 */
trait PublishModule extends mill.javalib.PublishModule, mill.scalalib.ScalaModule {
  self : ScalaModule =>

  /**
   * We only need java dependencies since all scala classes are included in the jar
   * libraryInformation gets its dependency from the classpath so that all required java jars should be found.
   */
  def requiredJavaJars: T[Seq[Dependency]] = Task {
    val resolve: mill.javalib.Dep => Dependency = this.resolvePublishDependency()
    libraryInformation()
      .view
      .filter(_.isJavaLibrary)
      .map(_.classpath.path)
      .flatMap(readJavaDepFromPom)
      .map(resolve)
      .toSeq
  }

  /**
   * Include only (transitive) dependent java libs:
   * note if not overwritten, same result as requiredJavaJars
   */
  override def publishXmlDeps: Task[Seq[Dependency]] = Task.Anon {
    requiredJavaJars()
  }

  /**
   * Include (transitive) dependent java libs only:
   * note: we reuse the original pom.xml file and adjust the dependencies section
   */
  override def ivy: T[PathRef] = Task {
    // create new dependencies to java libs:
    val newDependencies = requiredJavaJars()
      .map(dep => {
        val artifact = dep.artifact
        <dependency
          org={artifact.group}
          name={artifact.id}
          rev={artifact.version}
          conf={scala.xml.Unparsed("compile->compile;runtime->runtime")}
        />
     })

    val path = super.ivy().path
    val xml = XML.loadFile(path.toIO)

    // create new xml:
    val newXml = xml.copy(
      child = xml.child.map {
        case e: Elem if e.label == "dependencies" =>
          e.copy(child = newDependencies)
        case other => other
      }
    )

    val printer = new PrettyPrinter(width = 200, step = 2)
    val prettyXml = printer.format(newXml)

    val newFile: Path = Task.dest / "ivy.xml"
    os.write.over(newFile, prettyXml)
    resolvePublishDependency
    PathRef(newFile)
  }

  /**
   * We want to publish a pure java lib, this string should be empty.
   */
  override def artifactScalaVersion: T[String] = Task{""}

  /**
   * We want to publish a pure java lib, this string should be empty.
   */
  override def artifactSuffix: T[String] = Task{""}


  /**
   * Resolve the 'mill.javalib.Dep' dependency for a java lib from the jarPath
   */
  private def readJavaDepFromPom(jarPath: Path): Option[Dep] = {
    import coursier.core.compatibility._
    import coursier.maven.Pom
    val isJarFile = jarPath.ext == "jar"
    val pomFileName = jarPath.last.replace(".jar", ".pom")
    val pomPath = (jarPath / os.up) / pomFileName
    if (isJarFile && os.isFile(pomPath)) {
      val pomAsString: String = os.read(pomPath)
      val pomAsXml: Either[String, coursier.util.Xml.Node] = xmlParse(pomAsString)
      val resolvedDep = for {
        xml     <- pomAsXml
        project <- Pom.project(xml)
        coreDep = coursier.core.Dependency(project.module, VersionConstraint(project.version0.repr))
      } yield mill.javalib.Dep(coreDep, CrossVersion.empty(true), false)
      resolvedDep.toOption
    } else {
      None
    }
  }

}
