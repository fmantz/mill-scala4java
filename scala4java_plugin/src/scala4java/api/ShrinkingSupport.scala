/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java.api

import mill.*
import mill.api.Task.Simple as T
import mill.api.{PathRef, Task}
import mill.contrib.proguard.Proguard
import mill.javalib.api.CompilationResult
import mill.javalib.testrunner.TestResult
import mill.javalib.{Assembly, AssemblyModule}
import mill.scalalib.Assembly.*
import mill.scalalib.*
import mill.util.Jvm
import os.{CommandResult, Shellable}

/**
 * Shrinking is the solution to minimize the size of the jar files
 * We use ProGuard here to minimize our jar file since this a well known java tool for this task.
 * Note: ProGuard is only used as command line application here for license reasons.
 * Furthermore, any ProGuard versions can so be used.
 * The plugin can also be used without ProGuard but this results in large libs of several MBs.
 */
trait ShrinkingSupport extends ShadingSupport, FileUtil { self =>

  /**
   * Proguard is used by an instance of the ProGuard module which is
   * a contributed mill plugin it-self
   */
  private lazy val internalProguardModule = new Proguard {
    override def proguardVersion: T[String] = Task {
      val rs = self.proguardVersion()
      if (rs == null || rs.isBlank) {
        Task.fail(
          """
            |No ProGuard version set!
            |
            |Either set ProGuard version e.g.:
            |
            |def proguardVersion = "7.9.1"
            |
            |OR disable shrinking:
            |
            |def enableShrinkingInBuild   = false
            |def enableObfuscationInBuild = false
            |""".stripMargin
        )
      }
      rs
    }

    /**
     * note: we only need the ProGuard if enableShrinkingInBuild or enableObfuscationInBuild are set to true (which are both as default)
     * @return the proguard classpath
     */
    override def proguardClasspath: T[Seq[PathRef]] = {
      // note: it is important here that enableShrinkingInBuild is not a task,
      //       if it is a task, proguard is already tried to download,
      //       which will fail if no proguardVersion is set!
      if (self.isProguardUsed) {
        super.proguardClasspath
      } else {
        Seq.empty
      }
    }
  }

  /**
   * Name of the ProGuard main class
   */
  def mainProguard: T[String] = Task {
    "proguard.ProGuard"
  }

  /**
   * Override this method to set the ProGuard version to use
   * note: it ProGuard wii be automatically downloaded by the used mill.Proguard plugin.
   * If enableShrinkingInBuild and enableObfuscationInBuild are set to false,
   * this method does not need to be overwritten.
   */
  def proguardVersion: T[String] = Task {
    ""
  }

  /**
   * Enable Proguard Shrinking for jar and assembly builds
   */
  def enableShrinkingInBuild: Boolean = true

  /**
   * Enable Proguard Obfuscation for jar and assembly builds
   */
  def enableObfuscationInBuild: Boolean = true


  final def isProguardUsed: Boolean = {
    enableShrinkingInBuild || enableObfuscationInBuild
  }

  /**
   * Should a license info added to the jar resp. assembly
   */
  def enableLicensesInfo: T[Boolean] = Task { true }

  /**
   * Filename of the file that contains some license information
   */
  def fileNameLicencesInfo: T[String] = Task { "THIRD_PARTY_LICENSES.TXT" }

  /**
   * Remove the tasty files from the jar/assembly file
   * @return true if this should be done
   */
  def removeTastyFiles: T[Boolean] = Task {
    true
  }

  /**
   * By default, contain all java + scala classes of the project:
   */
  def keepClassNames: T[Seq[String]] = initialKeepClassNames

  /**
   * By default, contain all java + scala classes:
   * note: overwrite keepClassNames if necessary
   */
  final def initialKeepClassNames: T[Seq[String]] = Task {
    // alternative:
    // calculateInitialRootClassNames(Seq("java", "scala"), this.allSources())
    //
    // note we prefer the current implementation since scala do
    // not force the folder structure to represent the package structure
    // and additionally scala allows arbitrary number of classes in one file.
    calculateKeepRootClassNames(Seq("class"), this.localClasspath())
  }

  /**
   * important:
   * LocalVariable tables may improve that proguard works correctly
   * https://docs.oracle.com/en/java/javacard/3.1/guide/setting-java-compiler-options.html
   * therefore -g is added to the mandatoryJavacOptions.
   */
  override def mandatoryJavacOptions: T[Seq[String]] = {
    super.mandatoryJavacOptions() :+ "-g"
  }

