/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package example;

import java.util.List;

/**
 * A simple facade for scala class
 */
public class ExampleJavaLibFacade {

    /**
     * Do not allow to create instances of this class
     */
    private ExampleJavaLibFacade(){
    }

    /**
     * data structure representing a generic table
     * @param header header of the table
     * @param data data of the table
     */
    public record TableData(List<String> header, List<List<String>> data) {
    }

    /**
     * generate html headline with correct escaping
     * @param in is any string
     * @return a html string
     */
    public static String generateHtmlHeadLine(String in) {
        return ExampleScalaLib.generateHtmlHeadLine(in);
    }

    /**
     * generate an html table
     * @param data a table object specifying the html table
     * @return a html string representing a table
     */
    public static String generateHtmlTable(TableData data) {
        var myHeader = data.header.toArray(new String[0]);
        var myData = new String[data.data.size()][myHeader.length];
        var i = 0;
        for(var row: data.data){
            var j = 0;
            for(var col: row){
                myData[i][j] = col;
                j++;
            }
            i++;
        }
        return ExampleScalaLib.generateHtmlTable(myHeader, myData);
    }

}
