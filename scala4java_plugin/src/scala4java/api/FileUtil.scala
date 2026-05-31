/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java.api

import mill.api.PathRef
import os.{CommandResult, Path}
import scala4java.api.LibraryInformation

/**
 * Note: This utility is implemented as a trait, allowing its methods to be overridden.
 */
trait FileUtil {

  /**
   * Calculate a list of class names from a list of directories.
   * Use all files in that list that have a specific suffix to generate the class names.
   * The package names are generated from the subdirectories of the given root directories.
   */
  protected def calculateKeepRootClassNames(suffixes: Seq[String], allSources: Seq[PathRef]): Seq[String] = {
    val classNames = scala.collection.mutable.ArrayBuffer.empty[String]
    for {
      cp: PathRef <- allSources if os.isDir(cp.path)
      rootPathAsString = cp.path.toString + "/"
      f <- os.walk(cp.path)     if os.isFile(f)
      pathAsString = f.toString
    } {
      for (suffix <- suffixes.find(_ == f.ext)) {
        classNames += pathAsString
          .stripPrefix(rootPathAsString)
          .stripSuffix("." + suffix)
          .replace('/', '.')
      }
    }
    classNames.toSeq
  }

  /**
   * After ProGuard shrunk the assembly, all scala4Java flagged tests are
   * executed to check if all tests still succeed.
   * The result of these tests are written to a log file with this function.
   * @param path where the log file should be written to
   * @param commandRs test results that should be logged.
   */
  protected def writeLog(path: Path, commandRs: CommandResult): Unit = {
    val logFile = scala.collection.mutable.ArrayBuffer.empty[String]
    logFile += "COMMAND: " + commandRs.command.mkString(" ")
    logFile += "EXIT_CODE: " + commandRs.exitCode
    logFile += "ERROR_LOG:\n" + commandRs.err.text()
    logFile += "OUT_LOG:\n" + commandRs.out.text()
    os.write.over(path, logFile.mkString("\n"))
  }

  /**
   * Write a file that gives some help to find out which third party licenses have
   * to be considered when publishing the library jar.
   * @param path where is the license file is initially written to
   * @param libraryInformation information about the libraries
   */
  protected def writeLicencesFileJar(path: os.Path, libraryInformation: Seq[LibraryInformation]): Unit = {
    val licenceInfos = new scala.collection.mutable.StringBuilder()
    licenceInfos.append("LICENCES OF THIRD PARTY PRODUCTS:\n\n")
    licenceInfos.append("The licences text you find in the packages on maven central etc. or in the linked web pages.")
    if (libraryInformation.nonEmpty) {
      val (scalaLibInfos, javaLibInfos) = libraryInformation.partition(_.isScalaLibrary)
      if (scalaLibInfos.nonEmpty) {
        licenceInfos.append("\n\nPARTLY INCLUDED IN THIS LIBRARY:\n\n")
        licenceInfos.append(scalaLibInfos.sortBy(_.jarName).flatMap(_.licenseInfo).mkString("\n"))
      }
      if (javaLibInfos.nonEmpty) {
        licenceInfos.append("\n\nREFERENCED BY THIS LIBRARY:\n\n")
        licenceInfos.append(javaLibInfos.sortBy(_.jarName).flatMap(_.licenseInfo).mkString("\n"))
      }
    }
    licenceInfos.append("\n") // posix empty line
    os.write.over(path, licenceInfos.toString())
  }

  /**
   * Write a file that gives some help to find out which third party licenses have to be considered it this assembly.
   * @param path where is the license file is initially written to
   * @param libInfos information about the libraries
   * @param extraLibInfosForScala4JavaTests information about the test-libraries
   */
  protected def writeLicencesFileAssembly(path: os.Path, libInfos: Seq[LibraryInformation], extraLibInfosForScala4JavaTests: Seq[LibraryInformation]): Unit = {
    val licenceInfos = new scala.collection.mutable.StringBuilder()
    licenceInfos.append("LICENCES OF THIRD PARTY PRODUCTS:\n\n")
    licenceInfos.append("The licences text you find in the packages on maven central etc or in the linked web pages.")
    if (libInfos.nonEmpty) {
      licenceInfos.append(libInfos.sortBy(_.jarName).flatMap(_.licenseInfo).mkString("\n"))
    }
    if(extraLibInfosForScala4JavaTests.nonEmpty) {
      licenceInfos.append(extraLibInfosForScala4JavaTests.sortBy(_.jarName).flatMap(_.licenseInfo).mkString("\n"))
    }
    licenceInfos.append("\n") // posix empty line
    os.write.over(path, licenceInfos.toString())
  }

}
