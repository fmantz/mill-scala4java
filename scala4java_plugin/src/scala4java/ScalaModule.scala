/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java

import mill.*
import mill.api.Task.Simple as T
import mill.api.{PathRef, Task}
import mill.javalib.{Assembly, AssemblyModule}
import mill.scalalib.*
import mill.scalalib.Assembly.*
import mill.util.Jvm
import os.{Path, RelPath}
import scala4java.api.*

/**
 * This Module replaces the standard Scala module. It adjusted its behavior for the use case
 * that we want to create a pure java library. Therefore, it adds the functionality of shading and shrinking,
 * which is used as follows:
 * - All (non-test) scala classes are shaded and included in the jar.
 * - Therefore an assembly is computed first and then the compiled scala classes are reused from the assembly in the jar.
 * - If shrinking is enabled the assembly file is shrunk before its class files are reused.
 * - As entry point for the shrinker 'ProGuard' all sources code classes are used as well as all test classes.
 * - After shrinking all test cases from scala4java.TestModules are executed to check if the shrunk code still works.
 */
trait ScalaModule extends mill.scalalib.ScalaModule, ShrinkingSupport, ShadingSupport {  self =>
  
  /**
   * An executable uber-jar/assembly containing all the resources and compiled
   * class files from this module and all it's upstream modules and dependencies
   *
   * Note: - uses extended set of assemblyRules
   *       - create a file of all essential class names if (filterClasses == true, default)
   */
  override def assembly: T[PathRef] = Task {
    // prepare input:
    val baseAssemblyRoot    = prepareAssemblyAndRunScala4JavaTests().path
    val baseAssemblySources = baseAssemblyRoot / Constants.FolderBuildCode
    val licencePath         = baseAssemblyRoot / Constants.FileNameGeneratedLicenceFileAssembly
    val prependScript       = Option(prependShellScript()).filter(_ != "")

    val licenceTempDir: Path = os.temp.dir(Task.dest)

    if(enableLicensesInfo()) {
      val licenceTargetPath: Path = licenceTempDir / fileNameLicencesInfo()
      os.copy.over(licencePath, licenceTargetPath)
    }

    val inputPaths = Seq(baseAssemblySources, licenceTempDir)

    val created: Assembly = Assembly.create(
      destJar = Task.dest / "out.jar",
      inputPaths = inputPaths,
      manifest = manifest(),
      prependShellScript = prependScript,
      base = None,
      assemblyRules = assemblyRulesExtended(),
      shader = AssemblyModule.jarjarabramsWorker()
    )

    // delete temp dir:
    os.remove.all(licenceTempDir)

    // See https://github.com/com-lihaoyi/mill/pull/2655#issuecomment-1672468284
    val problematicEntryCount = 65535

    if (prependScript.isDefined && created.entries > problematicEntryCount) {
      Task.fail(
        s"""The created assembly jar contains more than $problematicEntryCount ZIP entries.
           |JARs of that size are known to not work correctly with a prepended shell script.
           |Either reduce the entries count of the assembly or disable the prepended shell script with:
           |
           |  def prependShellScript = ""
           |""".stripMargin
      )
    } else {
      created.pathRef
    }
  }

  /**
   * Create a pure java lib (see comment of this class)
   */
  override def jar: T[PathRef] = Task {
    if(assemblyRules.exists(_.isInstanceOf[Rule.Relocate])){
      Task.fail("Task 'jar' is not supported with own Relocate rules")
    }

    val jarPath = Task.dest / "out.jar"
    val baseAssemblyRoot = prepareAssemblyAndRunScala4JavaTests().path
    val baseAssemblySources = baseAssemblyRoot / Constants.FolderBuildCode

    // copy files from assembly:

    val shadedSourceDir = baseAssemblySources / shadedRootDir()
    val tempDir = os.temp.dir(Task.dest)

    // 1. all in shaded classes:
    // note: we do not copy code from scala libs only used for testing like utest here!
    if (enableShadingScalaFiles) {
      val shadedTargetDir = tempDir / shadedRootDir()
      os.makeDir.all(shadedTargetDir)
      os.copy.over(shadedSourceDir, shadedTargetDir)
    } else if (enableObfuscationInBuild) {
      Task.fail(
        s"""
           |'enableObfuscationInBuild = true' together with 'enableShadingScalaFiles = false' is unsupported for task 'jar'.
           |Switch one of theses flags to build the jar file.
           |""".stripMargin
      )
    }

    // if shading is off we need a different approach to
    // copy all third party scala classes to the jar:
    val additionalFilterFunction: String => Boolean = if(!enableShadingScalaFiles){
      val thirdPartyRootClassNames = libraryInformation()
        .view
        .filter(_.isScalaLibrary)
        .flatMap(_.rootPackages)
        .toSeq

      className => !enableShadingScalaFiles && thirdPartyRootClassNames.exists(p => className.startsWith(p))
    } else {
        _ => false
    }

    // 2. all classes connected to the source code:
    val allSourceCodeClassNames  = initialKeepClassNames()
    val sourceDirAsString = baseAssemblySources.toString + "/"
    for(file <- os.walk(baseAssemblySources, skip = _ == shadedSourceDir) if os.isFile(file)){
      val sourceFile = file.toString.stripPrefix(sourceDirAsString) // string including package paths
      if(!sourceFile.endsWith(".class")){
        val targetPath = tempDir / RelPath(sourceFile)
        os.copy.over(file, targetPath, createFolders = true)
      } else {
        val className      = sourceFile.stripSuffix(".class").replace('/', '.')
        val dollarIndex    = className.indexOf("$")
        val outerClassName = if(dollarIndex < 0) className else className.substring(0, dollarIndex)
        if(allSourceCodeClassNames.contains(outerClassName) || additionalFilterFunction(className)) {
          val targetPath = tempDir / RelPath(sourceFile)
          os.copy.over(file, targetPath, createFolders = true)
        }
      }
    }

    // 3. copy created license file
    if(enableLicensesInfo()) {
      val licenceFile = baseAssemblyRoot / Constants.FileNameGeneratedLicenceFileJar
      val licenceTargetFile = tempDir / fileNameLicencesInfo()
      os.copy.over(licenceFile, licenceTargetFile)
    }

    Jvm.createJar(jarPath, Seq(tempDir), manifest())
    os.remove.all(tempDir)

    PathRef(jarPath)
  }

  /**
   * create doc by javadoc
   * note: we do not use scaladoc, since the scala code is shaded and it does not support java record classes
   */
  override def docJar: T[PathRef] = Task {
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq(javadocGenerated().path)))
  }

}