  /**
   * The entry point for shrinking the jar and assembly file with ProGuard.
   * By default, it uses all 'rootClassNames' from the correspondingly named task,
   * that means all project classes.
   */
  def prepareAssemblyProGuardEntryPoints: T[Seq[String]] = Task {
    val rs = scala.collection.mutable.ArrayBuffer.empty[String]
    // project classes:
    for (n <- keepClassNames()) {
      rs += s"-keep class $n { *; }"
    }
    if (isProguardUsed) {
      // libs only used in tests: (later removed from jar):
      val libInfo4TestsExtra = extraLibsForScala4JavaTests()
      for {
        info <- libInfo4TestsExtra
        pn   <- info.rootPackages
      } {
        rs += s"-keep class $pn.** { *; }"
      }
      // classes for running the tests (later removed from jar):
      for {
        testRoot <- compileScala4JavaTests().map(_.classes)
        file     <- os.walk(testRoot.path) if os.isFile(file) && file.ext == "class"
      } {
        val fileAsString = file.toString
        val searchPattern = "/compile.dest/classes/"
        val startPos = fileAsString.indexOf(searchPattern)
        val className = fileAsString
          .substring(startPos + searchPattern.length)
          .stripSuffix(".class")
          .replace('/', '.')
        rs += s"-keep class $className { *; }"
      }
    }
    rs.toSeq
  }

  /**
   * ProGuard options that should be used in most cases
   * (Should only be overwritten if you really know what you are doing)
   */
  def prepareAssemblyProGuardOptions: T[Seq[String]] = Task {
    val rs = scala.collection.mutable.ArrayBuffer.empty[String]
    if (!enableShrinkingInBuild) {
      rs += "-dontshrink"
    }
    if (!enableObfuscationInBuild) {
      rs += "-dontobfuscate"
    }

    // keep annotations like @Test
    rs += "-keepattributes *Annotation*,AnnotationDefault"
    rs += "-keep @interface **"

    // keep record classes:
    rs += "-keep class * extends java.lang.Record { *; }"
    rs += "-keepattributes Record,MethodParameters,*Annotation*,Signature,InnerClasses,EnclosingMethod"

    // keep for easier debugging:
    rs += "-keepattributes SourceFile,LineNumberTable"

    // keep all package names without subdirectories of ${shadeDir}:
    if(enableObfuscationInBuild) {
      val shadedDir = shadedRootDir()
      rs += s"-keep class !$shadedDir.** { *; }"
      rs += s"-keepdirectories !$shadedDir/**,**"
    }

    rs += "-ignorewarnings"
    rs += "-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)"

    rs.toSeq
  }

  /**
   * Here, optional ProGuard options can be defined. By default, '-verbose' is enabled
   * for the ProGuard Log file in folder 'out/prepareAssembly.dest'
   *
   * @return
   */
  def prepareAssemblyProGuardAdditionalOptions: T[Seq[String]] = Task {
    Seq(
      "-verbose"
    )
  }

  /**
   * If shrinking / obfuscating is enabled we add the test libs (e.g. Junit) to the IvyAssembly
   * so that we can run the tests with our custom generated preassembly.
   */
  def prepareIvyAssemblyInput: T[Seq[PathRef]] = Task {
    val inputPaths = upstreamIvyAssemblyClasspath()
    val extraClasspath = if(isProguardUsed) {
      extraLibsForScala4JavaTests().map(_.classpath)
    } else {
      Seq.empty
    }
    (inputPaths ++ extraClasspath).distinct
  }

  /**
   * Build the assembly for third-party dependencies separate from the current
   * classpath
   *
   * This should allow much faster assembly creation in the common case where
   * third-party dependencies do not change
   *
   * Note:
   *  - uses extended set of assemblyRules
   *  - if shrinking is enabled also test lib dependencies are added to the preassembly
   */
  override def resolvedIvyAssembly: T[Assembly] = Task {
    Assembly.create(
      destJar = Task.dest / "out.jar",
      inputPaths = prepareIvyAssemblyInput().map(_.path),
      manifest = manifest(),
      assemblyRules = assemblyRulesExtended(),
      shader = AssemblyModule.jarjarabramsWorker()
    )
  }

