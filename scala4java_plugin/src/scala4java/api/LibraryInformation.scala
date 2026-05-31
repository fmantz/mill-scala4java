/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java.api

import mill.api.PathRef
import mill.javalib.publish.Dependency
import org.apache.bcel.classfile.ClassParser
import os.{Path, SubPath}
import upickle.default.{macroRW, ReadWriter as RW}

import scala.collection.mutable.ArrayBuffer
import scala.util.Using

/**
 * This class is a helper class to store some useful information
 * of a used library
 * @param jarName the name of the jar
 * @param normalizedJarName jarName without suffix, version is normalized to a string not containing dots (since this would result in subdirectories when used)
 * @param directDependency the artifact information if the library is direct dependency of the project, if it is only a transitive one the field is empty
 * @param licenseInfo the manifest if the jar file (is set only for direct dependencies)
 * @param rootPackages the root packages of the library
 * @param isScalaLibrary true if the jar contains compiled Scala code
 * @param classpath the path to the library (pointing to the local repository)
 */
case class LibraryInformation(
  jarName: String,
  normalizedJarName: String,
  directDependency: Option[mill.javalib.publish.Dependency], // filled only if it is a direct dependency
  licenseInfo: Option[String],
  rootPackages: Seq[String], // contain all package names that ar root packages (of the jar)
  isScalaLibrary: Boolean,
  classpath: PathRef
) {
  /**
   * A library that do not contain any scala code is considered a java library
   * @return true it the library does not contain compiled Scala code
   */
  def isJavaLibrary: Boolean = !isScalaLibrary
}

/**
 * Helper methods to fill instances of case class LibraryInformation
 */
object LibraryInformation {

  /**
   * The path to the MANIFEST.MF file in a jar
   */
  private val ManifestPath: SubPath = os.SubPath("META-INF/MANIFEST.MF")

  /**
   * A filter of Manifest entries that are not interesting as information in the license files.
   */
  private val LicencesTextFilter: Set[String] = Set("Import-Package", "Export-Package", "Name")

  /**
   * ReadWriter so that mill can produce JSONs in the output directory containing LibraryInformations
   */
  given RW[LibraryInformation] = macroRW

  /**
   * Collect library information from known dependencies and their jars
   * @param dependencies known dependencies
   * @param jarFiles corresponding pathrefs to the jars
   * @return a sequence of collected library information
   */
  def fromClasspathAndDependencies(dependencies: Seq[Dependency], jarFiles: Seq[PathRef]): Seq[LibraryInformation] = {
    // dependency to map: base-filename -> dependency:
    val depMap: Map[String, Dependency] = dependencies.map(d => (d.artifact.id + "-" + d.artifact.version) -> d).toMap

    val rs = scala.collection.mutable.ArrayBuffer.empty[LibraryInformation]
    for (cp: PathRef <- jarFiles if cp.path.ext == "jar") yield {
      val baseName = cp.path.baseName
      val zipInfos = Using(os.zip.open(cp.path)) { fs =>
        val rootDirs   = extractRootDirs(fs)
        val isScalaLib = isScalaLibByName(baseName) || checkIsScalaLib(fs)
        (rootDirs, isScalaLib)
      }
      for ((dirs, isScalaLib) <- zipInfos) {
        val curDependency    = depMap.get(baseName)
        val curVersionString = getJarNameWithVersion(baseName)
        val curLicenseInfo   = buildLicenseInfo(cp, curDependency.map(_.artifact))
        rs += LibraryInformation(
          jarName = baseName,
          normalizedJarName = curVersionString,
          directDependency = curDependency,
          licenseInfo = curLicenseInfo,
          rootPackages = dirs.map(_.tail.replace('/', '.')), // to package names
          isScalaLibrary = isScalaLib,
          classpath = cp
        )
      }
    }

    rs.toSeq
  }

  /**
   * Collect library information from the class path only, some attributes of LibraryInformation will not be set
   * @param classpath classpath where the jars are found
   * @return a sequence of collected library information
   */
  def fromClasspath(classpath: Seq[PathRef]): Seq[LibraryInformation] = {
    val rs = scala.collection.mutable.ArrayBuffer.empty[LibraryInformation]
    for (cp: PathRef <- classpath) yield {
      val baseName = cp.path.baseName
      val zipInfos = Using(os.zip.open(cp.path)) { fs =>
        val rootDirs = extractRootDirs(fs)
        val isScalaLib = isScalaLibByName(baseName) || checkIsScalaLib(fs)
        (rootDirs, isScalaLib)
      }
      for ((dirs, isScalaLib) <- zipInfos) {
        val curVersionString = getJarNameWithVersion(baseName)
        val curLicenseInfo   = buildLicenseInfo(cp, None) // extract less infos, since dep is NONE
        rs += LibraryInformation(
          jarName = baseName,
          normalizedJarName = curVersionString,
          directDependency = None,
          licenseInfo = curLicenseInfo,
          rootPackages = dirs.map(_.tail.replace('/', '.')), // to package names
          isScalaLibrary = isScalaLib,
          classpath = cp
        )
      }
    }
    rs.toSeq
  }

