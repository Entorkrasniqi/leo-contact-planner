package io.github.entorkrasniqi.contacts;

import static io.github.entorkrasniqi.contacts.TestData.pass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class TimelineSvgTest {

    @BeforeAll
    static void setUp() {
        TestData.initOrekit();
    }

    private static Document parse(String svg) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void producesWellFormedSvgWithOneBarPerPassAndDashedOutlinesForUnusedOnes() throws Exception {
        final Pass used = pass("S1", "Station A", 600, 900, 100.0);
        final Pass unused = pass("S2", "Station A", 700, 1000, 50.0);
        final Pass other = pass("S2", "Station B", 3000, 3300, 80.0);
        final Schedule schedule = new Schedule("test", List.of(used, other), true);

        final String svg = TimelineSvg.render(List.of(used, unused, other), schedule,
                TestData.utc(0, 0, 0), TestData.utc(2, 0, 0), OrekitSetup.utc());
        final Document doc = parse(svg);
        assertEquals("svg", doc.getDocumentElement().getNodeName());

        final NodeList rects = doc.getElementsByTagName("rect");
        int dashed = 0;
        int titled = 0;
        for (int i = 0; i < rects.getLength(); i++) {
            final Element rect = (Element) rects.item(i);
            if (rect.hasAttribute("stroke-dasharray")) {
                dashed++;
            }
            if (rect.getElementsByTagName("title").getLength() > 0) {
                titled++;
            }
        }
        assertEquals(1, dashed);     // only the unscheduled pass
        assertEquals(3, titled);     // every pass has a hover tooltip
        assertTrue(svg.contains("Station A") && svg.contains("Station B"));
        assertTrue(svg.contains("2 of 3 passes scheduled"));
    }

    @Test
    void specialCharactersInNamesAreEscaped() throws Exception {
        final Pass p = pass("S<1>", "A&B", 0, 100, 1.0);
        final String svg = TimelineSvg.render(List.of(p), new Schedule("t", List.of(p), true),
                TestData.utc(0, 0, 0), TestData.utc(1, 0, 0), OrekitSetup.utc());
        parse(svg);   // throws if the XML is not well-formed
        assertTrue(svg.contains("A&amp;B"));
    }
}