  /**
   * Build the assembly for upstream dependencies separate from the current
   * classpath
   *
   * This should allow much faster assembly creation in the common case where
   * upstream dependencies do not change
   *
   * Note: uses extended set of assemblyRules
   */
  override def upstreamAssembly: T[Assembly] = Task {
    Assembly.create(
      destJar = Task.dest / "out.jar",
      inputPaths = upstreamLocalAssemblyClasspath().map(_.path),
      manifest = manifest(),
      base = Some(resolvedIvyAssembly()),
      assemblyRules = assemblyRulesExtended(),
      shader = AssemblyModule.jarjarabramsWorker()
    )
  }

  /**
   * Prepare class path for assembly
   * @return classpaths for assembly input
   */
  def prepareAssemblyInput: T[Seq[PathRef]] = Task {
    // if shrinking is enabled the tests should be runnable on the
    // shrunk code therefore the test classes are needed and also
    // the testing libraries:
    val extraClasspath: Seq[PathRef] = if(isProguardUsed){
      compileScala4JavaTests().map(_.classes)
    } else {
      Seq.empty
    }
    (localClasspath() ++ extraClasspath).distinct
  }

  /**
   * An executable uber-jar/assembly containing all the resources and compiled
   * class files from this module and all it's upstream modules and dependencies
   *
   * Note: - uses extended set of assemblyRules
   *       - basically does what assembly did before but without adding the prependShellScript,
   *         since this is not necessary here and with the prepended script os.unzip is not
   *         able to read the file anymore.
   */
  def prepareAssembly: T[PathRef] = Task {
    val libInfo  = libraryInformation()
    val libTests = extraLibsForScala4JavaTests()

    // write licenses files for jar and assembly:
    // Those files are later moved and renamed to their target locations and names.
    writeLicencesFileJar(Task.dest / Constants.FileNameGeneratedLicenceFileJar, libInfo)
    writeLicencesFileAssembly(Task.dest / Constants.FileNameGeneratedLicenceFileAssembly, libInfo, libTests)

    val upstream = upstreamAssembly()
    val assemblyRules = assemblyRulesExtended()
    val myManifest = manifest()
    val excludeTastyFilter = if (removeTastyFiles()) Seq(""".+\.tasty$""".r) else Seq.empty

    // collect all compilation code using a temp assembly jar:
    val created: Assembly = Assembly.create(
      destJar = Task.dest / "out.jar",
      inputPaths = prepareAssemblyInput().map(_.path),
      manifest = myManifest,
      prependShellScript = None,
      base = Some(upstream),
      assemblyRules = assemblyRules,
      shader = AssemblyModule.jarjarabramsWorker()
    )

    if(!isProguardUsed) {

      // unzip created to target directory:
      // this code will later directly be used to either create the assembly or the jar file:
      os.unzip.stream(os.read.stream(created.pathRef.path), Task.dest / Constants.FolderBuildCode, excludeTastyFilter)
      os.remove(created.pathRef.path) // cleanup

    } else  {

      // filter files:
      val myEntryPoints = prepareAssemblyProGuardEntryPoints()
      if (myEntryPoints.isEmpty) {
        Task.fail(
          s"""
             |THERE ARE NO ENTRYPOINTS FOR PROGUARD DEFINED!
             |CHECK:
             |- Are there any Scala or Java source files?
             |- Has been method 'prepareAssemblyProGuardEntryPoints' wrongly overwritten?
             |""".stripMargin
        )
      }

      // unzip created assembly to working directory:
      os.unzip.stream(os.read.stream(created.pathRef.path), Task.dest / Constants.FolderBaseCodeUnfiltered, excludeTastyFilter)
      os.remove(created.pathRef.path) // cleanup

      // use proguard to minimize the code:
      val args: Seq[String] = Seq[Shellable](
        "-injars",
        Task.dest / Constants.FolderBaseCodeUnfiltered,
        "-outjars",
        Task.dest / Constants.FileNameProGuardOut,
        myEntryPoints,
        prepareAssemblyProGuardOptions(),
        prepareAssemblyProGuardAdditionalOptions()
      ).flatMap(_.value)

      val ruleFile = Task.dest / Constants.FileNameProGuardRules
      os.write.over(ruleFile, args.mkString("\n"))
      val argToFile = s"@${ruleFile.toString}"

      Task.log.info(s"Running: ${mainProguard()} $argToFile")

      // Proguard is called here, note:
      // since we added the testing code to the entry points
      // of ProGuard, writing tests may help that the
      // minimized library still works.
      val commandRs: CommandResult = Jvm.callProcess(
        mainClass = mainProguard(),
        classPath = internalProguardModule.proguardClasspath().map(_.path).toVector,
        mainArgs  = Seq(argToFile),
        cwd       = Task.dest
      )

      // write the proguard log into a file, usually ProGuard
      // should have a hand-crafted config for its specific use case,
      // we use a generic configuration here that ignores all warnings.
      // This may not always work but may simplify the use of this plugin
      // in many cases.
      writeLog(Task.dest / Constants.FileNameProGuardLogFile, commandRs)

      if (commandRs.exitCode != 0) {
        Task.fail(
          s"""
             |PROGUARD FAILED:
             |- Check ${Task.dest / Constants.FileNameProGuardLogFile}
             |
             |What else you can do:
             |- Give additional options by overwriting task 'prepareAssemblyProGuardAdditionalOptions'
             |- Disable proguard by overwriting flags:
             |     - def enableShrinkingInBuild   = false
             |     - def enableObfuscationInBuild = false
             |- Other tasks you may consider to overwrite:
             |     - rootClassNames
             |     - prepareAssemblyProGuardEntryPoints
             |     - prepareAssemblyProGuardOptions
             |""".stripMargin
        )
      }
      os.unzip.stream(
        os.read.stream(Task.dest / Constants.FileNameProGuardOut),
        Task.dest / Constants.FolderBuildCode
      )
    }

    PathRef(Task.dest)
  }

