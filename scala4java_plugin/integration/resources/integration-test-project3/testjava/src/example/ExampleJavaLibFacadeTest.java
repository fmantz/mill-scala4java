/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * A simple test of the Java facade using a java testing lib
 */
class ExampleJavaLibFacadeTest{

    @Test
    void simple() {
        var result = ExampleJavaLibFacade.generateHtmlHeadLine("hello");
        assertEquals(result, "<h1>hello</h1>");
    }

    @Test
    void escaping() {
        var result = ExampleJavaLibFacade.generateHtmlHeadLine("<hello>");
        assertEquals(result, "<h1>&lt;hello&gt;</h1>");
    }

    @Test
    void table() {
        var data = new ExampleJavaLibFacade.TableData(
          List.of("alpha", "beta", "gamma"),
          List.of(
            List.of("1", "2", "3"),
            List.of("10", "20", "30")
          )
        );
        var result = ExampleJavaLibFacade.generateHtmlTable(data);
        assertEquals(result,
"""
<html>
  <head></head>
  <body>
    <table>
      <thead>
        <tr>
          <th>Alpha</th>
          <th>Beta</th>
          <th>Gamma</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>1</td>
          <td>2</td>
          <td>3</td>
        </tr>
        <tr>
          <td>10</td>
          <td>20</td>
          <td>30</td>
        </tr>
      </tbody>
    </table>
  </body>
</html>
""".trim());

    }

}
