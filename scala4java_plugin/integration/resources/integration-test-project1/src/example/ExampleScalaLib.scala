package example

/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
import org.apache.commons.lang3.StringUtils
import org.jsoup.Jsoup
import scalatags.Text.all._
import grizzled.slf4j.Logging // scala lib that has a java lib as dependency

/**
 * A simple example scala class using methods form scala and java libs
 */
object ExampleScalaLib extends Logging {

  /**
   * Generate a html headline by just calling a scala lib
   * @param text that should be appear as headline
   * @return a html string
   */
  def generateHtmlHeadLine(text: String): String = {
    val rs = h1(text).toString
    debug(rs)
    rs
  }

  /**
   * Generate a html table from a header and a data array containing a array of data for each line
   * @param header of the html table
   * @param data content of the html table
   * @return a html string
   */
  def generateHtmlTable(header: Array[String], data: Array[Array[String]]): String = {
    require(data.forall(_.length == header.length), "header and data rows must have same length")
    // use scala lib to generate html:
    val headerTh = header.view.map(StringUtils.capitalize).map(s => th(s)).toSeq // use java lib to UpperCase first letters
    val dataTr = data.map(l => tr(l.map(s => td(s))))
    val htmlString = table(
      thead(
        tr(headerTh: _*)
      ),
      tbody(dataTr: _*)
    ).toString
    // use java lib to format html
    val doc = Jsoup.parse(htmlString);
    doc.outputSettings().indentAmount(2).prettyPrint(true)
    val rs = doc.html()
    debug(rs)
    rs
  }

}
