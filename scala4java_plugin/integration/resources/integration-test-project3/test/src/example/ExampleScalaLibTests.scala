/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package example

import utest._

/**
 * A simple tests of the scala class using a scala testing lib
 */
object ExampleScalaLibTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = ExampleScalaLib.generateHtmlHeadLine("hello")
      assert(result == "<h1>hello</h1>")
    }
    test("escaping") {
      val result = ExampleScalaLib.generateHtmlHeadLine("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
    }
    test("table") {
      val result = ExampleScalaLib.generateHtmlTable(
        Array("alpha", "beta", "gamma"),
        Array(Array("1", "2", "3"), Array("10", "20", "30"))
      )
      assert(result ==
        """
          |<html>
          |  <head></head>
          |  <body>
          |    <table>
          |      <thead>
          |        <tr>
          |          <th>Alpha</th>
          |          <th>Beta</th>
          |          <th>Gamma</th>
          |        </tr>
          |      </thead>
          |      <tbody>
          |        <tr>
          |          <td>1</td>
          |          <td>2</td>
          |          <td>3</td>
          |        </tr>
          |        <tr>
          |          <td>10</td>
          |          <td>20</td>
          |          <td>30</td>
          |        </tr>
          |      </tbody>
          |    </table>
          |  </body>
          |</html>
          |""".stripMargin.trim)
    }
  }
}
