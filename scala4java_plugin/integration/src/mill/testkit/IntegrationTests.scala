/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java
import mill.testkit.IntegrationTester
import utest.*

/**
 * Integration test for the plugin
 * 1. run "mill scala4java_plugin.publishLocal"
 * 2. run "mill scala4java_plugin.integration"
 * The test only checks if publishLocal is successful
 */
object IntegrationTests extends TestSuite {

  println("initializing scala4java_plugin.IntegrationTest")

  def tests: Tests = Tests {
    println("initializing scala4java_plugin.IntegrationTest.tests")

    test("integration test without ProGuard") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      val tester = new IntegrationTester(
        daemonMode = true,
        workspaceSourcePath = resourceFolder / "integration-test-project1",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
      val rs = tester.eval("publishLocal")

      rs.result.err.lines().foreach(println)

      assert(rs.result.exitCode == 0)
    }

    test("integration test with ProGuard") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      val tester = new IntegrationTester(
        daemonMode = true,
        workspaceSourcePath = resourceFolder / "integration-test-project2",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
      val rs = tester.eval("publishLocal")

      rs.result.err.lines().foreach(println)

      assert(rs.result.exitCode == 0)
    }

    test("integration test with ProGuard but without shading") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      val tester = new IntegrationTester(
        daemonMode = true,
        workspaceSourcePath = resourceFolder / "integration-test-project3",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
      val rs = tester.eval("publishLocal")

      rs.result.err.lines().foreach(println)

      assert(rs.result.exitCode == 0)
    }
  }
}
