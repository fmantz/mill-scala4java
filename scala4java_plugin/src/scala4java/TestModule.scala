/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java

import mill.api.{PathRef, Task}
import mill.javalib.TestModuleUtil
import mill.javalib.testrunner.TestResult
import mill.givenDerivedNamedTupleWriter
import os.{Path, RelPath}
import scala4java.api.Constants

/**
 * We also have our own test module, since we want to invoke our test cases also on the
 * shrunk code. We do this to test if the shrunk code still works.
 */
trait TestModule(root: ScalaModule) extends mill.javalib.TestModule, mill.javalib.JavaModule {

  /**
   * note: needed to be overwritten because of conflicting inherited definitions.
   */
  override def defaultTask() = "testForked"
  
  /**
   * Discovers and runs the module's tests in a subprocess, reporting the results to the console.
   * @see [[testCached]]
   */
  def scala4javaTestForked(): Task.Command[(msg: String, results: Seq[TestResult])] = {
    Task.Command {
      scala4JavaTestTask()()
    }
  }

  /**
   * The class path of the scala4java test is adjusted:
   * - we use the original resources
   * - the compiled and shrunk classes from the preassembly
   */
  def runClassPathScala4Test: Task.Simple[Seq[PathRef]] = Task {
    val classPathResources: Seq[PathRef] = runClasspath().filter(_.path.endsWith(RelPath("resources")))

    // build new class path
    val newClassPath = scala.collection.mutable.ArrayBuffer.empty[PathRef]
    newClassPath  += PathRef(root.prepareAssembly().path / Constants.FolderBuildCode)
    newClassPath ++= classPathResources
    newClassPath.toSeq
  }

  /**
   * The actual task shared by `test`-tasks that runs test in a forked JVM.
   * This test method is only needed and enabled if the code has been really be shrunk / obfuscated by ProGuard.
   * If this is not the case the regular test task of the TestModule are enough.
   */
  def scala4JavaTestTask(): Task[(msg: String, results: Seq[TestResult])] = Task.Anon {
    if (root.isProguardUsed) {
      val testModuleUtil = new TestModuleUtil(
        testUseArgsFile(),
        forkArgs(),
        Seq.empty,
        jvmWorker().scalalibClasspath(),
        resources(),
        testFramework(),
        runClassPathScala4Test(),
        testClasspath(),
        testArgsDefault(),
        testForkGrouping(),
        jvmWorker().testrunnerEntrypointClasspath(),
        allForkEnv(),
        testSandboxWorkingDir(),
        forkWorkingDir(),
        testReportXml(),
        javaHome().map(_.path),
        testParallelism(),
        testLogLevel(),
        propagateEnv(),
        jvmWorker().internalWorker()
      )

      val rs = testModuleUtil.runTests()

      val allStatus: Seq[String] = for {
        result <- rs.toOption.toList
        testResult <- result.results
      } yield testResult.status.trim


      if (!allStatus.distinct.exists(_.equalsIgnoreCase("Success"))) {
        val msg: String = (for (result <- rs.toOption) yield result.msg).getOrElse("")
        val failedMsg: Seq[String] = for {
          result <- rs.toOption.toList
          testResult <- result.results if !testResult.status.trim.equalsIgnoreCase("Success")
        } yield s"${testResult.fullyQualifiedName}: ${testResult.status} " +
          s"EXCEPTION ${testResult.exceptionMsg.getOrElse("")} \n    " +
          s"TRACE: ${testResult.exceptionTrace.map(_.mkString("\n")).getOrElse("")} "
        Task.fail(
          s"""
             |$msg
             |
             |$failedMsg
             |""".stripMargin
        )
      }
      rs
    } else {
      mill.api.daemon.Result.create("SKIP", Seq.empty)
    }
  }

}