  /**
   * Calls prepareAssembly and runs all scala4Java tests on the
   * minimized result (if ProguardUsed is used).
   * The hope is that the tests still run after the code is shrunk / obfuscated.
   * If the tests fail and did not fail before the ProGuard
   * entry points needs to be adjusted.
   * @return path to the input code for the jar and the assembly
   */
  def prepareAssemblyAndRunScala4JavaTests: T[PathRef] = Task {
    val rs = prepareAssembly()
    if(isProguardUsed) {
      runAllScala4JavaAfterPrepareAssembly()()
    }
    rs
  }

  /**
   * Run all scala4java tests on the compiled (non-minimized) code
   * This method can be used to check if the tests already fail
   * without minimization.
   * @return test results
   */
  def runAllScala4JavaTests(): Task[Seq[(msg: String, results: Seq[TestResult])]] = {
    val tasks: Seq[Task.Command[(msg: String, results: Seq[TestResult])]] = moduleDirectChildren.collect {
      case m: scala4java.TestModule => m.testForked()
    }
    Task.sequence(tasks)
  }

  /**
   *  Run all scala4java tests on the compiled + minimized code
   *  This method is called automatically after code is shrunk to
   *  check if the minimization broke the code.
    * @return test results
   */
  def runAllScala4JavaAfterPrepareAssembly(): Task[Seq[(msg: String, results: Seq[TestResult])]] = {
    val tasks: Seq[Task.Command[(msg: String, results: Seq[TestResult])]] = moduleDirectChildren.collect {
      case m: scala4java.TestModule => m.scala4javaTestForked()
    }
    Task.sequence(tasks)
  }

  /**
   * Collect the class paths to run the tests on all scala4Java flagged tests.
   * @return Sequence of class paths for each test module
   */
  def classPathForScala4JavaLibs: Task[Seq[Seq[PathRef]]] = {
    val cp: Seq[T[Seq[PathRef]]] = moduleDirectChildren.collect {
      case m: scala4java.TestModule => m.runClasspath
    }
    Task.sequence(cp)
  }

  /**
   * Compile all scala4Java Tests
   * note: we need the compiled classes to add them to the code
   * before we shrink the code
   * @return compilation results for each test module
   */
  def compileScala4JavaTests: Task[Seq[CompilationResult]] = {
    val cp: Seq[T[CompilationResult]] = moduleDirectChildren.collect {
      case m: scala4java.TestModule => m.compile
    }
    Task.sequence(cp)
  }

  /**
   * Calculate the library information for the scala4Java Tests
   */
  def libraryInformationForScala4JavaTests: T[Seq[LibraryInformation]] = Task {
    LibraryInformation.fromClasspath(classPathForScala4JavaLibs().flatten)
  }

  /**
   * @return the library information that are additional for running the scala4Java tests
   */
  def extraLibsForScala4JavaTests: T[Seq[LibraryInformation]] = Task{
    val libInfo = libraryInformation()
    val libInfo4Tests = libraryInformationForScala4JavaTests()
    libInfo4Tests.filterNot(l => libInfo.exists(_.classpath == l.classpath))
  }

}