  /**
   * Collect some information from where a user can easily retrieve the
   * license conditions of used library
   * @param classpath to the jar file
   * @param maybeArtifact filled if it is a direct dependency
   * @return a string containing the license infos
   */
  private def buildLicenseInfo(classpath: PathRef, maybeArtifact: Option[mill.javalib.publish.Artifact]) : Option[String] =  {
    import scala.jdk.CollectionConverters.*
    Using(os.zip.open(classpath.path)) { fs =>
      val mPath : os.Path = fs / ManifestPath
      if(os.exists(mPath) && os.isFile(mPath)){
        val manifest              = os.read.stream(mPath).readBytesThrough(s => new java.util.jar.Manifest(s))
        val licenceInfo           = new ArrayBuffer[String]()
        val selectedManifestInfos = manifest
          .getMainAttributes
          .asScala
          .filterNot((k,v) => LicencesTextFilter.contains(k.toString)).map((k,v) => s"$k: $v")
        licenceInfo.appendAll(selectedManifestInfos)
        maybeArtifact.foreach{ a =>
          licenceInfo.append(s"Artifact-Id: ${a.id}")
          licenceInfo.append(s"Artifact-Version: ${a.version}")
          licenceInfo.append(s"Artifact-Group: ${a.group}")
          licenceInfo.append(s"Artifact-IsSnapshot: ${a.isSnapshot}")
        }
        Option(licenceInfo.sorted.mkString("\n") + "\n")
      } else {
        None
      }
    }.get
  }

  /**
   * Convert . into _ in the version name since we do not want dots in directory names
   * @param jarName without the extension
   * @return version string
   */
  private def getJarNameWithVersion(jarName: String) : String = {
    jarName.replace('.','_')
  }

  /**
   * @param p path to the file
   * @return true if it is a class file
   */
  private def isClassFile(p: Path): Boolean = p.ext == "class"

  /**
   * Note: in java the module-info classes have always the same name
   * @param p path to the file
   * @return true if the class is a java module-info.class
   */
  private def isModuleInfo(p: Path): Boolean = p.last == "module-info.class"

  /**
   * Extract all root dirs in a file system
   * @param fs filesystem (of the jar file)
   * @return list of root directories
   */
  private def extractRootDirs(fs: os.Path) = {
    val buffer = scala.collection.mutable.ArrayBuffer.empty[String]
    for (dir <- os.walk(fs).filter(isClassFile).filterNot(isModuleInfo).map(f => f / os.up).distinct) {
      // paths are presented as strings here,
      // only keep the directories-strings that do not already have a shorter prefix string in the buffer:
      val replaceEntry = buffer.find(i => i.startsWith(dir.toString))
      if (replaceEntry.isDefined) {
        // there is a longer entry in the buffer:
        buffer -= replaceEntry.get
        buffer += dir.toString
      } else if (buffer.exists(i => dir.toString.startsWith(i))) {
        // there is a shorter entry in the buffer:
        // do nothing
      } else {
        // there is no entry in the buffer:
        // new entry:
        buffer += dir.toString
      }
    }
    buffer.toSeq
  }

  /**
   * Check if a file contains a compiled scala class
   * @param p path to the class file
   * @return true if the file is a scala class file
   */
  private def isScalaFile(p: Path): Boolean = {
    // starting from scala 3 there is a tasty file:
    if (p.ext == "tasty") {
      true
    // non-class file:
    } else if (p.ext != "class") {
      false
      // scala 2 need as more elaborate approach, since tasty files exists only in scala >= version 3.0.0
    } else try {
      // we use apache.bcel:
      val parser = new ClassParser(os.read.inputStream(p), p.ext)
      val clazz = parser.parse()
      val attrs = clazz.getAttributes
      if (attrs == null) {
        false
      } else {
        attrs.exists(a => a.getName.startsWith("Scala"))
      }
    } catch {
      case _: Throwable => false
    }
  }

  /**
   * @param libName name of the lib
   * @return true if the lib starts with the word scala, all scala core libs start with scala in the name and a few other libs as well
   * note: here we may get unwanted state / too large jar if someone calls his pure java lib scala ... (put this should be punished ;-))
   */
  private def isScalaLibByName(libName: String): Boolean = {
    libName.startsWith("scala")
  }

  /**
   * @param fs filesystem of the jar file
   * @return true is one Scala file is found
   */
  private def checkIsScalaLib(fs: os.Path) : Boolean = {
    os.walk(fs).exists(isScalaFile)
  }

}
