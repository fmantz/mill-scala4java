/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java.api

import mill.api.Task.Simple as T
import mill.api.{PathRef, Task}
import mill.javalib.Assembly
import mill.javalib.publish.Dependency
import upickle.default.{macroRW, ReadWriter as RW}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Shading is the solution to avoid conflicts with classes living in the same name space.
 * This trait is mainly used to avoid clashes of Scala libs of different versions.
 */
trait ShadingSupport extends mill.scalalib.ScalaModule, mill.javalib.JavaModule {
  private implicit val relocationRuleReadWriter: RW[Assembly.Rule.Relocate] = macroRW

  /**
   * Get Information about the used libraries including if the lib contains scala code or not
   */
  def libraryInformation: T[Seq[LibraryInformation]] = Task {
    val resolveFn = super.resolvePublishDependency()
    val dependencies: Seq[Dependency] = super.allMvnDeps().map(resolveFn)
    val jarFiles: Seq[PathRef] = super[JavaModule].resolvedMvnDeps()
    LibraryInformation.fromClasspathAndDependencies(dependencies, jarFiles)
  }

  /**
   * Name of the root directory of all shaded files
   */
  def shadedRootDir: T[String] = Task { "shaded" }

  /**
   * Shade the Scala files
   */
  def enableShadingScalaFiles: Boolean = true

  /**
   * Is ProGuard used for shrinking or obfuscation
   */
  def isProguardUsed: Boolean

  /**
   * To avoid conflicts with other libraries using other Scala versions,
   * Scala code that is not of the current project is shaded.
   * This task automatically creates the shading rules.
   */
  def scalaLibRelocationRules: T[Seq[Assembly.Rule.Relocate]] = Task {
    val rootDir = shadedRootDir()
    val sourceCodeId = if(isProguardUsed) Some(shadedSourceCodeId()) else None
    val scalaVersionStr = scalaVersionString()
    val scalaLibs  = libraryInformation()
    val rs = for {
      lib <- scalaLibs if lib.isScalaLibrary
      rp <- lib.rootPackages
    } yield {
      // if ProGuard is used, the Scala library code becomes program specific:
      // this means a unique id for the program code is needed
      // if ProGuard is not used the code becomes only version specific:
      // this means an identifier is only needed that depicts the scala version and the version of the used libs.
      val codeId = sourceCodeId.getOrElse(scalaVersionStr + "." + lib.normalizedJarName)
      Assembly.Rule.Relocate(s"$rp.**", s"$rootDir.$codeId.$rp.@1")
    }
    rs.distinct
  }

  /**
   * Identifier for the source code
   * note: the string should be valid as path name
   */
  def shadedSourceCodeId: T[String] = Task {
    val digestInstance = MessageDigest.getInstance("MD5")
    val digest = digestInstance.digest(allSourceFiles().map(_.sig).mkString("-").getBytes(StandardCharsets.UTF_8))
    val hexString = new scala.collection.mutable.StringBuilder()
    for (b <- digest) {
      hexString.append(String.format("%02x", b))
    }
    hexString.toString
  }

  /**
   * Scala version string, note the string should be valid as path name
   */
  def scalaVersionString: T[String] = Task {
    s"scala${scalaVersion().replace('.', '_')}"
  }
  
  /**
   * Shade scala to make different non-compatible Scala versions work together without special class loading:
   */
  def assemblyRulesExtended: Task[Seq[Assembly.Rule]] = Task.Anon[Seq[Assembly.Rule]] {
    if (!enableShadingScalaFiles) {
      assemblyRules
    } else {
      (assemblyRules ++ scalaLibRelocationRules()).distinct
    }
  }
}
